/*
 * Copyright 2026 CloudburstMC
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

package org.cloudburstmc.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.cloudburstmc.netty.channel.raknet.RakServerChannel;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.cloudburstmc.netty.channel.raknet.RakConstants.ID_UNCONNECTED_PING;

public class RakRateLimiterTests {

    private static final int PORT = 19135;
    private static final byte[] PROXY_V2_SIGNATURE = new byte[]{
            0x0D, 0x0A, 0x0D, 0x0A,
            0x00, 0x0D, 0x0A,
            0x51, 0x55, 0x49, 0x54,
            0x0A
    };

    private final List<Channel> channels = new ArrayList<>();
    private EventLoopGroup group;

    @BeforeEach
    public void setup() {
        this.group = new NioEventLoopGroup();
    }

    @AfterEach
    public void teardown() {
        for (int i = this.channels.size() - 1; i >= 0; i--) {
            this.channels.get(i).close().awaitUninterruptibly();
        }
        this.channels.clear();
        this.group.shutdownGracefully().awaitUninterruptibly();
    }

    @Test
    public void testRateLimitBlocksAllSocketsSharingTheSameAddress() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", PORT);
        this.track(new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(this.group)
                .option(RakChannelOption.RAK_GUID, 1L)
                .option(RakChannelOption.RAK_PACKET_LIMIT, 1)
                .option(RakChannelOption.RAK_ADVERTISEMENT, Unpooled.wrappedBuffer("rate-limit-test".getBytes(StandardCharsets.UTF_8)))
                .handler(new ChannelInitializer<RakServerChannel>() {
                    @Override
                    protected void initChannel(RakServerChannel ch) {
                    }
                })
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                    }
                })
                .bind(serverAddress)
                .awaitUninterruptibly()
                .channel());

        BlockingQueue<DatagramPacket> firstResponses = new LinkedBlockingQueue<>();
        BlockingQueue<DatagramPacket> secondResponses = new LinkedBlockingQueue<>();

        Channel first = this.track(this.createRawClient(firstResponses));
        Channel second = this.track(this.createRawClient(secondResponses));

        first.write(new DatagramPacket(this.createUnconnectedPing(1L), serverAddress));
        first.write(new DatagramPacket(this.createUnconnectedPing(2L), serverAddress));
        first.flush();

        DatagramPacket firstReply = firstResponses.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(firstReply, "The first socket should receive an initial pong before being rate limited");
        firstReply.release();

        first.writeAndFlush(new DatagramPacket(this.createUnconnectedPing(3L), serverAddress)).awaitUninterruptibly();
        Assertions.assertNull(firstResponses.poll(300, TimeUnit.MILLISECONDS),
                "The abusive socket should be temporarily blocked after exceeding the packet limit");

        second.writeAndFlush(new DatagramPacket(this.createUnconnectedPing(4L), serverAddress)).awaitUninterruptibly();
        Assertions.assertNull(secondResponses.poll(300, TimeUnit.MILLISECONDS),
                "Another socket sharing the same IP should also be blocked once the address exceeds the packet limit");
    }

    @Test
    public void testRateLimitUsesProxiedClientAddress() throws Exception {
        InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", PORT);
        this.track(new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(this.group)
                .option(RakChannelOption.RAK_GUID, 1L)
                .option(RakChannelOption.RAK_PACKET_LIMIT, 1)
                .option(RakChannelOption.RAK_PROXY_PROTOCOL, true)
                .option(RakChannelOption.RAK_ADVERTISEMENT, Unpooled.wrappedBuffer("proxy-rate-limit-test".getBytes(StandardCharsets.UTF_8)))
                .handler(new ChannelInitializer<RakServerChannel>() {
                    @Override
                    protected void initChannel(RakServerChannel ch) {
                    }
                })
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                    }
                })
                .bind(serverAddress)
                .awaitUninterruptibly()
                .channel());

        BlockingQueue<DatagramPacket> firstResponses = new LinkedBlockingQueue<>();
        BlockingQueue<DatagramPacket> secondResponses = new LinkedBlockingQueue<>();

        Channel first = this.track(this.createRawClient(firstResponses));
        Channel second = this.track(this.createRawClient(secondResponses));

        InetSocketAddress firstProxiedAddress = new InetSocketAddress("8.8.8.8", 12345);
        InetSocketAddress secondProxiedAddress = new InetSocketAddress("1.1.1.1", 12345);

        first.write(new DatagramPacket(
                this.createProxyProtocolPacket(firstProxiedAddress, serverAddress, this.createUnconnectedPing(1L)),
                serverAddress));
        first.write(new DatagramPacket(this.createUnconnectedPing(2L), serverAddress));
        first.flush();

        DatagramPacket firstReply = firstResponses.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(firstReply, "The first proxied address should receive an initial pong before being rate limited");
        firstReply.release();

        first.writeAndFlush(new DatagramPacket(this.createUnconnectedPing(3L), serverAddress)).awaitUninterruptibly();
        Assertions.assertNull(firstResponses.poll(300, TimeUnit.MILLISECONDS),
                "The first proxied address should be temporarily blocked after exceeding the packet limit");

        second.writeAndFlush(new DatagramPacket(
                this.createProxyProtocolPacket(secondProxiedAddress, serverAddress, this.createUnconnectedPing(4L)),
                serverAddress)).awaitUninterruptibly();
        DatagramPacket secondReply = secondResponses.poll(1, TimeUnit.SECONDS);
        Assertions.assertNotNull(secondReply,
                "A different proxied IP should not be blocked by the first proxied IP's packet limit");
        secondReply.release();
    }

    private Channel createRawClient(BlockingQueue<DatagramPacket> responses) {
        return new Bootstrap()
                .group(this.group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof DatagramPacket packet) {
                            responses.add(packet.retain());
                        }
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .awaitUninterruptibly()
                .channel();
    }

    private Channel track(Channel channel) {
        this.channels.add(channel);
        return channel;
    }

    private ByteBuf createUnconnectedPing(long pingTime) {
        ByteBuf buffer = Unpooled.buffer();
        buffer.writeByte(ID_UNCONNECTED_PING);
        buffer.writeLong(pingTime);
        buffer.writeBytes(RakConstants.DEFAULT_UNCONNECTED_MAGIC);
        buffer.writeLong(123456789L);
        return buffer;
    }

    private ByteBuf createProxyProtocolPacket(InetSocketAddress source, InetSocketAddress destination, ByteBuf payload) {
        ByteBuf header = this.createProxyProtocolHeader(source, destination);
        ByteBuf packet = Unpooled.buffer(header.readableBytes() + payload.readableBytes());
        try {
            packet.writeBytes(header);
            packet.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
            return packet;
        } catch (Throwable throwable) {
            packet.release();
            throw throwable;
        } finally {
            header.release();
            payload.release();
        }
    }

    private ByteBuf createProxyProtocolHeader(InetSocketAddress source, InetSocketAddress destination) {
        byte[] sourceAddress = source.getAddress().getAddress();
        byte[] destinationAddress = destination.getAddress().getAddress();
        if (sourceAddress.length != 4 || destinationAddress.length != 4) {
            throw new IllegalArgumentException("This test only supports IPv4 addresses");
        }

        ByteBuf buffer = Unpooled.buffer(28);
        buffer.writeBytes(PROXY_V2_SIGNATURE);
        buffer.writeByte(0x21);
        buffer.writeByte(0x12);
        buffer.writeShort(12);
        buffer.writeBytes(sourceAddress);
        buffer.writeBytes(destinationAddress);
        buffer.writeShort(source.getPort());
        buffer.writeShort(destination.getPort());
        return buffer;
    }
}
