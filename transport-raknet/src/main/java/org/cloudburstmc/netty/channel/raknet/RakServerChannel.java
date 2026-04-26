/*
 * Copyright 2022 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty.channel.raknet;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ServerChannel;
import io.netty.channel.socket.DatagramChannel;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.PromiseCombiner;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.proxy.ProxyChannel;
import org.cloudburstmc.netty.channel.raknet.config.DefaultRakServerConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerChannelConfig;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.cloudburstmc.netty.handler.codec.raknet.common.UnconnectedPongEncoder;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerOfflineHandler;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRateLimiter;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRouteHandler;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerTailHandler;
import org.cloudburstmc.netty.util.RakUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class RakServerChannel extends ProxyChannel<DatagramChannel> implements ServerChannel {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakServerChannel.class);

    private final RakServerChannelConfig config;
    private final Map<SocketAddress, RakChildChannel> childChannelMap = new ConcurrentHashMap<>();
    private final Consumer<RakChannel> childConsumer;

    public RakServerChannel(DatagramChannel channel) {
        this(channel, null);
    }

    public RakServerChannel(DatagramChannel channel, Consumer<RakChannel> childConsumer) {
        super(channel);
        this.childConsumer = childConsumer;
        this.config = new DefaultRakServerConfig(this);
        this.initPipeline();
    }

    protected void initPipeline() {
        this.pipeline().addLast(UnconnectedPongEncoder.NAME, UnconnectedPongEncoder.INSTANCE);
        if (this.config().getPacketLimit() > 0) { // No point in enabling this.
            this.pipeline().addLast(RakServerRateLimiter.NAME, new RakServerRateLimiter(this));
        }
        this.pipeline().addLast(RakServerOfflineHandler.NAME, new RakServerOfflineHandler(this));
        this.pipeline().addLast(RakServerRouteHandler.NAME, new RakServerRouteHandler(this));
        this.pipeline().addLast(RakServerTailHandler.NAME, RakServerTailHandler.INSTANCE);
    }

    /**
     * Create new child channel assigned to remote address.
     *
     * @param address         remote address of new connection.
     * @param localAddress    local address the connection was received on.
     * @param clientGuid      unique GUID of the connecting client.
     * @param mtu             negotiated MTU for this connection.
     * @param protocolVersion RakNet protocol version from the handshake cookie, or 0 if not available.
     * @return RakChildChannel instance of new channel, or {@code null} if a non-replaceable channel already exists.
     */
    public ChildChannelCreationResult createChildChannel(InetSocketAddress address, InetSocketAddress localAddress, long clientGuid, int mtu, int protocolVersion) {
        RakChildChannel existingChannel = this.childChannelMap.get(address);
        RakServerCookieMode cookieMode = this.config().getCookieMode();
        boolean trustedIp = cookieMode != RakServerCookieMode.NONE
                && cookieMode != RakServerCookieMode.STATELESS
                && cookieMode != RakServerCookieMode.OFF;
        if (existingChannel != null
                && existingChannel.config().getGuid() == clientGuid
                && existingChannel.config().getMtu() == mtu
                && Objects.equals(existingChannel.localAddress(), localAddress)
                && !existingChannel.isActive()) {
            return ChildChannelCreationResult.existing(existingChannel);
        }
        if (trustedIp && existingChannel != null) {
            existingChannel.close();
        } else if (existingChannel != null) {
            // Could be spoofed, so we don't close the existing channel.
            return ChildChannelCreationResult.alreadyConnected();
        }

        int maxConnections = this.config().getMaxConnections();
        if (existingChannel == null && maxConnections > 0 && this.childChannelMap.size() >= maxConnections) {
            return ChildChannelCreationResult.noFreeIncomingConnections();
        }

        RakChildChannel channel = new RakChildChannel(address, localAddress, this, clientGuid, mtu, childConsumer);
        channel.closeFuture().addListener((GenericFutureListener<ChannelFuture>) this::onChildClosed);
        // Set before fireChannelRead because initChannel runs async on the child worker thread.
        if (protocolVersion != 0) {
            channel.config().setOption(RakChannelOption.RAK_PROTOCOL_VERSION, protocolVersion);
        }
        // Fire channel thought ServerBootstrap,
        // register to eventLoop, assign default options and attributes
        this.childChannelMap.put(address, channel);
        try {
            this.pipeline().fireChannelRead(channel).fireChannelReadComplete();
        } catch (Throwable t) {
            this.childChannelMap.remove(address, channel);
            try {
                channel.close();
            } catch (Throwable closeCause) {
                log.warn("Failed to close rejected child channel {}", channel, closeCause);
            }
            throw t;
        }
        if (!channel.isOpen()) {
            this.childChannelMap.remove(address, channel);
            return ChildChannelCreationResult.alreadyConnected();
        }

        if (this.config().getMetrics() != null) {
            this.config().getMetrics().channelOpen(address);
        }
        return ChildChannelCreationResult.success(channel);
    }

    public boolean hasCapacityFor(InetSocketAddress address) {
        if (this.childChannelMap.containsKey(address)) {
            return true;
        }
        int maxConnections = this.config().getMaxConnections();
        return maxConnections <= 0 || this.childChannelMap.size() < maxConnections;
    }

    public RakChildChannel getChildChannel(SocketAddress address) {
        return this.childChannelMap.get(address);
    }

    void onChildClosing(RakChildChannel channel) {
        // Release the slot as soon as the child starts closing so new handshakes are not rejected while the
        // closeFuture listener is still pending.
        this.childChannelMap.remove(channel.remoteAddress(), channel);
    }

    private void onChildClosed(ChannelFuture channelFuture) {
        RakChildChannel channel = (RakChildChannel) channelFuture.channel();
        this.childChannelMap.remove(channel.remoteAddress(), channel);

        if (this.config().getMetrics() != null) {
            this.config().getMetrics().channelClose(channel.remoteAddress());
        }

        Runnable destroyTask = () -> {
            channel.rakPipeline().fireChannelInactive();
            channel.rakPipeline().fireChannelUnregistered();
            // Need to use reflection to destroy pipeline because
            // DefaultChannelPipeline.destroy() is only called when channel.isOpen() is false,
            // but the method is called on parent channel, and there is no other way to destroy pipeline.
            RakUtils.destroyChannelPipeline(channel.rakPipeline());
        };
        if (channel.eventLoop().inEventLoop()) {
            destroyTask.run();
        } else {
            channel.eventLoop().execute(destroyTask);
        }
    }

    @Override
    public void onCloseTriggered(ChannelPromise promise) {
        if (log.isTraceEnabled()) {
            log.trace("Closing RakServerChannel: {}", Thread.currentThread().getName(), new Throwable());
        }
        promise.addListener(future -> {
            if (!future.isSuccess()) {
                log.warn("Failed to close RakServerChannel", future.cause());
            }
        });

        PromiseCombiner combiner = new PromiseCombiner(this.eventLoop());
        this.childChannelMap.values().forEach(channel -> combiner.add(channel.close()));

        ChannelPromise combinedPromise = this.newPromise();
        combinedPromise.addListener(future -> super.onCloseTriggered(promise));
        combiner.finish(combinedPromise);
    }

    public boolean tryBlockAddress(InetAddress address, long time, TimeUnit unit) {
        RakServerRateLimiter rateLimiter = this.pipeline().get(RakServerRateLimiter.class);
        if (rateLimiter != null) {
            return rateLimiter.blockAddress(address, time, unit);
        }
        return false;
    }

    @Override
    public RakServerChannelConfig config() {
        return this.config;
    }

    public static final class ChildChannelCreationResult {
        private final RakChildChannel channel;
        private final ChildChannelCreationFailure failure;

        private ChildChannelCreationResult(RakChildChannel channel, ChildChannelCreationFailure failure) {
            this.channel = channel;
            this.failure = failure;
        }

        public static ChildChannelCreationResult success(RakChildChannel channel) {
            return new ChildChannelCreationResult(channel, ChildChannelCreationFailure.NONE);
        }

        public static ChildChannelCreationResult existing(RakChildChannel channel) {
            return new ChildChannelCreationResult(channel, ChildChannelCreationFailure.EXISTING_CHANNEL);
        }

        public static ChildChannelCreationResult alreadyConnected() {
            return new ChildChannelCreationResult(null, ChildChannelCreationFailure.ALREADY_CONNECTED);
        }

        public static ChildChannelCreationResult noFreeIncomingConnections() {
            return new ChildChannelCreationResult(null, ChildChannelCreationFailure.NO_FREE_INCOMING_CONNECTIONS);
        }

        public RakChildChannel channel() {
            return this.channel;
        }

        public ChildChannelCreationFailure failure() {
            return this.failure;
        }
    }

    public enum ChildChannelCreationFailure {
        NONE,
        EXISTING_CHANNEL,
        ALREADY_CONNECTED,
        NO_FREE_INCOMING_CONNECTIONS
    }
}
