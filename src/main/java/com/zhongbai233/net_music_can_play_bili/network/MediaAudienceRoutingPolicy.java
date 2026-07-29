package com.zhongbai233.net_music_can_play_bili.network;

/**
 * Decides whether a public media route is still needed after headphone
 * delivery.
 */
final class MediaAudienceRoutingPolicy {
    private static final String PAD_SESSION_MARKER = "-pad-";

    private MediaAudienceRoutingPolicy() {
    }

    static boolean shouldSendPublicToOwner(boolean ownerReceivedHeadphoneRoute) {
        return !ownerReceivedHeadphoneRoute;
    }

    static boolean shouldBroadcastPublic(boolean anyHeadphoneRouteDelivered) {
        return !anyHeadphoneRouteDelivered;
    }

    static boolean shouldBroadcastPlayerSource(String sessionId, boolean anyHeadphoneRouteDelivered) {
        return !isPadSession(sessionId) && shouldBroadcastPublic(anyHeadphoneRouteDelivered);
    }

    static boolean isPadSession(String sessionId) {
        return sessionId != null && sessionId.contains(PAD_SESSION_MARKER);
    }
}