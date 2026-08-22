package com.zhongbai233.net_music_can_play_bili.link;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 客户端全局链接注册表。
 * <p>
 * 维护 "目标方块 → 来源方块集合" 的映射，供渲染器等客户端逻辑查询。
 * 例如：唱片机（目标）← 投影仪/音响（来源）。
 * </p>
 */
public final class ClientLinkRegistry {
    /** 目标方块位置 → 来源方块位置集合 */
    private static final Map<BlockPos, Set<BlockPos>> LINKS = new ConcurrentHashMap<>();
    /** 只有字幕投影仪和承担字幕投影的中控台才进入此表，避免把视频投影仪/音响误报为字幕投影。 */
    private static final Map<BlockPos, Set<BlockPos>> SUBTITLE_PROJECTION_LINKS = new ConcurrentHashMap<>();

    private ClientLinkRegistry() {
    }

    /** 注册一个链接：来源方块→目标方块 */
    public static void link(BlockPos sourcePos, BlockPos targetPos) {
        if (sourcePos == null || targetPos == null) {
            return;
        }
        // 一个来源只能指向一个目标；重绑前清除旧目标，避免旧唱片机永久显示“已连接投影仪”。
        unlink(sourcePos);
        LINKS.computeIfAbsent(targetPos.immutable(), k -> new CopyOnWriteArraySet<>())
                .add(sourcePos.immutable());
    }

    /** 注册一个会接管唱片机头顶字幕的链接。 */
    public static void linkSubtitleProjector(BlockPos sourcePos, BlockPos targetPos) {
        link(sourcePos, targetPos);
        SUBTITLE_PROJECTION_LINKS.computeIfAbsent(targetPos.immutable(), k -> new CopyOnWriteArraySet<>())
                .add(sourcePos.immutable());
    }

    /** 移除指定来源方块的所有链接 */
    public static void unlink(BlockPos sourcePos) {
        if (sourcePos == null) {
            return;
        }
        removeSource(LINKS, sourcePos);
        removeSource(SUBTITLE_PROJECTION_LINKS, sourcePos);
    }

    /** 移除来源→指定目标的链接 */
    public static void unlink(BlockPos sourcePos, BlockPos targetPos) {
        Set<BlockPos> sources = LINKS.get(targetPos);
        if (sources != null) {
            sources.remove(sourcePos);
            if (sources.isEmpty()) {
                LINKS.remove(targetPos);
            }
        }
        Set<BlockPos> projectionSources = SUBTITLE_PROJECTION_LINKS.get(targetPos);
        if (projectionSources != null) {
            projectionSources.remove(sourcePos);
            if (projectionSources.isEmpty()) {
                SUBTITLE_PROJECTION_LINKS.remove(targetPos);
            }
        }
    }

    /** 目标方块是否被任何来源方块连接 */
    public static boolean isTargetLinked(BlockPos targetPos) {
        Set<BlockPos> sources = LINKS.get(targetPos);
        return sources != null && !sources.isEmpty();
    }

    /** 目标唱片机的头顶字幕是否由字幕投影仪或中控台接管。 */
    public static boolean isSubtitleProjectionTarget(BlockPos targetPos) {
        Set<BlockPos> sources = SUBTITLE_PROJECTION_LINKS.get(targetPos);
        return sources != null && !sources.isEmpty();
    }

    /** 获取连接到指定目标的所有来源 */
    public static Set<BlockPos> getSources(BlockPos targetPos) {
        return LINKS.getOrDefault(targetPos, Set.of());
    }

    /** 客户端断连/切世界时清空所有旧世界链接。 */
    public static void clear() {
        LINKS.clear();
        SUBTITLE_PROJECTION_LINKS.clear();
    }

    private static void removeSource(Map<BlockPos, Set<BlockPos>> links, BlockPos sourcePos) {
        links.values().forEach(set -> set.remove(sourcePos));
        links.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
}
