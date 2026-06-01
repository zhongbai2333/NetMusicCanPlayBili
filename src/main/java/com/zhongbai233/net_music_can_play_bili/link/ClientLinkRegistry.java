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

    private ClientLinkRegistry() {
    }

    /** 注册一个链接：来源方块→目标方块 */
    public static void link(BlockPos sourcePos, BlockPos targetPos) {
        LINKS.computeIfAbsent(targetPos.immutable(), k -> new CopyOnWriteArraySet<>())
                .add(sourcePos.immutable());
    }

    /** 移除指定来源方块的所有链接 */
    public static void unlink(BlockPos sourcePos) {
        LINKS.values().forEach(set -> set.remove(sourcePos));
        LINKS.entrySet().removeIf(e -> e.getValue().isEmpty());
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
    }

    /** 目标方块是否被任何来源方块连接 */
    public static boolean isTargetLinked(BlockPos targetPos) {
        Set<BlockPos> sources = LINKS.get(targetPos);
        return sources != null && !sources.isEmpty();
    }

    /** 获取连接到指定目标的所有来源 */
    public static Set<BlockPos> getSources(BlockPos targetPos) {
        return LINKS.getOrDefault(targetPos, Set.of());
    }
}
