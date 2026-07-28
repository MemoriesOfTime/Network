package org.cloudburstmc.netty.handler.codec.rcon;

import com.nukkitx.network.NetworkListener;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Getter;
import org.cloudburstmc.netty.handler.codec.rcon.handler.RconHandler;

import java.net.InetSocketAddress;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class RconNetworkListener extends ChannelInitializer<SocketChannel> implements NetworkListener {
    private final RconEventListener eventListener;
    private final InetSocketAddress address;
    private final ServerBootstrap bootstrap;
    private final EventLoopGroup group;
    private final Set<Channel> childChannels = ConcurrentHashMap.newKeySet();
    @Getter
    private final ExecutorService commandExecutionService = Executors.newSingleThreadExecutor(
            new DefaultThreadFactory("rcon-command-executor", true));
    private final byte[] password;
    private Channel serverChannel;
    private SocketChannel channel;

    public RconNetworkListener(RconEventListener eventListener, byte[] password, String address, int port) {
        this.eventListener = eventListener;
        this.password = password;
        this.address = new InetSocketAddress(address, port);

        this.group = NettyTransport.newEventLoopGroup("rcon-listener");
        this.bootstrap = new ServerBootstrap()
                .group(this.group)
                .channel(NettyTransport.serverSocketChannelClass())
                .childHandler(this);
    }

    @Override
    public boolean bind() {
        ChannelFuture future = bootstrap.bind(address).awaitUninterruptibly();
        if (future.isSuccess()) {
            this.serverChannel = future.channel();
        }
        return future.isSuccess();
    }

    @Override
    public void close() {
        commandExecutionService.shutdown();
        try {
            commandExecutionService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
            for (Channel childChannel : childChannels) {
                childChannel.close().syncUninterruptibly();
            }
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Override
    public InetSocketAddress getAddress() {
        return address;
    }

    @Override
    protected void initChannel(SocketChannel socketChannel) throws Exception {
        this.channel = socketChannel;
        this.childChannels.add(socketChannel);
        socketChannel.closeFuture().addListener(future -> this.childChannels.remove(socketChannel));

        channel.pipeline().addLast("lengthDecoder", new LengthFieldBasedFrameDecoder(ByteOrder.LITTLE_ENDIAN, 4096, 0, 4, 0, 4, true));
        channel.pipeline().addLast("rconDecoder", new RconCodec());
        channel.pipeline().addLast("rconHandler", new RconHandler(eventListener, password));
        channel.pipeline().addLast("lengthPrepender", new LengthFieldPrepender(ByteOrder.LITTLE_ENDIAN, 4, 0, false));
        channel.pipeline().addLast("exceptionHandler", new ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                ctx.close();
            }
        });
    }
}
