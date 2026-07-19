package com.zhongbai233.net_music_can_play_bili.client.pad;

/** Pure evidence-combination policy for selecting an indoor Pad map profile. */
final class PadMapViewProfilePolicy {
    private PadMapViewProfilePolicy() {
    }

    static boolean isIndoorEvidence(int ceilingHits, int artificialCeilingHits, int nearbyArtificialHits,
            int ceilingMinHits, int artificialMinHits) {
        return ceilingHits >= ceilingMinHits
                && (artificialCeilingHits >= ceilingMinHits || nearbyArtificialHits >= artificialMinHits);
    }
}