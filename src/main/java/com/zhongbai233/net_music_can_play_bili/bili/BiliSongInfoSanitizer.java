package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** 保证 B站唱片只保存 BV/AV 选集，不保存 CDN/直链。 */
public final class BiliSongInfoSanitizer {
    private BiliSongInfoSanitizer() {
    }

    public static ItemMusicCD.SongInfo sanitize(ItemMusicCD.SongInfo song) {
        if (song == null || song.songUrl == null || song.songUrl.isBlank()) {
            return song;
        }
        BiliApiClient.VideoSelection selection = BiliApiClient.extractVideoSelectionLenient(song.songUrl);
        if (selection == null) {
            return song;
        }
        String stored = BiliApiClient.formatStoredVideoSelection(selection.videoId(), selection.page());
        if (stored.equals(song.songUrl)) {
            return song;
        }
        ItemMusicCD.SongInfo normalized = new ItemMusicCD.SongInfo(stored,
                song.songName == null ? "" : song.songName,
                Math.max(0, song.songTime), song.vip);
        normalized.readOnly = song.readOnly;
        normalized.artists = song.artists;
        return normalized;
    }

    public static ItemStack sanitizeDisc(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return stack == null ? ItemStack.EMPTY : stack;
        }
        ItemMusicCD.SongInfo song = ItemMusicCD.getSongInfo(stack);
        ItemMusicCD.SongInfo sanitized = sanitize(song);
        if (sanitized == song) {
            return stack;
        }
        return ItemMusicCD.setSongInfo(Objects.requireNonNull(sanitized),
                Objects.requireNonNull(stack.copyWithCount(1)));
    }

    public static boolean isForbiddenBiliDirectUrl(String raw) {
        if (raw == null || raw.isBlank() || BiliApiClient.extractVideoSelectionLenient(raw) != null) {
            return false;
        }
        try {
            URI uri = URI.create(raw.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            return lower.endsWith("bilivideo.com")
                    || lower.endsWith("hdslb.com")
                    || lower.endsWith("bilibili.com")
                    || lower.endsWith("biliapi.net")
                    || lower.endsWith("biliapi.com");
        } catch (IllegalArgumentException ignored) {
            String lower = raw.toLowerCase(Locale.ROOT);
            return lower.contains("bilivideo.com")
                    || lower.contains("hdslb.com")
                    || lower.contains("bilibili.com/video/");
        }
    }
}
