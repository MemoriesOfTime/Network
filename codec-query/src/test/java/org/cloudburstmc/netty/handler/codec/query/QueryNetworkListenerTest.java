package org.cloudburstmc.netty.handler.codec.query;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.cloudburstmc.netty.handler.codec.query.packet.StatisticsPacket;

import java.net.InetSocketAddress;

class QueryNetworkListenerTest {

    @Test
    void bindAndCloseWithNetty42EventLoopGroup() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 0);
        QueryNetworkListener listener = new QueryNetworkListener(address, remoteAddress -> null);
        try {
            Assertions.assertEquals(address, listener.getAddress());
            Assertions.assertTrue(listener.bind());
        } finally {
            listener.close();
        }
    }

    @Test
    void disabledNativeEventLoopFallsBackToNio() {
        String previous = System.getProperty(NettyTransport.DISABLE_NATIVE_EVENT_LOOP_PROPERTY);
        System.setProperty(NettyTransport.DISABLE_NATIVE_EVENT_LOOP_PROPERTY, "true");
        try {
            Assertions.assertSame(NioDatagramChannel.class, NettyTransport.datagramChannelClass());
        } finally {
            restoreProperty(previous);
        }
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecatedStatsAccessorsStillAllocateWithoutDefaultAllocator() {
        QueryEventListener.Data data = new QueryEventListener.Data(
                "server",
                "SMP",
                "world",
                1,
                10,
                19132,
                "127.0.0.1",
                "MINECRAFTPE",
                "1.20.0",
                "server 1.0",
                false,
                new String[]{"plugin"},
                new String[]{"player"},
                null,
                null);

        ByteBuf longStats = data.getLongStats();
        ByteBuf shortStats = data.getShortStats();
        try {
            Assertions.assertTrue(longStats.isReadable());
            Assertions.assertTrue(shortStats.isReadable());
        } finally {
            longStats.release();
            shortStats.release();
        }
    }

    @Test
    void statsAccessorsAllocateFreshPayloads() {
        QueryEventListener.Data data = new QueryEventListener.Data(
                "server",
                "SMP",
                "world",
                1,
                10,
                19132,
                "127.0.0.1",
                "MINECRAFTPE",
                "1.20.0",
                "server 1.0",
                false,
                new String[]{"plugin"},
                new String[]{"player"},
                null,
                null);
        ByteBuf first = data.getLongStats(UnpooledByteBufAllocator.DEFAULT);
        ByteBuf second = data.getLongStats(UnpooledByteBufAllocator.DEFAULT);
        try {
            Assertions.assertNotSame(first, second);
            Assertions.assertTrue(first.isReadable());
            Assertions.assertTrue(second.isReadable());
        } finally {
            first.release();
            second.release();
        }
    }

    @Test
    void statisticsPacketEncodeDoesNotConsumePayloadReaderIndex() {
        QueryEventListener.Data data = new QueryEventListener.Data(
                "server",
                "SMP",
                "world",
                1,
                10,
                19132,
                "127.0.0.1",
                "MINECRAFTPE",
                "1.20.0",
                "server 1.0",
                false,
                new String[]{"plugin"},
                new String[]{"player"},
                null,
                null);
        ByteBuf payload = data.getLongStats(UnpooledByteBufAllocator.DEFAULT);
        int payloadBytes = payload.readableBytes();

        StatisticsPacket statistics = new StatisticsPacket();
        statistics.setPayload(payload);

        ByteBuf first = Unpooled.buffer();
        ByteBuf second = Unpooled.buffer();
        try {
            statistics.encode(first);
            statistics.encode(second);

            Assertions.assertEquals(payloadBytes, first.readableBytes() - Integer.BYTES);
            Assertions.assertEquals(payloadBytes, second.readableBytes() - Integer.BYTES);
            Assertions.assertEquals(payloadBytes, payload.readableBytes());
        } finally {
            first.release();
            second.release();
            payload.release();
        }
    }

    private static void restoreProperty(String previous) {
        if (previous == null) {
            System.clearProperty(NettyTransport.DISABLE_NATIVE_EVENT_LOOP_PROPERTY);
        } else {
            System.setProperty(NettyTransport.DISABLE_NATIVE_EVENT_LOOP_PROPERTY, previous);
        }
    }
}
