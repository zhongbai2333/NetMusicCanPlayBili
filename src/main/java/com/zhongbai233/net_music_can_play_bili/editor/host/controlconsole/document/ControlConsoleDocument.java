package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document;

import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 中控台的业务文档草稿；不包含相机、GPU、地形缓存或媒体运行时状态。
 *
 * @param schemaVersion 文档结构版本
 * @param revision 服务端提交修订号
 * @param displayName 编辑器中显示的名称
 * @param sourceDimension 绑定源所在维度，未绑定时为空
 * @param sourceX 绑定源 X 坐标
 * @param sourceY 绑定源 Y 坐标
 * @param sourceZ 绑定源 Z 坐标
 * @param hardRangeX 矩形硬范围 X 半径
 * @param hardRangeY 矩形硬范围 Y 半径
 * @param hardRangeZ 矩形硬范围 Z 半径
 */
public record ControlConsoleDocument(
        int schemaVersion,
    UUID consoleId,
        long revision,
        UUID ownerId,
        AccessMode accessMode,
        Set<UUID> trustedPlayerIds,
        String displayName,
        String sourceDimension,
        SourceKind sourceKind,
        int sourceX,
        int sourceY,
        int sourceZ,
        double hardRangeX,
        double hardRangeY,
        double hardRangeZ,
        List<ControlConsoleElement> elements) {
    public static final int CURRENT_SCHEMA_VERSION = 6;
    public static final double DEFAULT_HARD_RANGE_X = 64.0D;
    public static final double DEFAULT_HARD_RANGE_Y = 32.0D;
    public static final double DEFAULT_HARD_RANGE_Z = 64.0D;
    /** 仅防止异常 NBT/网络包耗尽内存，不代表编辑器的产品容量。 */
    public static final int MAX_ELEMENTS = 4096;
    public static final int MAX_TRUSTED_PLAYERS = 256;

    public enum AccessMode {
        OWNER_ONLY,
        TRUSTED,
        PUBLIC_EDIT;

        public static AccessMode parse(String value) {
            try {
                return valueOf(Objects.requireNonNull(value, "value").trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("unsupported control console access mode: " + value, invalid);
            }
        }
    }

    public enum SourceKind {
        TURNTABLE,
        LIVE_STREAMER
    }

    public ControlConsoleDocument {
        if (schemaVersion <= 0 || schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported document schema version: " + schemaVersion);
        }
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        consoleId = Objects.requireNonNull(consoleId, "consoleId");
        if (sourceDimension == null) {
            sourceKind = null;
        } else if (sourceKind == null) {
            throw new IllegalArgumentException("sourceKind is required when sourceDimension is present");
        }
        accessMode = Objects.requireNonNull(accessMode, "accessMode");
        Set<UUID> suppliedTrustedPlayerIds = Objects.requireNonNull(trustedPlayerIds, "trustedPlayerIds");
        if (suppliedTrustedPlayerIds.size() > MAX_TRUSTED_PLAYERS
                || suppliedTrustedPlayerIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("trustedPlayerIds must contain at most 256 non-null UUIDs");
        }
        trustedPlayerIds = Set.copyOf(suppliedTrustedPlayerIds);
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty() || displayName.length() > 64) {
            throw new IllegalArgumentException("displayName must contain 1-64 characters");
        }
        if (sourceDimension != null && sourceDimension.isBlank()) {
            throw new IllegalArgumentException("sourceDimension must be non-blank when present");
        }
        validateRange(hardRangeX, "hardRangeX");
        validateRange(hardRangeY, "hardRangeY");
        validateRange(hardRangeZ, "hardRangeZ");
        elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        if (elements.size() > MAX_ELEMENTS) {
            throw new IllegalArgumentException("elements exceed the transport safety limit");
        }
        if (elements.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("elements must not contain null");
        }
        java.util.HashSet<UUID> elementIds = new java.util.HashSet<>();
        for (ControlConsoleElement element : elements) {
            if (!elementIds.add(element.elementId())) {
                throw new IllegalArgumentException("elementId must be unique");
            }
        }
    }

    /** v1 兼容构造器；旧文档没有元素列表。 */
    public ControlConsoleDocument(int schemaVersion, long revision, String displayName,
            String sourceDimension, int sourceX, int sourceY, int sourceZ,
            double hardRangeX, double hardRangeY, double hardRangeZ) {
        this(schemaVersion, UUID.randomUUID(), revision, null, AccessMode.OWNER_ONLY, Set.of(), displayName,
            sourceDimension, sourceDimension == null ? null : SourceKind.TURNTABLE, sourceX, sourceY, sourceZ,
            hardRangeX, hardRangeY, hardRangeZ, List.of());
    }

        /** 兼容 schema v2 元素构造面；ACL 由服务端迁移或放置时认领。 */
        public ControlConsoleDocument(int schemaVersion, long revision, String displayName,
            String sourceDimension, int sourceX, int sourceY, int sourceZ,
            double hardRangeX, double hardRangeY, double hardRangeZ, List<ControlConsoleElement> elements) {
        this(schemaVersion, UUID.randomUUID(), revision, null, AccessMode.OWNER_ONLY, Set.of(), displayName,
            sourceDimension, sourceDimension == null ? null : SourceKind.TURNTABLE, sourceX, sourceY, sourceZ,
            hardRangeX, hardRangeY, hardRangeZ, elements);
        }

    public ControlConsoleDocument(int schemaVersion, long revision, UUID ownerId, AccessMode accessMode,
            Set<UUID> trustedPlayerIds, String displayName, String sourceDimension, int sourceX, int sourceY,
            int sourceZ, double hardRangeX, double hardRangeY, double hardRangeZ, List<ControlConsoleElement> elements) {
        this(schemaVersion, UUID.randomUUID(), revision, ownerId, accessMode, trustedPlayerIds, displayName,
            sourceDimension, sourceDimension == null ? null : SourceKind.TURNTABLE, sourceX, sourceY, sourceZ,
            hardRangeX, hardRangeY, hardRangeZ, elements);
    }

    public static ControlConsoleDocument empty() {
        return new ControlConsoleDocument(CURRENT_SCHEMA_VERSION, UUID.randomUUID(), 0L, null, AccessMode.OWNER_ONLY, Set.of(),
            "中控台", null,
            null, 0, 0, 0, DEFAULT_HARD_RANGE_X, DEFAULT_HARD_RANGE_Y, DEFAULT_HARD_RANGE_Z,
            List.of(ControlConsoleElement.defaultScreen()));
    }

    /**
     * 兼容早期版本中尚未保存任何元素的初始文档。只提升 revision 0，用户明确删除并保存后的
     * 较新空文档保持为空。
     */
    public ControlConsoleDocument withInitialScreenIfPristine() {
        if (revision != 0L || !elements.isEmpty()) {
            return this;
        }
        return new ControlConsoleDocument(schemaVersion, consoleId, revision, ownerId, accessMode, trustedPlayerIds,
                displayName, sourceDimension, sourceKind, sourceX, sourceY, sourceZ,
                hardRangeX, hardRangeY, hardRangeZ, List.of(ControlConsoleElement.defaultScreen()));
    }

    public boolean hasSourceBinding() {
        return sourceDimension != null;
    }

    public ControlConsoleDocument withRevision(long newRevision) {
        return new ControlConsoleDocument(schemaVersion, consoleId, newRevision, ownerId, accessMode, trustedPlayerIds,
            displayName, sourceDimension,
            sourceKind, sourceX, sourceY, sourceZ, hardRangeX, hardRangeY, hardRangeZ, elements);
    }

    public boolean canEdit(UUID playerId, boolean administrator) {
        if (administrator) {
            return true;
        }
        if (playerId == null || ownerId == null) {
            return false;
        }
        return ownerId.equals(playerId) || accessMode == AccessMode.PUBLIC_EDIT
                || accessMode == AccessMode.TRUSTED && trustedPlayerIds.contains(playerId);
    }

    public ControlConsoleDocument withOwnerIfAbsent(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return ownerId != null ? this : new ControlConsoleDocument(schemaVersion, consoleId, revision, playerId,
            accessMode, trustedPlayerIds, displayName, sourceDimension, sourceKind, sourceX, sourceY, sourceZ,
                hardRangeX, hardRangeY, hardRangeZ, elements);
    }

    public ControlConsoleDocument withAccessControl(AccessMode newAccessMode, Set<UUID> newTrustedPlayerIds) {
        return new ControlConsoleDocument(schemaVersion, consoleId, revision + 1L, ownerId,
            newAccessMode, newTrustedPlayerIds, displayName, sourceDimension, sourceKind, sourceX, sourceY, sourceZ,
                hardRangeX, hardRangeY, hardRangeZ, elements);
    }

    private static void validateRange(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }
}
