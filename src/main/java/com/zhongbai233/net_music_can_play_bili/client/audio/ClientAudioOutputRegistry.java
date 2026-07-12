package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.AudioUtils;
import com.zhongbai233.net_music_can_play_bili.bili.DolbyAudioHandler;
import com.zhongbai233.net_music_can_play_bili.bili.SpeakerAudioRelay;
import com.zhongbai233.net_music_can_play_bili.bili.StereoOpenALHandler;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.util.concurrent.NetMusicThreadFactory;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

public class ClientAudioOutputRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ACTIVE_TURNTABLES = 16;
    private static final long CLEANUP_TIMEOUT_MILLIS = 2_000L;
    private static final long AUDIO_SYNC_AHEAD_TOLERANCE_TICKS = Long.getLong(
            "bili.audio.openal.ahead_tolerance_ticks", 0L);
    private static final AtomicInteger ANONYMOUS_COUNTER = new AtomicInteger();
    private static final ConcurrentMap<BlockPos, DolbyEntry> DOLBY_HANDLERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<BlockPos, StereoEntry> STEREO_HANDLERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, BlockPos> MINECART_KEYS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Float> SOURCE_VOLUMES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Float> OWNER_VOLUMES = new ConcurrentHashMap<>();
    private static final int MAX_VOLUME_ENTRIES = 5_000;

    private static final Gson GSON = new Gson();
    private static final Type VOLUME_MAP_TYPE = new TypeToken<Map<String, Float>>() {
    }.getType();
    /** 音量持久化 debounce 专用，单线程复用，避免每次新建线程 */
    private static final ScheduledExecutorService VOLUME_SAVE_EXECUTOR = Executors
            .newSingleThreadScheduledExecutor(NetMusicThreadFactory.daemon("VolumeSaveDebounce"));
    private static volatile long lastSaveMillis;
    private static volatile boolean pendingSave;

    static {
        loadVolumes();
    }

    private static final ConcurrentMap<BlockPos, BlockPos> MACHINE_OVERRIDES = new ConcurrentHashMap<>();
    /** 音响 relay 注册表：speakerPos → relay */
    private static final ConcurrentMap<BlockPos, SpeakerAudioRelay> RELAYS = new ConcurrentHashMap<>();
    /** relay → turntable 反向映射 */
    private static final ConcurrentMap<BlockPos, BlockPos> RELAY_TURNTABLE = new ConcurrentHashMap<>();

    private static volatile float[] fallbackMachinePos;
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
        hardStopIfSessionChanged(key, normalizedSessionId);
        DolbyEntry entry = new DolbyEntry(key, centerFor(pos), handler, System.currentTimeMillis(),
                startOffsetTicks(startOffsetSeconds), normalizedSessionId, ownerId);
        handler.setUserVolume(ownerId != null ? OWNER_VOLUMES.getOrDefault(ownerId, 1.0F) : volumeFor(key));
        closeStereoAt(key);
        DolbyEntry old = DOLBY_HANDLERS.put(key, entry);
        if (old != null) {
            hardStopAndCleanup(old);
        }
        // 自动连接已注册的音响 relay
        connectPendingRelays(key, handler);
        enforceActiveLimit();
    }

    public static void unregister(DolbyAudioHandler handler) {
        if (handler == null) {
            return;
        }
        DOLBY_HANDLERS.entrySet().removeIf(entry -> {
            if (entry.getValue().handler() == handler) {
                return true;
            }
            return false;
        });
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
        hardStopIfSessionChanged(key, normalizedSessionId);
        StereoEntry entry = new StereoEntry(key, centerFor(pos), handler, System.currentTimeMillis(),
                startOffsetTicks(startOffsetSeconds), normalizedSessionId, ownerId);
        handler.setUserVolume(ownerId != null ? OWNER_VOLUMES.getOrDefault(ownerId, 1.0F) : volumeFor(key));
        closeDolbyAt(key);
        StereoEntry old = STEREO_HANDLERS.put(key, entry);
        if (old != null) {
            hardStopAndCleanup(old);
        }
        // 自动连接已注册的音响 relay
        connectPendingRelays(key, handler);
        enforceActiveLimit();
    }

    /** handler 创建时自动连接已注册的音响 relay */
    private static void connectPendingRelays(BlockPos handlerKey, DolbyAudioHandler handler) {
        for (var entry : RELAYS.entrySet()) {
            BlockPos speakerPos = entry.getKey();
            BlockPos linkedTurntable = RELAY_TURNTABLE.get(speakerPos);
            if (linkedTurntable != null && linkedTurntable.equals(handlerKey)) {
                handler.addRelay(entry.getValue());
            }
        }
    }

    private static void connectPendingRelays(BlockPos handlerKey, StereoOpenALHandler handler) {
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
        STEREO_HANDLERS.entrySet().removeIf(entry -> {
            if (entry.getValue().handler() == handler) {
                return true;
            }
            return false;
        });
    }

    public static void updatePositions(float[] listenerPos) {
        if (listenerPos == null) {
            return;
        }

        float[] currentListenerPos = AudioUtils.copyPos3(listenerPos);
        ClientAudioOutputRegistry.listenerPos = currentListenerPos;
        for (DolbyEntry entry : DOLBY_HANDLERS.values()) {
            if (isRealWorldKey(entry.pos())
                    && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState
                            .linkedTurntableOutOfRange(entry.pos())) {
                if (DOLBY_HANDLERS.remove(entry.pos(), entry)) {
                    hardStopAndCleanup(entry);
                }
                continue;
            }
            if (isRealWorldKey(entry.pos())
                    && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState
                            .suppressesTurntable(entry.pos())) {
                entry.handler().tick(entry.machinePos(), currentListenerPos, Long.MIN_VALUE, false);
                continue;
            }
            float[] pos = resolveMachinePos(entry);
            if (isRealWorldKey(entry.pos())
                    && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState
                            .handlesTurntable(entry.pos())) {
                pos = currentListenerPos;
            }
            entry.handler().tick(pos, currentListenerPos, targetRelativeTicks(entry), followsLocalPlayerFront(entry));
        }
        for (StereoEntry entry : STEREO_HANDLERS.values()) {
            if (isRealWorldKey(entry.pos())
                    && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState
                            .linkedTurntableOutOfRange(entry.pos())) {
                if (STEREO_HANDLERS.remove(entry.pos(), entry)) {
                    hardStopAndCleanup(entry);
                }
                continue;
            }
            if (isRealWorldKey(entry.pos())
                    && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState
                            .suppressesTurntable(entry.pos())) {
                entry.handler().tick(entry.machinePos(), currentListenerPos, Long.MIN_VALUE, false);
                continue;
            }
            float[] pos = resolveMachinePos(entry);
            if (isRealWorldKey(entry.pos())
                    && com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState
                            .handlesTurntable(entry.pos())) {
                pos = currentListenerPos;
            }
            entry.handler().tick(pos, currentListenerPos, targetRelativeTicks(entry), followsLocalPlayerFront(entry));
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
        if (entry != null && ClientMinecartAudioAnchors.isMoving(entry.sessionId())) {
            return Long.MAX_VALUE;
        }
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.level == null || entry == null || entry.pos() == null
                || entry.pos().getX() == Integer.MIN_VALUE) {
            return Long.MAX_VALUE;
        }
        if (!(mc.level.getBlockEntity(entry.pos()) instanceof ModernTurntableBlockEntity turntable)
                || !turntable.isPlaying()) {
            return Long.MAX_VALUE;
        }
        long targetTicks = turntable.getPlaybackElapsedMillis(mc.level.getGameTime()) / 50L;
        return Math.max(0L, targetTicks - entry.startOffsetTicks() + AUDIO_SYNC_AHEAD_TOLERANCE_TICKS);
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

    /** 将指定唱片机的音频输出重定向到音响位置 */
    public static void setMachineOverride(BlockPos turntablePos, BlockPos speakerPos) {
        // 保留 API 兼容旧调用；多音响模式下不再把主 handler 移到某个音响，避免后注册音响独占主输出。
        if (turntablePos == null || speakerPos == null)
            return;
    }

    /** 清除指定唱片机的音频位置重定向 */
    public static void clearMachineOverride(BlockPos turntablePos) {
        if (turntablePos == null)
            return;
        MACHINE_OVERRIDES.remove(turntablePos);
    }

    /** 清除指向此音响的所有音频位置重定向 */
    public static void clearMachineOverrideForSpeaker(BlockPos speakerPos) {
        if (speakerPos == null)
            return;
        MACHINE_OVERRIDES.entrySet().removeIf(e -> e.getValue().equals(speakerPos));
        RELAY_TURNTABLE.remove(speakerPos);
        SpeakerAudioRelay relay = RELAYS.remove(speakerPos);
        if (relay != null) {
            relay.cleanup();
            for (DolbyEntry entry : DOLBY_HANDLERS.values()) {
                entry.handler().removeRelay(relay);
            }
            for (StereoEntry entry : STEREO_HANDLERS.values()) {
                entry.handler().removeRelay(relay);
            }
        }
    }

    /** 注册音响 relay 并关联到对应的唱片机 handler */
    public static void registerRelay(BlockPos speakerPos, BlockPos turntablePos, SpeakerAudioRelay relay) {
        if (speakerPos == null || turntablePos == null || relay == null)
            return;
        relay.setSpeakerPos(AudioUtils.centerFor(speakerPos, fallbackMachinePos));
        SpeakerAudioRelay old = RELAYS.put(speakerPos, relay);
        RELAY_TURNTABLE.put(speakerPos, turntablePos);
        if (old != null) {
            old.cleanup();
            for (DolbyEntry entry : DOLBY_HANDLERS.values()) {
                entry.handler().removeRelay(old);
            }
            for (StereoEntry entry : STEREO_HANDLERS.values()) {
                entry.handler().removeRelay(old);
            }
        }
        BlockPos key = keyFor(turntablePos);
        DolbyEntry dolby = DOLBY_HANDLERS.get(key);
        if (dolby != null) {
            dolby.handler().addRelay(relay);
        }
        StereoEntry stereo = STEREO_HANDLERS.get(key);
        if (stereo != null) {
            stereo.handler().addRelay(relay);
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
        DolbyEntry dolby = DOLBY_HANDLERS.get(key);
        if (dolby != null) {
            dolby.handler().setChannelMask(channelMask);
            dolby.handler().setUserVolume(volume);
            dolby.handler().setForceStaticJoc(autoMixJoc);
        }
        StereoEntry stereo = STEREO_HANDLERS.get(key);
        if (stereo != null) {
            stereo.handler().setUserVolume(volume);
        }
    }

    public static void updatePositions(float[] machinePos, float[] listenerPos) {
        updatePositions(listenerPos);
    }

    public static void setMachinePos(double x, double y, double z) {
        fallbackMachinePos = new float[] { (float) x, (float) y, (float) z };
    }

    public static float[] getMachinePos() {
        DolbyEntry dolby = firstValue(DOLBY_HANDLERS);
        if (dolby != null) {
            return AudioUtils.copyPos3(dolby.machinePos());
        }
        StereoEntry stereo = firstValue(STEREO_HANDLERS);
        if (stereo != null) {
            return AudioUtils.copyPos3(stereo.machinePos());
        }
        return AudioUtils.copyPos3(fallbackMachinePos);
    }

    public static float[] getListenerPos() {
        return AudioUtils.copyPos3(listenerPos);
    }

    public static boolean isActive() {
        return !DOLBY_HANDLERS.isEmpty() || !STEREO_HANDLERS.isEmpty();
    }

    public static void setUserVolume(BlockPos pos, float volume) {
        if (pos == null) {
            return;
        }
        BlockPos key = keyFor(pos);
        float clamped = AudioUtils.clampGain(volume);
        String volumeKey = volumeKeyFor(key);
        if (volumeKey == null) {
            return;
        }
        SOURCE_VOLUMES.put(volumeKey, clamped);
        evictVolumesIfNeeded();
        DolbyEntry dolby = DOLBY_HANDLERS.get(key);
        if (dolby != null) {
            dolby.handler().setUserVolume(clamped);
        }
        StereoEntry stereo = STEREO_HANDLERS.get(key);
        if (stereo != null) {
            stereo.handler().setUserVolume(clamped);
        }
        saveVolumesDebounced();
    }

    public static void setOwnerVolume(UUID ownerId, float volume) {
        if (ownerId == null) {
            return;
        }
        float clamped = AudioUtils.clampGain(volume);
        OWNER_VOLUMES.put(ownerId, clamped);
        for (DolbyEntry dolby : DOLBY_HANDLERS.values()) {
            if (ownerId.equals(dolby.ownerId())) {
                dolby.handler().setUserVolume(clamped);
            }
        }
        for (StereoEntry stereo : STEREO_HANDLERS.values()) {
            if (ownerId.equals(stereo.ownerId())) {
                stereo.handler().setUserVolume(clamped);
            }
        }
    }

    public static float userVolume(BlockPos pos) {
        if (pos == null) {
            return 1.0f;
        }
        return volumeFor(keyFor(pos));
    }

    public static float audioLevel(BlockPos pos) {
        if (pos == null) {
            return 0.0f;
        }
        BlockPos key = keyFor(pos);
        StereoEntry stereo = STEREO_HANDLERS.get(key);
        if (stereo != null) {
            return stereo.handler().audioLevel();
        }
        DolbyEntry dolby = DOLBY_HANDLERS.get(key);
        if (dolby != null) {
            return dolby.handler().audioLevel();
        }
        return 0.0f;
    }

    /** 获取指定位置立体声 OpenAL 管线的歌词播放位置（tick），未开始播放返回 -1 */
    public static long getStereoPositionTicks(BlockPos pos) {
        if (pos == null) {
            return -1L;
        }
        StereoEntry entry = STEREO_HANDLERS.get(keyFor(pos));
        if (entry == null) {
            return -1L;
        }
        return entry.handler().getPositionTicks();
    }

    public static long getAnyPositionTicks(BlockPos pos) {
        if (pos == null) {
            return -1L;
        }
        BlockPos key = keyFor(pos);
        StereoEntry stereo = STEREO_HANDLERS.get(key);
        if (stereo != null) {
            long ticks = stereo.handler().getPositionTicks();
            if (ticks >= 0L)
                return ticks;
        }
        DolbyEntry dolby = DOLBY_HANDLERS.get(key);
        if (dolby != null) {
            return dolby.handler().getPositionTicks();
        }
        return -1L;
    }

    public static long getAnyMediaMillis(BlockPos pos) {
        if (pos == null) {
            return -1L;
        }
        BlockPos key = keyFor(pos);
        long relayMillis = getRelayMediaMillis(key);
        StereoEntry stereo = STEREO_HANDLERS.get(key);
        if (stereo != null) {
            long positionMillis = stereo.handler().getPositionMillis();
            if (positionMillis >= 0L) {
                long mainMillis = adjustedAudibleMillis(stereo.startOffsetTicks(), positionMillis,
                        stereo.handler().getOutputDelayMillis());
                return relayMillis >= 0L ? Math.max(mainMillis, relayMillis) : mainMillis;
            }
        }
        DolbyEntry dolby = DOLBY_HANDLERS.get(key);
        if (dolby != null) {
            long positionMillis = dolby.handler().getPositionMillis();
            if (positionMillis >= 0L) {
                long mainMillis = adjustedAudibleMillis(dolby.startOffsetTicks(), positionMillis,
                        dolby.handler().getOutputDelayMillis());
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
        StereoEntry stereo = STEREO_HANDLERS.get(key);
        if (stereo != null) {
            mainSessionId = stereo.sessionId();
            long positionMillis = stereo.handler().getPositionMillis();
            if (positionMillis >= 0L) {
                mainMillis = adjustedAudibleMillis(stereo.startOffsetTicks(), positionMillis,
                        stereo.handler().getOutputDelayMillis());
            }
            long fedMillis = stereo.handler().getFedPositionMillis();
            if (fedMillis >= 0L) {
                mainFedMillis = Math.max(0L, startOffsetMillis(stereo.startOffsetTicks()) + fedMillis);
            }
        } else {
            DolbyEntry dolby = DOLBY_HANDLERS.get(key);
            if (dolby != null) {
                mainSessionId = dolby.sessionId();
                long positionMillis = dolby.handler().getPositionMillis();
                if (positionMillis >= 0L) {
                    mainMillis = adjustedAudibleMillis(dolby.startOffsetTicks(), positionMillis,
                            dolby.handler().getOutputDelayMillis());
                }
                long fedMillis = dolby.handler().getFedPositionMillis();
                if (fedMillis >= 0L) {
                    mainFedMillis = Math.max(0L, startOffsetMillis(dolby.startOffsetTicks()) + fedMillis);
                }
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
        StereoEntry stereo = STEREO_HANDLERS.values().stream()
                .filter(entry -> ownerId.equals(entry.ownerId()))
                .max(Comparator.comparingLong(entry -> entry.createdAtMillis()))
                .orElse(null);
        if (stereo != null) {
            long positionMillis = stereo.handler().getPositionMillis();
            long audibleMillis = positionMillis >= 0L
                    ? adjustedAudibleMillis(stereo.startOffsetTicks(), positionMillis,
                            stereo.handler().getOutputDelayMillis())
                    : -1L;
            long fedMillis = stereo.handler().getFedPositionMillis();
            long mainFedMillis = fedMillis >= 0L
                    ? Math.max(0L, startOffsetMillis(stereo.startOffsetTicks()) + fedMillis)
                    : -1L;
            return new AudioTimeline(audibleMillis, mainFedMillis, -1L, audibleMillis, 0, 0, stereo.sessionId());
        }
        DolbyEntry dolby = DOLBY_HANDLERS.values().stream()
                .filter(entry -> ownerId.equals(entry.ownerId()))
                .max(Comparator.comparingLong(entry -> entry.createdAtMillis()))
                .orElse(null);
        if (dolby != null) {
            long positionMillis = dolby.handler().getPositionMillis();
            long audibleMillis = positionMillis >= 0L
                    ? adjustedAudibleMillis(dolby.startOffsetTicks(), positionMillis,
                            dolby.handler().getOutputDelayMillis())
                    : -1L;
            long fedMillis = dolby.handler().getFedPositionMillis();
            long mainFedMillis = fedMillis >= 0L
                    ? Math.max(0L, startOffsetMillis(dolby.startOffsetTicks()) + fedMillis)
                    : -1L;
            return new AudioTimeline(audibleMillis, mainFedMillis, -1L, audibleMillis, 0, 0, dolby.sessionId());
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
    }

    private static long startOffsetTicksFor(BlockPos key) {
        StereoEntry stereo = STEREO_HANDLERS.get(key);
        if (stereo != null) {
            return stereo.startOffsetTicks();
        }
        DolbyEntry dolby = DOLBY_HANDLERS.get(key);
        return dolby != null ? dolby.startOffsetTicks() : 0L;
    }

    public static List<String> describeActiveSources() {
        float[] listener = listenerPos;
        if (!isActive()) {
            return List.of("No active Dolby/OpenAL audio");
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.format("Active OpenAL turntables: dolby=%d stereo=%d",
                DOLBY_HANDLERS.size(), STEREO_HANDLERS.size()));

        DOLBY_HANDLERS.values().stream()
                .sorted(Comparator.comparingLong(entry -> entry.createdAtMillis()))
                .forEach(entry -> {
                    lines.add(String.format("Dolby @ %s", AudioUtils.fmtPos(entry.machinePos())));
                    for (String line : entry.handler().describeSources(entry.machinePos(), listener)) {
                        lines.add("  " + line);
                    }
                });
        STEREO_HANDLERS.values().stream()
                .sorted(Comparator.comparingLong(entry -> entry.createdAtMillis()))
                .forEach(entry -> {
                    lines.add(String.format("Stereo @ %s", AudioUtils.fmtPos(entry.machinePos())));
                    for (String line : entry.handler().describeState()) {
                        lines.add("  " + line);
                    }
                });
        return lines;
    }

    public static void cleanup() {
        List<Runnable> cleanupTasks = new ArrayList<>();
        for (DolbyEntry entry : DOLBY_HANDLERS.values()) {
            cleanupTasks.add(entry::cleanup);
        }
        for (StereoEntry entry : STEREO_HANDLERS.values()) {
            cleanupTasks.add(entry::cleanup);
        }
        for (SpeakerAudioRelay relay : RELAYS.values()) {
            cleanupTasks.add(relay::cleanup);
        }
        DOLBY_HANDLERS.clear();
        STEREO_HANDLERS.clear();
        RELAYS.clear();
        RELAY_TURNTABLE.clear();
        MACHINE_OVERRIDES.clear();
        fallbackMachinePos = null;
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

    private static void closeDolbyAt(BlockPos key) {
        DolbyEntry old = DOLBY_HANDLERS.remove(key);
        if (old != null) {
            hardStopAndCleanup(old);
        }
    }

    private static void closeStereoAt(BlockPos key) {
        StereoEntry old = STEREO_HANDLERS.remove(key);
        if (old != null) {
            hardStopAndCleanup(old);
        }
    }

    private static void hardStopIfSessionChanged(BlockPos key, String newSessionId) {
        if (key == null || key.getX() == Integer.MIN_VALUE || newSessionId.isBlank()) {
            return;
        }
        DolbyEntry dolby = DOLBY_HANDLERS.get(key);
        if (dolby != null && !dolby.sessionId().isBlank() && !newSessionId.equals(dolby.sessionId())) {
            if (DOLBY_HANDLERS.remove(key, dolby)) {
                LOGGER.debug("音频会话切换，硬停旧 Dolby 输出: pos={} oldSession={} newSession={}", key, dolby.sessionId(),
                        newSessionId);
                hardStopAndCleanup(dolby);
            }
        }
        StereoEntry stereo = STEREO_HANDLERS.get(key);
        if (stereo != null && !stereo.sessionId().isBlank() && !newSessionId.equals(stereo.sessionId())) {
            if (STEREO_HANDLERS.remove(key, stereo)) {
                LOGGER.debug("音频会话切换，硬停旧 Stereo 输出: pos={} oldSession={} newSession={}", key,
                        stereo.sessionId(), newSessionId);
                hardStopAndCleanup(stereo);
            }
        }
    }

    private static void hardStopAndCleanup(AudioEntry entry) {
        if (entry == null) {
            return;
        }
        try {
            entry.hardStopOutput();
        } catch (Throwable t) {
            LOGGER.debug("OpenAL hard-stop failed before cleanup: {}", t.toString());
        }
        entry.cleanup();
    }

    private static void enforceActiveLimit() {
        while (DOLBY_HANDLERS.size() + STEREO_HANDLERS.size() > MAX_ACTIVE_TURNTABLES) {
            AudioEntry oldest = oldestEntry();
            if (oldest == null) {
                return;
            }
            if (oldest instanceof DolbyEntry dolby) {
                if (DOLBY_HANDLERS.remove(dolby.pos(), dolby)) {
                    dolby.cleanup();
                }
            } else if (oldest instanceof StereoEntry stereo) {
                if (STEREO_HANDLERS.remove(stereo.pos(), stereo)) {
                    stereo.cleanup();
                }
            }
        }
    }

    private static AudioEntry oldestEntry() {
        AudioEntry oldest = null;
        for (AudioEntry entry : DOLBY_HANDLERS.values()) {
            oldest = older(oldest, entry);
        }
        for (AudioEntry entry : STEREO_HANDLERS.values()) {
            oldest = older(oldest, entry);
        }
        return oldest;
    }

    private static AudioEntry older(AudioEntry current, AudioEntry candidate) {
        if (current == null || candidate.createdAtMillis() < current.createdAtMillis()) {
            return candidate;
        }
        return current;
    }

    private static <T> T firstValue(Map<?, T> map) {
        for (T value : map.values()) {
            return value;
        }
        return null;
    }

    private static float volumeFor(BlockPos key) {
        String volumeKey = volumeKeyFor(key);
        if (volumeKey == null) {
            return 1.0f;
        }
        return SOURCE_VOLUMES.getOrDefault(volumeKey, 1.0f);
    }

    // ---- 音量持久化 ----

    private static Path volumeFilePath() {
        return Paths.get("config", "net_music_can_play_bili", "turntable_volumes.json");
    }

    private static void loadVolumes() {
        Path file = volumeFilePath();
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            Map<String, Float> saved = GSON.fromJson(reader, VOLUME_MAP_TYPE);
            if (saved != null) {
                for (var entry : saved.entrySet()) {
                    String key = entry.getKey();
                    float value = AudioUtils.clampGain(entry.getValue());
                    if (isScopedVolumeKey(key)) {
                        SOURCE_VOLUMES.put(key, value);
                    }
                }
                LOGGER.debug("加载已保存的唱片机音量: {} 个位置", SOURCE_VOLUMES.size());
            }
        } catch (IOException e) {
            LOGGER.warn("读取唱片机音量文件失败: {}", e.toString());
        }
    }

    private static void saveVolumesDebounced() {
        long now = System.currentTimeMillis();
        lastSaveMillis = now;
        if (pendingSave) {
            return;
        }
        pendingSave = true;
        VOLUME_SAVE_EXECUTOR.schedule(() -> {
            // 如果 1.5s 内又有新写入，跳过本次（下次写入会触发新的 schedule）
            if (System.currentTimeMillis() - lastSaveMillis < 1400) {
                pendingSave = false;
                return;
            }
            saveVolumesNow();
            pendingSave = false;
        }, 1500, TimeUnit.MILLISECONDS);
    }

    private static void saveVolumesNow() {
        if (SOURCE_VOLUMES.isEmpty()) {
            return;
        }
        Map<String, Float> data = new HashMap<>();
        for (var entry : SOURCE_VOLUMES.entrySet()) {
            data.put(entry.getKey(), entry.getValue());
        }
        if (data.isEmpty()) {
            return;
        }
        Path file = volumeFilePath();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("保存唱片机音量文件失败: {}", e.toString());
        }
    }

    private static boolean isScopedVolumeKey(String key) {
        return key != null && key.contains("|") && key.indexOf('|') != key.lastIndexOf('|');
    }

    private static String volumeKeyFor(BlockPos pos) {
        if (pos == null || pos.getX() == Integer.MIN_VALUE) {
            return null;
        }
        return currentWorldScope() + "|" + currentDimensionKey() + "|"
                + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String currentDimensionKey() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.level != null) {
            try {
                return mc.level.dimension().toString();
            } catch (Exception ignored) {
            }
        }
        return "unknown_dimension";
    }

    private static String currentWorldScope() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) {
            return "unknown_world";
        }

        if (mc.getCurrentServer() != null) {
            String ip = mc.getCurrentServer().ip;
            return "server:" + sanitizeScope(!isBlank(ip) ? ip : mc.getCurrentServer().name);
        }

        if (mc.getSingleplayerServer() != null) {
            String levelName = mc.getSingleplayerServer().getWorldData().getLevelName();
            return "singleplayer:" + sanitizeScope(levelName);
        }

        return "unknown_world";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sanitizeScope(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replace('\\', '/').replace('|', '_').trim();
    }

    private static void evictVolumesIfNeeded() {
        if (SOURCE_VOLUMES.size() <= MAX_VOLUME_ENTRIES) {
            return;
        }
        // 驱逐最旧的一半条目
        int target = MAX_VOLUME_ENTRIES / 2;
        var iter = SOURCE_VOLUMES.entrySet().iterator();
        int removed = 0;
        while (iter.hasNext() && SOURCE_VOLUMES.size() > target) {
            iter.next();
            iter.remove();
            removed++;
        }
        if (removed > 0) {
            LOGGER.debug("音量表驱逐 {} 条旧记录", removed);
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
            return MINECART_KEYS.computeIfAbsent(minecartUuid, ignored ->
                    new BlockPos(Integer.MIN_VALUE + 2, 0, ANONYMOUS_COUNTER.getAndIncrement()));
        }
        return keyFor(pos, ownerId);
    }

    private static boolean isRealWorldKey(BlockPos pos) {
        return pos != null && pos.getX() > Integer.MIN_VALUE + 2;
    }

    private static float[] centerFor(BlockPos pos) {
        return AudioUtils.centerFor(pos, fallbackMachinePos);
    }

    private interface AudioEntry {
        BlockPos pos();

        float[] machinePos();

        long createdAtMillis();

        long startOffsetTicks();

        String sessionId();

        UUID ownerId();

        void hardStopOutput();

        void cleanup();
    }

    private record DolbyEntry(BlockPos pos, float[] machinePos, DolbyAudioHandler handler, long createdAtMillis,
            long startOffsetTicks, String sessionId, UUID ownerId)
            implements AudioEntry {
        @Override
        public void hardStopOutput() {
            handler.hardStopOutput();
        }

        @Override
        public void cleanup() {
            handler.cleanup();
        }
    }

    private record StereoEntry(BlockPos pos, float[] machinePos, StereoOpenALHandler handler, long createdAtMillis,
            long startOffsetTicks, String sessionId, UUID ownerId)
            implements AudioEntry {
        @Override
        public void hardStopOutput() {
            handler.hardStopOutput();
        }

        @Override
        public void cleanup() {
            handler.cleanup();
        }
    }

    private static String normalizeSessionId(String sessionId) {
        return sessionId != null ? sessionId : "";
    }
}
