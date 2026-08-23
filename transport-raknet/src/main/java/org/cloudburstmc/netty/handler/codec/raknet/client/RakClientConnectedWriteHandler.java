/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.cloudburstmc.netty.handler.codec.raknet.client;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;

/**
 * Sits at the head of the rak pipeline, after all user-supplied handlers, and unwraps
 * {@link DatagramPacket}s bound for the connected peer into raw buffers.
 *
 * kqueue/epoll transport addressed packets via sendto(), which macOS rejects with
 * EISCONN on connected sockets; write() delivers to the connected peer on every
 * platform. Unwrapping must happen here rather than in {@link RakClientProxyRouteHandler},
 * because handlers between the two (e.g. PROXY protocol prependers) expect to see
 * DatagramPackets.
 */
public class RakClientConnectedWriteHandler extends ChannelDuplexHandler {

    public static final String NAME = "rak-client-connected-write-handler";

    public RakClientConnectedWriteHandler() {
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof DatagramPacket && ((DatagramChannel) ctx.channel()).isConnected()) {
            DatagramPacket packet = (DatagramPacket) msg;
            if (packet.recipient() == null || packet.recipient().equals(ctx.channel().remoteAddress())) {
                ctx.write(packet.content().retain(), promise);
                packet.release();
                return;
            }
        }
        ctx.write(msg, promise);
    }
}
