package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaSyncPayload;

import java.util.Objects;
import java.util.UUID;

/** Pure decision policy for observed MP4 source locations. */
final class MP4PlaybackSourceObservationPolicy {
    private MP4PlaybackSourceObservationPolicy() {
    }

    static Action action(Observation existing, Observation observed) {
        Objects.requireNonNull(observed, "observed");
        if (existing == null) {
            return Action.START;
        }
        boolean matches = switch (observed.sourceType()) {
            case ClientMediaSyncPayload.SOURCE_PLAYER ->
                existing.sourceType() == ClientMediaSyncPayload.SOURCE_PLAYER;
            case ClientMediaSyncPayload.SOURCE_ITEM ->
                existing.sourceType() == ClientMediaSyncPayload.SOURCE_ITEM
                        && existing.sourceEntityId() == observed.sourceEntityId();
            case ClientMediaSyncPayload.SOURCE_BLOCK ->
                existing.sourceType() == ClientMediaSyncPayload.SOURCE_BLOCK
                        && Objects.equals(existing.sourcePosition(), observed.sourcePosition())
                        && existing.containerSlot() == observed.containerSlot();
            case ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY ->
                existing.sourceType() == ClientMediaSyncPayload.SOURCE_CONTAINER_ENTITY
                        && existing.sourceEntityId() == observed.sourceEntityId()
                        && existing.containerSlot() == observed.containerSlot();
            default -> false;
        };
        return matches ? Action.KEEP : Action.MIGRATE;
    }

    static boolean samePhysicalSource(PhysicalIdentity left, PhysicalIdentity right) {
        return left != null && right != null
                && Objects.equals(left.levelKey(), right.levelKey())
                && left.sourceType() == right.sourceType()
                && left.sourceEntityId() == right.sourceEntityId()
                && Objects.equals(left.sourcePosition(), right.sourcePosition())
                && left.containerSlot() == right.containerSlot()
                && Objects.equals(left.ownerId(), right.ownerId());
    }

    enum Action {
        START,
        KEEP,
        MIGRATE
    }

    record Observation(int sourceType, int sourceEntityId, Object sourcePosition, int containerSlot) {
    }

    record PhysicalIdentity(Object levelKey, UUID ownerId, int sourceType, int sourceEntityId,
            Object sourcePosition, int containerSlot) {
    }
}
