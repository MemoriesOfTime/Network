package com.nukkitx.network.raknet.pipeline;

import com.nukkitx.network.raknet.RakNetServer;
import com.nukkitx.network.raknet.RakNetServerSession;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;

@ChannelHandler.Sharable
public class ServerDatagramHandler extends SimpleChannelInboundHandler<DatagramPacket> {
    public static final String NAME = "rak-server-datagram-handler";
    private final RakNetServer server;

    public ServerDatagramHandler(RakNetServer server) {
        this.server = server;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        ByteBuf buffer = packet.content();

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

}
