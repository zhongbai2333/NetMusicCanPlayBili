package com.zhongbai233.net_music_can_play_bili.compat.areacontrol;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.media.audio.AreaAudioZone;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional, reflection-only AreaControl bridge so ordinary servers gain no hard dependency. */
public final class AreaControlAudioCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean RUNTIME_WARNING_LOGGED = new AtomicBoolean();
    private static final Adapter ADAPTER = createAdapter();

    private AreaControlAudioCompat() {
    }

    public static boolean active() {
        return ADAPTER != null;
    }

    public static AreaAudioZone zoneAt(ServerLevel level, BlockPos pos) {
        if (ADAPTER == null || level == null || pos == null) {
            return AreaAudioZone.unrestricted();
        }
        try {
            return AreaAudioZone.isolated(ADAPTER.find(level.dimension().identifier().toString(), pos));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (RUNTIME_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("AreaControl acoustic lookup failed; NCPB audio isolation is failing open", ex);
            }
            return AreaAudioZone.unrestricted();
        }
    }

    private static Adapter createAdapter() {
        if (!ModList.get().isLoaded("area_control")) {
            return null;
        }
        try {
            Class<?> apiClass = Class.forName("org.teacon.areacontrol.api.AreaControlAPI");
            Object lookup = apiClass.getField("areaLookup").get(null);
            UUID wildness = (UUID) apiClass.getField("WILDNESS").get(null);
            Method findBy = Class.forName("org.teacon.areacontrol.api.AreaLookup").getMethod("findBy", String.class,
                    int.class, int.class, int.class);
            Class<?> areaClass = Class.forName("org.teacon.areacontrol.api.Area");
            Field uid = areaClass.getField("uid");
            LOGGER.info("AreaControl detected: strict acoustic region isolation enabled");
            return new Adapter(lookup, findBy, uid, wildness);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            LOGGER.warn("AreaControl is installed but its public API is unavailable; NCPB audio isolation is disabled",
                    ex);
            return null;
        }
    }

    private record Adapter(Object lookup, Method findBy, Field uid, UUID wildness) {
        private UUID find(String dimension, BlockPos pos) throws ReflectiveOperationException {
            Object area = findBy.invoke(lookup, dimension, pos.getX(), pos.getY(), pos.getZ());
            return area != null ? (UUID) uid.get(area) : wildness;
        }
    }
}
