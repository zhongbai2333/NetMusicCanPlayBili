package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;

import java.util.UUID;

/** Executes one delayed retry command and converges a rejected dispatch. */
final class ClientMediaRetryDispatch {
    private static final System.Logger LOGGER = System.getLogger(ClientMediaRetryDispatch.class.getName());

    private ClientMediaRetryDispatch() {
    }

    static boolean dispatch(ClientMediaRetryPolicy policy, UUID sourceId, PlaybackSessionId sessionId,
            ClientMediaPlaybackRegistry.ActivePlayback active, Throwable error, Runnable rejected) {
        if (policy == null || sourceId == null || sessionId == null || active == null) {
            reject(rejected);
            return false;
        }
        try {
            if (policy.tryScheduleRetry(sourceId, sessionId.value(), active, error)) {
                return true;
            }
            LOGGER.log(System.Logger.Level.DEBUG,
                    "客户端媒体 retry dispatch 被拒绝: source={0} session={1}", sourceId, sessionId);
        } catch (RuntimeException dispatchError) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "客户端媒体 retry dispatch 异常: source=" + sourceId + " session=" + sessionId,
                    dispatchError);
        }
        reject(rejected);
        return false;
    }

    private static void reject(Runnable rejected) {
        if (rejected != null) {
            rejected.run();
        }
    }
}
