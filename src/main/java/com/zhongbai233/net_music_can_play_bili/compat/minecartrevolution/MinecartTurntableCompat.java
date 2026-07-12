package com.zhongbai233.net_music_can_play_bili.compat.minecartrevolution;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional bridge to MinecartRevolution's simulated levels without a hard
 * dependency.
 */
public final class MinecartTurntableCompat {
    private static final String SIMULATION_PACKAGE = "ml.mypals.minecartrevolution.entity.minecarts.simulation.";
    /**
     * MinecartRevolution's public sentinel for resolving the minecart that hosts
     * a simulated level. This is -2147483647, deliberately not Integer.MIN_VALUE.
     */
    private static final int HOST_MINECART_ENTITY_ID = -Integer.MAX_VALUE;
    private static final ConcurrentHashMap<Class<?>, Optional<Field>> MINECART_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Level, WeakReference<Entity>> HOSTS_BY_LEVEL = Collections
            .synchronizedMap(new WeakHashMap<>());

    private MinecartTurntableCompat() {
    }

    public static Entity hostMinecart(Level level) {
        if (level == null || !level.getClass().getName().startsWith(SIMULATION_PACKAGE)) {
            return null;
        }
        WeakReference<Entity> cached = HOSTS_BY_LEVEL.get(level);
        Entity cachedHost = cached != null ? cached.get() : null;
        if (isUsable(cachedHost)) {
            return cachedHost;
        }

        Entity host = hostFromSentinel(level);
        if (isUsable(host)) {
            cache(level, host);
            return host;
        }

        return hostFromLegacyField(level);
    }

    public static UUID hostUuid(Level level) {
        Entity host = hostMinecart(level);
        return host != null ? host.getUUID() : null;
    }

    private static Entity hostFromSentinel(Level level) {
        try {
            return level.getEntity(HOST_MINECART_ENTITY_ID);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Entity hostFromLegacyField(Level level) {
        Optional<Field> field = MINECART_FIELDS.computeIfAbsent(level.getClass(), MinecartTurntableCompat::findField);
        if (field.isEmpty()) {
            return null;
        }
        try {
            Object value = field.get().get(level);
            if (value instanceof Entity entity && isUsable(entity)) {
                cache(level, entity);
                return entity;
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isUsable(Entity entity) {
        return entity != null && !entity.isRemoved();
    }

    private static void cache(Level level, Entity entity) {
        HOSTS_BY_LEVEL.put(level, new WeakReference<>(entity));
    }

    private static Optional<Field> findField(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField("minecart");
                field.setAccessible(true);
                return Optional.of(field);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}