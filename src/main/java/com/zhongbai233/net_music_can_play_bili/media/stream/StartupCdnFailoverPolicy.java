package com.zhongbai233.net_music_can_play_bili.media.stream;

/** Pure startup policy shared by the async prefetcher and its deterministic tests. */
final class StartupCdnFailoverPolicy {
    private StartupCdnFailoverPolicy() {
    }

    static long firstRequestBytes(long normalChunkBytes, long startupTargetBytes,
            boolean firstRequestHasNoCachedData) {
        long normal = Math.max(1L, normalChunkBytes);
        if (!firstRequestHasNoCachedData || startupTargetBytes <= 0L) {
            return normal;
        }
        return Math.max(1L, Math.min(normal, startupTargetBytes));
    }

    static boolean shouldSwitch(boolean closed, long cachedBytes, int candidateCount,
            boolean requestInFlight, boolean switchAlreadyRequested) {
        return !closed && cachedBytes <= 0L && candidateCount > 1
                && requestInFlight && !switchAlreadyRequested;
    }
}
