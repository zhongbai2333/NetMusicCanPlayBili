package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import com.zhongbai233.net_music_can_play_bili.media.stream.LiveVideoSampleBus;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
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
    private static final VideoClientProperties.Live VIDEO_PROPERTIES = VideoClientProperties.live();
    private static final int HIGH_QUALITY_CEILING = 116;

    /** 每个 session 最近一次的决策指纹，只在状态变化时输出日志。 */
    private static final ConcurrentHashMap<PlaybackSessionId, String> LAST_DECISION = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PlaybackSessionId, Integer> ACTIVE_QUALITY = new ConcurrentHashMap<>();
    private static final MediaConsumerRegistry<BlockPos> CONTROL_CONSOLE_CONSUMERS = new MediaConsumerRegistry<>();
    private static final ConcurrentHashMap<BlockPos, Integer> CONTROL_CONSOLE_QUALITY = new ConcurrentHashMap<>();

    private LiveStreamerVideoClient() {
    }

    /** 由 LiveStreamerSound 周期性调用（客户端线程）。 */
    public static void sync(BlockPos livePos, String sessionId) {
        Minecraft minecraft = Minecraft.getInstance();
        PlaybackSessionId sessionKey = PlaybackSessionId.parse(sessionId).orElse(null);
        if (livePos == null || sessionKey == null || minecraft.level == null) {
            return;
        }
        int rawSourceCount = ClientLinkRegistry.getSources(livePos).size();
        List<VideoProjectorBlockEntity> projectors = findLinkedVideoProjectors(livePos);
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        projectors.stream().map(projector -> projector.getBlockPos().immutable()).forEach(positions::add);
        positions.addAll(CONTROL_CONSOLE_CONSUMERS.consumersFor(livePos));
        boolean holographicConsumer = HolographicGlassesClient.handlesTurntable(livePos);
        if (positions.isEmpty() && !holographicConsumer) {
            logDecision(sessionKey, "no-consumer", "直播画面暂无消费端: pos=" + livePos + " session=" + sessionId
                    + " registrySources=" + rawSourceCount + "（需要链接视频投影仪或佩戴全息眼镜）");
            VideoBillboardPreview.stopIfSession(sessionId);
            return;
        }
        if (!isAudioReady(livePos, sessionId)) {
            logDecision(sessionKey, "wait-audio", "直播画面等待音频输出就绪: pos=" + livePos + " session=" + sessionId);
            return;
        }
        List<BlockPos> consumerPositions = List.copyOf(positions);
        int qualityCeiling = qualityCeiling(projectors, CONTROL_CONSOLE_CONSUMERS.consumersFor(livePos));
        if (VideoBillboardPreview.isSessionRunning(sessionId)) {
            if (!java.util.Objects.equals(ACTIVE_QUALITY.get(sessionKey), qualityCeiling)) {
                VideoBillboardPreview.stopIfSession(sessionId);
            } else {
                VideoBillboardPreview.updateSessionProjectors(sessionId, consumerPositions);
                logDecision(sessionKey, "running:" + consumerPositions.size(),
                    "直播画面会话运行中: session=" + sessionId + " consumers=" + consumerPositions.size());
                return;
            }
        }
        int width = qualityCeiling >= HIGH_QUALITY_CEILING ? 1920 : 1280;
        int height = qualityCeiling >= HIGH_QUALITY_CEILING ? 1080 : 720;
        LOGGER.info("直播画面会话启动: session={} pos={} {}x{}@{}fps projectors={} holographic={}",
                sessionId, livePos, width, height, VIDEO_PROPERTIES.fps(), consumerPositions.size(),
                holographicConsumer);
        LAST_DECISION.put(sessionKey, "started");
        ACTIVE_QUALITY.put(sessionKey, qualityCeiling);
        VideoBillboardPreview.startLiveSession(LiveVideoSampleBus.busUrl(sessionKey), width, height,
                VIDEO_PROPERTIES.fps(),
                sessionId, consumerPositions, livePos);
    }

    public static void registerControlConsoleConsumer(BlockPos livePos, BlockPos consolePos, int qualityCeiling) {
        if (livePos != null && consolePos != null) {
            CONTROL_CONSOLE_CONSUMERS.register(livePos.immutable(), consolePos.immutable());
            CONTROL_CONSOLE_QUALITY.put(consolePos.immutable(), qualityCeiling);
        }
    }

    public static void unregisterControlConsoleConsumer(BlockPos consolePos) {
        CONTROL_CONSOLE_CONSUMERS.unregister(consolePos);
        if (consolePos != null) {
            CONTROL_CONSOLE_QUALITY.remove(consolePos);
        }
    }

    public static void clear() {
        CONTROL_CONSOLE_CONSUMERS.clear();
        CONTROL_CONSOLE_QUALITY.clear();
        LAST_DECISION.clear();
        ACTIVE_QUALITY.clear();
    }

    /** 直播会话结束时停止渲染实例。 */
    public static void forget(String sessionId) {
        PlaybackSessionId.parse(sessionId).ifPresent(sessionKey -> {
            LAST_DECISION.remove(sessionKey);
            ACTIVE_QUALITY.remove(sessionKey);
            VideoBillboardPreview.stopIfSession(sessionId);
        });
    }

    private static void logDecision(PlaybackSessionId sessionId, String fingerprint, String message) {
        String previous = LAST_DECISION.put(sessionId, fingerprint);
        if (!fingerprint.equals(previous)) {
            LOGGER.debug("{}", message);
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

        private static int qualityCeiling(List<VideoProjectorBlockEntity> projectors,
            java.util.Collection<BlockPos> consoleConsumers) {
        int projectorQuality = projectors.stream()
                .mapToInt(projector -> projector.getPreferredQuality() > 0
                        ? projector.getPreferredQuality()
                        : VIDEO_PROPERTIES.qualityCeiling())
                .max()
                .orElse(0);
        int consoleQuality = consoleConsumers.stream()
            .mapToInt(pos -> CONTROL_CONSOLE_QUALITY.getOrDefault(pos, VIDEO_PROPERTIES.qualityCeiling()))
            .max()
            .orElse(0);
        int selected = Math.max(projectorQuality, consoleQuality);
        return selected > 0 ? selected : VIDEO_PROPERTIES.qualityCeiling();
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
