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

package com.nukkitx.network.raknet.pipeline;

import com.nukkitx.network.raknet.RakNetConstants;
import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.RakNetServerSession;
import com.nukkitx.network.raknet.RakNetUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import net.jodah.expiringmap.ExpirationPolicy;
import net.jodah.expiringmap.ExpiringMap;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.nukkitx.network.raknet.RakNetConstants.*;

public class RakServerOfflineHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-offline-handler";

    private static final InternalLogger log = InternalLoggerFactory.getInstance(RakServerOfflineHandler.class);

    private final ExpiringMap<InetAddress, AtomicInteger> packetsCounter = ExpiringMap.builder()
            .expiration(1, TimeUnit.SECONDS)
            .expirationPolicy(ExpirationPolicy.CREATED)
            .build();

    private final RakNetServer server;

    public RakServerOfflineHandler(RakNetServer server) {
        this.server = server;
    }

    @Override
    public boolean acceptInboundMessage(Object msg) throws Exception {
        if (!super.acceptInboundMessage(msg)) {
            return false;
        }

        DatagramPacket packet = (DatagramPacket) msg;
        ByteBuf buf = packet.content();
        if (!buf.isReadable()) {
            return false; // No packet ID
        }

        int startIndex = buf.readerIndex();
        try {
            int packetId = buf.readUnsignedByte();
            switch (packetId) {
                case ID_UNCONNECTED_PING:
                    if (buf.isReadable(8)) {
                        buf.readLong(); // Ping time
                    }
                case ID_OPEN_CONNECTION_REQUEST_1:
                case ID_OPEN_CONNECTION_REQUEST_2:
                    return buf.isReadable(RakNetConstants.unconnectedMagic.readableBytes()) && ByteBufUtil.equals(buf.readSlice(RakNetConstants.unconnectedMagic.readableBytes()), RakNetConstants.unconnectedMagic);
                default:
                    return false;
            }
        } finally {
            buf.readerIndex(startIndex);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buffer = packet.content();
        short packetId = buffer.readByte();

        AtomicInteger counter = this.packetsCounter.computeIfAbsent(packet.sender().getAddress(), s -> new AtomicInteger());
        if (counter.incrementAndGet() > RakNetConstants.DEFAULT_OFFLINE_PACKET_LIMIT) {
            log.warn("[{}] Sent too many packets per second", packet.sender());
            this.server.tryBlockAddress(packet.sender().getAddress(), 10, TimeUnit.SECONDS);
            return;
        }

        // These packets don't require a session
        switch (packetId) {
            case ID_UNCONNECTED_PING:
                this.onUnconnectedPing(ctx, packet);
                return;
            case ID_OPEN_CONNECTION_REQUEST_1:
                this.server.onOpenConnectionRequest1(ctx, packet);
                return;
        }

        buffer.readerIndex(0);

        RakNetServerSession session = this.server.getSession(packet.sender());
        if (session != null) {
            if (session.getEventLoop().inEventLoop()) {
                session.onDatagram(buffer.retain());
            } else {
                ByteBuf buf = buffer.retain();
                session.getEventLoop().execute(() -> session.onDatagram(buf));
            }
        } else if (this.server.getListener() != null) {
            this.server.getListener().onUnhandledDatagram(ctx, packet);
        }
    }

    private void onUnconnectedPing(ChannelHandlerContext ctx, DatagramPacket packet) {
        if (!packet.content().isReadable(24)) {
            return;
        }

        long pingTime = packet.content().readLong();
        if (!RakNetUtils.verifyUnconnectedMagic(packet.content())) {
            return;
        }

        byte[] userData = null;
        if (this.server.getListener() != null) {
            userData = this.server.getListener().onQuery(packet.sender());
        }

        if (userData == null) {
            userData = new byte[0];
        }

        int packetLength = 35 + userData.length;

        ByteBuf buffer = ctx.alloc().ioBuffer(packetLength, packetLength);
        buffer.writeByte(ID_UNCONNECTED_PONG);
        buffer.writeLong(pingTime);
        buffer.writeLong(this.server.getGuid());
        RakNetUtils.writeUnconnectedMagic(buffer);
        buffer.writeShort(userData.length);
        buffer.writeBytes(userData);
        ctx.writeAndFlush(new DatagramPacket(buffer, packet.sender()));
    }

}
