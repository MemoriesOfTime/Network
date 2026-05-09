package org.cloudburstmc.netty.handler.codec.rcon;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

class RconNetworkListenerTest {

    @Test
    void exposesConfiguredAddress() {
        RconNetworkListener listener = new RconNetworkListener(
                message -> "",
                "password".getBytes(StandardCharsets.UTF_8),
                "127.0.0.1",
                0);
        try {
            Assertions.assertEquals(new InetSocketAddress("127.0.0.1", 0), listener.getAddress());
        } finally {
            listener.close();
        }
    }

    @Test
    void bindAndCloseWithNetty42EventLoopGroup() {
        RconNetworkListener listener = new RconNetworkListener(
                message -> "",
                "password".getBytes(StandardCharsets.UTF_8),
                "127.0.0.1",
                0);
        try {
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
            Assertions.assertSame(NioServerSocketChannel.class, NettyTransport.serverSocketChannelClass());
        } finally {
            restoreProperty(previous);
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
