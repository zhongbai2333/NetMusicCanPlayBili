package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioHandler;
import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

public class ClientAudioOutputRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ACTIVE_TURNTABLES = 16;
    private static final long CLEANUP_TIMEOUT_MILLIS = 2_000L;
    private static final AtomicInteger ANONYMOUS_COUNTER = new AtomicInteger();
    private static final BoundedConcurrentStore<BlockPos, AudioEntry> OUTPUTS = new BoundedConcurrentStore<>(
            MAX_ACTIVE_TURNTABLES);
    private static final ConcurrentMap<UUID, BlockPos> MINECART_KEYS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Float> OWNER_VOLUMES = new ConcurrentHashMap<>();

    /** 音响 relay 注册表：speakerPos → relay */
    private static final ConcurrentMap<BlockPos, SpeakerAudioRelay> RELAYS = new ConcurrentHashMap<>();
    /** relay → turntable 反向映射 */
    private static final ConcurrentMap<BlockPos, BlockPos> RELAY_TURNTABLE = new ConcurrentHashMap<>();

    private static volatile float[] listenerPos;

    public static void register(DolbyAudioHandler handler) {
        register(handler, null);
    }

    public static void register(DolbyAudioHandler handler, BlockPos pos) {
        register(handler, pos, 0f);
    }

    public static void register(DolbyAudioHandler handler, BlockPos pos, float startOffsetSeconds) {
        register(handler, pos, startOffsetSeconds, "");
    }

    public static void register(DolbyAudioHandler handler, BlockPos pos, float startOffsetSeconds, String sessionId) {
        register(handler, pos, startOffsetSeconds, sessionId, null);
    }

    public static void register(DolbyAudioHandler handler, BlockPos pos, float startOffsetSeconds, String sessionId,
            UUID ownerId) {
        if (handler == null) {
            return;
        }
        BlockPos key = keyFor(pos, ownerId, sessionId);
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (!ClientAudioOutputPolicy.isCurrentSession(pos, normalizedSessionId)) {
            handler.hardStopOutput();
            handler.cleanup();
            return;
        }
        AudioEntry entry = new AudioEntry(key, centerFor(pos), handler, OutputKind.DOLBY, System.currentTimeMillis(),
                startOffsetTicks(startOffsetSeconds), normalizedSessionId, ownerId);
        handler.setUserVolume(ownerId != null ? OWNER_VOLUMES.getOrDefault(ownerId, 1.0F)
                : ClientAudioOutputPolicy.volume(pos));
        cleanupAfterPut(OUTPUTS.put(key, entry, entry.createdAtMillis()));
        // 自动连接已注册的音响 relay
        connectPendingRelays(key, handler);
    }

    public static void unregister(DolbyAudioHandler handler) {
        if (handler == null) {
            return;
        }
        unregisterOutput(handler);
    }

    public static void registerStereo(StereoOpenALHandler handler) {
        registerStereo(handler, null);
    }

    public static void registerStereo(StereoOpenALHandler handler, BlockPos pos) {
        registerStereo(handler, pos, 0f);
    }

    public static void registerStereo(StereoOpenALHandler handler, BlockPos pos, float startOffsetSeconds) {
        registerStereo(handler, pos, startOffsetSeconds, "");
    }

    public static void registerStereo(StereoOpenALHandler handler, BlockPos pos, float startOffsetSeconds,
            String sessionId) {
        registerStereo(handler, pos, startOffsetSeconds, sessionId, null);
    }

    public static void registerStereo(StereoOpenALHandler handler, BlockPos pos, float startOffsetSeconds,
            String sessionId, UUID ownerId) {
        if (handler == null) {
            return;
        }
        BlockPos key = keyFor(pos, ownerId, sessionId);
        String normalizedSessionId = normalizeSessionId(sessionId);
        if (!ClientAudioOutputPolicy.isCurrentSession(pos, normalizedSessionId)) {
            handler.hardStopOutput();
            handler.cleanup();
            return;
        }
        AudioEntry entry = new AudioEntry(key, centerFor(pos), handler, OutputKind.STEREO, System.currentTimeMillis(),
                startOffsetTicks(startOffsetSeconds), normalizedSessionId, ownerId);
        handler.setUserVolume(ownerId != null ? OWNER_VOLUMES.getOrDefault(ownerId, 1.0F)
                : ClientAudioOutputPolicy.volume(pos));
        cleanupAfterPut(OUTPUTS.put(key, entry, entry.createdAtMillis()));
        // 自动连接已注册的音响 relay
        connectPendingRelays(key, handler);
    }

    /** handler 创建时自动连接已注册的音响 relay */
    private static void connectPendingRelays(BlockPos handlerKey, AudioOutputHandle handler) {
        for (var entry : RELAYS.entrySet()) {
            BlockPos speakerPos = entry.getKey();
            BlockPos linkedTurntable = RELAY_TURNTABLE.get(speakerPos);
            if (linkedTurntable != null && linkedTurntable.equals(handlerKey)) {
                handler.addRelay(entry.getValue());
            }
        }
    }

    public static void unregisterStereo(StereoOpenALHandler handler) {
        if (handler == null) {
            return;
        }
        unregisterOutput(handler);
    }

    private static void unregisterOutput(AudioOutputHandle handler) {
        OUTPUTS.removeIf(entry -> entry.output() == handler);
    }

    public static void updatePositions(float[] listenerPos) {
        if (listenerPos == null) {
            return;
        }

        float[] currentListenerPos = AudioUtils.copyPos3(listenerPos);
        ClientAudioOutputRegistry.listenerPos = currentListenerPos;
        for (AudioEntry entry : OUTPUTS.values()) {
            if (discardIfStaleOutput(entry)) {
                continue;
            }
            if (entry.ownerId() == null) {
                entry.output().setUserVolume(ClientAudioOutputPolicy.volume(entry.pos()));
            }
            if (isRealWorldKey(entry.pos())
                    && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState
                            .linkedTurntableOutOfRange(entry.pos())) {
                if (OUTPUTS.remove(entry.pos(), entry)) {
                    hardStopAndCleanup(entry);
                }
                continue;
            }
            if (isRealWorldKey(entry.pos())
                    && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState
                            .suppressesTurntable(entry.pos())) {
                entry.output().tick(entry.machinePos(), currentListenerPos, Long.MIN_VALUE, false);
                continue;
            }
            float[] pos = resolveMachinePos(entry);
            if (isRealWorldKey(entry.pos())
                    && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState
                            .handlesTurntable(entry.pos())) {
                pos = currentListenerPos;
            }
            entry.output().tick(pos, currentListenerPos, targetRelativeTicks(entry), followsLocalPlayerFront(entry));
        }
    }

    private static boolean followsLocalPlayerFront(AudioEntry entry) {
        if (entry != null && isRealWorldKey(entry.pos())
                && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState.handlesTurntable(entry.pos())) {
            return true;
        }
        if (entry == null || entry.ownerId() == null) {
            return false;
        }
        if (com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback
                .sourcePosition(entry.ownerId()) != null) {
            return com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback
                    .followsLocalPlayerFront(entry.ownerId());
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.player != null && entry.ownerId().equals(mc.player.getUUID());
    }

    private static long targetRelativeTicks(AudioEntry entry) {
        return entry != null
                ? ClientAudioOutputPolicy.targetRelativeTicks(entry.pos(), entry.sessionId(), entry.startOffsetTicks())
                : Long.MAX_VALUE;
    }

    /**
     * 新 session 的网络播放已开始但新音频流尚未完成解码时，旧 handler 仍可能存在几百毫秒。
     * 不应把新唱片机时间轴传给旧 PCM 队列，否则会触发数百万 sample 的错误追赶并输出白噪音。
     */
    private static boolean discardIfStaleOutput(AudioEntry entry) {
        if (entry == null || !isRealWorldKey(entry.pos()) || entry.sessionId().isBlank()) {
            return false;
        }
        if (ClientAudioOutputPolicy.isCurrentSession(entry.pos(), entry.sessionId())) {
            return false;
        }
        if (OUTPUTS.remove(entry.pos(), entry)) {
            LOGGER.debug("丢弃等待新音频流期间的旧输出: pos={} oldSession={}", entry.pos(), entry.sessionId());
            hardStopAndCleanup(entry);
        }
        return true;
    }

    private static long startOffsetTicks(float startOffsetSeconds) {
        return Math.max(0L, Math.round(Math.max(0f, startOffsetSeconds) * 20.0D));
    }

    private static float[] resolveMachinePos(BlockPos handlerKey, float[] originalPos, UUID ownerId) {
        if (ownerId != null) {
            var mp4Pos = com.zhongbai233.net_music_can_play_bili.client.sync.ClientMediaPlayback
                    .sourcePosition(ownerId);
            if (mp4Pos != null) {
                return new float[] { (float) mp4Pos.x, (float) mp4Pos.y, (float) mp4Pos.z };
            }
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.level != null) {
                var owner = mc.level.getPlayerByUUID(ownerId);
                if (owner != null) {
                    return new float[] { (float) owner.getX(), (float) (owner.getY() + 1.2D), (float) owner.getZ() };
                }
            }
        }
        return originalPos;
    }

    private static float[] resolveMachinePos(AudioEntry entry) {
        var movingPos = ClientMinecartAudioAnchors.position(entry.sessionId());
        if (movingPos != null) {
            return new float[] { (float) movingPos.x, (float) movingPos.y, (float) movingPos.z };
        }
        return resolveMachinePos(entry.pos(), entry.machinePos(), entry.ownerId());
    }

    /** 清除指向此音响的所有音频位置重定向 */
    public static void clearMachineOverrideForSpeaker(BlockPos speakerPos) {
        if (speakerPos == null)
            return;
        RELAY_TURNTABLE.remove(speakerPos);
        SpeakerAudioRelay relay = RELAYS.remove(speakerPos);
        if (relay != null) {
            relay.cleanup();
            for (AudioEntry entry : OUTPUTS.values()) {
                entry.output().removeRelay(relay);
            }
        }
    }

    /** 注册音响 relay 并关联到对应的唱片机 handler */
    public static void registerRelay(BlockPos speakerPos, BlockPos turntablePos, SpeakerAudioRelay relay) {
        if (speakerPos == null || turntablePos == null || relay == null)
            return;
        relay.setSpeakerPos(AudioUtils.centerFor(speakerPos));
        SpeakerAudioRelay old = RELAYS.put(speakerPos, relay);
        RELAY_TURNTABLE.put(speakerPos, turntablePos);
        if (old != null) {
            old.cleanup();
            for (AudioEntry entry : OUTPUTS.values()) {
                entry.output().removeRelay(old);
            }
        }
        BlockPos key = keyFor(turntablePos);
        AudioEntry output = OUTPUTS.get(key);
        if (output != null) {
            output.output().addRelay(relay);
        }
    }

    /** 更新音响 relay 的声道和音量 */
    public static void updateRelayConfig(BlockPos speakerPos, int channelIndex, float volume, boolean autoMixJoc) {
        if (speakerPos == null)
            return;
        SpeakerAudioRelay relay = RELAYS.get(speakerPos);
        if (relay != null) {
            relay.setChannelIndex(channelIndex);
            relay.setAutoMixJoc(autoMixJoc);
            relay.setUserVolume(volume);
        }
    }

    /** 将音响配置（声道掩码/音量/JOC 静态化）应用到对应唱片机的 handler */
    public static void applySpeakerConfig(BlockPos turntablePos, int channelMask, float volume, boolean autoMixJoc) {
        if (turntablePos == null)
            return;
        BlockPos key = keyFor(turntablePos);
        AudioEntry entry = OUTPUTS.get(key);
        if (entry != null) {
            entry.output().setUserVolume(volume);
            if (entry.output() instanceof DolbyAudioHandler dolby) {
                dolby.setChannelMask(channelMask);
                dolby.setForceStaticJoc(autoMixJoc);
            }
        }
    }

    public static float[] getListenerPos() {
        return AudioUtils.copyPos3(listenerPos);
    }

    public static boolean isActive() {
        return !OUTPUTS.isEmpty();
    }

    public static void setOwnerVolume(UUID ownerId, float volume) {
        if (ownerId == null) {
            return;
        }
        float clamped = AudioUtils.clampGain(volume);
        OWNER_VOLUMES.put(ownerId, clamped);
        for (AudioEntry entry : OUTPUTS.values()) {
            if (ownerId.equals(entry.ownerId())) {
                entry.output().setUserVolume(clamped);
            }
        }
    }

    public static float audioLevel(BlockPos pos) {
        if (pos == null) {
            return 0.0f;
        }
        BlockPos key = keyFor(pos);
        AudioEntry entry = OUTPUTS.get(key);
        return entry != null ? entry.output().audioLevel() : 0.0F;
    }

    /** 获取指定位置立体声 OpenAL 管线的歌词播放位置（tick），未开始播放返回 -1 */
    public static long getStereoPositionTicks(BlockPos pos) {
        if (pos == null) {
            return -1L;
        }
        AudioEntry entry = OUTPUTS.get(keyFor(pos));
        if (entry == null || entry.kind() != OutputKind.STEREO) {
            return -1L;
        }
        return entry.output().getPositionTicks();
    }

    public static long getAnyPositionTicks(BlockPos pos) {
        if (pos == null) {
            return -1L;
        }
        BlockPos key = keyFor(pos);
        AudioEntry entry = OUTPUTS.get(key);
        return entry != null ? entry.output().getPositionTicks() : -1L;
    }

    public static long getAnyMediaMillis(BlockPos pos) {
        if (pos == null) {
            return -1L;
        }
        BlockPos key = keyFor(pos);
        long relayMillis = getRelayMediaMillis(key);
        AudioEntry entry = OUTPUTS.get(key);
        if (entry != null) {
            long positionMillis = entry.output().getPositionMillis();
            if (positionMillis >= 0L) {
                long mainMillis = adjustedAudibleMillis(entry.startOffsetTicks(), positionMillis,
                        entry.output().getOutputDelayMillis());
                return relayMillis >= 0L ? Math.max(mainMillis, relayMillis) : mainMillis;
            }
        }
        return relayMillis;
    }

    public static AudioTimeline getAudioTimeline(BlockPos pos) {
        if (pos == null) {
            return AudioTimeline.EMPTY;
        }
        BlockPos key = keyFor(pos);
        RelayTimeline relayTimeline = getRelayTimeline(key);
        long mainMillis = -1L;
        long mainFedMillis = -1L;
        String mainSessionId = "";
        AudioEntry entry = OUTPUTS.get(key);
        if (entry != null) {
            mainSessionId = entry.sessionId();
            long positionMillis = entry.output().getPositionMillis();
            if (positionMillis >= 0L) {
                mainMillis = adjustedAudibleMillis(entry.startOffsetTicks(), positionMillis,
                        entry.output().getOutputDelayMillis());
            }
            long fedMillis = entry.output().getFedPositionMillis();
            if (fedMillis >= 0L) {
                mainFedMillis = Math.max(0L, startOffsetMillis(entry.startOffsetTicks()) + fedMillis);
            }
        }
        long combinedMillis = mainMillis >= 0L ? mainMillis : relayTimeline.mediaMillis();
        return new AudioTimeline(mainMillis, mainFedMillis, relayTimeline.mediaMillis(), combinedMillis,
                relayTimeline.startedCount(), relayTimeline.registeredCount(), mainSessionId);
    }

    public static AudioTimeline getOwnerAudioTimeline(UUID ownerId) {
        if (ownerId == null) {
            return AudioTimeline.EMPTY;
        }
        AudioEntry entry = OUTPUTS.values().stream()
                .filter(candidate -> ownerId.equals(candidate.ownerId()))
                .max(Comparator.comparingLong(candidate -> candidate.createdAtMillis()))
                .orElse(null);
        if (entry != null) {
            long positionMillis = entry.output().getPositionMillis();
            long audibleMillis = positionMillis >= 0L
                    ? adjustedAudibleMillis(entry.startOffsetTicks(), positionMillis,
                            entry.output().getOutputDelayMillis())
                    : -1L;
            long fedMillis = entry.output().getFedPositionMillis();
            long mainFedMillis = fedMillis >= 0L
                    ? Math.max(0L, startOffsetMillis(entry.startOffsetTicks()) + fedMillis)
                    : -1L;
            return new AudioTimeline(audibleMillis, mainFedMillis, -1L, audibleMillis, 0, 0, entry.sessionId());
        }
        return AudioTimeline.EMPTY;
    }

    private static long getRelayMediaMillis(BlockPos turntableKey) {
        long best = -1L;
        for (var entry : RELAY_TURNTABLE.entrySet()) {
            if (!turntableKey.equals(keyFor(entry.getValue()))) {
                continue;
            }
            SpeakerAudioRelay relay = RELAYS.get(entry.getKey());
            if (relay == null) {
                continue;
            }
            long positionMillis = relay.getPositionMillis();
            if (positionMillis >= 0L) {
                long startOffsetTicks = startOffsetTicksFor(turntableKey);
                best = Math.max(best,
                        adjustedAudibleMillis(startOffsetTicks, positionMillis, relay.getOutputDelayMillis()));
            }
        }
        return best;
    }

    private static RelayTimeline getRelayTimeline(BlockPos turntableKey) {
        long best = -1L;
        int registered = 0;
        int started = 0;
        for (var entry : RELAY_TURNTABLE.entrySet()) {
            if (!turntableKey.equals(keyFor(entry.getValue()))) {
                continue;
            }
            registered++;
            SpeakerAudioRelay relay = RELAYS.get(entry.getKey());
            if (relay == null) {
                continue;
            }
            if (relay.isStarted()) {
                started++;
            }
            long positionMillis = relay.getPositionMillis();
            if (positionMillis >= 0L) {
                long startOffsetTicks = startOffsetTicksFor(turntableKey);
                best = Math.max(best,
                        adjustedAudibleMillis(startOffsetTicks, positionMillis, relay.getOutputDelayMillis()));
            }
        }
        return new RelayTimeline(best, started, registered);
    }

    private static long adjustedAudibleMillis(long startOffsetTicks, long relativeMillis, long outputDelayMillis) {
        long mediaMillis = Math.max(0L, startOffsetMillis(startOffsetTicks) + Math.max(0L, relativeMillis));
        return Math.max(0L, mediaMillis - Math.max(0L, outputDelayMillis));
    }

    private static long startOffsetMillis(long startOffsetTicks) {
        return Math.max(0L, startOffsetTicks) * 50L;
    }

    private record RelayTimeline(long mediaMillis, int startedCount, int registeredCount) {
    }

    public record AudioTimeline(long mainMillis, long mainFedMillis, long relayMillis, long combinedMillis,
            int relayStartedCount, int relayRegisteredCount, String sessionId) {
        private static final AudioTimeline EMPTY = new AudioTimeline(-1L, -1L, -1L, -1L, 0, 0, "");

        public long audibleMillis() {
            return mainMillis >= 0L ? mainMillis : relayMillis;
        }

        /** 显式语义 accessor，避免调用方把输出 session 与播放命令 session 混淆。 */
        public String audioSessionId() {
            return sessionId;
        }

        /** 已送入主输出队列的媒体位置。 */
        public long fedMillis() {
            return mainFedMillis;
        }
    }

    private static long startOffsetTicksFor(BlockPos key) {
        AudioEntry entry = OUTPUTS.get(key);
        return entry != null ? entry.startOffsetTicks() : 0L;
    }

    public static List<String> describeActiveSources() {
        float[] listener = listenerPos;
        if (!isActive()) {
            return List.of("No active Dolby/OpenAL audio");
        }

        List<String> lines = new ArrayList<>();
        long dolbyCount = OUTPUTS.values().stream().filter(entry -> entry.kind() == OutputKind.DOLBY).count();
        long stereoCount = OUTPUTS.size() - dolbyCount;
        lines.add(String.format("Active OpenAL turntables: dolby=%d stereo=%d", dolbyCount, stereoCount));

        OUTPUTS.values().stream()
                .sorted(Comparator.comparingLong(entry -> entry.createdAtMillis()))
                .forEach(entry -> {
                    lines.add(String.format("%s @ %s", entry.kind().displayName(),
                            AudioUtils.fmtPos(entry.machinePos())));
                    if (entry.output() instanceof DolbyAudioHandler dolby) {
                        for (String line : dolby.describeSources(entry.machinePos(), listener)) {
                            lines.add("  " + line);
                        }
                    } else if (entry.output() instanceof StereoOpenALHandler stereo) {
                        for (String line : stereo.describeState()) {
                            lines.add("  " + line);
                        }
                    }
                });
        return lines;
    }

    public static void cleanup() {
        List<Runnable> cleanupTasks = new ArrayList<>();
        for (AudioEntry entry : OUTPUTS.values()) {
            cleanupTasks.add(entry::cleanup);
        }
        for (SpeakerAudioRelay relay : RELAYS.values()) {
            cleanupTasks.add(relay::cleanup);
        }
        OUTPUTS.clear();
        RELAYS.clear();
        RELAY_TURNTABLE.clear();
        listenerPos = null;
        ClientMinecartAudioAnchors.clear();
        MINECART_KEYS.clear();
        runCleanupTasks(cleanupTasks);
    }

    private static void runCleanupTasks(List<Runnable> cleanupTasks) {
        if (cleanupTasks.isEmpty()) {
            return;
        }
        List<Thread> threads = new ArrayList<>(cleanupTasks.size());
        for (int i = 0; i < cleanupTasks.size(); i++) {
            Runnable task = cleanupTasks.get(i);
            Thread thread = NetMusicThreadFactory.daemonThread("DolbyRegistryCleanup-" + i, () -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    LOGGER.debug("OpenAL registry cleanup task failed: {}", t.toString());
                }
            });
            thread.start();
            threads.add(thread);
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLEANUP_TIMEOUT_MILLIS);
        int unfinished = 0;
        for (Thread thread : threads) {
            long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMillis <= 0L) {
                unfinished++;
                continue;
            }
            try {
                thread.join(remainingMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                unfinished++;
                break;
            }
            if (thread.isAlive()) {
                unfinished++;
            }
        }
        if (unfinished > 0) {
            LOGGER.debug("OpenAL registry cleanup timed out with {} task(s) still running", unfinished);
        }
    }

    private static void hardStopAndCleanup(AudioEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            entry.output().hardStopOutput();
        } catch (Throwable t) {
            LOGGER.debug("OpenAL hard-stop failed before cleanup: {}", t.toString());
        }
        entry.output().cleanup();
    }

    private static void cleanupAfterPut(BoundedConcurrentStore.PutResult<AudioEntry> result) {
        if (result.replaced() != null) {
            hardStopAndCleanup(result.replaced());
        }
        for (AudioEntry evicted : result.evicted()) {
            hardStopAndCleanup(evicted);
        }
    }

    private static BlockPos keyFor(BlockPos pos) {
        if (pos != null) {
            return AudioUtils.copyPos(pos);
        }
        int id = ANONYMOUS_COUNTER.getAndIncrement();
        return new BlockPos(Integer.MIN_VALUE, 0, id);
    }

    private static BlockPos keyFor(BlockPos pos, UUID ownerId) {
        if (pos != null) {
            return AudioUtils.copyPos(pos);
        }
        if (ownerId != null) {
            return new BlockPos(Integer.MIN_VALUE + 1, ownerId.hashCode(), (int) ownerId.getLeastSignificantBits());
        }
        return keyFor((BlockPos) null);
    }

    private static BlockPos keyFor(BlockPos pos, UUID ownerId, String sessionId) {
        UUID minecartUuid = ClientMinecartAudioAnchors.entityUuid(sessionId);
        if (minecartUuid != null) {
            return MINECART_KEYS.computeIfAbsent(minecartUuid,
                    ignored -> new BlockPos(Integer.MIN_VALUE + 2, 0, ANONYMOUS_COUNTER.getAndIncrement()));
        }
        return keyFor(pos, ownerId);
    }

    private static boolean isRealWorldKey(BlockPos pos) {
        return pos != null && pos.getX() > Integer.MIN_VALUE + 2;
    }

    private static float[] centerFor(BlockPos pos) {
        return AudioUtils.centerFor(pos);
    }

    private record AudioEntry(BlockPos pos, float[] machinePos, AudioOutputHandle output, OutputKind kind,
            long createdAtMillis, long startOffsetTicks, String sessionId, UUID ownerId) {
        private void cleanup() {
            output.cleanup();
        }
    }

    private enum OutputKind {
        DOLBY("Dolby"),
        STEREO("Stereo");

        private final String displayName;

        OutputKind(String displayName) {
            this.displayName = displayName;
        }

        private String displayName() {
            return displayName;
        }
    }

    private static String normalizeSessionId(String sessionId) {
        return sessionId != null ? sessionId : "";
    }
}
