package com.zhongbai233.net_music_can_play_bili.media.sync;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.UUID;

public final class PlaybackSync {
    private static final String SESSION_KEY = "nmb_session=";
    private static final String ELAPSED_MS_KEY = "nmb_elapsed_ms=";
    private static final String TOTAL_MS_KEY = "nmb_total_ms=";
    private static final String MINECART_ENTITY_KEY = "nmb_minecart_entity=";
    private static final String MINECART_UUID_KEY = "nmb_minecart_uuid=";

    private PlaybackSync() {
    }

    /** @param elapsedMillis 已播放毫秒数（精确到 tick: 50ms） */
    public static String withSync(String value, String sessionId, long elapsedMillis) {
        return withSync(value, sessionId, elapsedMillis, 0L);
    }

    /**
     * @param elapsedMillis 已播放毫秒数（精确到 tick: 50ms）
     * @param totalMillis   歌曲总时长毫秒数；用于 HTTP/MP3 Range seek 的字节偏移估算
     */
    public static String withSync(String value, String sessionId, long elapsedMillis, long totalMillis) {
        if (value == null || value.isBlank() || sessionId == null || sessionId.isBlank()) {
            return value;
        }
        String clean = strip(value);
        long elapsed = Math.max(0L, elapsedMillis);
        long total = Math.max(0L, totalMillis);
        String sync = clean + "#" + SESSION_KEY + sessionId + "&" + ELAPSED_MS_KEY + elapsed;
        return total > 0L ? sync + "&" + TOTAL_MS_KEY + total : sync;
    }

    public static String transferSync(String source, String target) {
        Metadata sync = parse(source);
        if (!sync.hasSession()) {
            return target;
        }
        String result = withSync(target, sync.sessionId(), sync.elapsedMillis(), sync.totalMillis());
        MinecartAnchor anchor = parseMinecartAnchor(source);
        return anchor != null ? withMinecartAnchor(result, anchor.entityId(), anchor.entityUuid()) : result;
    }

    public static String withMinecartAnchor(String value, int entityId, UUID entityUuid) {
        if (value == null || value.isBlank() || entityId < 0 || entityUuid == null) {
            return value;
        }
        String separator = value.indexOf('#') >= 0 ? "&" : "#";
        return value + separator + MINECART_ENTITY_KEY + entityId + "&" + MINECART_UUID_KEY + entityUuid;
    }

    public static MinecartAnchor parseMinecartAnchor(String value) {
        if (value == null) {
            return null;
        }
        int hash = value.indexOf('#');
        if (hash < 0 || hash == value.length() - 1) {
            return null;
        }
        int entityId = -1;
        UUID entityUuid = null;
        for (String part : value.substring(hash + 1).split("&")) {
            if (part.startsWith(MINECART_ENTITY_KEY)) {
                try {
                    entityId = Integer.parseInt(part.substring(MINECART_ENTITY_KEY.length()));
                } catch (NumberFormatException ignored) {
                    entityId = -1;
                }
            } else if (part.startsWith(MINECART_UUID_KEY)) {
                try {
                    entityUuid = UUID.fromString(part.substring(MINECART_UUID_KEY.length()));
                } catch (IllegalArgumentException ignored) {
                    entityUuid = null;
                }
            }
        }
        return entityId >= 0 && entityUuid != null ? new MinecartAnchor(entityId, entityUuid) : null;
    }

    public static Metadata parse(String value) {
        if (value == null) {
            return Metadata.empty();
        }
        int hash = value.indexOf('#');
        if (hash < 0 || hash == value.length() - 1) {
            return Metadata.empty();
        }
        String sessionId = "";
        long elapsedMillis = 0L;
        long totalMillis = 0L;
        for (String part : value.substring(hash + 1).split("&")) {
            if (part.startsWith(SESSION_KEY)) {
                sessionId = part.substring(SESSION_KEY.length());
            } else if (part.startsWith(ELAPSED_MS_KEY)) {
                try {
                    elapsedMillis = Math.max(0L, Long.parseLong(part.substring(ELAPSED_MS_KEY.length())));
                } catch (NumberFormatException ignored) {
                    elapsedMillis = 0L;
                }
            } else if (part.startsWith(TOTAL_MS_KEY)) {
                try {
                    totalMillis = Math.max(0L, Long.parseLong(part.substring(TOTAL_MS_KEY.length())));
                } catch (NumberFormatException ignored) {
                    totalMillis = 0L;
                }
            }
        }
        return sessionId.isBlank() ? Metadata.empty() : new Metadata(sessionId, elapsedMillis, totalMillis);
    }

    public static String strip(String value) {
        if (value == null) {
            return null;
        }
        int hash = value.indexOf('#');
        if (hash < 0) {
            return value;
        }
        String fragment = value.substring(hash + 1);
        return fragment.contains(SESSION_KEY) || fragment.contains(ELAPSED_MS_KEY) || fragment.contains(TOTAL_MS_KEY)
                || fragment.contains(MINECART_ENTITY_KEY) || fragment.contains(MINECART_UUID_KEY)
                        ? value.substring(0, hash)
                        : value;
    }

    public static URL strip(URL url) throws MalformedURLException {
        String original = url.toString();
        String clean = strip(original);
        return original.equals(clean) ? url : URI.create(clean).toURL();
    }

    public record Metadata(String sessionId, long elapsedMillis, long totalMillis) {
        static Metadata empty() {
            return new Metadata("", 0L, 0L);
        }

        public boolean hasSession() {
            return !sessionId.isBlank();
        }

        public int elapsedSeconds() {
            return (int) Math.min(Integer.MAX_VALUE, elapsedMillis / 1000L);
        }
    }

    public record MinecartAnchor(int entityId, UUID entityUuid) {
    }
}
