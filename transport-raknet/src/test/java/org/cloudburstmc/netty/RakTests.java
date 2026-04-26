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
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
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
import java.net.SocketAddress;
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

    private RakChildChannel newChildChannel(RakServerChannel server, InetSocketAddress remoteAddress, InetSocketAddress localAddress) throws Exception {
        Constructor<RakChildChannel> constructor = RakChildChannel.class.getDeclaredConstructor(
                InetSocketAddress.class, InetSocketAddress.class, RakServerChannel.class, long.class, int.class, Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                remoteAddress,
                localAddress,
                server,
                12345L,
                RakConstants.MAXIMUM_MTU_SIZE,
                null);
    }

    @SuppressWarnings("unchecked")
    private Map<SocketAddress, RakChildChannel> childChannelMap(RakServerChannel server) throws Exception {
        Field childChannelMapField = RakServerChannel.class.getDeclaredField("childChannelMap");
        childChannelMapField.setAccessible(true);
        return (Map<SocketAddress, RakChildChannel>) childChannelMapField.get(server);
    }

    private RakChildChannel registerChildChannel(RakChildChannel child) {
        this.serverChildGroup.register(child).syncUninterruptibly();
        return this.track(child);
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
    public void testClientConnectFailsWhenParentConnectFails() {
        Channel occupied = this.track(new Bootstrap()
                .group(this.clientGroup)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter())
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .syncUninterruptibly()
                .channel());
        InetSocketAddress occupiedAddress = (InetSocketAddress) occupied.localAddress();

        ChannelFuture connectFuture = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) {
                    }
                })
                .connect(new InetSocketAddress("127.0.0.1", 19132), occupiedAddress);
        RakClientChannel client = (RakClientChannel) this.track(connectFuture.channel());

        Assertions.assertTrue(connectFuture.awaitUninterruptibly(2, TimeUnit.SECONDS),
                "Client connect future should fail promptly when the parent UDP connect fails");
        Assertions.assertFalse(connectFuture.isSuccess(), "Client connect future should surface the parent failure");
        Assertions.assertTrue(client.getConnectPromise().isDone(),
                "RakNet connect promise should also be completed when the parent connect fails");
        Assertions.assertFalse(client.getConnectPromise().isSuccess(),
                "RakNet connect promise should fail when the parent connect fails");
        Assertions.assertNotNull(client.getConnectPromise().cause(),
                "RakNet connect promise should retain the parent failure cause");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testClosingChildChannelImmediatelyReleasesCapacity() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        server.config().setOption(RakChannelOption.RAK_MAX_CONNECTIONS, 1);

        InetSocketAddress firstAddress = new InetSocketAddress("127.0.0.1", 19132);
        RakChildChannel child = newChildChannel(
                server,
                firstAddress,
                new InetSocketAddress("127.0.0.1", 19132));

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
    public void testChildClosedDuringInitializationDoesNotRemainInMap() throws Exception {
        RakServerChannel server = (RakServerChannel) this.track(new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(this.serverParentGroup, this.serverParentGroup)
                .option(RakChannelOption.RAK_MAX_CONNECTIONS, 1)
                .childHandler(new ChannelInitializer<RakChildChannel>() {
                    @Override
                    protected void initChannel(RakChildChannel ch) {
                        ch.close();
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .syncUninterruptibly()
                .channel());

        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 30000);
        InetSocketAddress localAddress = server.localAddress();
        RakServerChannel.ChildChannelCreationResult result = server.eventLoop()
                .submit(() -> server.createChildChannel(
                        remoteAddress, localAddress, 12345L, RakConstants.MAXIMUM_MTU_SIZE, 11))
                .get(1, TimeUnit.SECONDS);

        if (result.channel() != null) {
            result.channel().closeFuture().awaitUninterruptibly(1, TimeUnit.SECONDS);
        }
        Assertions.assertNull(server.getChildChannel(remoteAddress),
                "Child closed during initialization should not remain in the server route map");
        Assertions.assertTrue(server.hasCapacityFor(new InetSocketAddress("127.0.0.1", 30001)),
                "Closed initialization child should not consume max-connection capacity");
    }

    @Test
    public void testServerRouteReleasesBufferWhenChildEventLoopRejects() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 30000);
        InetSocketAddress localAddress = new InetSocketAddress("127.0.0.1", 19132);
        RakChildChannel child = newChildChannel(server, remoteAddress, localAddress);
        EventLoopGroup rejectingGroup = new DefaultEventLoopGroup(1);
        ByteBuf payload = Unpooled.buffer(1).writeByte(1);
        EmbeddedChannel routeChannel = new EmbeddedChannel(new org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRouteHandler(server));

        try {
            rejectingGroup.register(child).syncUninterruptibly();
            rejectingGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
            childChannelMap(server).put(remoteAddress, child);

            Assertions.assertDoesNotThrow(
                    () -> routeChannel.writeInbound(new DatagramPacket(payload, localAddress, remoteAddress)),
                    "Rejected child event loop execution should be handled by the route handler");
            Assertions.assertEquals(0, payload.refCnt(),
                    "Route handler should release the retained payload if execute rejects it");
        } finally {
            childChannelMap(server).remove(remoteAddress, child);
            if (payload.refCnt() > 0) {
                payload.release(payload.refCnt());
            }
            routeChannel.finishAndReleaseAll();
            rejectingGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void testRakPipelineReleasesMessageWhenChildEventLoopRejects() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        RakChildChannel child = newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 30000),
                new InetSocketAddress("127.0.0.1", 19132));
        EventLoopGroup rejectingGroup = new DefaultEventLoopGroup(1);
        ByteBuf message = Unpooled.buffer(1).writeByte(1);
        Method onUnhandledInboundMessage = child.rakPipeline().getClass()
                .getDeclaredMethod("onUnhandledInboundMessage", ChannelHandlerContext.class, Object.class);
        onUnhandledInboundMessage.setAccessible(true);

        try {
            rejectingGroup.register(child).syncUninterruptibly();
            rejectingGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();

            Assertions.assertDoesNotThrow(
                    () -> onUnhandledInboundMessage.invoke(child.rakPipeline(), null, message),
                    "Rejected child event loop execution should be handled by the RakNet pipeline");
            Assertions.assertEquals(0, message.refCnt(),
                    "RakNet pipeline should release the retained message if execute rejects it");
        } finally {
            if (message.refCnt() > 0) {
                message.release(message.refCnt());
            }
            rejectingGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void testUnhandledRakPipelineExceptionClosesChildChannel() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        RakChildChannel child = registerChildChannel(newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 19132),
                new InetSocketAddress("127.0.0.1", 19132)));

        Assertions.assertDoesNotThrow(
                () -> child.rakPipeline().fireExceptionCaught(new IllegalStateException("boom")),
                "Unhandled RakNet pipeline exceptions should not throw while closing the child channel");
        Assertions.assertTrue(child.closeFuture().awaitUninterruptibly(5, TimeUnit.SECONDS),
                "Unhandled RakNet pipeline exceptions should close the child channel");
        Assertions.assertFalse(child.isOpen(), "Child channel should be closed after an unhandled RakNet pipeline exception");
    }

    @Test
    public void testChildPipelineExceptionClosesChildChannel() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        RakChildChannel child = registerChildChannel(newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 19132),
                new InetSocketAddress("127.0.0.1", 19132)));
        child.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                throw new IllegalStateException("boom");
            }
        });

        Assertions.assertDoesNotThrow(
                () -> child.rakPipeline().fireChannelRead("message"),
                "Child pipeline exceptions should not escape the RakNet pipeline");
        Assertions.assertTrue(child.closeFuture().awaitUninterruptibly(5, TimeUnit.SECONDS),
                "Child pipeline exceptions should close the child channel");
        Assertions.assertFalse(child.isOpen(), "Child channel should be closed after a child pipeline exception");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testInvalidConnectionRequestDoesNotDependOnContextChannelType() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());

        RakChildChannel child = newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 19132),
                new InetSocketAddress("127.0.0.1", 19132));

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
