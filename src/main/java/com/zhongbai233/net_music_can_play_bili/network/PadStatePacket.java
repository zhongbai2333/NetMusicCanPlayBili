package com.zhongbai233.net_music_can_play_bili.network;

import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadDocument;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMediaEntry;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadMapSettings;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerMode;
import com.zhongbai233.net_music_can_play_bili.item.pad.PadTriggerPoint;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record PadStatePacket(UUID deviceId, PadDocument document, long clientUpdatedAtMillis, long clientSequence)
        implements CustomPacketPayload {
    public static final Type<PadStatePacket> TYPE = new Type<>(NetworkPayloadIds.id("pad_state"));
    private static final Map<SyncKey, SyncVersion> LAST_CLIENT_SYNCS = new ConcurrentHashMap<>();

    public static final StreamCodec<RegistryFriendlyByteBuf, PadStatePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public PadStatePacket decode(RegistryFriendlyByteBuf buffer) {
            return new PadStatePacket(buffer.readUUID(), readDocument(buffer), buffer.readVarLong(),
                    buffer.readVarLong());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, PadStatePacket packet) {
            buffer.writeUUID(packet.deviceId());
            writeDocument(buffer, packet.document());
            buffer.writeVarLong(Math.max(0L, packet.clientUpdatedAtMillis()));
            buffer.writeVarLong(Math.max(0L, packet.clientSequence()));
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PadStatePacket payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player) || payload.deviceId() == null
                || payload.document() == null) {
            return;
        }
        java.util.List<ItemStack> stacks = PadItem.findAllByDeviceId(player, payload.deviceId());
        ItemStack stack = stacks.stream().filter(PadItem::isPad).filter(candidate -> !PadItem.readLocked(candidate))
                .findFirst().orElse(stacks.stream().filter(PadItem::isPad).findFirst().orElse(ItemStack.EMPTY));
        if (!PadItem.isPad(stack) || PadItem.readLocked(stack)) {
            return;
        }
        SyncKey syncKey = new SyncKey(player.getUUID(), payload.deviceId());
        SyncVersion incoming = new SyncVersion(payload.clientUpdatedAtMillis(), payload.clientSequence());
        SyncVersion previous = LAST_CLIENT_SYNCS.get(syncKey);
        if (previous != null && incoming.compareTo(previous) < 0) {
            return;
        }
        LAST_CLIENT_SYNCS.put(syncKey, incoming);
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        PadDocument current = PadDocumentStore.getOrCreate(level, payload.deviceId(), stack);
        if (compareVersion(payload.document(), current) < 0) {
            return;
        }
        PadDocument draftDocument = payload.document().copyWithLocked(false);
        PadDocumentStore.update(level, payload.deviceId(), draftDocument);
        PadItem.writeDocumentToUnlockedCopies(player, payload.deviceId(), draftDocument);
        PadItem.writeDocumentToLockedCopies(player, payload.deviceId(), draftDocument);
        PadDeviceHolderTracker.invalidate(payload.deviceId());
    }

    static void writeDocument(RegistryFriendlyByteBuf buffer, PadDocument document) {
        PadDocument safe = document == null ? PadDocument.DEFAULT : document;
        buffer.writeUtf(safe.title(), 128);
        buffer.writeUtf(safe.author(), 128);
        buffer.writeBoolean(safe.locked());
        buffer.writeVarLong(safe.updatedAtMillis());
        buffer.writeVarLong(safe.sequence());
        PadMapSettings settings = safe.mapSettings();
        buffer.writeUtf(settings.dimension(), 128);
        buffer.writeVarInt(settings.centerX());
        buffer.writeVarInt(settings.centerZ());
        buffer.writeVarInt(settings.radiusBlocks());
        buffer.writeFloat(settings.zoom());
        buffer.writeBoolean(settings.autoFollowPlayer());
        List<PadMediaEntry> media = safe.mediaEntries();
        int mediaSize = Math.min(PadDocument.MAX_MEDIA_ENTRIES, media.size());
        buffer.writeVarInt(mediaSize);
        for (int i = 0; i < mediaSize; i++) {
            PadMediaEntry entry = media.get(i);
            buffer.writeVarInt(entry.mediaId());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, entry.disc());
        }
        List<PadTriggerPoint> points = safe.triggerPoints();
        int pointSize = Math.min(PadDocument.MAX_TRIGGER_POINTS, points.size());
        buffer.writeVarInt(pointSize);
        for (int i = 0; i < pointSize; i++) {
            PadTriggerPoint point = points.get(i);
            buffer.writeUUID(point.pointId());
            buffer.writeUtf(point.name(), 128);
            buffer.writeDouble(point.x());
            buffer.writeDouble(point.y());
            buffer.writeDouble(point.z());
            buffer.writeVarInt(point.radiusBlocks());
            buffer.writeVarInt(point.mediaId());
            buffer.writeUtf(point.triggerMode().name(), 32);
            buffer.writeBoolean(point.loop());
            buffer.writeVarInt(point.volumePerMille());
            buffer.writeBoolean(point.visible());
        }
    }

    static PadDocument readDocument(RegistryFriendlyByteBuf buffer) {
        String title = buffer.readUtf(128);
        String author = buffer.readUtf(128);
        boolean locked = buffer.readBoolean();
        long updatedAt = buffer.readVarLong();
        long sequence = buffer.readVarLong();
        PadMapSettings settings = new PadMapSettings(buffer.readUtf(128), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readFloat(), buffer.readBoolean());
        int mediaSize = Math.max(0, Math.min(PadDocument.MAX_MEDIA_ENTRIES, buffer.readVarInt()));
        List<PadMediaEntry> media = new ArrayList<>(mediaSize);
        for (int i = 0; i < mediaSize; i++) {
            int mediaId = buffer.readVarInt();
            ItemStack disc = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            if (PadItem.isNetMusicDisc(disc)) {
                media.add(new PadMediaEntry(mediaId, disc));
            }
        }
        int pointSize = Math.max(0, Math.min(PadDocument.MAX_TRIGGER_POINTS, buffer.readVarInt()));
        List<PadTriggerPoint> points = new ArrayList<>(pointSize);
        for (int i = 0; i < pointSize; i++) {
            points.add(new PadTriggerPoint(buffer.readUUID(), buffer.readUtf(128), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readVarInt(), buffer.readVarInt(),
                    PadTriggerMode.byName(buffer.readUtf(32)), buffer.readBoolean(), buffer.readVarInt(),
                    buffer.readBoolean()));
        }
        return new PadDocument(title, author, locked, updatedAt, sequence, settings, media, points);
    }

    static int compareVersion(PadDocument left, PadDocument right) {
        int timeCompare = Long.compare(left.updatedAtMillis(), right.updatedAtMillis());
        return timeCompare != 0 ? timeCompare : Long.compare(left.sequence(), right.sequence());
    }

    private record SyncKey(UUID playerId, UUID deviceId) {
    }

    private record SyncVersion(long updatedAtMillis, long sequence) implements Comparable<SyncVersion> {
        @Override
        public int compareTo(SyncVersion other) {
            if (other == null) {
                return 1;
            }
            int timeCompare = Long.compare(updatedAtMillis, other.updatedAtMillis());
            return timeCompare != 0 ? timeCompare : Long.compare(sequence, other.sequence());
        }
    }
}