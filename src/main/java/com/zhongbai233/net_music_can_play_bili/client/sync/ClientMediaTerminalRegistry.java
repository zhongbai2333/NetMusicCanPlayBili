package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSync;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Server-issued physical transport ownership, independent of retained logical session identity. */
public final class ClientMediaTerminalRegistry {
    private static final Map<UUID, Transport> VIDEO_ONLY = new HashMap<>();
    private static final Map<UUID, Transport> CURRENT = new HashMap<>();
    private ClientMediaTerminalRegistry() { }
    public static boolean observe(ClientMediaSyncPayload payload, UUID source) {
        return observe(source, payload.ownerId(), payload.sessionId(),
                PlaybackSync.parseTerminalToken(payload.playUrl()), payload.durationSeconds());
    }
    static boolean observe(UUID source, UUID owner, String session, String token, int duration) {
        Transport next = new Transport(owner, session, token, duration);
        Transport old = CURRENT.put(source, next);
        if (old == null || !old.equals(next)) VIDEO_ONLY.remove(source);
        return old != null && !old.equals(next);
    }
    public static boolean current(UUID source, String session, String token) {
        Transport current = CURRENT.get(source);
        return current != null && current.session.equals(session) && !token.isEmpty() && current.token.equals(token);
    }
    public static boolean reporter(UUID source, String session, String token, UUID player) {
        Transport current = CURRENT.get(source);
        return current(source, session, token) && Objects.equals(current.owner, player);
    }
    public static void markVideoOnly(UUID source, String session, String token) {
        if (current(source, session, token)) VIDEO_ONLY.put(source, CURRENT.get(source));
    }
    public static boolean videoOnly(UUID source, String session) {
        Transport transport = VIDEO_ONLY.get(source);
        return transport != null && transport.session.equals(session) && transport.equals(CURRENT.get(source));
    }
    public static String token(UUID source, String session) {
        Transport value = CURRENT.get(source);
        return value != null && value.session.equals(session) ? value.token : "";
    }
    public static void remove(UUID source) { CURRENT.remove(source); VIDEO_ONLY.remove(source); }
    public static void clear() { CURRENT.clear(); VIDEO_ONLY.clear(); }
    private record Transport(UUID owner, String session, String token, int duration) { }
}
