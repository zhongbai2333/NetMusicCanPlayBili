package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
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
import java.util.concurrent.atomic.AtomicInteger;

public class DolbyAudioRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ACTIVE_TURNTABLES = 16;
    private static final AtomicInteger ANONYMOUS_COUNTER = new AtomicInteger();
    private static final ConcurrentMap<BlockPos, DolbyEntry> DOLBY_HANDLERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<BlockPos, StereoEntry> STEREO_HANDLERS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Float> SOURCE_VOLUMES = new ConcurrentHashMap<>();
    private static final int MAX_VOLUME_ENTRIES = 5_000;

    private static final Gson GSON = new Gson();
    private static final Type VOLUME_MAP_TYPE = new TypeToken<Map<String, Float>>() {
    }.getType();
    private static volatile long lastSaveMillis;
    private static volatile boolean pendingSave;

    static {
        loadVolumes();
    }

    private static volatile float[] fallbackMachinePos;
    private static volatile float[] listenerPos;

    public static void register(DolbyAudioHandler handler) {
        register(handler, null);
    }

    public static void register(DolbyAudioHandler handler, BlockPos pos) {
        if (handler == null) {
            return;
        }
        BlockPos key = keyFor(pos);
        DolbyEntry entry = new DolbyEntry(key, centerFor(pos), handler, System.currentTimeMillis());
        handler.setUserVolume(volumeFor(key));
        closeStereoAt(key);
        DolbyEntry old = DOLBY_HANDLERS.put(key, entry);
        if (old != null) {
            old.cleanup();
        }
        enforceActiveLimit();
    }

    public static void unregister(DolbyAudioHandler handler) {
        if (handler == null) {
            return;
        }
        DOLBY_HANDLERS.entrySet().removeIf(entry -> entry.getValue().handler() == handler);
    }

    public static void registerStereo(StereoOpenALHandler handler) {
        registerStereo(handler, null);
    }

    public static void registerStereo(StereoOpenALHandler handler, BlockPos pos) {
        if (handler == null) {
            return;
        }
        BlockPos key = keyFor(pos);
        StereoEntry entry = new StereoEntry(key, centerFor(pos), handler, System.currentTimeMillis());
        handler.setUserVolume(volumeFor(key));
        closeDolbyAt(key);
        StereoEntry old = STEREO_HANDLERS.put(key, entry);
        if (old != null) {
            old.cleanup();
        }
        enforceActiveLimit();
    }

    public static void unregisterStereo(StereoOpenALHandler handler) {
        if (handler == null) {
            return;
        }
        STEREO_HANDLERS.entrySet().removeIf(entry -> entry.getValue().handler() == handler);
    }

    public static void updatePositions(float[] listenerPos) {
        if (listenerPos == null) {
            return;
        }

        float[] currentListenerPos = AudioUtils.copyPos3(listenerPos);
        DolbyAudioRegistry.listenerPos = currentListenerPos;
        for (DolbyEntry entry : DOLBY_HANDLERS.values()) {
            entry.handler().tick(entry.machinePos(), currentListenerPos);
        }
        for (StereoEntry entry : STEREO_HANDLERS.values()) {
            entry.handler().tick(entry.machinePos(), currentListenerPos);
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

    public static List<String> describeActiveSources() {
        float[] listener = listenerPos;
        if (!isActive()) {
            return List.of("No active Dolby/OpenAL audio");
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.format("Active OpenAL turntables: dolby=%d stereo=%d",
                DOLBY_HANDLERS.size(), STEREO_HANDLERS.size()));

        DOLBY_HANDLERS.values().stream()
                .sorted(Comparator.comparingLong(DolbyEntry::createdAtMillis))
                .forEach(entry -> {
                    lines.add(String.format("Dolby @ %s", AudioUtils.fmtPos(entry.machinePos())));
                    for (String line : entry.handler().describeSources(entry.machinePos(), listener)) {
                        lines.add("  " + line);
                    }
                });
        STEREO_HANDLERS.values().stream()
                .sorted(Comparator.comparingLong(StereoEntry::createdAtMillis))
                .forEach(entry -> {
                    lines.add(String.format("Stereo @ %s", AudioUtils.fmtPos(entry.machinePos())));
                    for (String line : entry.handler().describeState()) {
                        lines.add("  " + line);
                    }
                });
        return lines;
    }

    public static void cleanup() {
        for (DolbyEntry entry : DOLBY_HANDLERS.values()) {
            entry.cleanup();
        }
        for (StereoEntry entry : STEREO_HANDLERS.values()) {
            entry.cleanup();
        }
        DOLBY_HANDLERS.clear();
        STEREO_HANDLERS.clear();
    }

    private static void closeDolbyAt(BlockPos key) {
        DolbyEntry old = DOLBY_HANDLERS.remove(key);
        if (old != null) {
            old.cleanup();
        }
    }

    private static void closeStereoAt(BlockPos key) {
        StereoEntry old = STEREO_HANDLERS.remove(key);
        if (old != null) {
            old.cleanup();
        }
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
                    } else {
                        // 旧版 x,y,z 格式：迁移到 scoped key（当前作用域 + 默认维度）
                        BlockPos legacy = parseLegacyPosKey(key);
                        if (legacy != null) {
                            String migrated = volumeKeyFor(legacy);
                            if (migrated != null) {
                                SOURCE_VOLUMES.put(migrated, value);
                            }
                        }
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
        Thread saveThread = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                pendingSave = false;
                return;
            }
            // 如果 1.5s 内又有新写入，跳过本次（下次写入会触发新的 debounce）
            if (System.currentTimeMillis() - lastSaveMillis < 1400) {
                pendingSave = false;
                return;
            }
            saveVolumesNow();
            pendingSave = false;
        }, "VolumeSaveDebounce");
        saveThread.setDaemon(true);
        saveThread.start();
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

    private static BlockPos parseLegacyPosKey(String key) {
        if (key == null) {
            return null;
        }
        String[] parts = key.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException ignored) {
            return null;
        }
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

    private static float[] centerFor(BlockPos pos) {
        return AudioUtils.centerFor(pos, fallbackMachinePos);
    }

    private interface AudioEntry {
        BlockPos pos();

        float[] machinePos();

        long createdAtMillis();

        void cleanup();
    }

    private record DolbyEntry(BlockPos pos, float[] machinePos, DolbyAudioHandler handler, long createdAtMillis)
            implements AudioEntry {
        @Override
        public void cleanup() {
            handler.cleanup();
        }
    }

    private record StereoEntry(BlockPos pos, float[] machinePos, StereoOpenALHandler handler, long createdAtMillis)
            implements AudioEntry {
        @Override
        public void cleanup() {
            handler.cleanup();
        }
    }
}
