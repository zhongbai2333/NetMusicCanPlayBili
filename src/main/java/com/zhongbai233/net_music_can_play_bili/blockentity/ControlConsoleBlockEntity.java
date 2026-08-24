package com.zhongbai233.net_music_can_play_bili.blockentity;

import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.server.level.ServerPlayer;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleGeometryValidator;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleRangeMigration;
import com.zhongbai233.net_music_can_play_bili.server.ControlConsolePermissionPolicy;
import com.zhongbai233.net_music_can_play_bili.server.NetMusicPermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** 中控台的最小持久化宿主。媒体运行时、地形快照和相机状态不进入这里。 */
public final class ControlConsoleBlockEntity extends SyncedBlockEntity {
    public static final int CURRENT_SCHEMA_VERSION = ControlConsoleDocument.CURRENT_SCHEMA_VERSION;
    private static final String SCHEMA_VERSION_TAG = "SchemaVersion";
    private static final String DOCUMENT_TAG = "Document";
    private static final String DOCUMENT_REVISION_TAG = "DocumentRevision";
    private static final String CONSOLE_ID_TAG = "ConsoleId";
    private static final String OWNER_ID_TAG = "OwnerId";
    private static final String ACCESS_MODE_TAG = "AccessMode";
    private static final String TRUSTED_PLAYERS_TAG = "TrustedPlayers";
    private static final String PLAYER_ID_TAG = "PlayerId";
    private static final String DISPLAY_NAME_TAG = "DisplayName";
    private static final String HARD_RANGE_X_TAG = "HardRangeX";
    private static final String HARD_RANGE_Y_TAG = "HardRangeY";
    private static final String HARD_RANGE_Z_TAG = "HardRangeZ";
    private static final String SOURCE_DIMENSION_TAG = "SourceDimension";
    private static final String SOURCE_KIND_TAG = "SourceKind";
    private static final String SOURCE_X_TAG = "SourceX";
    private static final String SOURCE_Y_TAG = "SourceY";
    private static final String SOURCE_Z_TAG = "SourceZ";
    private static final String ELEMENTS_TAG = "Elements";
    private static final String ELEMENT_TYPE_TAG = "Type";
    private static final String ELEMENT_NAME_TAG = "Name";
    private static final String ELEMENT_DISTANCE_TAG = "Distance";
    private static final String ELEMENT_OFFSET_X_TAG = "OffsetX";
    private static final String ELEMENT_OFFSET_Y_TAG = "OffsetY";
    private static final String ELEMENT_HEIGHT_TAG = "Height";
    private static final String ELEMENT_ASPECT_TAG = "Aspect";
    private static final String ELEMENT_YAW_TAG = "Yaw";
    private static final String ELEMENT_PITCH_TAG = "Pitch";
    private static final String ELEMENT_ROLL_TAG = "Roll";
    private static final String ELEMENT_SCALE_X_TAG = "ScaleX";
    private static final String ELEMENT_SCALE_Y_TAG = "ScaleY";
    private static final String ELEMENT_SCALE_Z_TAG = "ScaleZ";
    private static final String ELEMENT_PIVOT_X_TAG = "PivotX";
    private static final String ELEMENT_PIVOT_Y_TAG = "PivotY";
    private static final String ELEMENT_PIVOT_Z_TAG = "PivotZ";
    private static final String ELEMENT_SKEW_X_BY_Y_TAG = "SkewXByY";
    private static final String ELEMENT_SKEW_Y_BY_X_TAG = "SkewYByX";
    private static final String ELEMENT_BRIGHTNESS_TAG = "Brightness";
    private static final String ELEMENT_CONTENT_MODE_TAG = "ContentMode";
    private static final String ELEMENT_TEXT_TAG = "Text";
    private static final String ELEMENT_FOLLOW_LYRICS_TAG = "FollowLyrics";
    private static final String ELEMENT_SHOW_TRANSLATION_TAG = "ShowTranslation";
    private static final String ELEMENT_TEXT_SCALE_TAG = "TextScale";
    private static final String ELEMENT_COLOR_TAG = "Color";
    private static final String ELEMENT_VOLUME_TAG = "Volume";
    private static final String ELEMENT_CHANNEL_INDEX_TAG = "ChannelIndex";
    private static final String ELEMENT_MAX_DISTANCE_TAG = "MaxDistance";
    private static final String ELEMENT_ENABLED_TAG = "Enabled";
    private static final String ELEMENT_ID_TAG = "ElementId";
    private static final String ELEMENT_LOCKED_TAG = "Locked";
    private static final String ELEMENT_TRANSLATION_COLOR_TAG = "TranslationColor";
    private static final String ELEMENT_BACKGROUND_COLOR_TAG = "BackgroundColor";
    private static final String ELEMENT_ALIGNMENT_TAG = "Alignment";
    private static final String ELEMENT_MAX_WIDTH_TAG = "MaxWidth";
    private static final String ELEMENT_WRAP_TAG = "Wrap";

