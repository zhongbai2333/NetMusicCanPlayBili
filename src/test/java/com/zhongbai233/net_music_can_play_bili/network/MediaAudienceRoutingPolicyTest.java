package com.zhongbai233.net_music_can_play_bili.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaAudienceRoutingPolicyTest {
    @Test
    void ownerGetsPublicRouteOnlyWithoutHeadphoneDelivery() {
        assertTrue(MediaAudienceRoutingPolicy.shouldSendPublicToOwner(false));
        assertFalse(MediaAudienceRoutingPolicy.shouldSendPublicToOwner(true));
    }

    @Test
    void publicAudienceIsSuppressedAfterAnyHeadphoneDelivery() {
        assertTrue(MediaAudienceRoutingPolicy.shouldBroadcastPublic(false));
        assertFalse(MediaAudienceRoutingPolicy.shouldBroadcastPublic(true));
    }

    @Test
    void mp4PlayerSourceBroadcastsOnlyWithoutHeadphoneDelivery() {
        assertTrue(MediaAudienceRoutingPolicy.shouldBroadcastPlayerSource("device-mp4-123", false));
        assertFalse(MediaAudienceRoutingPolicy.shouldBroadcastPlayerSource("device-mp4-123", true));
    }

    @Test
    void padPlayerSourceNeverBroadcasts() {
        assertTrue(MediaAudienceRoutingPolicy.isPadSession("device-pad-point-session"));
        assertFalse(MediaAudienceRoutingPolicy.shouldBroadcastPlayerSource("device-pad-point-session", false));
        assertFalse(MediaAudienceRoutingPolicy.shouldBroadcastPlayerSource("device-pad-point-session", true));
    }

    @Test
    void nonPlayerAndRegularPlayerSourcesShareNearbySuppression() {
        assertEquals(MediaAudienceRoutingPolicy.PublicRoute.NEARBY,
                MediaAudienceRoutingPolicy.publicRoute(false, "mp4", false, false, false));
        assertEquals(MediaAudienceRoutingPolicy.PublicRoute.NONE,
                MediaAudienceRoutingPolicy.publicRoute(false, "mp4", true, false, false));
        assertEquals(MediaAudienceRoutingPolicy.PublicRoute.NEARBY,
                MediaAudienceRoutingPolicy.publicRoute(true, "device-mp4-session", false, true, false));
        assertEquals(MediaAudienceRoutingPolicy.PublicRoute.NONE,
                MediaAudienceRoutingPolicy.publicRoute(true, "device-mp4-session", true, true, false));
    }

    @Test
    void padPublicRouteTargetsOnlyAnOnlineOwnerWithoutHeadphoneDelivery() {
        assertEquals(MediaAudienceRoutingPolicy.PublicRoute.OWNER,
                MediaAudienceRoutingPolicy.publicRoute(true, "device-pad-session", false, true, false));
        assertEquals(MediaAudienceRoutingPolicy.PublicRoute.OWNER,
                MediaAudienceRoutingPolicy.publicRoute(true, "device-pad-session", true, true, false));
        assertEquals(MediaAudienceRoutingPolicy.PublicRoute.NONE,
                MediaAudienceRoutingPolicy.publicRoute(true, "device-pad-session", true, true, true));
        assertEquals(MediaAudienceRoutingPolicy.PublicRoute.NONE,
                MediaAudienceRoutingPolicy.publicRoute(true, "device-pad-session", false, false, false));
    }

    @Test
    void padPlayerStopSuppressesOnlyTheNearbyRoute() {
        assertFalse(MediaAudienceRoutingPolicy.shouldBroadcastStopNearby(true, "device-pad-session"));
        assertTrue(MediaAudienceRoutingPolicy.shouldBroadcastStopNearby(true, "device-mp4-session"));
        assertTrue(MediaAudienceRoutingPolicy.shouldBroadcastStopNearby(false, "device-pad-session"));
    }
}
