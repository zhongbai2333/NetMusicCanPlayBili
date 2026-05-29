package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver;
import com.mojang.logging.LogUtils;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
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
        if (info.staffNames() != null && !info.staffNames().isEmpty()) {
            songInfo.artists = new java.util.ArrayList<>(info.staffNames());
        }
        LOGGER.info("B站视频信息获取成功: {} (P{}, cid={}, {}s) UP主: {}",
                info.displayTitle(), info.page(), info.cid(), info.duration(), info.staffNames());
        LOGGER.info("唱片将存储原始 ID: {}", songInfo.songUrl);
        return songInfo;
    }

    @Override
    public boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        return BiliApiClient.isStoredVideoSelection(songInfo.songUrl);
    }

    public static String resolvePlayableUrl(String storedSelection) throws Exception {
        BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(storedSelection);
        if (selection == null) {
            throw new IllegalArgumentException("无法从唱片存储的内容提取 B站 ID: " + storedSelection);
        }

        BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
        return BiliApiClient.getBestAudioUrl(selection.videoId(), info.cid());
    }

    public static ItemMusicCD.SongInfo resolvePlayableSongInfo(ItemMusicCD.SongInfo songInfo) throws Exception {
        BiliApiClient.VideoSelection selection = BiliApiClient.parseStoredVideoSelection(songInfo.songUrl);
        if (selection == null) {
            throw new IllegalArgumentException("无法从唱片存储的内容提取 B站 ID: " + songInfo.songUrl);
        }

        BiliApiClient.VideoInfo info = BiliApiClient.getVideoInfo(selection.videoId(), selection.page());
        String audioUrl = Objects.requireNonNull(BiliApiClient.getBestAudioUrl(selection.videoId(), info.cid()),
                "音频直链不能为空");

        String title = songInfo.songName;
        if (title == null || title.isBlank()) {
            title = info.displayTitle();
        }
        title = Objects.requireNonNull(title, "标题不能为空");

        // 不修改传入的 songInfo，避免 CDN 直链覆盖 BV 号导致下次无法重新解析
        ItemMusicCD.SongInfo resolved = new ItemMusicCD.SongInfo(
                audioUrl,
                title,
                songInfo.songTime > 0 ? songInfo.songTime : info.duration(),
                false);
        if ((songInfo.artists == null || songInfo.artists.isEmpty())
                && info.staffNames() != null && !info.staffNames().isEmpty()) {
            resolved.artists = new java.util.ArrayList<>(info.staffNames());
        } else {
            resolved.artists = songInfo.artists;
        }
        return resolved;
    }

    @Override
    public CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        if (FMLEnvironment.getDist() == Dist.DEDICATED_SERVER) {
            LOGGER.info("服务端跳过 B站 CDN 直链解析，保留原始 ID 下发给客户端: {}", songInfo.songUrl);
            return CompletableFuture.completedFuture(songInfo);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                ItemMusicCD.SongInfo resolved = resolvePlayableSongInfo(songInfo);
                LOGGER.info("B站 CDN 直链刷新成功: {}", resolved.songName);
                return resolved;

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
