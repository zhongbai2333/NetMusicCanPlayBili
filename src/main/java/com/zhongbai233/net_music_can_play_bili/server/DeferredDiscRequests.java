package com.zhongbai233.net_music_can_play_bili.server;
import java.util.UUID;
/** Shared holder so server lifecycle cleanup does not load a Mixin class. */
public final class DeferredDiscRequests {
    private static final DeferredRequestGate<UUID> REQUESTS = new DeferredRequestGate<>(256);
    private DeferredDiscRequests() { }
    public static DeferredRequestGate<UUID> requests() { return REQUESTS; }
    public static void clear() { REQUESTS.clear(); }
}
