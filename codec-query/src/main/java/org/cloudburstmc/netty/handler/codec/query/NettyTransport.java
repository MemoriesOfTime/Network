package org.cloudburstmc.netty.handler.codec.query;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDatagramChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDatagramChannel;
import io.netty.channel.kqueue.KQueueIoHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
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

    static Class<? extends DatagramChannel> datagramChannelClass() {
        return selectedTransport().datagramChannelClass;
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
        EPOLL(EpollDatagramChannel.class) {
            @Override
            IoHandlerFactory newIoHandlerFactory() {
                return EpollIoHandler.newFactory();
            }
        },
        KQUEUE(KQueueDatagramChannel.class) {
            @Override
            IoHandlerFactory newIoHandlerFactory() {
                return KQueueIoHandler.newFactory();
            }
        },
        NIO(NioDatagramChannel.class) {
            @Override
            IoHandlerFactory newIoHandlerFactory() {
                return NioIoHandler.newFactory();
            }
        };

        private final Class<? extends DatagramChannel> datagramChannelClass;

        Transport(Class<? extends DatagramChannel> datagramChannelClass) {
            this.datagramChannelClass = datagramChannelClass;
        }

        abstract IoHandlerFactory newIoHandlerFactory();
    }
}
