package org.cloudburstmc.netty.handler.codec.rcon;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;

final class NettyTransport {
    static final String DISABLE_NATIVE_EVENT_LOOP_PROPERTY = "disableNativeEventLoop";

    private NettyTransport() {
    }

    static EventLoopGroup newEventLoopGroup(String threadName) {
        return new MultiThreadIoEventLoopGroup(
                new DefaultThreadFactory(threadName, true),
                selectedTransport().newIoHandlerFactory());
    }

    static Class<? extends ServerSocketChannel> serverSocketChannelClass() {
        return selectedTransport().serverSocketChannelClass;
    }

    private static Transport selectedTransport() {
        if (!System.getProperties().containsKey(DISABLE_NATIVE_EVENT_LOOP_PROPERTY)) {
            if (Epoll.isAvailable()) {
                return Transport.EPOLL;
            }
            if (KQueue.isAvailable()) {
                return Transport.KQUEUE;
            }
        }
        return Transport.NIO;
    }

    private enum Transport {
        EPOLL(EpollServerSocketChannel.class) {
            @Override
            IoHandlerFactory newIoHandlerFactory() {
                return EpollIoHandler.newFactory();
            }
        },
        KQUEUE(KQueueServerSocketChannel.class) {
            @Override
            IoHandlerFactory newIoHandlerFactory() {
                return KQueueIoHandler.newFactory();
            }
        },
        NIO(NioServerSocketChannel.class) {
            @Override
            IoHandlerFactory newIoHandlerFactory() {
                return NioIoHandler.newFactory();
            }
        };

        private final Class<? extends ServerSocketChannel> serverSocketChannelClass;

        Transport(Class<? extends ServerSocketChannel> serverSocketChannelClass) {
            this.serverSocketChannelClass = serverSocketChannelClass;
        }

        abstract IoHandlerFactory newIoHandlerFactory();
    }
}
