package com.zhongbai233.net_music_can_play_bili.client.renderer.video;

/** 异步视频解析结果在产生任何客户端副作用前的准入策略。 */
public final class VideoResolveAdmissionPolicy {
    public enum Decision {
        START,
        DROP_STALE_REQUEST,
        DROP_SESSION_CHANGED,
        DROP_SOURCE_STOPPED,
        DROP_NO_CONSUMER
    }

    private VideoResolveAdmissionPolicy() {
    }

    public static Decision decide(boolean latestRequest, boolean sameSession, boolean sourcePlaying,
            boolean hasConsumer) {
        if (!latestRequest) {
            return Decision.DROP_STALE_REQUEST;
        }
        if (!sameSession) {
            return Decision.DROP_SESSION_CHANGED;
        }
        if (!sourcePlaying) {
            return Decision.DROP_SOURCE_STOPPED;
        }
        return hasConsumer ? Decision.START : Decision.DROP_NO_CONSUMER;
    }
}