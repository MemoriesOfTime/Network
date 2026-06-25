/*
 * Copyright 2024 CloudburstMC
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

package org.cloudburstmc.netty.handler.codec.raknet.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakServerMetrics;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RakServerRateLimiter extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-server-rate-limiter";
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakServerRateLimiter.class);

    private final RakServerChannel channel;

    private final Map<InetAddress, AddressCounters> rateLimitMap = new HashMap<>();
    private final Map<InetAddress, Long> blockedConnections = new ConcurrentHashMap<>();
    private final Map<InetAddress, InetSocketAddress> blockedConnectionSources = new ConcurrentHashMap<>();

    private final Collection<InetAddress> exceptions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private int globalCounter;

    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> blockedTickFuture;

    public RakServerRateLimiter(RakServerChannel channel) {
        this.channel = channel;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.tickFuture = ctx.channel().eventLoop().scheduleAtFixedRate(this::onRakTick, 10, 10, TimeUnit.MILLISECONDS);
        this.blockedTickFuture = ctx.channel().eventLoop().scheduleAtFixedRate(this::onBlockedTick, 100, 100, TimeUnit.MILLISECONDS);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        this.tickFuture.cancel(false);
        this.blockedTickFuture.cancel(true);
        this.rateLimitMap.clear();
        this.blockedConnections.clear();
        this.blockedConnectionSources.clear();
    }

    protected void onRakTick() {
        this.rateLimitMap.clear();
        this.globalCounter = 0;
    }

    protected void onBlockedTick() {
        long currTime = System.currentTimeMillis();

        RakServerMetrics metrics = this.channel.config().getMetrics();

        Iterator<Map.Entry<InetAddress, Long>> iterator = this.blockedConnections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<InetAddress, Long> entry = iterator.next();
            if (entry.getValue() != 0 && currTime > entry.getValue()) {
                iterator.remove();
                InetSocketAddress sourceAddress = this.blockedConnectionSources.remove(entry.getKey());
                log.info("Unblocked address {}", entry.getKey());
                if (metrics != null) {
                    if (sourceAddress != null) {
                        metrics.addressUnblocked(sourceAddress);
                    } else {
                        metrics.addressUnblocked(entry.getKey());
                    }
                }
            }
        }
    }

    public boolean blockAddress(InetAddress address, long time, TimeUnit unit) {
        return this.blockAddress(address, null, time, unit);
    }

    public boolean blockAddress(InetSocketAddress address, long time, TimeUnit unit) {
        Objects.requireNonNull(address, "address");
        return this.blockAddress(address.getAddress(), address, time, unit);
    }

    private boolean blockAddress(InetAddress address, InetSocketAddress sourceAddress, long time, TimeUnit unit) {
        Objects.requireNonNull(address, "address");
        if (this.exceptions.contains(address)) {
            return false;
        }

        long millis = unit.toMillis(time);
        this.blockedConnections.put(address, System.currentTimeMillis() + millis);
        if (sourceAddress != null) {
            this.blockedConnectionSources.put(address, sourceAddress);
        } else {
            this.blockedConnectionSources.remove(address);
        }

        if (this.channel.config().getMetrics() != null) {
            if (sourceAddress != null) {
                this.channel.config().getMetrics().addressBlocked(sourceAddress);
            } else {
                this.channel.config().getMetrics().addressBlocked(address);
            }
        }
        return true;
    }

    public void unblockAddress(InetAddress address) {
        Objects.requireNonNull(address, "address");
        if (this.blockedConnections.remove(address) == null) {
            return;
        }

        InetSocketAddress sourceAddress = this.blockedConnectionSources.remove(address);
        log.info("Unblocked address {}", address);

        if (this.channel.config().getMetrics() != null) {
            if (sourceAddress != null) {
                this.channel.config().getMetrics().addressUnblocked(sourceAddress);
            } else {
                this.channel.config().getMetrics().addressUnblocked(address);
            }
        }
    }

    public void unblockAddress(InetSocketAddress address) {
        Objects.requireNonNull(address, "address");
        this.unblockAddress(address.getAddress());
    }

    public boolean isAddressBlocked(InetAddress address) {
        Objects.requireNonNull(address, "address");
        return this.blockedConnections.containsKey(address);
    }

    public boolean isAddressBlocked(InetSocketAddress address) {
        Objects.requireNonNull(address, "address");
        return this.isAddressBlocked(address.getAddress());
    }

    public void addException(InetAddress address) {
        Objects.requireNonNull(address, "address");
        this.exceptions.add(address);
    }

    public void addException(InetSocketAddress address) {
        Objects.requireNonNull(address, "address");
        this.addException(address.getAddress());
    }

    public void removeException(InetAddress address) {
        Objects.requireNonNull(address, "address");
        this.exceptions.remove(address);
    }

    public void removeException(InetSocketAddress address) {
        Objects.requireNonNull(address, "address");
        this.removeException(address.getAddress());
    }

    public Collection<InetAddress> getExceptions() {
        return Collections.unmodifiableCollection(this.exceptions);
    }

    protected int getAddressMaxPacketCount(InetAddress address) {
        return this.channel.config().getPacketLimit();
    }

    protected int getAddressMaxPacketCount(InetSocketAddress address) {
        Objects.requireNonNull(address, "address");
        return this.getAddressMaxPacketCount(address.getAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket datagram) throws Exception {
        if (++this.globalCounter > this.channel.config().getGlobalPacketLimit()) {
            if (log.isTraceEnabled()) {
                log.trace("[{}] Dropped incoming packet because global packet limit was reached: {}", datagram.sender(), this.globalCounter);
            }
            return;
        }

        InetSocketAddress effectiveAddress = this.channel.getClientAddress(datagram.sender());
        if (effectiveAddress == null) {
            return;
        }

        InetAddress address = effectiveAddress.getAddress();
        if (this.blockedConnections.containsKey(address)) {
            return;
        }

        AddressCounters counter = this.rateLimitMap.get(address);
        if (counter == null) {
            counter = new AddressCounters();
            this.rateLimitMap.put(address, counter);
        }

        if (++counter.total > this.getAddressMaxPacketCount(effectiveAddress) &&
                this.blockAddress(effectiveAddress, 10, TimeUnit.SECONDS)) {
            log.warn("[{}] Blocked because packet limit was reached", effectiveAddress);
        } else {
            ctx.fireChannelRead(datagram.retain());
        }
    }

    private static final class AddressCounters {
        int total;
    }
}
