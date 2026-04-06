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

package org.cloudburstmc.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerOnlineInitialHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.IntStream;

public class RakTests {

    private static final byte[] ADVERTISEMENT = new StringJoiner(";", "", ";")
            .add("MCPE")
            .add("RakNet unit test")
            .add(Integer.toString(542))
            .add("1.19.0")
            .add(Integer.toString(0))
            .add(Integer.toString(4))
            .add(Long.toUnsignedString(ThreadLocalRandom.current().nextLong()))
            .add("C")
            .add("Survival")
            .add("1")
            .add("19132")
            .add("19132")
            .toString().getBytes(StandardCharsets.UTF_8);

    private static final int RESEND_PACKET_ID = 0xFF;
    private final List<Channel> openChannels = new ArrayList<>();
    private EventLoopGroup serverParentGroup;
    private EventLoopGroup serverChildGroup;
    private EventLoopGroup clientGroup;

    private static SimpleChannelInboundHandler<RakMessage> RESEND_HANDLER() {
        return new SimpleChannelInboundHandler<RakMessage>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) throws Exception {
                int packetId = message.content().getUnsignedByte(message.content().readerIndex());
                if (packetId == RESEND_PACKET_ID) {
                    ctx.writeAndFlush(new RakMessage(message.content().retain()));
                } else {
                    ctx.fireChannelRead(message.retain());
                }
            }
        };
    };

    private static SimpleChannelInboundHandler<RakMessage> RESEND_RECEIVER(ByteBuf expectedMessage) {
        return new SimpleChannelInboundHandler<RakMessage>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) throws Exception {
                int packetId = message.content().getUnsignedByte(message.content().readerIndex());
                if (packetId != RESEND_PACKET_ID) {
                    ctx.fireChannelRead(message.retain());
                    return;
                }

                ByteBuf buffer = message.content().skipBytes(1);
                if (ByteBufUtil.equals(buffer, expectedMessage)) {
                    System.out.println("Received message is valid");
                } else {
                    throw new IllegalStateException("Malformed message received\nExpected: " + ByteBufUtil.hexDump(expectedMessage) + "\nReceived: " + ByteBufUtil.hexDump(buffer));
                }
            }
        };
    };

    @BeforeEach
    public void setup() {
        this.serverParentGroup = new NioEventLoopGroup(1);
        this.serverChildGroup = new NioEventLoopGroup(1);
        this.clientGroup = new NioEventLoopGroup(1);
    }

    @AfterEach
    public void teardown() {
        for (int i = this.openChannels.size() - 1; i >= 0; i--) {
            Channel channel = this.openChannels.get(i);
            channel.close().awaitUninterruptibly();
        }
        this.openChannels.clear();
        this.clientGroup.shutdownGracefully().awaitUninterruptibly();
        this.serverChildGroup.shutdownGracefully().awaitUninterruptibly();
        this.serverParentGroup.shutdownGracefully().awaitUninterruptibly();
    }

    private <T extends Channel> T track(T channel) {
        this.openChannels.add(channel);
        return channel;
    }

    private ServerBootstrap serverBootstrap() {
        return new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(this.serverParentGroup, this.serverChildGroup)
                .option(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, new int[]{11})
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, 1)
                .childOption(RakChannelOption.RAK_ORDERING_CHANNELS, 1)
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .option(RakChannelOption.RAK_ADVERTISEMENT, Unpooled.wrappedBuffer(ADVERTISEMENT))
                .handler(new ChannelInitializer<RakServerChannel>() {
                    @Override
                    protected void initChannel(RakServerChannel ch) throws Exception {
                        System.out.println("Initialised server channel");
                    }
                })
                .childHandler(new ChannelInitializer<RakChildChannel>() {
                    @Override
                    protected void initChannel(RakChildChannel ch) throws Exception {
                        Assertions.assertNotSame(ch.parent().eventLoop(), ch.eventLoop(),
                                "Server child channel should run on the child event loop");
                        Assertions.assertSame(ch, ch.rakPipeline().channel(),
                                "Server child RakNet pipeline should be bound to the child channel");
                        System.out.println("Server child channel initialized " + ch.remoteAddress());
                        ch.pipeline().addLast(RESEND_HANDLER());
                    }
                });
    }

    private Bootstrap clientBootstrap(int mtu) {
        return new Bootstrap()
                .channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
                .group(this.clientGroup)
                .option(RakChannelOption.RAK_PROTOCOL_VERSION, 11)
                .option(RakChannelOption.RAK_MTU, mtu)
                .option(RakChannelOption.RAK_ORDERING_CHANNELS, 1);
    }

    private static IntStream validMtu() {
        return IntStream.range(RakConstants.MINIMUM_MTU_SIZE, RakConstants.MAXIMUM_MTU_SIZE)
                .filter(i -> i % 12 == 0);
    }

    public void setupServer() {
        this.track(serverBootstrap()
                .bind(new InetSocketAddress("127.0.0.1", 19132))
                .awaitUninterruptibly()
                .channel());
    }

    @Test
    public void testClientConnect() {
        setupServer();
        int mtu = RakConstants.MAXIMUM_MTU_SIZE;
        System.out.println("Testing client with MTU " + mtu);

        Channel channel = this.track(clientBootstrap(mtu)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) throws Exception {
                        System.out.println("Client channel initialized");
                    }
                })
                .connect(new InetSocketAddress("127.0.0.1", 19132))
                .awaitUninterruptibly()
                .channel());
        Assertions.assertTrue(channel.isActive(), "Client channel should complete the RakNet handshake");
    }

    @Test
    public void testCompatibleClientConnect() {
        setupServer();
        int mtu = RakConstants.MAXIMUM_MTU_SIZE;
        System.out.println("Testing client with MTU " + mtu);

        Channel channel = this.track(clientBootstrap(mtu)
                .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) throws Exception {
                        System.out.println("Client channel initialized");
                    }
                })
                .connect(new InetSocketAddress("127.0.0.1", 19132))
                .awaitUninterruptibly()
                .channel());
        Assertions.assertTrue(channel.isActive(), "Compatibility-mode client should complete the RakNet handshake");
    }

    @Test
    public void testMaxConnectionsRejectsSecondClient() {
        setupServer();

        RakClientChannel firstChannel = (RakClientChannel) this.track(clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) throws Exception {
                        System.out.println("First client channel initialized");
                    }
                })
                .connect(new InetSocketAddress("127.0.0.1", 19132))
                .awaitUninterruptibly()
                .channel());
        // Explicitly wait for the RakNet handshake to complete before starting the second client
        Assertions.assertTrue(firstChannel.getConnectPromise().awaitUninterruptibly(5, TimeUnit.SECONDS),
                "First client RakNet handshake should complete within timeout");
        Assertions.assertTrue(firstChannel.isActive(), "First client should complete the RakNet handshake");

        RakClientChannel secondChannel = (RakClientChannel) this.track(clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) throws Exception {
                        System.out.println("Second client channel initialized");
                    }
                })
                .connect(new InetSocketAddress("127.0.0.1", 19132))
                .awaitUninterruptibly()
                .channel());

        // connect() future only reflects UDP bind/connect, not the RakNet handshake.
        // Wait for the RakNet-level connectPromise which fails when the server rejects us.
        ChannelPromise rakPromise = secondChannel.getConnectPromise();
        Assertions.assertTrue(rakPromise.awaitUninterruptibly(5, TimeUnit.SECONDS),
                "Second client RakNet handshake should complete (success or failure) within timeout");
        Assertions.assertFalse(rakPromise.isSuccess(), "Second client should be rejected when max connections is reached");
        Assertions.assertNotNull(rakPromise.cause(), "Second client rejection should surface the failure reason");
        Assertions.assertTrue(rakPromise.cause().getMessage().contains("No free incoming connections"),
                "Second client should fail with no free incoming connections");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testClosingChildChannelImmediatelyReleasesCapacity() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        server.config().setOption(RakChannelOption.RAK_MAX_CONNECTIONS, 1);

        Constructor<RakChildChannel> constructor = RakChildChannel.class.getDeclaredConstructor(
                InetSocketAddress.class, InetSocketAddress.class, RakServerChannel.class, long.class, int.class, Consumer.class);
        constructor.setAccessible(true);

        InetSocketAddress firstAddress = new InetSocketAddress("127.0.0.1", 19132);
        RakChildChannel child = constructor.newInstance(
                firstAddress,
                new InetSocketAddress("127.0.0.1", 19132),
                server,
                12345L,
                RakConstants.MAXIMUM_MTU_SIZE,
                null);

        Field childChannelMapField = RakServerChannel.class.getDeclaredField("childChannelMap");
        childChannelMapField.setAccessible(true);
        Map<InetSocketAddress, RakChildChannel> childChannelMap =
                (Map<InetSocketAddress, RakChildChannel>) childChannelMapField.get(server);
        childChannelMap.put(firstAddress, child);

        InetSocketAddress secondAddress = new InetSocketAddress("127.0.0.1", 19133);
        Assertions.assertFalse(server.hasCapacityFor(secondAddress),
                "Server should report no capacity before the existing child starts closing");

        Method doClose = RakChildChannel.class.getDeclaredMethod("doClose");
        doClose.setAccessible(true);
        doClose.invoke(child);

        Assertions.assertTrue(server.hasCapacityFor(secondAddress),
                "Server should release capacity as soon as the child enters doClose()");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInvalidConnectionRequestDoesNotDependOnContextChannelType() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        Constructor<RakChildChannel> constructor = RakChildChannel.class.getDeclaredConstructor(
                InetSocketAddress.class, InetSocketAddress.class, RakServerChannel.class, long.class, int.class, Consumer.class);
        constructor.setAccessible(true);

        RakChildChannel child = constructor.newInstance(
                new InetSocketAddress("127.0.0.1", 19132),
                new InetSocketAddress("127.0.0.1", 19132),
                server,
                12345L,
                RakConstants.MAXIMUM_MTU_SIZE,
                null);

        List<ByteBuf> outboundMessages = new ArrayList<>();
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("capture-outbound", new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (msg instanceof ByteBuf) {
                    outboundMessages.add(((ByteBuf) msg).retain());
                }
                ReferenceCountUtil.release(msg);
                promise.setSuccess();
            }
        });
        channel.pipeline().addLast(RakSessionCodec.NAME, new ChannelDuplexHandler());
        channel.pipeline().addLast("online-initial", new RakServerOnlineInitialHandler(child));

        ByteBuf request = Unpooled.buffer(18);
        request.writeByte(RakConstants.ID_CONNECTION_REQUEST);
        request.writeLong(54321L);
        request.writeLong(System.currentTimeMillis());
        request.writeBoolean(false);

        EncapsulatedPacket packet = EncapsulatedPacket.newInstance();
        packet.setBuffer(request);

        Assertions.assertDoesNotThrow(() -> channel.writeInbound(packet),
                "Invalid connection requests should be rejected without assuming ctx.channel() is the parent server channel");
        Assertions.assertFalse(outboundMessages.isEmpty(), "Server should emit CONNECTION_REQUEST_FAILED for an invalid online request");
        Assertions.assertEquals(RakConstants.ID_CONNECTION_REQUEST_FAILED, outboundMessages.get(0).getUnsignedByte(0));

        outboundMessages.forEach(ByteBuf::release);
        channel.finishAndReleaseAll();
    }


    @ParameterizedTest
    @MethodSource("validMtu")
    public void testClientResend(int mtu) {
        setupServer();
        System.out.println("Testing client with MTU " + mtu);

        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[mtu * 16];
        random.nextBytes(bytes);
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);

        ChannelHandler sender = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ByteBuf buf = buffer.alloc().buffer(buffer.readableBytes() + 1);
                buf.writeByte(RESEND_PACKET_ID);
                buf.writeBytes(buffer.slice());

                ctx.channel().writeAndFlush(new RakMessage(buf));
            }
        };

        ChannelInitializer<RakClientChannel> initializer = new ChannelInitializer<RakClientChannel>() {
            @Override
            protected void initChannel(RakClientChannel ch) throws Exception {
                ch.pipeline().addLast(RESEND_RECEIVER(buffer));
                ch.pipeline().addLast(sender);
            }
        };

        Channel channel = this.track(clientBootstrap(mtu)
                .handler(initializer)
                .connect(new InetSocketAddress("127.0.0.1", 19132))
                .awaitUninterruptibly()
                .channel());
        Assertions.assertTrue(channel.isActive(), "Resend test client should complete the RakNet handshake");

        Object o = new Object();
        synchronized (o) {
            try {
                o.wait(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
