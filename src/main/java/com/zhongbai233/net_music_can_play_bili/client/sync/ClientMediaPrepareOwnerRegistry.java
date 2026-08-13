package com.zhongbai233.net_music_can_play_bili.client.sync;

import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSourceId;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Exact ownership registry for asynchronous client media prepare and lyric tasks. */
final class ClientMediaPrepareOwnerRegistry {
    private final ConcurrentMap<Key, Owner> owners = new ConcurrentHashMap<>();

    boolean tryRegister(Key key, Owner owner) {
        return owners.putIfAbsent(Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(owner, "owner")) == null;
    }

    void replace(Key key, Owner owner) {
        Owner replacement = Objects.requireNonNull(owner, "owner");
        Owner previous = owners.put(Objects.requireNonNull(key, "key"), replacement);
        if (previous != null && previous != replacement) {
            previous.cancel();
        }
    }

    boolean remove(Key key, Owner owner) {
        return key != null && owner != null && owners.remove(key, owner);
    }

    void cancelSource(PlaybackSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        owners.forEach((key, owner) -> {
            if (sourceId.equals(key.sourceId()) && owners.remove(key, owner)) {
                owner.cancel();
            }
        });
    }

    boolean contains(Key key) {
        return key != null && owners.containsKey(key);
    }

    int size() {
        return owners.size();
    }

    void clear() {
        owners.forEach((key, owner) -> {
            if (owners.remove(key, owner)) {
                owner.cancel();
            }
        });
    }

    record Key(PlaybackSourceId sourceId, PlaybackSessionId sessionId, boolean headphoneRouted) {
        Key {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(sessionId, "sessionId");
        }
    }

    interface Owner {
        void cancel();
    }
}
