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
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ReferenceCountUtil;
import org.cloudburstmc.netty.channel.TransportDisconnectReason;
import org.cloudburstmc.netty.channel.raknet.*;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerMetrics;
import org.cloudburstmc.netty.channel.raknet.config.RakServerThrottle;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakDatagramPacket;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakAcknowledgeHandler;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakDatagramCodec;
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec;
import org.cloudburstmc.netty.handler.codec.raknet.server.RakServerOnlineInitialHandler;
import org.cloudburstmc.netty.util.BitQueue;
import org.cloudburstmc.netty.util.FastBinaryMinHeap;
import org.cloudburstmc.netty.util.RoundRobinArray;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    private static final int WARMUP_PACKET_ID = 0xFE;
    private static final int BURST_PACKET_ID = 0xFD;

    // Shared loop for tests that drive raw datagram clients; shut down once for the whole class.
    private static EventLoopGroup group;
    private Channel serverChannel;

    @BeforeAll
    public static void setupGroup() {
        group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    @AfterAll
    public static void shutdownGroup() {
        group.shutdownGracefully().awaitUninterruptibly();
    }

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

    private static SimpleChannelInboundHandler<RakMessage> RESEND_RECEIVER(ByteBuf expectedMessage,
                                                                           CountDownLatch received) {
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
                    received.countDown();
                } else {
                    throw new IllegalStateException("Malformed message received\nExpected: " + ByteBufUtil.hexDump(expectedMessage) + "\nReceived: " + ByteBufUtil.hexDump(buffer));
                }
            }
        };
    };

    @BeforeEach
    public void setup() {
        this.serverParentGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.serverChildGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    @AfterEach
    public void teardown() {
        for (int i = this.openChannels.size() - 1; i >= 0; i--) {
            Channel channel = this.openChannels.get(i);
            channel.close().awaitUninterruptibly(2, TimeUnit.SECONDS);
        }
        this.openChannels.clear();
        // Bounded shutdown: a live session tick keeps resetting the default quiet period forever
        this.clientGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        this.serverChildGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        this.serverParentGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
    }

    private <T extends Channel> T track(T channel) {
        this.openChannels.add(channel);
        return channel;
    }

    private RakChildChannel newChildChannel(RakServerChannel server, InetSocketAddress remoteAddress, InetSocketAddress localAddress) throws Exception {
        Constructor<RakChildChannel> constructor = RakChildChannel.class.getDeclaredConstructor(
                InetSocketAddress.class, InetSocketAddress.class, InetSocketAddress.class, RakServerChannel.class, long.class, int.class, Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                remoteAddress,
                localAddress,
                remoteAddress,
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

    private void trackChildCloseCallbacks(RakServerChannel server, AtomicInteger metricCloses, AtomicInteger throttleCloses) {
        server.config().setMetrics(new RakServerMetrics() {
            @Override
            public void channelClose(InetSocketAddress address) {
                metricCloses.incrementAndGet();
            }
        });
        server.config().setThrottle(new RakServerThrottle() {
            @Override
            public boolean accept(InetSocketAddress address) {
                return true;
            }

            @Override
            public void closed(InetSocketAddress address) {
                throttleCloses.incrementAndGet();
            }
        });
    }

    private void assertChildClosedAndCleanedUp(RakServerChannel server, RakChildChannel child, SocketAddress remoteAddress,
                                               AtomicInteger metricCloses, AtomicInteger throttleCloses) {
        Assertions.assertFalse(child.isOpen());
        Assertions.assertNull(server.getChildChannel(remoteAddress));
        Assertions.assertNull(child.rakPipeline().get(RakSessionCodec.class));
        Assertions.assertEquals(1, metricCloses.get());
        Assertions.assertEquals(1, throttleCloses.get());
    }

    private RakChildChannel registerChildChannel(RakChildChannel child) {
        this.serverChildGroup.register(child).syncUninterruptibly();
        return this.track(child);
    }

    @SuppressWarnings("unchecked")
    private RakSessionCodec newInboundSessionCodec(RakChildChannel child) throws Exception {
        RakSessionCodec sessionCodec = new RakSessionCodec(child);
        this.setSessionField(sessionCodec, "state", RakState.CONNECTED);
        this.setSessionField(sessionCodec, "slidingWindow", new RakSlidingWindow(RakConstants.MAXIMUM_MTU_SIZE));
        this.setSessionField(sessionCodec, "outgoingAcks", new ArrayDeque<>());
        this.setSessionField(sessionCodec, "outgoingNaks", new ArrayDeque<>());
        this.setSessionField(sessionCodec, "reliableDatagramQueue", new BitQueue(512));
        this.setSessionField(sessionCodec, "splitPackets", new RoundRobinArray<>(256));
        this.setSessionField(sessionCodec, "orderReadIndex", new int[1]);
        this.setSessionField(sessionCodec, "orderWriteIndex", new int[1]);
        this.setSessionField(sessionCodec, "orderingHeaps", new FastBinaryMinHeap[]{new FastBinaryMinHeap<EncapsulatedPacket>(64)});
        return sessionCodec;
    }

    private void setSessionField(RakSessionCodec sessionCodec, String name, Object value) throws Exception {
        Field field = RakSessionCodec.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(sessionCodec, value);
    }

    private void handleDatagram(RakSessionCodec sessionCodec, ChannelHandlerContext ctx, RakDatagramPacket datagram) throws Exception {
        Method handleDatagram = RakSessionCodec.class.getDeclaredMethod(
                "handleDatagram", ChannelHandlerContext.class, RakDatagramPacket.class);
        handleDatagram.setAccessible(true);
        handleDatagram.invoke(sessionCodec, ctx, datagram);
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

    public InetSocketAddress setupServer() {
        this.serverChannel = this.track(serverBootstrap()
                .bind(new InetSocketAddress("127.0.0.1", 19132))
                .awaitUninterruptibly()
                .channel());
        return (InetSocketAddress) this.serverChannel.localAddress();
    }

    private InetSocketAddress setupServer(BlockingQueue<RakChildChannel> children) {
        return bind(serverBootstrap().childHandler(new ChannelInitializer<RakChildChannel>() {
            @Override
            public void initChannel(RakChildChannel ch) {
                children.add(ch);
            }
        }));
    }

    // An event would replace the reason the disconnecting code already holds, such as a kick message
    private static ChannelInboundHandler reasonRecorder(AtomicBoolean fired) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof TransportDisconnectReason) {
                    fired.set(true);
                }
            }
        };
    }

    private InetSocketAddress bind(ServerBootstrap bootstrap) {
        this.serverChannel = bootstrap
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .syncUninterruptibly()
                .channel();
        this.track(this.serverChannel);
        return (InetSocketAddress) this.serverChannel.localAddress();
    }

    @Test
    public void testClientConnect() {
        InetSocketAddress address = setupServer();
        int mtu = RakConstants.MAXIMUM_MTU_SIZE;
        System.out.println("Testing client with MTU " + mtu);

        Channel channel = clientBootstrap(mtu)
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) throws Exception {
                        System.out.println("Client channel initialized");
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();

        Assertions.assertTrue(channel.isActive(), "Client should connect");
        channel.close().awaitUninterruptibly();
    }

    @Test
    public void testCompatibleClientConnect() {
        InetSocketAddress address = setupServer();
        int mtu = RakConstants.MAXIMUM_MTU_SIZE;
        System.out.println("Testing client with MTU " + mtu);

        Channel channel = clientBootstrap(mtu)
                .option(RakChannelOption.RAK_COMPATIBILITY_MODE, true)
                .option(RakChannelOption.RAK_GUID, ThreadLocalRandom.current().nextLong())
                .handler(new ChannelInitializer<RakClientChannel>() {
                    @Override
                    protected void initChannel(RakClientChannel ch) throws Exception {
                        System.out.println("Client channel initialized");
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();

        Assertions.assertTrue(channel.isActive(), "Client should connect in compatibility mode");
        channel.close().awaitUninterruptibly();
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
    public void testClosingUnregisteredChildReleasesCapacityAndCleansUp() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        try {
            server.config().setOption(RakChannelOption.RAK_MAX_CONNECTIONS, 1);
            AtomicInteger metricCloses = new AtomicInteger();
            AtomicInteger throttleCloses = new AtomicInteger();
            this.trackChildCloseCallbacks(server, metricCloses, throttleCloses);

            InetSocketAddress firstAddress = new InetSocketAddress("127.0.0.1", 19132);
            RakChildChannel child = this.newChildChannel(server, firstAddress, firstAddress);
            this.childChannelMap(server).put(firstAddress, child);
            Assertions.assertFalse(child.isRegistered());

            InetSocketAddress secondAddress = new InetSocketAddress("127.0.0.1", 19133);
            Assertions.assertFalse(server.hasCapacityFor(secondAddress),
                    "Server should report no capacity before the existing child starts closing");

            ChannelFuture closeFuture = child.close();
            Assertions.assertTrue(closeFuture.awaitUninterruptibly(5, TimeUnit.SECONDS));
            Assertions.assertTrue(closeFuture.isSuccess());
            this.assertChildClosedAndCleanedUp(server, child, firstAddress, metricCloses, throttleCloses);
            Assertions.assertTrue(server.hasCapacityFor(secondAddress),
                    "Server should release capacity as soon as the child enters doClose()");
        } finally {
            server.parent().unsafe().closeForcibly();
        }
    }

    @Test
    public void testForceClosedUnregisteredChildCleansUp() throws Exception {
        RakServerChannel server = (RakServerChannel) this.track(new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(this.serverParentGroup, this.serverChildGroup)
                .childOption(RakChannelOption.RAK_MAX_CHANNELS, 0)
                .childHandler(new ChannelInboundHandlerAdapter())
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .syncUninterruptibly()
                .channel());
        AtomicInteger metricCloses = new AtomicInteger();
        AtomicInteger throttleCloses = new AtomicInteger();
        this.trackChildCloseCallbacks(server, metricCloses, throttleCloses);

        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 30000);
        RakServerChannel.ChildChannelCreationResult result = server.eventLoop()
                .submit(() -> server.createChildChannel(
                        remoteAddress, server.localAddress(), 12345L, RakConstants.MAXIMUM_MTU_SIZE, 11))
                .get(5, TimeUnit.SECONDS);
        RakChildChannel child = result.channel();

        Assertions.assertNotNull(child);
        Assertions.assertFalse(child.isRegistered());
        this.assertChildClosedAndCleanedUp(server, child, remoteAddress, metricCloses, throttleCloses);
    }

    @Test
    public void testServerCloseBeforeChildRegistrationCleansUp() throws Exception {
        AtomicReference<RakChildChannel> childRef = new AtomicReference<>();
        AtomicReference<ChannelFuture> serverCloseRef = new AtomicReference<>();
        RakServerChannel server = (RakServerChannel) this.track(new ServerBootstrap()
                .channelFactory(RakChannelFactory.server(NioDatagramChannel.class))
                .group(this.serverParentGroup, this.serverChildGroup)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof RakChildChannel) {
                            childRef.set((RakChildChannel) msg);
                            serverCloseRef.set(ctx.channel().close());
                            return;
                        }
                        ctx.fireChannelRead(msg);
                    }
                })
                .childHandler(new ChannelInboundHandlerAdapter())
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .syncUninterruptibly()
                .channel());
        AtomicInteger metricCloses = new AtomicInteger();
        AtomicInteger throttleCloses = new AtomicInteger();
        this.trackChildCloseCallbacks(server, metricCloses, throttleCloses);

        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 30000);
        server.eventLoop().submit(() -> server.createChildChannel(
                remoteAddress, server.localAddress(), 12345L, RakConstants.MAXIMUM_MTU_SIZE, 11))
                .get(5, TimeUnit.SECONDS);

        RakChildChannel child = childRef.get();
        Assertions.assertNotNull(child);
        Assertions.assertFalse(child.isRegistered());
        Assertions.assertTrue(child.closeFuture().awaitUninterruptibly(5, TimeUnit.SECONDS));
        Assertions.assertTrue(child.closeFuture().isSuccess());
        this.assertChildClosedAndCleanedUp(server, child, remoteAddress, metricCloses, throttleCloses);

        ChannelFuture serverClose = serverCloseRef.get();
        Assertions.assertNotNull(serverClose);
        Assertions.assertTrue(serverClose.awaitUninterruptibly(5, TimeUnit.SECONDS));
        Assertions.assertTrue(serverClose.isSuccess());
    }

    @Test
    public void testRejectedUnregisteredChildCleansUp() throws Exception {
        AtomicReference<RakChildChannel> childRef = new AtomicReference<>();
        IllegalStateException readFailure = new IllegalStateException("Failed to forward child channel");
        RakServerChannel server = new RakServerChannel(
                new NioDatagramChannel(), channel -> childRef.set((RakChildChannel) channel)) {
            @Override
            public ChannelPipeline pipeline() {
                throw readFailure;
            }
        };
        try {
            AtomicInteger metricCloses = new AtomicInteger();
            AtomicInteger throttleCloses = new AtomicInteger();
            this.trackChildCloseCallbacks(server, metricCloses, throttleCloses);
            InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 30000);

            IllegalStateException thrown = Assertions.assertThrows(IllegalStateException.class,
                    () -> server.createChildChannel(
                            remoteAddress, remoteAddress, 12345L, RakConstants.MAXIMUM_MTU_SIZE, 11));

            Assertions.assertSame(readFailure, thrown);
            this.assertChildClosedAndCleanedUp(
                    server, childRef.get(), remoteAddress, metricCloses, throttleCloses);
        } finally {
            server.parent().unsafe().closeForcibly();
        }
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
        List<String> metricEvents = new ArrayList<>();
        server.config().setMetrics(new RakServerMetrics() {
            @Override
            public void channelOpen(InetSocketAddress address) {
                metricEvents.add("open");
            }

            @Override
            public void channelClose(InetSocketAddress address) {
                metricEvents.add("close");
            }
        });

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
        Assertions.assertEquals(List.of("open", "close"), metricEvents,
                "Metrics should report channel open before an inline close");
    }

    @Test
    public void testServerRouteReleasesBufferWhenChildEventLoopRejects() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 30000);
        InetSocketAddress localAddress = new InetSocketAddress("127.0.0.1", 19132);
        RakChildChannel child = newChildChannel(server, remoteAddress, localAddress);
        EventLoopGroup rejectingGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
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
    public void testServerRouteDropsDatagramWhenChildInboundQueueLimitExceeded() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 30000);
        InetSocketAddress localAddress = new InetSocketAddress("127.0.0.1", 19132);
        RakChildChannel child = newChildChannel(server, remoteAddress, localAddress);
        child.config().setOption(RakChannelOption.RAK_CHILD_INBOUND_QUEUE_LIMIT, 1);
        EventLoopGroup childGroup = new DefaultEventLoopGroup(1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch routed = new CountDownLatch(1);
        ByteBuf firstPayload = Unpooled.buffer(1).writeByte(1);
        ByteBuf droppedPayload = Unpooled.buffer(1).writeByte(2);
        EmbeddedChannel routeChannel = new EmbeddedChannel(new org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRouteHandler(server));

        child.rakPipeline().addFirst("capture-routed", new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            }

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                routed.countDown();
            }
        });

        try {
            childGroup.register(child).syncUninterruptibly();
            child.eventLoop().execute(() -> {
                blockerStarted.countDown();
                try {
                    releaseBlocker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Assertions.assertTrue(blockerStarted.await(5, TimeUnit.SECONDS),
                    "Child event loop blocker should start before routing datagrams");
            childChannelMap(server).put(remoteAddress, child);

            routeChannel.writeInbound(new DatagramPacket(firstPayload, localAddress, remoteAddress));
            routeChannel.writeInbound(new DatagramPacket(droppedPayload, localAddress, remoteAddress));

            Assertions.assertEquals(1, firstPayload.refCnt(),
                    "First routed payload should stay retained while queued on the child event loop");
            Assertions.assertEquals(0, droppedPayload.refCnt(),
                    "Datagram over the child inbound queue limit should be dropped without retaining its payload");
        } finally {
            releaseBlocker.countDown();
            if (child.isRegistered()) {
                child.eventLoop().submit(() -> {
                }).syncUninterruptibly();
            }
            routed.await(5, TimeUnit.SECONDS);
            childChannelMap(server).remove(remoteAddress, child);
            if (firstPayload.refCnt() > 0) {
                firstPayload.release(firstPayload.refCnt());
            }
            if (droppedPayload.refCnt() > 0) {
                droppedPayload.release(droppedPayload.refCnt());
            }
            routeChannel.finishAndReleaseAll();
            childGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void testServerRouteDropsDatagramWhenChildInboundQueueBytesExceeded() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 30000);
        InetSocketAddress localAddress = new InetSocketAddress("127.0.0.1", 19132);
        RakChildChannel child = newChildChannel(server, remoteAddress, localAddress);
        child.config().setOption(RakChannelOption.RAK_CHILD_INBOUND_QUEUE_BYTES, 1);
        ByteBuf payload = Unpooled.buffer(2).writeByte(1).writeByte(2);
        EmbeddedChannel routeChannel = new EmbeddedChannel(new org.cloudburstmc.netty.handler.codec.raknet.server.RakServerRouteHandler(server));

        try {
            childChannelMap(server).put(remoteAddress, child);

            routeChannel.writeInbound(new DatagramPacket(payload, localAddress, remoteAddress));

            Assertions.assertEquals(0, payload.refCnt(),
                    "Datagram over the child inbound queue byte limit should be dropped without retaining its payload");
        } finally {
            childChannelMap(server).remove(remoteAddress, child);
            if (payload.refCnt() > 0) {
                payload.release(payload.refCnt());
            }
            routeChannel.finishAndReleaseAll();
        }
    }

    @Test
    public void testChildRoutedDatagramAcquireReleaseAllowsNextDatagram() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        RakChildChannel child = newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 30000),
                new InetSocketAddress("127.0.0.1", 19132));
        child.config().setOption(RakChannelOption.RAK_CHILD_INBOUND_QUEUE_LIMIT, 1);

        Assertions.assertTrue(child.tryAcquireRoutedDatagram(1),
                "First routed datagram should acquire the only pending slot");
        Assertions.assertFalse(child.tryAcquireRoutedDatagram(1),
                "Second routed datagram should be rejected while the only slot is pending");
        child.releaseRoutedDatagram(1);
        Assertions.assertTrue(child.tryAcquireRoutedDatagram(1),
                "Releasing a routed datagram should make the pending slot available again");
        child.releaseRoutedDatagram(1);
    }

    @Test
    public void testRakPipelineReleasesMessageWhenChildEventLoopRejects() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        RakChildChannel child = newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 30000),
                new InetSocketAddress("127.0.0.1", 19132));
        EventLoopGroup rejectingGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
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

    @Test
    public void testReliableSequencedDatagramDecodesWithoutSignedReliabilityOverflow() {
        EmbeddedChannel channel = new EmbeddedChannel(new RakDatagramCodec());
        ByteBuf datagram = Unpooled.buffer();
        datagram.writeByte(RakConstants.FLAG_VALID);
        datagram.writeMediumLE(0);
        datagram.writeByte(RakReliability.RELIABLE_SEQUENCED.ordinal() << 5);
        datagram.writeShort(8);
        datagram.writeMediumLE(0);
        datagram.writeMediumLE(0);
        datagram.writeMediumLE(0);
        datagram.writeByte(0);
        datagram.writeByte(0x42);

        Assertions.assertDoesNotThrow(() -> channel.writeInbound(datagram),
                "High-bit reliability values should decode without signed byte overflow");

        RakDatagramPacket packet = channel.readInbound();
        try {
            Assertions.assertNotNull(packet, "A valid RakNet datagram should decode to a RakDatagramPacket");
            Assertions.assertEquals(RakReliability.RELIABLE_SEQUENCED,
                    packet.getPackets().get(0).getReliability(),
                    "Reliability id 4 should decode as RELIABLE_SEQUENCED");
        } finally {
            ReferenceCountUtil.release(packet);
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testHugeReliableGapDoesNotGrowReceiveQueue() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        RakChildChannel child = newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 19132),
                new InetSocketAddress("127.0.0.1", 19132));
        RakSessionCodec sessionCodec = this.newInboundSessionCodec(child);
        EmbeddedChannel contextChannel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());

        RakDatagramPacket datagram = RakDatagramPacket.newInstance();
        datagram.setSequenceIndex(0);

        EncapsulatedPacket encapsulated = EncapsulatedPacket.newInstance();
        encapsulated.setReliability(RakReliability.RELIABLE);
        encapsulated.setReliabilityIndex(65536);
        encapsulated.setBuffer(Unpooled.buffer(1).writeByte(0x42));
        datagram.getPackets().add(encapsulated);

        Assertions.assertDoesNotThrow(() -> this.handleDatagram(
                        sessionCodec, contextChannel.pipeline().firstContext(), datagram),
                "A huge reliable index gap should not throw through the session pipeline");

        Field reliableDatagramQueueField = RakSessionCodec.class.getDeclaredField("reliableDatagramQueue");
        reliableDatagramQueueField.setAccessible(true);
        BitQueue reliableDatagramQueue = (BitQueue) reliableDatagramQueueField.get(sessionCodec);
        Assertions.assertTrue(reliableDatagramQueue.size() <= 512,
                "A huge reliable index gap should not be allowed to grow the receive queue");

        datagram.release();
        contextChannel.finishAndReleaseAll();
    }

    @Test
    public void testInvalidOrderingChannelDoesNotThrowOrQueuePacket() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        RakChildChannel child = newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 19132),
                new InetSocketAddress("127.0.0.1", 19132));
        RakSessionCodec sessionCodec = this.newInboundSessionCodec(child);
        EmbeddedChannel contextChannel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());

        RakDatagramPacket datagram = RakDatagramPacket.newInstance();
        datagram.setSequenceIndex(0);

        EncapsulatedPacket encapsulated = EncapsulatedPacket.newInstance();
        encapsulated.setReliability(RakReliability.RELIABLE_ORDERED);
        encapsulated.setReliabilityIndex(0);
        encapsulated.setOrderingIndex(0);
        encapsulated.setOrderingChannel((short) 1);
        encapsulated.setBuffer(Unpooled.buffer(1).writeByte(0x42));
        datagram.getPackets().add(encapsulated);

        Assertions.assertDoesNotThrow(() -> this.handleDatagram(
                        sessionCodec, contextChannel.pipeline().firstContext(), datagram),
                "An out-of-range ordering channel should be treated as a bad packet, not an uncaught exception");

        Field orderingHeapsField = RakSessionCodec.class.getDeclaredField("orderingHeaps");
        orderingHeapsField.setAccessible(true);
        FastBinaryMinHeap<EncapsulatedPacket>[] orderingHeaps =
                (FastBinaryMinHeap<EncapsulatedPacket>[]) orderingHeapsField.get(sessionCodec);
        Assertions.assertEquals(0, orderingHeaps[0].size(),
                "Invalid ordering channels should not enqueue packets into any ordering heap");

        datagram.release();
        contextChannel.finishAndReleaseAll();
    }

    @Test
    public void testIncompleteSplitPacketsAreCappedPerSession() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        RakChildChannel child = newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 19132),
                new InetSocketAddress("127.0.0.1", 19132));
        RakSessionCodec sessionCodec = this.newInboundSessionCodec(child);
        EmbeddedChannel contextChannel = new EmbeddedChannel(new ChannelInboundHandlerAdapter());

        for (int i = 0; i < 65; i++) {
            RakDatagramPacket datagram = RakDatagramPacket.newInstance();
            datagram.setSequenceIndex(i);

            EncapsulatedPacket encapsulated = EncapsulatedPacket.newInstance();
            encapsulated.setReliability(RakReliability.RELIABLE);
            encapsulated.setReliabilityIndex(i);
            encapsulated.setSplit(true);
            encapsulated.setPartCount(2);
            encapsulated.setPartId(i);
            encapsulated.setPartIndex(0);
            encapsulated.setBuffer(Unpooled.buffer(1).writeByte(0x42));
            datagram.getPackets().add(encapsulated);

            this.handleDatagram(sessionCodec, contextChannel.pipeline().firstContext(), datagram);
            datagram.release();
        }

        Field splitPacketsField = RakSessionCodec.class.getDeclaredField("splitPackets");
        splitPacketsField.setAccessible(true);
        int incompleteSplits = 0;
        for (Object helper : (Iterable<?>) splitPacketsField.get(sessionCodec)) {
            if (helper != null) {
                incompleteSplits++;
            }
        }
        Assertions.assertTrue(incompleteSplits <= 64,
                "A session should cap incomplete split packet reassembly state");

        contextChannel.finishAndReleaseAll();
    }

    @Test
    public void testTruncatedAcknowledgePacketDoesNotThrowThroughPipeline() throws Exception {
        ByteBuf[] packets = {
                Unpooled.buffer(1).writeByte(RakConstants.FLAG_VALID | RakConstants.FLAG_ACK),
                Unpooled.buffer(2)
                        .writeByte(RakConstants.FLAG_VALID | RakConstants.FLAG_ACK)
                        .writeByte(0),
                Unpooled.buffer(3)
                        .writeByte(RakConstants.FLAG_VALID | RakConstants.FLAG_ACK)
                        .writeShort(1)
        };

        for (ByteBuf ack : packets) {
            RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
            RakChildChannel child = newChildChannel(
                    server,
                    new InetSocketAddress("127.0.0.1", 19132),
                    new InetSocketAddress("127.0.0.1", 19132));
            RakSessionCodec sessionCodec = this.newInboundSessionCodec(child);
            EmbeddedChannel channel = new EmbeddedChannel(new RakAcknowledgeHandler(sessionCodec));

            Assertions.assertDoesNotThrow(() -> channel.writeInbound(ack),
                    "Truncated ACK/NACK payloads should be rejected without an uncaught decoder exception");
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void testReversedAcknowledgeRangeClosesWithoutDisconnectNotification() throws Exception {
        RakServerChannel server = new RakServerChannel(new NioDatagramChannel());
        RakChildChannel child = newChildChannel(
                server,
                new InetSocketAddress("127.0.0.1", 19132),
                new InetSocketAddress("127.0.0.1", 19132));
        AtomicReference<TransportDisconnectReason> closeReason = new AtomicReference<>();
        AtomicBoolean disconnectCalled = new AtomicBoolean();
        RakSessionCodec sessionCodec = new RakSessionCodec(child) {
            @Override
            public void close(TransportDisconnectReason reason) {
                closeReason.set(reason);
            }

            @Override
            public void disconnect(TransportDisconnectReason reason) {
                disconnectCalled.set(true);
            }
        };
        this.setSessionField(sessionCodec, "incomingAcks", new ArrayDeque<>());
        EmbeddedChannel channel = new EmbeddedChannel(new RakAcknowledgeHandler(sessionCodec));
        ByteBuf ack = Unpooled.buffer(10)
                .writeByte(RakConstants.FLAG_VALID | RakConstants.FLAG_ACK)
                .writeShort(1)
                .writeBoolean(false)
                .writeMediumLE(2)
                .writeMediumLE(1);

        channel.writeInbound(ack);

        Assertions.assertEquals(TransportDisconnectReason.BAD_PACKET, closeReason.get());
        Assertions.assertFalse(disconnectCalled.get(),
                "A reversed acknowledge range should close without sending a disconnect notification");
        channel.finishAndReleaseAll();
    }

    @Test
    public void testClientClosesOnRemoteDisconnect() throws InterruptedException {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        AtomicBoolean inactiveWhileOpen = new AtomicBoolean();

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                // Long enough that only the disconnect notification can close the client in time
                .option(RakChannelOption.RAK_SESSION_TIMEOUT, 30_000L)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        if (ctx.channel().isOpen()) {
                            inactiveWhileOpen.set(true);
                        }
                        ctx.fireChannelInactive();
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        child.rakPipeline().get(RakSessionCodec.class).disconnect(TransportDisconnectReason.DISCONNECTED);

        Assertions.assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS),
                "Client should close when the server disconnects");
        Assertions.assertFalse(inactiveWhileOpen.get(), "Client should not go inactive while still open");
    }

    @Test
    public void testServerClosesOnRemoteDisconnect() throws InterruptedException {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        AtomicBoolean reasonFired = new AtomicBoolean();

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(reasonRecorder(reasonFired))
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        channel.disconnect().awaitUninterruptibly();

        Assertions.assertTrue(child.closeFuture().await(5, TimeUnit.SECONDS),
                "Server child should close when the client disconnects");
        Assertions.assertFalse(reasonFired.get(), "Disconnecting a client should not fire a disconnect reason");
    }

    @Test
    public void testServerDisconnectNotifiesClient() throws InterruptedException {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                // Long enough that only the disconnect notification can close the client in time
                .option(RakChannelOption.RAK_SESSION_TIMEOUT, 30_000L)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        AtomicBoolean reasonFired = new AtomicBoolean();
        child.pipeline().addLast(reasonRecorder(reasonFired));
        child.disconnect();

        Assertions.assertTrue(channel.closeFuture().await(5, TimeUnit.SECONDS),
                "Client should close when the server disconnects it");
        Assertions.assertTrue(child.closeFuture().await(5, TimeUnit.SECONDS), "Server child should close");
        Assertions.assertFalse(reasonFired.get(), "Disconnecting a child should not fire a disconnect reason");
    }

    @Test
    public void testServerClosesWhenDisconnectNotificationFails() throws InterruptedException {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        // Child session pipelines allocate through the server channel's own config
        NotificationFailingAllocator allocator = new NotificationFailingAllocator();
        serverChannel.config().setAllocator(allocator);

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelInboundHandlerAdapter())
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        allocator.failNotification = true;
        child.rakPipeline().get(RakSessionCodec.class).disconnect(TransportDisconnectReason.DISCONNECTED);

        // The live client keeps the session from timing out, so only the disconnect can close it
        Assertions.assertTrue(child.closeFuture().await(5, TimeUnit.SECONDS),
                "Server child should close even if the disconnect notification fails");
        channel.close().awaitUninterruptibly();
    }

    @Test
    public void testRepeatedDisconnectCompletesOnClose() throws Exception {
        InetSocketAddress address = setupServer();
        AtomicReference<Runnable> heldClose = new AtomicReference<>();

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
                        // Hold the close, so the session stays disconnecting while the channel is open
                        heldClose.set(() -> ctx.close(promise));
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        ChannelFuture first = channel.disconnect();
        ChannelFuture second = channel.disconnect();
        channel.eventLoop().submit(() -> { }).sync(); // Both disconnects have run once this does
        Assertions.assertTrue(first.isSuccess(), "First disconnect should succeed");
        Assertions.assertNull(second.cause(), "A repeated disconnect should not fail");
        Assertions.assertNotNull(heldClose.get(), "First disconnect should close the channel");

        channel.eventLoop().execute(heldClose.get());
        Assertions.assertTrue(second.await(5, TimeUnit.SECONDS) && second.isSuccess(),
                "A repeated disconnect should complete once the channel closes");
        Assertions.assertFalse(channel.isOpen(), "Client should be closed");
    }

    @Test
    public void testConnectFailsAsClosedWhenClosedDuringHandshake() throws InterruptedException {
        // A peer that never replies keeps the client in the handshake
        CountDownLatch handshakeStarted = new CountDownLatch(1);
        Channel silentPeer = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        ReferenceCountUtil.release(msg);
                        handshakeStarted.countDown();
                    }
                })
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .syncUninterruptibly()
                .channel();
        try {
            ChannelFuture future = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                    .handler(new ChannelInboundHandlerAdapter())
                    .connect(silentPeer.localAddress());
            Assertions.assertTrue(handshakeStarted.await(5, TimeUnit.SECONDS), "Client should start the handshake");
            future.channel().close();

            Assertions.assertTrue(future.await(5, TimeUnit.SECONDS), "Connect should complete once the client closes");
            Assertions.assertTrue(future.cause() instanceof ClosedChannelException,
                    "Connect should fail as closed, not cancelled: " + future.cause());
        } finally {
            silentPeer.close().awaitUninterruptibly();
        }
    }

    @ParameterizedTest
    @MethodSource("validMtu")
    public void testClientResend(int mtu) throws InterruptedException {
        InetSocketAddress address = setupServer();
        System.out.println("Testing client with MTU " + mtu);

        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[mtu * 16];
        random.nextBytes(bytes);
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        CountDownLatch received = new CountDownLatch(1);

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
                ch.pipeline().addLast(RESEND_RECEIVER(buffer, received));
                ch.pipeline().addLast(sender);
            }
        };

        Channel channel = this.track(clientBootstrap(mtu)
                .handler(initializer)
                .connect(address)
                .awaitUninterruptibly()
                .channel());
        Assertions.assertTrue(channel.isActive(), "Resend test client should complete the RakNet handshake");

        Assertions.assertTrue(received.await(5, TimeUnit.SECONDS), "Client should receive the resent message");
        channel.close().awaitUninterruptibly();
    }

    @Test
    public void testReliableMessagesStayWithinWindow() throws Exception {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        int warmup = 100;
        int burst = RakConstants.RELIABLE_WINDOW_SIZE * 2;
        CountDownLatch warmupReceived = new CountDownLatch(warmup);
        CountDownLatch burstReceived = new CountDownLatch(burst);

        Channel channel = connectCounting(address, RakConstants.MAXIMUM_MTU_SIZE, warmupReceived, burstReceived);
        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        AckBlocker blocker = new AckBlocker();
        child.rakPipeline().addAfter(RakDatagramCodec.NAME, "ack-blocker", blocker);

        // Acknowledged full datagrams grow the congestion window well past the reliable window, so bytes alone
        // would not hold the burst back
        sendFromSession(child, warmup, () -> {
            ByteBuf buf = Unpooled.buffer(1200);
            buf.writeByte(WARMUP_PACKET_ID);
            return buf.writeZero(1199);
        });
        Assertions.assertTrue(warmupReceived.await(5, TimeUnit.SECONDS), "Client should receive the warmup");
        int windowEnd = blocker.highestReliabilityIndex + 1 + RakConstants.RELIABLE_WINDOW_SIZE;

        blocker.blocking = true;
        sendFromSession(child, burst, () -> Unpooled.buffer(1).writeByte(BURST_PACKET_ID));
        Thread.sleep(200);
        Assertions.assertTrue(blocker.highestReliabilityIndex < windowEnd,
                "Sent reliable index " + blocker.highestReliabilityIndex + " past the window ending at " + windowEnd);
        Assertions.assertTrue(burstReceived.getCount() > 0, "Burst should wait for acknowledgements");

        blocker.blocking = false;
        Assertions.assertTrue(burstReceived.await(10, TimeUnit.SECONDS),
                "Client should receive the burst once acknowledgements resume");
        channel.close().awaitUninterruptibly();
    }

    @Test
    public void testSplitMessagesInFlightStayWithinLimit() throws Exception {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        int warmup = 1000;
        int burst = RakConstants.MAXIMUM_SPLITS_IN_FLIGHT * 2;
        CountDownLatch warmupReceived = new CountDownLatch(warmup);
        CountDownLatch burstReceived = new CountDownLatch(burst);

        Channel channel = connectCounting(address, RakConstants.MAXIMUM_MTU_SIZE, warmupReceived, burstReceived);
        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        AckBlocker blocker = new AckBlocker();
        child.rakPipeline().addAfter(RakDatagramCodec.NAME, "ack-blocker", blocker);

        // A full datagram per warmup message grows the congestion window past the burst, so bytes alone would let
        // it all out. Blocking the last acknowledgements would leave the warmup to be resent, shrinking the window.
        sendFromSession(child, warmup, () -> Unpooled.buffer(1200).writeByte(WARMUP_PACKET_ID).writeZero(1199));
        Assertions.assertTrue(warmupReceived.await(5, TimeUnit.SECONDS), "Client should receive the warmup");
        Thread.sleep(200);

        blocker.blocking = true;
        int partSize = RakConstants.MAXIMUM_MTU_SIZE - RakConstants.UDP_HEADER_SIZE - 20
                - RakConstants.MAXIMUM_ENCAPSULATED_HEADER_SIZE - RakConstants.RAKNET_DATAGRAM_HEADER_SIZE;
        sendFromSession(child, burst,
                () -> Unpooled.buffer(partSize + 1).writeByte(BURST_PACKET_ID).writeZero(partSize));
        Thread.sleep(200);
        Assertions.assertTrue(blocker.splitIds.size() <= RakConstants.MAXIMUM_SPLITS_IN_FLIGHT,
                "Sent " + blocker.splitIds.size() + " split messages without acknowledgements");
        Assertions.assertTrue(burstReceived.getCount() > 0, "Burst should wait for acknowledgements");

        blocker.blocking = false;
        Assertions.assertTrue(burstReceived.await(10, TimeUnit.SECONDS),
                "Client should receive the burst once acknowledgements resume");
        channel.close().awaitUninterruptibly();
    }

    // Counts what the client receives by the first byte of each message
    private Channel connectCounting(InetSocketAddress address, int mtu, CountDownLatch warmupReceived,
                                           CountDownLatch burstReceived) {
        Channel channel = clientBootstrap(mtu)
                .handler(new SimpleChannelInboundHandler<RakMessage>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) {
                        int id = message.content().getUnsignedByte(message.content().readerIndex());
                        (id == WARMUP_PACKET_ID ? warmupReceived : burstReceived).countDown();
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");
        return channel;
    }

    @Test
    public void testOversizedMessageFailsWithoutStallingTheSession() throws Exception {
        BlockingQueue<RakChildChannel> children = new LinkedBlockingQueue<>();
        InetSocketAddress address = setupServer(children);
        BlockingQueue<Integer> received = new LinkedBlockingQueue<>();

        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new SimpleChannelInboundHandler<RakMessage>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, RakMessage message) {
                        received.add(message.content().readableBytes());
                    }
                })
                .connect(address)
                .awaitUninterruptibly()
                .channel();
        Assertions.assertTrue(channel.isActive(), "Client should connect");

        RakChildChannel child = children.poll(5, TimeUnit.SECONDS);
        Assertions.assertNotNull(child, "Server should create a child channel");
        int largest = child.maxMessageSize();
        // Every part full: the MTU less the UDP, IPv4, encapsulated and datagram headers
        Assertions.assertEquals(RakConstants.MAXIMUM_SPLIT_COUNT * (RakConstants.MAXIMUM_MTU_SIZE
                - RakConstants.UDP_HEADER_SIZE - 20 - RakConstants.MAXIMUM_ENCAPSULATED_HEADER_SIZE
                - RakConstants.RAKNET_DATAGRAM_HEADER_SIZE), largest);
        Assertions.assertEquals(largest, ((RakChannel) channel).maxMessageSize(), "Client sees the same limit");

        ChannelFuture oversized = child.rakPipeline().writeAndFlush(new RakMessage(userMessage(largest + 1)));
        Assertions.assertTrue(oversized.await(5, TimeUnit.SECONDS), "Oversized write should complete");
        Assertions.assertTrue(oversized.cause() instanceof IllegalArgumentException,
                "Oversized write should fail: " + oversized.cause());

        // Ordered after the refused write, so it only arrives if that write left no gap in the ordering channel
        child.rakPipeline().writeAndFlush(new RakMessage(userMessage(largest)));
        Assertions.assertEquals(largest, received.poll(10, TimeUnit.SECONDS), "The largest message should arrive");
        channel.close().awaitUninterruptibly();
    }

    @Test
    public void testMaxMessageSizeWithoutASession() {
        // Never connected, so no handshake added a session
        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelInboundHandlerAdapter())
                .register()
                .syncUninterruptibly()
                .channel();
        try {
            // Every part full at the smallest MTU, over IPv6
            Assertions.assertEquals(RakConstants.MAXIMUM_SPLIT_COUNT * (RakConstants.MINIMUM_MTU_SIZE
                    - RakConstants.UDP_HEADER_SIZE - 40 - RakConstants.MAXIMUM_ENCAPSULATED_HEADER_SIZE
                    - RakConstants.RAKNET_DATAGRAM_HEADER_SIZE), ((RakChannel) channel).maxMessageSize());
        } finally {
            channel.close().awaitUninterruptibly();
        }
    }

    @Test
    public void testPingWithoutASession() {
        Channel channel = clientBootstrap(RakConstants.MAXIMUM_MTU_SIZE)
                .handler(new ChannelInboundHandlerAdapter())
                .register()
                .syncUninterruptibly()
                .channel();
        try {
            Assertions.assertEquals(0, ((RakChannel) channel).getPing());
        } finally {
            channel.close().awaitUninterruptibly();
        }
    }

    // Starts with a user packet ID, as a leading zero would be taken for a connected ping
    private static ByteBuf userMessage(int size) {
        return Unpooled.buffer(size).writeByte(BURST_PACKET_ID).writeZero(size - 1);
    }

    // Queues messages in one task on the session's thread, so a single flush sees all of them
    private static void sendFromSession(RakChildChannel child, int count, Supplier<ByteBuf> message) throws Exception {
        child.parent().eventLoop().submit(() -> {
            for (int i = 0; i < count; i++) {
                child.rakPipeline().write(new RakMessage(message.get()));
            }
            child.rakPipeline().flush();
        }).sync();
    }

    // Drops acknowledgements while blocking, and records the highest reliable index and the split IDs sent
    private static final class AckBlocker extends ChannelDuplexHandler {
        private final Set<Integer> splitIds = ConcurrentHashMap.newKeySet();
        private volatile boolean blocking;
        private volatile int highestReliabilityIndex = -1;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // Data datagrams are decoded by now, so a buffer is an ACK or NACK
            if (this.blocking && msg instanceof ByteBuf) {
                ByteBuf buffer = (ByteBuf) msg;
                if ((buffer.getByte(buffer.readerIndex()) & RakConstants.FLAG_ACK) != 0) {
                    buffer.release();
                    return;
                }
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof RakDatagramPacket) {
                for (EncapsulatedPacket packet : ((RakDatagramPacket) msg).getPackets()) {
                    if (packet.getReliability().isReliable()) {
                        this.highestReliabilityIndex = Math.max(this.highestReliabilityIndex,
                                packet.getReliabilityIndex());
                    }
                    if (packet.isSplit()) {
                        this.splitIds.add(packet.getPartId());
                    }
                }
            }
            ctx.write(msg, promise);
        }
    }

    private static final class NotificationFailingAllocator extends AbstractByteBufAllocator {
        private volatile boolean failNotification;

        @Override
        public ByteBuf ioBuffer(int initialCapacity) {
            // The disconnect notification is the only single byte allocation in the session
            if (initialCapacity == 1 && this.failNotification) {
                this.failNotification = false;
                throw new OutOfMemoryError("Simulated direct memory exhaustion");
            }
            return super.ioBuffer(initialCapacity);
        }

        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            return UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity);
        }

        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            return UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity);
        }

        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }
    }
}
