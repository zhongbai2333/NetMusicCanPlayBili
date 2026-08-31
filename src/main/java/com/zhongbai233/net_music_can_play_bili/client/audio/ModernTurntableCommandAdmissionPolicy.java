package com.zhongbai233.net_music_can_play_bili.client.audio;

/** 客户端现代唱片机播放命令在替换现有会话前的准入策略。 */
final class ModernTurntableCommandAdmissionPolicy {
    enum Decision {
        ACCEPT_AUTHORITATIVE(true),
        ACCEPT_TRACKED(true),
        ACCEPT_COMPATIBILITY_FALLBACK(true),
        DROP_AUTHORITATIVE_STOPPED(false),
        DROP_EXPLICITLY_STOPPED(false),
        DROP_AUTHORITATIVE_SESSION_MISMATCH(false),
        DROP_TRACKED_SESSION_MISMATCH(false);

        private final boolean accepted;

        Decision(boolean accepted) {
            this.accepted = accepted;
        }

        boolean accepted() {
            return accepted;
        }
    }

    private ModernTurntableCommandAdmissionPolicy() {
    }

    /**
     * 权威方块实体状态优先于本地 tracker；方块实体状态暂不可用时，tracker
     * 只允许当前会话继续，避免迟到的旧命令反向替换已经接受的新会话。
     */
    static Decision decide(String incomingSessionId, String authoritativeSessionId, String trackedSessionId) {
        return decide(incomingSessionId, authoritativeSessionId, trackedSessionId, false, false);
    }

    static Decision decide(String incomingSessionId, String authoritativeSessionId, String trackedSessionId,
            boolean authoritativeSourcePresent) {
        return decide(incomingSessionId, authoritativeSessionId, trackedSessionId,
                authoritativeSourcePresent, false);
    }

    static Decision decide(String incomingSessionId, String authoritativeSessionId, String trackedSessionId,
            boolean authoritativeSourcePresent, boolean explicitlyStopped) {
        String incoming = normalize(incomingSessionId);
        String authoritative = normalize(authoritativeSessionId);
        String tracked = normalize(trackedSessionId);
        if (explicitlyStopped) {
            return Decision.DROP_EXPLICITLY_STOPPED;
        }
        if (authoritativeSourcePresent && authoritative.isBlank()) {
            return Decision.DROP_AUTHORITATIVE_STOPPED;
        }
        if (!authoritative.isBlank()) {
            return authoritative.equals(incoming)
                    ? Decision.ACCEPT_AUTHORITATIVE
                    : Decision.DROP_AUTHORITATIVE_SESSION_MISMATCH;
        }
        if (tracked.isBlank()) {
            return Decision.ACCEPT_COMPATIBILITY_FALLBACK;
        }
        return tracked.equals(incoming)
                ? Decision.ACCEPT_TRACKED
                : Decision.DROP_TRACKED_SESSION_MISMATCH;
    }

    private static String normalize(String value) {
        return value != null ? value : "";
    }
}
