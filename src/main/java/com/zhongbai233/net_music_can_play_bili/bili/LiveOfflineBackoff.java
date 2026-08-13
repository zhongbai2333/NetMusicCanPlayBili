package com.zhongbai233.net_music_can_play_bili.bili;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 未开播直播间的客户端重试退避。
 *
 * <p>
 * 服务端会按同步节奏（约 3 秒）反复拉起客户端播放；对已知未开播的房间，
 * 每次拉起都完整走一遍 B站 API 解析并创建声音实例，既刷接口也会耗尽
 * Minecraft 声音引擎的流式句柄。退避期内客户端直接跳过，不发请求也不建声音，
 * 到期后放行一次探测——房间开播后自动恢复播放（"电台等待开播"语义）。
 * </p>
 */
public final class LiveOfflineBackoff {
    private static final long RETRY_MILLIS = BiliApiProperties.liveOfflineRetryMillis();
    private static final ConcurrentHashMap<String, Long> BLOCKED_UNTIL = new ConcurrentHashMap<>();

    private LiveOfflineBackoff() {
    }

    /** 记录一次"未开播"结果，进入退避期。 */
    public static void recordOffline(String roomId) {
        recordOffline(roomId, System.currentTimeMillis());
    }

    static void recordOffline(String roomId, long nowMillis) {
        if (roomId == null || roomId.isBlank()) {
            return;
        }
        // 顺手清掉已过期的条目，长期运行时不同房间号不会无界累积。
        BLOCKED_UNTIL.entrySet().removeIf(entry -> nowMillis >= entry.getValue());
        BLOCKED_UNTIL.put(roomId, saturatedAdd(nowMillis, RETRY_MILLIS));
    }

    /** @return true 表示该房间仍在退避期内，本轮应直接跳过 */
    public static boolean isBlocked(String roomId) {
        return isBlocked(roomId, System.currentTimeMillis());
    }

    static boolean isBlocked(String roomId, long nowMillis) {
        if (roomId == null || roomId.isBlank()) {
            return false;
        }
        Long until = BLOCKED_UNTIL.get(roomId);
        if (until == null) {
            return false;
        }
        if (nowMillis >= until) {
            BLOCKED_UNTIL.remove(roomId, until);
            return false;
        }
        return true;
    }

    /** 房间成功开播（或被手动停止/换台）后清除退避。 */
    public static void clear(String roomId) {
        if (roomId != null && !roomId.isBlank()) {
            BLOCKED_UNTIL.remove(roomId);
        }
    }

    public static long retryMillis() {
        return RETRY_MILLIS;
    }

    private static long saturatedAdd(long value, long increment) {
        try {
            return Math.addExact(value, increment);
        } catch (ArithmeticException ignored) {
            return increment >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }
}