    private ControlConsoleDocument document = ControlConsoleDocument.empty();
    private int loadedSchemaVersion = CURRENT_SCHEMA_VERSION;
    private CompoundTag preservedFutureDocument;
    private final LinkedHashSet<UUID> appliedOperationIds = new LinkedHashSet<>();

    public ControlConsoleBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONTROL_CONSOLE.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) {
            // 消费资格属于方块实体生命周期，不能依赖 BER 是否进入视锥或渲染距离。
            com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer
                    .registerConsumer(this);
        }
    }

    @Override
    public void setRemoved() {
        if (level != null && level.isClientSide()) {
            // setRemoved 也会在区块卸载时调用。区块卸载不等于解绑，否则玩家走远时
            // 唱片机主输出会意外恢复；只有世界中该位置已不再是中控台才释放路由所有权。
            boolean bindingDestroyed = !level.getBlockState(worldPosition).is(ModBlocks.CONTROL_CONSOLE.get());
            com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer
                .notifyConsoleRemoved(worldPosition, bindingDestroyed);
        }
        super.setRemoved();
    }

    public int documentSchemaVersion() {
        return loadedSchemaVersion;
    }

    public boolean isDocumentReadOnly() {
        return loadedSchemaVersion > CURRENT_SCHEMA_VERSION;
    }

    public long documentRevision() {
        return document.revision();
    }

    public void advanceDocumentRevision() {
        if (isDocumentReadOnly()) {
            return;
        }
        document = document.withRevision(document.revision() + 1L);
        markDirtyAndSync();
    }

    public ControlConsoleDocument document() {
        return document;
    }

    public boolean claimIfUnowned(UUID playerId) {
        java.util.Objects.requireNonNull(playerId, "playerId");
        if (isDocumentReadOnly() || document.ownerId() != null) {
            return false;
        }
        document = document.withOwnerIfAbsent(playerId);
        markDirtyAndSync();
        return true;
    }

    public boolean canEdit(ServerPlayer player) {
        if (player == null || isDocumentReadOnly()) {
            return false;
        }
        boolean administrator = NetMusicPermissions.canAdministerControlConsole(player.createCommandSourceStack());
        if (!ControlConsolePermissionPolicy.passesBuildGate(player.mayBuild(), administrator)) {
            return false;
        }
        return document.canEdit(player.getUUID(), administrator);
    }

    public ReplaceResult replaceAccessControl(ServerPlayer player, UUID operationId, long expectedRevision,
            ControlConsoleDocument.AccessMode accessMode, java.util.Set<UUID> trustedPlayerIds) {
        java.util.Objects.requireNonNull(operationId, "operationId");
        if (player == null || isDocumentReadOnly()) {
            return isDocumentReadOnly() ? ReplaceResult.READ_ONLY : ReplaceResult.REJECTED;
        }
        boolean administrator = NetMusicPermissions.canAdministerControlConsole(player.createCommandSourceStack());
        if (!ControlConsolePermissionPolicy.passesBuildGate(player.mayBuild(), administrator)) {
            return ReplaceResult.REJECTED;
        }
        if (!administrator && !player.getUUID().equals(document.ownerId())) {
            return ReplaceResult.REJECTED;
        }
        if (appliedOperationIds.contains(operationId)) {
            return ReplaceResult.DUPLICATE;
        }
        if (document.revision() != expectedRevision) {
            return ReplaceResult.CONFLICT;
        }
        document = document.withAccessControl(accessMode, trustedPlayerIds);
        rememberOperation(operationId);
        markDirtyAndSync();
        return ReplaceResult.APPLIED;
    }

    public boolean replaceDocument(long expectedRevision, String displayName, double hardRangeX,
            double hardRangeY, double hardRangeZ, List<ControlConsoleElement> elements) {
        if (isDocumentReadOnly()) {
            return false;
        }
        if (document.revision() != expectedRevision) {
            return false;
        }
        if (!com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElementLockPolicy
                .permits(document.elements(), elements)) {
            return false;
        }
        ControlConsoleGeometryValidator.ValidationResult geometry = ControlConsoleGeometryValidator.validate(
                hardRangeX, hardRangeY, hardRangeZ, elements);
        if (!geometry.valid()) {
            throw new IllegalArgumentException(geometry.reason());
        }
        document = new ControlConsoleDocument(document.schemaVersion(), document.consoleId(), document.revision() + 1L,
            document.ownerId(), document.accessMode(), document.trustedPlayerIds(), displayName,
            document.sourceDimension(), document.sourceKind(), document.sourceX(), document.sourceY(), document.sourceZ(),
                hardRangeX, hardRangeY, hardRangeZ, elements);
        markDirtyAndSync();
        return true;
    }

    /**
     * 应用带幂等 ID 的编辑提交。操作 ID 只在当前方块实体生命周期内缓存，
     * 持久化文档 revision 仍是跨重载的并发权威。
     */
    public ReplaceResult replaceDocument(UUID operationId, long expectedRevision, String displayName,
            double hardRangeX, double hardRangeY, double hardRangeZ, List<ControlConsoleElement> elements) {
        java.util.Objects.requireNonNull(operationId, "operationId");
        if (appliedOperationIds.contains(operationId)) {
            return ReplaceResult.DUPLICATE;
        }
        if (isDocumentReadOnly()) {
            return ReplaceResult.READ_ONLY;
        }
        if (document.revision() != expectedRevision) {
            return ReplaceResult.CONFLICT;
        }
        if (!replaceDocument(expectedRevision, displayName, hardRangeX, hardRangeY, hardRangeZ, elements)) {
            // revision/read-only were checked above; a false result here means the draft tried to
            // mutate a locked element and must not be remembered or acknowledged as applied.
            return ReplaceResult.REJECTED;
        }
        rememberOperation(operationId);
        return ReplaceResult.APPLIED;
    }

    private void rememberOperation(UUID operationId) {
        appliedOperationIds.add(operationId);
        while (appliedOperationIds.size() > 64) {
            appliedOperationIds.remove(appliedOperationIds.iterator().next());
        }
    }

    public enum ReplaceResult {
        APPLIED,
        DUPLICATE,
        CONFLICT,
        READ_ONLY,
        REJECTED
    }

    public void linkTo(String dimension, BlockPos sourcePos) {
        linkTo(dimension, sourcePos, ControlConsoleDocument.SourceKind.TURNTABLE);
    }

    public void linkTo(String dimension, BlockPos sourcePos, ControlConsoleDocument.SourceKind sourceKind) {
        java.util.Objects.requireNonNull(dimension, "dimension");
        java.util.Objects.requireNonNull(sourcePos, "sourcePos");
        if (isDocumentReadOnly()) {
            return;
        }
        document = new ControlConsoleDocument(document.schemaVersion(), document.consoleId(), document.revision() + 1L,
            document.ownerId(), document.accessMode(), document.trustedPlayerIds(), document.displayName(),
            dimension, sourceKind, sourcePos.getX(), sourcePos.getY(), sourcePos.getZ(),
                document.hardRangeX(), document.hardRangeY(), document.hardRangeZ(), document.elements());
        markDirtyAndSync();
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt(SCHEMA_VERSION_TAG, loadedSchemaVersion);
        if (isDocumentReadOnly()) {
            if (preservedFutureDocument != null) {
                output.store(DOCUMENT_TAG, CompoundTag.CODEC, preservedFutureDocument.copy());
            }
            return;
        }
        ValueOutput documentOutput = output.child(DOCUMENT_TAG);
        saveDocument(documentOutput);
    }

    private void saveDocument(ValueOutput output) {
        output.putString(CONSOLE_ID_TAG, document.consoleId().toString());
        output.putLong(DOCUMENT_REVISION_TAG, document.revision());
        if (document.ownerId() != null) {
            output.putString(OWNER_ID_TAG, document.ownerId().toString());
        }
        output.putString(ACCESS_MODE_TAG, document.accessMode().name());
        ValueOutput.ValueOutputList trustedPlayers = output.childrenList(TRUSTED_PLAYERS_TAG);
        for (UUID playerId : document.trustedPlayerIds()) {
            trustedPlayers.addChild().putString(PLAYER_ID_TAG, playerId.toString());
        }
        output.putString(DISPLAY_NAME_TAG, document.displayName());
        output.putDouble(HARD_RANGE_X_TAG, document.hardRangeX());
        output.putDouble(HARD_RANGE_Y_TAG, document.hardRangeY());
        output.putDouble(HARD_RANGE_Z_TAG, document.hardRangeZ());
        if (document.hasSourceBinding()) {
            output.putString(SOURCE_DIMENSION_TAG, document.sourceDimension());
            if (document.sourceKind() != null) {
                output.putString(SOURCE_KIND_TAG, document.sourceKind().name());
            }
            output.putInt(SOURCE_X_TAG, document.sourceX());
            output.putInt(SOURCE_Y_TAG, document.sourceY());
            output.putInt(SOURCE_Z_TAG, document.sourceZ());
        }
        ValueOutput.ValueOutputList elements = output.childrenList(ELEMENTS_TAG);
        for (ControlConsoleElement element : document.elements()) {
            ValueOutput child = elements.addChild();
            child.putString(ELEMENT_ID_TAG, element.elementId().toString());
            child.putBoolean(ELEMENT_LOCKED_TAG, element.locked());
            child.putString(ELEMENT_TYPE_TAG, element.type().name());
            child.putString(ELEMENT_NAME_TAG, element.name());
            child.putFloat(ELEMENT_DISTANCE_TAG, element.distance());
            child.putFloat(ELEMENT_OFFSET_X_TAG, element.offsetX());
            child.putFloat(ELEMENT_OFFSET_Y_TAG, element.offsetY());
            child.putFloat(ELEMENT_HEIGHT_TAG, element.height());
            child.putFloat(ELEMENT_ASPECT_TAG, element.aspect());
            child.putFloat(ELEMENT_YAW_TAG, element.yaw());
            child.putFloat(ELEMENT_PITCH_TAG, element.pitch());
            child.putFloat(ELEMENT_ROLL_TAG, element.roll());
            child.putFloat(ELEMENT_SCALE_X_TAG, element.scaleX());
            child.putFloat(ELEMENT_SCALE_Y_TAG, element.scaleY());
            child.putFloat(ELEMENT_SCALE_Z_TAG, element.scaleZ());
            child.putFloat(ELEMENT_PIVOT_X_TAG, element.pivotX());
            child.putFloat(ELEMENT_PIVOT_Y_TAG, element.pivotY());
            child.putFloat(ELEMENT_PIVOT_Z_TAG, element.pivotZ());
            child.putFloat(ELEMENT_SKEW_X_BY_Y_TAG, element.skewXByY());
            child.putFloat(ELEMENT_SKEW_Y_BY_X_TAG, element.skewYByX());
            child.putFloat(ELEMENT_BRIGHTNESS_TAG, element.brightness());
            child.putString(ELEMENT_CONTENT_MODE_TAG, element.contentMode());
            child.putString(ELEMENT_TEXT_TAG, element.text());
            child.putBoolean(ELEMENT_FOLLOW_LYRICS_TAG, element.followLyrics());
            child.putBoolean(ELEMENT_SHOW_TRANSLATION_TAG, element.showTranslation());
            child.putFloat(ELEMENT_TEXT_SCALE_TAG, element.textScale());
            child.putInt(ELEMENT_COLOR_TAG, element.color());
            child.putFloat(ELEMENT_VOLUME_TAG, element.volume());
            child.putInt(ELEMENT_CHANNEL_INDEX_TAG, element.channelIndex());
            child.putFloat(ELEMENT_MAX_DISTANCE_TAG, element.maxDistance());
            child.putBoolean("AutoMixJoc", element.autoMixJoc());
            child.putInt(ELEMENT_TRANSLATION_COLOR_TAG, element.translationColor());
            child.putInt(ELEMENT_BACKGROUND_COLOR_TAG, element.backgroundColor());
            child.putString(ELEMENT_ALIGNMENT_TAG, element.alignment().name());
            child.putFloat(ELEMENT_MAX_WIDTH_TAG, element.maxWidth());
            child.putBoolean(ELEMENT_WRAP_TAG, element.wrap());
            child.putBoolean(ELEMENT_ENABLED_TAG, element.enabled());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        // 未知高版本只读保守处理：不按旧结构写回；当前骨架只读取受支持版本的 revision。
        int schemaVersion = input.getIntOr(SCHEMA_VERSION_TAG, 1);
        loadedSchemaVersion = schemaVersion;
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            preservedFutureDocument = input.read(DOCUMENT_TAG, CompoundTag.CODEC)
                    .map(tag -> tag.copy()).orElse(null);
            document = ControlConsoleDocument.empty();
            return;
        }
        try {
            ValueInput documentInput = schemaVersion >= 3 ? input.childOrEmpty(DOCUMENT_TAG) : input;
            String sourceDimension = documentInput.getStringOr(SOURCE_DIMENSION_TAG, "");
            ControlConsoleDocument.SourceKind sourceKind = parseSourceKind(
                    documentInput.getStringOr(SOURCE_KIND_TAG, ""));
            if (!sourceDimension.isBlank() && sourceKind == null && schemaVersion < 4) {
                sourceKind = inferLegacySourceKind(documentInput);
            }
                String dimension = level != null ? level.dimension().identifier().toString() : "unknown";
                List<ControlConsoleElement> elements = readElements(documentInput, dimension);
                var migratedRange = ControlConsoleRangeMigration.migrate(schemaVersion,
                    documentInput.getDoubleOr(HARD_RANGE_X_TAG, ControlConsoleDocument.DEFAULT_HARD_RANGE_X),
                    documentInput.getDoubleOr(HARD_RANGE_Y_TAG, ControlConsoleDocument.DEFAULT_HARD_RANGE_Y),
                    documentInput.getDoubleOr(HARD_RANGE_Z_TAG, ControlConsoleDocument.DEFAULT_HARD_RANGE_Z));
                document = new ControlConsoleDocument(CURRENT_SCHEMA_VERSION,
                    parseUuid(documentInput.getStringOr(CONSOLE_ID_TAG, "")) != null
                    ? parseUuid(documentInput.getStringOr(CONSOLE_ID_TAG, ""))
                    : stableId("console", dimension + "|" + worldPosition.asLong()),
                    documentInput.getLongOr(DOCUMENT_REVISION_TAG, 0L),
                parseUuid(documentInput.getStringOr(OWNER_ID_TAG, "")),
                ControlConsoleDocument.AccessMode.parse(
                    documentInput.getStringOr(ACCESS_MODE_TAG, "OWNER_ONLY")),
                readTrustedPlayers(documentInput),
                documentInput.getStringOr(DISPLAY_NAME_TAG, "中控台"),
                sourceDimension.isBlank() ? null : sourceDimension,
                sourceKind,
                documentInput.getIntOr(SOURCE_X_TAG, 0), documentInput.getIntOr(SOURCE_Y_TAG, 0),
                documentInput.getIntOr(SOURCE_Z_TAG, 0),
                            migratedRange.x(), migratedRange.y(), migratedRange.z(), elements);
            loadedSchemaVersion = CURRENT_SCHEMA_VERSION;
            preservedFutureDocument = null;
        } catch (IllegalArgumentException invalidDocument) {
            document = ControlConsoleDocument.empty();
            loadedSchemaVersion = CURRENT_SCHEMA_VERSION;
            preservedFutureDocument = null;
        }
    }

    private List<ControlConsoleElement> readElements(ValueInput input, String dimension) {
        List<ControlConsoleElement> elements = new ArrayList<>();
        int sourceIndex = 0;
        for (ValueInput child : input.childrenListOrEmpty(ELEMENTS_TAG)) {
            if (elements.size() >= ControlConsoleDocument.MAX_ELEMENTS) {
                break;
            }
            int elementSourceIndex = sourceIndex++;
            try {
                ControlConsoleElement.Type type = ControlConsoleElement.Type.parse(
                    child.getStringOr(ELEMENT_TYPE_TAG, ""));
                String name = child.getStringOr(ELEMENT_NAME_TAG, "");
                ControlConsoleElement legacy = new ControlConsoleElement(
                    type, name,
                    child.getFloatOr(ELEMENT_DISTANCE_TAG, 0.0F),
                    child.getFloatOr(ELEMENT_OFFSET_X_TAG, 0.0F),
                    child.getFloatOr(ELEMENT_OFFSET_Y_TAG, 0.0F),
                    child.getFloatOr(ELEMENT_HEIGHT_TAG, 1.0F),
                    child.getFloatOr(ELEMENT_ASPECT_TAG, 1.0F),
                    child.getFloatOr(ELEMENT_YAW_TAG, 0.0F),
                    child.getFloatOr(ELEMENT_PITCH_TAG, 0.0F),
                    child.getFloatOr(ELEMENT_ROLL_TAG, 0.0F));
                UUID elementId = parseUuid(child.getStringOr(ELEMENT_ID_TAG, ""));
                if (elementId == null) {
                    elementId = stableId("element",
                            dimension + "|" + worldPosition.asLong() + "|" + elementSourceIndex);
                }
                elements.add(new ControlConsoleElement(
                    elementId, type, name,
                        child.getFloatOr(ELEMENT_DISTANCE_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_OFFSET_X_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_OFFSET_Y_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_HEIGHT_TAG, 1.0F),
                        child.getFloatOr(ELEMENT_ASPECT_TAG, 1.0F),
                        child.getFloatOr(ELEMENT_YAW_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_PITCH_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_ROLL_TAG, 0.0F),
                        child.getStringOr(ELEMENT_CONTENT_MODE_TAG, legacy.contentMode()),
                        child.getStringOr(ELEMENT_TEXT_TAG, ""),
                        child.getBooleanOr(ELEMENT_FOLLOW_LYRICS_TAG, legacy.followLyrics()),
                        child.getBooleanOr(ELEMENT_SHOW_TRANSLATION_TAG, legacy.showTranslation()),
                        child.getFloatOr(ELEMENT_TEXT_SCALE_TAG, legacy.textScale()),
                        child.getIntOr(ELEMENT_COLOR_TAG, legacy.color()),
                        child.getFloatOr(ELEMENT_VOLUME_TAG, legacy.volume()),
                        child.getIntOr(ELEMENT_CHANNEL_INDEX_TAG, legacy.channelIndex()),
                        child.getFloatOr(ELEMENT_MAX_DISTANCE_TAG, legacy.maxDistance()),
                        child.getBooleanOr("AutoMixJoc", legacy.autoMixJoc()),
                        child.getIntOr(ELEMENT_TRANSLATION_COLOR_TAG, ControlConsoleElement.DEFAULT_TRANSLATION_COLOR),
                        child.getIntOr(ELEMENT_BACKGROUND_COLOR_TAG, ControlConsoleElement.DEFAULT_BACKGROUND_COLOR),
                        parseAlignment(child.getStringOr(ELEMENT_ALIGNMENT_TAG, "CENTER")),
                        child.getFloatOr(ELEMENT_MAX_WIDTH_TAG, ControlConsoleElement.DEFAULT_MAX_WIDTH),
                        child.getBooleanOr(ELEMENT_WRAP_TAG, false),
                        child.getBooleanOr(ELEMENT_ENABLED_TAG, legacy.enabled()),
                        child.getBooleanOr(ELEMENT_LOCKED_TAG, false),
                        child.getFloatOr(ELEMENT_SCALE_X_TAG, ControlConsoleElement.DEFAULT_SCALE),
                        child.getFloatOr(ELEMENT_SCALE_Y_TAG, ControlConsoleElement.DEFAULT_SCALE),
                        child.getFloatOr(ELEMENT_SCALE_Z_TAG, ControlConsoleElement.DEFAULT_SCALE),
                        child.getFloatOr(ELEMENT_PIVOT_X_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_PIVOT_Y_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_PIVOT_Z_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_SKEW_X_BY_Y_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_SKEW_Y_BY_X_TAG, 0.0F),
                        child.getFloatOr(ELEMENT_BRIGHTNESS_TAG, ControlConsoleElement.DEFAULT_BRIGHTNESS)));
            } catch (IllegalArgumentException ignored) {
                // 单个损坏元素不应使整个中控台文档丢失。
            }
        }
        return List.copyOf(elements);
    }

    private static java.util.Set<UUID> readTrustedPlayers(ValueInput input) {
        java.util.LinkedHashSet<UUID> players = new java.util.LinkedHashSet<>();
        for (ValueInput child : input.childrenListOrEmpty(TRUSTED_PLAYERS_TAG)) {
            UUID playerId = parseUuid(child.getStringOr(PLAYER_ID_TAG, ""));
            if (playerId != null) {
                players.add(playerId);
            }
            if (players.size() >= ControlConsoleDocument.MAX_TRUSTED_PLAYERS) {
                break;
            }
        }
        return java.util.Set.copyOf(players);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static ControlConsoleDocument.SourceKind parseSourceKind(String value) {
        if (value == null || value.isBlank()) return null;
        try { return ControlConsoleDocument.SourceKind.valueOf(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static ControlConsoleElement.Alignment parseAlignment(String value) {
        try {
            return ControlConsoleElement.Alignment.parse(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return ControlConsoleElement.Alignment.CENTER;
        }
    }

    private ControlConsoleDocument.SourceKind inferLegacySourceKind(ValueInput documentInput) {
        if (level != null) {
            BlockPos sourcePos = new BlockPos(documentInput.getIntOr(SOURCE_X_TAG, 0),
                    documentInput.getIntOr(SOURCE_Y_TAG, 0), documentInput.getIntOr(SOURCE_Z_TAG, 0));
            if (level.getBlockEntity(sourcePos) instanceof LiveStreamerBlockEntity) {
                return ControlConsoleDocument.SourceKind.LIVE_STREAMER;
            }
        }
        return ControlConsoleDocument.SourceKind.TURNTABLE;
    }

    private static UUID stableId(String namespace, String value) {
        return UUID.nameUUIDFromBytes((namespace + "|" + value).getBytes(StandardCharsets.UTF_8));
    }
}
