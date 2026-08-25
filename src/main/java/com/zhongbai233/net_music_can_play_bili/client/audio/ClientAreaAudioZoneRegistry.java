package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioOutputFades;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Client mirror of server-resolved zones plus one independent fade per real output endpoint. */
public final class ClientAreaAudioZoneRegistry {
    public static final float AUDIBLE_EPSILON = 0.001F;
    private static final AreaAudioOutputFades<BlockPos> OUTPUT_FADES = new AreaAudioOutputFades<>();
    private static final AreaAudioOutputFades<UUID> MOVING_FADES = new AreaAudioOutputFades<>();
    private static final ConcurrentMap<BlockPos, Set<BlockPos>> CONSOLE_OUTPUTS = new ConcurrentHashMap<>();

    private ClientAreaAudioZoneRegistry() {
    }

    public static void acceptListener(AreaAudioZone zone) {
        OUTPUT_FADES.acceptListener(zone);
        MOVING_FADES.acceptListener(zone);
    }

    public static void setOutputZone(BlockPos outputKey, AreaAudioZone zone) {
        if (outputKey != null) {
            OUTPUT_FADES.set(outputKey.immutable(), zone);
        }
    }

    public static void removeOutput(BlockPos outputKey) {
        if (outputKey != null) {
            BlockPos key = outputKey.immutable();
            OUTPUT_FADES.remove(key);
        }
    }

    public static void replaceConsoleOutputs(BlockPos consolePos, Map<BlockPos, AreaAudioZone> zones) {
        if (consolePos == null) {
            return;
        }
        BlockPos consoleKey = consolePos.immutable();
        Set<BlockPos> previous = CONSOLE_OUTPUTS.get(consoleKey);
        Set<BlockPos> keys = ConcurrentHashMap.newKeySet();
        if (zones != null) {
            zones.forEach((key, zone) -> {
                if (key != null) {
                    BlockPos immutableKey = key.immutable();
                    keys.add(immutableKey);
                    setOutputZone(immutableKey, zone);
                }
            });
        }
        if (previous != null) {
            previous.stream().filter(key -> !keys.contains(key)).forEach(ClientAreaAudioZoneRegistry::removeOutput);
        }
        if (!keys.isEmpty()) {
            CONSOLE_OUTPUTS.put(consoleKey, keys);
        } else {
            CONSOLE_OUTPUTS.remove(consoleKey);
        }
    }

    public static void clearConsoleOutputs(BlockPos consolePos) {
        if (consolePos == null) {
            return;
        }
        Set<BlockPos> old = CONSOLE_OUTPUTS.remove(consolePos.immutable());
        if (old != null) {
            old.forEach(ClientAreaAudioZoneRegistry::removeOutput);
        }
    }

    public static float gain(BlockPos outputKey, long nowNanos) {
        if (outputKey == null) {
            return 1.0F;
        }
        BlockPos key = outputKey.immutable();
        return OUTPUT_FADES.gain(key, nowNanos);
    }

    public static boolean audible(BlockPos outputKey, long nowNanos) {
        return gain(outputKey, nowNanos) > AUDIBLE_EPSILON;
    }

    public static void setMovingZone(UUID sourceId, AreaAudioZone zone) {
        if (sourceId != null) {
            MOVING_FADES.set(sourceId, zone);
        }
    }

    public static void removeMovingZone(UUID sourceId) {
        if (sourceId != null) {
            MOVING_FADES.remove(sourceId);
        }
    }

    public static float gain(UUID sourceId, long nowNanos) {
        if (sourceId == null) {
            return 1.0F;
        }
        return MOVING_FADES.gain(sourceId, nowNanos);
    }

    public static void clear() {
        OUTPUT_FADES.clear();
        MOVING_FADES.clear();
        CONSOLE_OUTPUTS.clear();
    }
}
