package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver;
import com.mojang.logging.LogUtils;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * NetMusic 附加解析器
 */
public class BiliAudioResolver implements IAsyncSongUrlResolver {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static ItemMusicCD.SongInfo resolveBiliSongInfo(String rawInput) throws Exception {
        return resolveBiliSongInfo(rawInput, 1);
    }

    public static ItemMusicCD.SongInfo resolveBiliSongInfo(String rawInput, int page) throws Exception {
        BiliApiClient.VideoId videoId = BiliApiClient.extractVideoId(rawInput);
        if (videoId == null) {
            throw new IllegalArgumentException("请输入 B站 BV 号或 AV 号: " + rawInput);
        }

        BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(videoId, page);

        ItemMusicCD.SongInfo songInfo = new ItemMusicCD.SongInfo(
                Objects.requireNonNull(BiliApiClient.formatStoredVideoSelection(videoId, info.page())),
                Objects.requireNonNull(info.displayTitle()),
                info.duration(),
                false);
        LOGGER.info("B站视频信息获取成功: {} (P{}, cid={}, {}s)",
                info.displayTitle(), info.page(), info.cid(), info.duration());
        LOGGER.info("唱片将存储原始 ID: {}", songInfo.songUrl);
        return songInfo;
    }

    @Override
    public boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        return BiliApiClient.isStoredVideoSelection(songInfo.songUrl);
    }

    @Override
    public CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(songInfo.songUrl);
                if (selection == null) {
                    LOGGER.error("无法从唱片存储的内容提取 B站 ID: {}", songInfo.songUrl);
                    return songInfo;
                }

                BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
                String audioUrl = BiliApiClient.getBestAudioUrl(selection.videoId(), info.cid());

                songInfo.songUrl = audioUrl;
                if (songInfo.songName == null || songInfo.songName.isBlank()) {
                    songInfo.songName = info.displayTitle();
                }
                if (songInfo.songTime <= 0) {
                    songInfo.songTime = info.duration();
                }
                LOGGER.info("B站 CDN 直链刷新成功: {}", info.displayTitle());

            } catch (Exception e) {
                LOGGER.error("B站音频解析失败: {}", e.getMessage());
            }
            return songInfo;
        });
    }

    @Override
    public int getPriority() {
        return 50;
    }
}
