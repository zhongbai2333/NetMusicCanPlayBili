package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直播机的视频同步入口。
 *
 * <p>
 * 与点播不同，直播视频不需要解析直链——样本由音频会话的同一条 FLV 连接经
 * {@link LiveVideoSampleBus} 供给，这里只决定"何时该有渲染会话"：有链接的
 * 视频投影仪（或全息眼镜）且音频已经开始输出时启动，投影仪拆除时停止。
 * </p>
 */
public final class LiveStreamerVideoClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_FPS = Math.max(15, Integer.getInteger("ncpb.video.live.fps", 30));
    private static final int HIGH_QUALITY_CEILING = 116;
    private static final int DEFAULT_QUALITY_CEILING = Integer.getInteger("bili.video.turntable.quality", 116);

    /** 每个 session 最近一次的决策指纹，只在状态变化时输出日志。 */
    private static final ConcurrentHashMap<String, String> LAST_DECISION = new ConcurrentHashMap<>();

    private LiveStreamerVideoClient() {
    }

    /** 由 LiveStreamerSound 周期性调用（客户端线程）。 */
    public static void sync(BlockPos livePos, String sessionId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (livePos == null || sessionId == null || sessionId.isBlank() || minecraft.level == null) {
            return;
        }
        int rawSourceCount = ClientLinkRegistry.getSources(livePos).size();
        List<VideoProjectorBlockEntity> projectors = findLinkedVideoProjectors(livePos);
        boolean holographicConsumer = HolographicGlassesClient.handlesTurntable(livePos);
        if (projectors.isEmpty() && !holographicConsumer) {
            logDecision(sessionId, "no-consumer", "直播画面暂无消费端: pos=" + livePos + " session=" + sessionId
                    + " registrySources=" + rawSourceCount + "（需要链接视频投影仪或佩戴全息眼镜）");
            VideoBillboardPreview.stopIfSession(sessionId);
            return;
        }
        if (!isAudioReady(livePos, sessionId)) {
            logDecision(sessionId, "wait-audio", "直播画面等待音频输出就绪: pos=" + livePos + " session=" + sessionId);
            return;
        }
        List<BlockPos> positions = projectors.stream()
                .map(projector -> projector.getBlockPos().immutable())
                .toList();
        if (VideoBillboardPreview.isSessionRunning(sessionId)) {
            VideoBillboardPreview.updateSessionProjectors(sessionId, positions);
            logDecision(sessionId, "running:" + positions.size(),
                    "直播画面会话运行中: session=" + sessionId + " projectors=" + positions.size());
            return;
        }
        int qualityCeiling = qualityCeiling(projectors);
        int width = qualityCeiling >= HIGH_QUALITY_CEILING ? 1920 : 1280;
        int height = qualityCeiling >= HIGH_QUALITY_CEILING ? 1080 : 720;
        LOGGER.info("直播画面会话启动: session={} pos={} {}x{}@{}fps projectors={} holographic={}",
                sessionId, livePos, width, height, DEFAULT_FPS, positions.size(), holographicConsumer);
        LAST_DECISION.put(sessionId, "started");
        VideoBillboardPreview.startLiveSession(LiveVideoSampleBus.busUrl(sessionId), width, height, DEFAULT_FPS,
                sessionId, positions, livePos);
    }

    /** 直播会话结束时停止渲染实例。 */
    public static void forget(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            LAST_DECISION.remove(sessionId);
            VideoBillboardPreview.stopIfSession(sessionId);
        }
    }

    private static void logDecision(String sessionId, String fingerprint, String message) {
        String previous = LAST_DECISION.put(sessionId, fingerprint);
        if (!fingerprint.equals(previous)) {
            LOGGER.info("{}", message);
        }
    }

    /** 音频总线锚点建立之前视频没有时间域，等音频先喂进 OpenAL。 */
    private static boolean isAudioReady(BlockPos livePos, String sessionId) {
        ClientAudioOutputRegistry.AudioTimeline timeline = ClientAudioOutputRegistry.getAudioTimeline(livePos);
        String audioSessionId = timeline.audioSessionId();
        if (audioSessionId != null && !audioSessionId.isBlank() && !audioSessionId.equals(sessionId)) {
            return false;
        }
        return timeline.audibleMillis() >= 0L || timeline.fedMillis() >= 0L;
    }

    private static int qualityCeiling(List<VideoProjectorBlockEntity> projectors) {
        return projectors.stream()
                .mapToInt(projector -> projector.getPreferredQuality() > 0
                        ? projector.getPreferredQuality()
                        : DEFAULT_QUALITY_CEILING)
                .max()
                .orElse(DEFAULT_QUALITY_CEILING);
    }

    private static List<VideoProjectorBlockEntity> findLinkedVideoProjectors(BlockPos livePos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (livePos == null || minecraft.level == null) {
            return List.of();
        }
        List<VideoProjectorBlockEntity> projectors = new ArrayList<>();
        for (BlockPos sourcePos : ClientLinkRegistry.getSources(livePos)) {
            BlockEntity be = minecraft.level.getBlockEntity(sourcePos);
            if (be instanceof VideoProjectorBlockEntity projector
                    && livePos.equals(projector.getLinkedTurntablePos())) {
                projectors.add(projector);
            }
        }
        return projectors;
    }
}
