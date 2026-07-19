package com.zhongbai233.net_music_can_play_bili.item.pad;

import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record PadDocument(String title, String author, boolean locked, long updatedAtMillis, long sequence,
        PadMapSettings mapSettings, List<PadMediaEntry> mediaEntries, List<PadTriggerPoint> triggerPoints) {
    public static final int MAX_MEDIA_ENTRIES = 64;
    public static final int MAX_TRIGGER_POINTS = 128;
    public static final PadDocument DEFAULT = new PadDocument("", "", false, 0L, 0L, PadMapSettings.DEFAULT,
            List.of(), List.of());

    public PadDocument {
        title = title == null ? "" : title;
        author = author == null ? "" : author;
        updatedAtMillis = Math.max(0L, updatedAtMillis);
        sequence = Math.max(0L, sequence);
        mapSettings = mapSettings == null ? PadMapSettings.DEFAULT : mapSettings;
        mediaEntries = mediaEntries == null ? List.of()
                : mediaEntries.stream()
                        .filter(entry -> entry != null && !entry.disc().isEmpty())
                        .sorted(Comparator.comparingInt(entry -> entry.mediaId()))
                        .limit(MAX_MEDIA_ENTRIES)
                        .toList();
        triggerPoints = triggerPoints == null ? List.of()
                : triggerPoints.stream()
                        .filter(point -> point != null)
                        .limit(MAX_TRIGGER_POINTS)
                        .toList();
    }

    public Optional<PadMediaEntry> media(int mediaId) {
        return mediaEntries.stream().filter(entry -> entry.mediaId() == mediaId).findFirst();
    }

    public int nextFreeMediaId() {
        for (int id = 1; id <= MAX_MEDIA_ENTRIES; id++) {
            final int candidate = id;
            if (mediaEntries.stream().noneMatch(entry -> entry.mediaId() == candidate)) {
                return candidate;
            }
        }
        return -1;
    }

    public PadDocument withAddedMedia(ItemStack disc) {
        int id = nextFreeMediaId();
        if (id < 0 || disc == null || disc.isEmpty()) {
            return this;
        }
        java.util.ArrayList<PadMediaEntry> entries = new java.util.ArrayList<>(mediaEntries);
        entries.add(new PadMediaEntry(id, disc));
        return touch(entries, triggerPoints);
    }

    public PadDocument withRemovedMedia(int mediaId) {
        java.util.ArrayList<PadMediaEntry> entries = new java.util.ArrayList<>(mediaEntries);
        if (!entries.removeIf(entry -> entry.mediaId() == mediaId)) {
            return this;
        }
        return touch(entries, triggerPoints);
    }

    public PadDocument withTrigger(PadTriggerPoint point) {
        if (point == null) {
            return this;
        }
        java.util.ArrayList<PadTriggerPoint> points = new java.util.ArrayList<>(triggerPoints);
        points.removeIf(existing -> existing.pointId().equals(point.pointId()));
        points.add(point);
        return touch(mediaEntries, points);
    }

    public PadDocument withLocked(boolean locked) {
        PadDocumentLockPolicy.LockCopy<PadMediaEntry, PadTriggerPoint> copy = PadDocumentLockPolicy.transition(
                this.locked, locked, updatedAtMillis, sequence, mediaEntries, triggerPoints,
                true, System.currentTimeMillis());
        if (copy == null) {
            return this;
        }
        return new PadDocument(title, author, copy.locked(), copy.updatedAtMillis(), copy.sequence(), mapSettings,
                copy.mediaEntries(), copy.triggerPoints());
    }

    public PadDocument copyWithLocked(boolean locked) {
        PadDocumentLockPolicy.LockCopy<PadMediaEntry, PadTriggerPoint> copy = PadDocumentLockPolicy.transition(
                this.locked, locked, updatedAtMillis, sequence, mediaEntries, triggerPoints,
                false, 0L);
        if (copy == null) {
            return this;
        }
        return new PadDocument(title, author, copy.locked(), copy.updatedAtMillis(), copy.sequence(), mapSettings,
                copy.mediaEntries(), copy.triggerPoints());
    }

    private PadDocument touch(List<PadMediaEntry> media, List<PadTriggerPoint> points) {
        return new PadDocument(title, author, locked, System.currentTimeMillis(), sequence + 1, mapSettings, media,
                points);
    }
}