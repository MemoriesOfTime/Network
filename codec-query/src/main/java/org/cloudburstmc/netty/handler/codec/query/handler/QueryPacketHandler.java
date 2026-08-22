package org.cloudburstmc.netty.handler.codec.query.handler;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import org.cloudburstmc.netty.handler.codec.query.QueryEventListener;
import org.cloudburstmc.netty.handler.codec.query.enveloped.DirectAddressedQueryPacket;
import org.cloudburstmc.netty.handler.codec.query.packet.HandshakePacket;
import org.cloudburstmc.netty.handler.codec.query.packet.StatisticsPacket;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class QueryPacketHandler extends SimpleChannelInboundHandler<DirectAddressedQueryPacket> {
    private static final int TOKEN_ROTATION_SECONDS = 30;

    private final QueryEventListener listener;
    private byte[] lastToken;
    private byte[] token = new byte[16];
    private ScheduledFuture<?> tokenRotationFuture;

    public QueryPacketHandler(QueryEventListener listener) {
        this.listener = listener;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        // The all-zero default key must never sign tokens, so randomize it before the channel serves queries.
        ThreadLocalRandom.current().nextBytes(this.token);
        this.tokenRotationFuture = ctx.channel().eventLoop().scheduleAtFixedRate(
                this::refreshToken, TOKEN_ROTATION_SECONDS, TOKEN_ROTATION_SECONDS, TimeUnit.SECONDS);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (this.tokenRotationFuture != null) {
            this.tokenRotationFuture.cancel(false);
            this.tokenRotationFuture = null;
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DirectAddressedQueryPacket packet) throws Exception {
        if (packet.content() instanceof HandshakePacket) {
            HandshakePacket handshake = (HandshakePacket) packet.content();
            handshake.setToken(getTokenString(packet.sender()));
            ctx.writeAndFlush(new DirectAddressedQueryPacket(handshake, packet.sender(), packet.recipient()), ctx.voidPromise());
        }
        if (packet.content() instanceof StatisticsPacket) {
            StatisticsPacket statistics = (StatisticsPacket) packet.content();
            if (!this.isValidToken(statistics.getToken(), packet.sender())) {
                return;
            }

            QueryEventListener.Data data = listener.onQuery(packet.sender());

            ByteBuf payload = statistics.isFull() ? data.getLongStats(ctx.alloc()) : data.getShortStats(ctx.alloc());
            boolean submitted = false;
            try {
                statistics.setPayload(payload);
                ctx.writeAndFlush(new DirectAddressedQueryPacket(statistics, packet.sender(), packet.recipient()))
                        .addListener(future -> payload.release());
                submitted = true;
            } finally {
                if (!submitted) {
                    payload.release();
                }
            }
        }
    }

    public void refreshToken() {
        this.lastToken = this.token;
        // A fresh array is required: refilling the old one in place would alias lastToken to token.
        this.token = new byte[16];
        ThreadLocalRandom.current().nextBytes(this.token);
    }

    private String getTokenString(InetSocketAddress socketAddress) {
        return Integer.toString(getTokenInt(socketAddress));

    }

    private int getTokenInt(InetSocketAddress socketAddress) {
        return ByteBuffer.wrap(getToken(socketAddress, token)).getInt();
    }

    private boolean isValidToken(int challengeToken, InetSocketAddress socketAddress) {
        if (challengeToken == this.getTokenInt(socketAddress)) {
            return true;
        }
        // Tokens handed out before the latest rotation stay valid until the next one.
        return this.lastToken != null
                && challengeToken == ByteBuffer.wrap(this.getToken(socketAddress, this.lastToken)).getInt();
    }

    private byte[] getToken(InetSocketAddress socketAddress, byte[] secret) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException var3) {
            throw new InternalError("SHA-256 not supported", var3);
        }
        digest.update(socketAddress.toString().getBytes());
        byte[] digested = digest.digest(secret);
        return Arrays.copyOf(digested, 4);
    }
}
