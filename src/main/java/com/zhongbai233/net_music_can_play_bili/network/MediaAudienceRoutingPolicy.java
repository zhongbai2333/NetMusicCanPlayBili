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

    static PublicRoute publicRoute(boolean playerSource, String sessionId, boolean anyHeadphoneRouteDelivered,
            boolean ownerOnline, boolean ownerReceivedHeadphoneRoute) {
        if (!playerSource) {
            return shouldBroadcastPublic(anyHeadphoneRouteDelivered) ? PublicRoute.NEARBY : PublicRoute.NONE;
        }
        if (shouldBroadcastPlayerSource(sessionId, anyHeadphoneRouteDelivered)) {
            return PublicRoute.NEARBY;
        }
        if (isPadSession(sessionId) && ownerOnline && shouldSendPublicToOwner(ownerReceivedHeadphoneRoute)) {
            return PublicRoute.OWNER;
        }
        return PublicRoute.NONE;
    }

    static boolean shouldBroadcastStopNearby(boolean playerSource, String sessionId) {
        return !playerSource || !isPadSession(sessionId);
    }

    enum PublicRoute {
        NEARBY,
        OWNER,
        NONE
    }
}
