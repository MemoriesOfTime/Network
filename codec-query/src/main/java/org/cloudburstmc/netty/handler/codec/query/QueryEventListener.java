package org.cloudburstmc.netty.handler.codec.query;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import lombok.Value;
import lombok.experimental.NonFinal;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

public interface QueryEventListener {

    Data onQuery(InetSocketAddress address);

    @Value
    class Data {
        private final String hostname;
        private final String gametype;
        private final String map;
        private final int playerCount;
        private final int maxPlayerCount;
        private final int hostport;
        private final String hostip;
        private final String gameId;
        private final String version;
        private final String softwareVersion;
        private final boolean whitelisted;
        private final String[] plugins;
        private final String[] players;
        @NonFinal
        private transient ByteBuf longStats;
        @NonFinal
        private transient ByteBuf shortStats;

        @Deprecated
        public ByteBuf getLongStats() {
            ByteBuf buffer = Unpooled.buffer();
            this.writeLongStats(buffer);
            return buffer;
        }

        public ByteBuf getLongStats(ByteBufAllocator allocator) {
            ByteBuf buffer = allocator.buffer();
            this.writeLongStats(buffer);
            return buffer;
        }

        private void writeLongStats(ByteBuf buffer) {
            buffer.writeBytes(QueryUtil.LONG_RESPONSE_PADDING_TOP);

            StringJoiner plugins = new StringJoiner(";");
            if (this.plugins != null) {
                for (String plugin : this.plugins) {
                    plugins.add(plugin);
                }
            }

            Map<String, String> kvs = new HashMap<>();
            kvs.put("hostname", hostname);
            kvs.put("gametype", gametype);
            kvs.put("map", map);
            kvs.put("numplayers", Integer.toString(playerCount));
            kvs.put("maxplayers", Integer.toString(maxPlayerCount));
            kvs.put("hostport", Integer.toString(hostport));
            kvs.put("hostip", hostip);
            kvs.put("game_id", gameId);
            kvs.put("version", version);
            kvs.put("plugins", softwareVersion + plugins.toString());
            kvs.put("whitelist", whitelisted ? "on" : "off");

            kvs.forEach((key, value) -> {
                QueryUtil.writeNullTerminatedString(buffer, key);
                QueryUtil.writeNullTerminatedString(buffer, value);
            });

            buffer.writeByte(0);
            buffer.writeBytes(QueryUtil.LONG_RESPONSE_PADDING_BOTTOM);

            if (players != null) {
                for (String player : players) {
                    QueryUtil.writeNullTerminatedString(buffer, player);
                }
            }
            buffer.writeByte(0);
        }

        @Deprecated
        public ByteBuf getShortStats() {
            ByteBuf buffer = Unpooled.buffer();
            this.writeShortStats(buffer);
            return buffer;
        }

        public ByteBuf getShortStats(ByteBufAllocator allocator) {
            ByteBuf buffer = allocator.buffer();
            this.writeShortStats(buffer);
            return buffer;
        }

        private void writeShortStats(ByteBuf buffer) {
            QueryUtil.writeNullTerminatedString(buffer, hostname);
            QueryUtil.writeNullTerminatedString(buffer, gametype);
            QueryUtil.writeNullTerminatedString(buffer, map);
            QueryUtil.writeNullTerminatedString(buffer, Integer.toString(playerCount));
            QueryUtil.writeNullTerminatedString(buffer, Integer.toString(maxPlayerCount));
            buffer.writeShortLE(hostport);
            QueryUtil.writeNullTerminatedString(buffer, hostip);
        }
    }
}
