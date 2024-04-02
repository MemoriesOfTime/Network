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

package com.nukkitx.network.raknet.pipeline;

import com.nukkitx.network.raknet.RakNetConstants;
import com.nukkitx.network.raknet.RakNetServer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.concurrent.ScheduledFuture;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RakServerRateLimiter extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-server-rate-limiter";
    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakServerRateLimiter.class);

    private final RakNetServer server;

    private final ConcurrentHashMap<InetAddress, AtomicInteger> rateLimitMap = new ConcurrentHashMap<>();
    private final Map<InetAddress, Long> blockedConnections = new ConcurrentHashMap<>();

    private final Collection<InetAddress> exceptions = Collections.synchronizedCollection(new HashSet<>());

    private final AtomicLong globalCounter = new AtomicLong(0);

    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> blockedTickFuture;

    public RakServerRateLimiter(RakNetServer server) {
        this.server = server;
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
    }

    private void onRakTick() {
        this.rateLimitMap.clear();
        this.globalCounter.set(0);
    }

    private void onBlockedTick() {
        long currTime = System.currentTimeMillis();

        Iterator<Map.Entry<InetAddress, Long>> iterator = this.blockedConnections.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<InetAddress, Long> entry = iterator.next();
            if (entry.getValue() != 0 && currTime > entry.getValue()) {
                iterator.remove();
                log.info("Unblocked address {}", entry.getKey());
            }
        }
    }

    public boolean blockAddress(InetAddress address, long time, TimeUnit unit) {
        if (this.exceptions.contains(address)) {
            return false;
        }

        long millis = unit.toMillis(time);
        this.blockedConnections.put(address, System.currentTimeMillis() + millis);
        return true;
    }

    public void unblockAddress(InetAddress address) {
        if (this.blockedConnections.remove(address) != null) {
            log.info("Unblocked address {}", address);
        }
    }

    public boolean isAddressBlocked(InetAddress address) {
        return this.blockedConnections.containsKey(address);
    }

    public void addException(InetAddress address) {
        this.exceptions.add(address);
    }

    public void removeException(InetAddress address) {
        this.exceptions.remove(address);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket datagram) throws Exception {
        if (this.globalCounter.incrementAndGet() > RakNetConstants.DEFAULT_GLOBAL_PACKET_LIMIT) {
            if (log.isTraceEnabled()) {
                log.trace("[{}] Dropped incoming packet because global packet limit was reached: {}", datagram.sender(), this.globalCounter.get());
            }
            return;
        }

        InetAddress address = datagram.sender().getAddress();
        if (this.blockedConnections.containsKey(address)) {
            return;
        }

        AtomicInteger counter = this.rateLimitMap.computeIfAbsent(address, a -> new AtomicInteger());
        if (counter.incrementAndGet() > RakNetConstants.DEFAULT_PACKET_LIMIT &&
                this.blockAddress(address, 10, TimeUnit.SECONDS)) {
            log.warn("[{}] Blocked because packet limit was reached");
        } else {
            ctx.fireChannelRead(datagram.retain());
        }
    }
}
