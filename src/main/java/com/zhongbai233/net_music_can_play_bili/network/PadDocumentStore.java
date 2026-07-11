package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime cache and SavedData bridge for Pad documents. */
public final class PadDocumentStore {
    private static final Map<UUID, PadDocument> RUNTIME = new ConcurrentHashMap<>();

    private PadDocumentStore() {
    }

    public static PadDocument getOrCreate(ServerLevel level, UUID deviceId, ItemStack seedStack) {
        if (deviceId == null) {
            return PadDocument.DEFAULT;
        }
        PadDocument runtime = RUNTIME.get(deviceId);
        if (runtime != null) {
            return syncStackCopy(level, deviceId, seedStack, runtime);
        }
        PadDocument saved = level != null ? PadDocumentSavedData.get(level).document(deviceId).orElse(null) : null;
        if (saved != null) {
            PadDocument restored = saved.copyWithLocked(false);
            RUNTIME.put(deviceId, restored);
            return syncStackCopy(level, deviceId, seedStack, restored);
        }
        PadDocument legacy = PadItem.readLegacyDocument(seedStack);
        PadDocument created = (legacy != null ? legacy : PadDocument.DEFAULT).copyWithLocked(false);
        RUNTIME.put(deviceId, created);
        if (level != null) {
            PadDocumentSavedData.get(level).put(deviceId, created);
        }
        return created;
    }

    public static PadDocument get(UUID deviceId) {
        return deviceId == null ? PadDocument.DEFAULT : RUNTIME.getOrDefault(deviceId, PadDocument.DEFAULT);
    }

    public static void update(ServerLevel level, UUID deviceId, PadDocument document) {
        if (deviceId == null || document == null) {
            return;
        }
        PadDocument normalized = document.copyWithLocked(false);
        RUNTIME.put(deviceId, normalized);
        if (level != null) {
            PadDocumentSavedData.get(level).put(deviceId, normalized);
        }
        PadDeviceHolderTracker.invalidate(deviceId);
    }

    public static void clearRuntime() {
        RUNTIME.clear();
    }

    private static PadDocument syncStackCopy(ServerLevel level, UUID deviceId, ItemStack stack, PadDocument current) {
        PadDocument stackCopy = PadItem.readLegacyDocument(stack);
        if (stackCopy != null && compareVersion(stackCopy, current) > 0) {
            PadDocument updated = stackCopy.copyWithLocked(false);
            update(level, deviceId, updated);
            return updated;
        }
        return current;
    }

    private static int compareVersion(PadDocument left, PadDocument right) {
        int timeCompare = Long.compare(left.updatedAtMillis(), right.updatedAtMillis());
        return timeCompare != 0 ? timeCompare : Long.compare(left.sequence(), right.sequence());
    }
}