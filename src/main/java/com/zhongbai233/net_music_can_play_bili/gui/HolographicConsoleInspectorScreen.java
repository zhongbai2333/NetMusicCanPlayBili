package com.zhongbai233.net_music_can_play_bili.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Vector3d;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.SubtitleLayout;
import com.zhongbai233.scene_editor.core.command.StateReplacementCommand;
import com.zhongbai233.net_music_can_play_bili.link.HolographicScreenSettings;
import com.zhongbai233.net_music_can_play_bili.network.ControlConsoleAccessPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Control-console document, inspector widgets, and element-list editing. */
abstract class HolographicConsoleInspectorScreen extends HolographicEditorLifecycleScreen {
    protected HolographicConsoleInspectorScreen(boolean bindEquippedGlasses, BlockPos controlConsolePos) {
        super(bindEquippedGlasses, controlConsolePos);
    }

    protected void applyInitialElementFocus() {
        int target = initialFocusElement;
        initialFocusElement = -1;
        if (!controlConsoleMode) {
            return;
        }
        if (target == -2) {
            terrainPreviewCenterLocal = new Vector3d(0.0D, 0.5D, 0.0D);
            focusControlConsoleCenter();
            syncNumericEditBoxes();
            return;
        }
        if (target < 0 || target >= screens.size()) {
            return;
        }
        selectElement(target);
        PreviewScreenSpec centered = screens.get(target);
        terrainPreviewCenterLocal = new Vector3d(centered.offsetX, 1.55D + centered.offsetY, centered.distance);
        focusSelectedScreen();
        syncNumericEditBoxes();
    }

    protected void addControlConsoleInspectorWidgets() {
        if (selectedScreen < 0) {
            return;
        }
        int panelX = width - CONTROL_RIGHT_PANEL_W;
        int leftX = panelX + 62;
        int rightX = panelX + 164;
        int y = 112;
        int boxW = 54;
        numericDistanceBox = addUnboundedInspectorBox(leftX, y, boxW, "距离", screen().distance, false,
            v -> editSelected("设置距离", selected -> selected.distance = v));
        numericOffsetXBox = addUnboundedInspectorBox(rightX, y, boxW, "位置X", screen().offsetX, false,
            v -> editSelected("设置位置 X", selected -> selected.offsetX = v));
        numericOffsetYBox = addUnboundedInspectorBox(leftX, y + 22, boxW, "位置Y", screen().offsetY, false,
            v -> editSelected("设置位置 Y", selected -> selected.offsetY = v));
        numericHeightBox = addUnboundedInspectorBox(rightX, y + 22, boxW, "高度", screen().height, true,
            v -> editSelected("设置高度", selected -> selected.height = v));
        numericAspectBox = addUnboundedInspectorBox(leftX, y + 44, boxW, "比例", screen().aspect, true,
            v -> editSelected("设置宽高比", selected -> selected.aspect = v));
        numericYawBox = addUnboundedInspectorBox(rightX, y + 44, boxW, "Yaw", screen().yaw, false,
            v -> editSelected("设置 Yaw", selected -> selected.yaw = v));
        numericPitchBox = addUnboundedInspectorBox(leftX, y + 66, boxW, "Pitch", screen().pitch, false,
            v -> editSelected("设置 Pitch", selected -> selected.pitch = v));
        numericRollBox = addUnboundedInspectorBox(rightX, y + 66, boxW, "Roll", screen().roll, false,
            v -> editSelected("设置 Roll", selected -> selected.roll = v));
        boolean editable = selectedElementEditable();
        for (EditBox box : List.of(numericDistanceBox, numericOffsetXBox, numericOffsetYBox,
                numericHeightBox, numericAspectBox, numericYawBox, numericPitchBox, numericRollBox)) {
            box.active = editable;
        }
        PreviewScreenSpec selected = screen();
        addRenderableWidget(new BlackGoldButton(panelX + 12, y - 18, 58, 18,
                Component.literal(showTransformInspector ? "查看内容" : "高级变换"), button -> {
                    showTransformInspector = !showTransformInspector;
                    init();
                }, GOLD_DIM));
        addRenderableWidget(new BlackGoldButton(panelX + 78, y - 18, 132, 18,
                Component.literal(selected.locked ? "🔒 已锁定（点击解锁）" : "🔓 未锁定（点击锁定）"), button -> {
                    edit("切换元素锁定", () -> selected.locked = !selected.locked);
                    init();
                }, GOLD_DIM));
        if (showTransformInspector) {
            numericScaleXBox = addInspectorBox(leftX, y + 94, 0, boxW, "缩放X", screen().scaleX,
                    ControlConsoleElement.MIN_SCALE, ControlConsoleElement.MAX_SCALE,
                    v -> editSelected("设置缩放 X", item -> item.scaleX = v));
            numericScaleYBox = addInspectorBox(rightX, y + 94, 0, boxW, "缩放Y", screen().scaleY,
                    ControlConsoleElement.MIN_SCALE, ControlConsoleElement.MAX_SCALE,
                    v -> editSelected("设置缩放 Y", item -> item.scaleY = v));
            numericScaleZBox = addInspectorBox(leftX, y + 116, 0, boxW, "缩放Z", screen().scaleZ,
                    ControlConsoleElement.MIN_SCALE, ControlConsoleElement.MAX_SCALE,
                    v -> editSelected("设置缩放 Z", item -> item.scaleZ = v));
            numericPivotXBox = addUnboundedInspectorBox(rightX, y + 116, boxW, "枢轴X", screen().pivotX, false,
                    v -> editSelected("设置枢轴 X", item -> item.pivotX = v));
            numericPivotYBox = addUnboundedInspectorBox(leftX, y + 138, boxW, "枢轴Y", screen().pivotY, false,
                    v -> editSelected("设置枢轴 Y", item -> item.pivotY = v));
            numericPivotZBox = addUnboundedInspectorBox(rightX, y + 138, boxW, "枢轴Z", screen().pivotZ, false,
                    v -> editSelected("设置枢轴 Z", item -> item.pivotZ = v));
            numericSkewXByYBox = addInspectorBox(leftX, y + 160, 0, boxW, "X←Y", screen().skewXByY,
                    ControlConsoleElement.MIN_SKEW, ControlConsoleElement.MAX_SKEW,
                    v -> editSelected("设置 X←Y 剪切", item -> item.skewXByY = v));
            numericSkewYByXBox = addInspectorBox(rightX, y + 160, 0, boxW, "Y←X", screen().skewYByX,
                    ControlConsoleElement.MIN_SKEW, ControlConsoleElement.MAX_SKEW,
                    v -> editSelected("设置 Y←X 剪切", item -> item.skewYByX = v));
            for (EditBox box : List.of(numericScaleXBox, numericScaleYBox, numericScaleZBox,
                    numericPivotXBox, numericPivotYBox, numericPivotZBox, numericSkewXByYBox,
                    numericSkewYByXBox)) {
                box.active = editable && screen().type != ElementType.AUDIO;
            }
        } else {
            addElementContentWidgets(panelX + 78, y + 94, screen());
        }
    }

        private void addElementContentWidgets(int x, int y, PreviewScreenSpec selected) {
        if (selected.type == ElementType.SUBTITLE) {
            elementTextBox = addConsoleTextBox(x, y, 132, selected.text,
                value -> editSelected("设置字幕文本", item -> item.text = value));
            elementTextBox.active = !selected.locked;
            BlackGoldButton lyricsButton = new BlackGoldButton(x, y + 22, 64, 18,
                Component.literal(selected.followLyrics ? "歌词：开" : "歌词：关"),
                button -> { editSelected("切换歌词跟随", item -> item.followLyrics = !item.followLyrics); init(); }, GOLD_DIM);
            lyricsButton.active = !selected.locked;
            addRenderableWidget(lyricsButton);
            BlackGoldButton translationButton = new BlackGoldButton(x + 68, y + 22, 64, 18,
                Component.literal(selected.showTranslation ? "翻译：开" : "翻译：关"),
                button -> { editSelected("切换字幕翻译", item -> item.showTranslation = !item.showTranslation); init(); }, GOLD_DIM);
            translationButton.active = !selected.locked;
            addRenderableWidget(translationButton);
            BlackGoldButton modeButton = new BlackGoldButton(x, y + 66, 64, 18,
                Component.literal(subtitleModeLabel(selected.contentMode)), button -> {
                    editSelected("切换字幕模式", HolographicConsoleInspectorScreen::cycleSubtitleMode); init();
                }, GOLD_DIM);
            BlackGoldButton trackButton = new BlackGoldButton(x + 68, y + 66, 64, 18,
                Component.literal(subtitleTrackLabel(selected.contentMode)), button -> {
                    editSelected("切换字幕轨道", item -> {
                        if (SubtitleLayout.isScrollingMode(item.contentMode)) {
                            item.contentMode = SubtitleLayout.toggleScrollingTrack(item.contentMode);
                            item.followLyrics = true;
                        }
                    });
                    init();
                }, GOLD_DIM);
            modeButton.active = !selected.locked;
            trackButton.active = !selected.locked && SubtitleLayout.isScrollingMode(selected.contentMode);
            addRenderableWidget(modeButton);
            addRenderableWidget(trackButton);
            elementTextScaleBox = addUnboundedInspectorBox(x, y + 44, 54, "字号", selected.textScale, true,
                value -> editSelected("设置字幕字号", item -> item.textScale = value));
            elementTextScaleBox.active = !selected.locked;
                elementColorBox = addColorBox(x, y + 88, 64, selected.color,
                    value -> editSelected("设置字幕颜色", item -> item.color = value));
                elementTranslationColorBox = addColorBox(x + 68, y + 88, 64, selected.translationColor,
                    value -> editSelected("设置翻译颜色", item -> item.translationColor = value));
                elementBackgroundColorBox = addColorBox(x, y + 110, 64, selected.backgroundColor,
                    value -> editSelected("设置字幕背景", item -> item.backgroundColor = value));
                elementMaxWidthBox = addUnboundedInspectorBox(x + 68, y + 110, 64, "宽度", selected.maxWidth,
                    false, value -> editSelected("设置字幕宽度", item -> { if (value >= 0.0F) item.maxWidth = value; }));
                BlackGoldButton alignmentButton = new BlackGoldButton(x, y + 132, 64, 18,
                    Component.literal(alignmentLabel(selected.alignment)), button -> {
                    editSelected("切换字幕对齐", item -> item.alignment = nextAlignment(item.alignment)); init();
                    }, GOLD_DIM);
                BlackGoldButton wrapButton = new BlackGoldButton(x + 68, y + 132, 64, 18,
                    Component.literal(selected.wrap ? "换行：开" : "换行：关"), button -> {
                    editSelected("切换字幕换行", item -> item.wrap = !item.wrap); init();
                    }, GOLD_DIM);
                alignmentButton.active = wrapButton.active = !selected.locked;
                elementColorBox.active = elementTranslationColorBox.active = elementBackgroundColorBox.active
                    = elementMaxWidthBox.active = !selected.locked;
                addRenderableWidget(alignmentButton);
                addRenderableWidget(wrapButton);
        } else if (selected.type == ElementType.AUDIO) {
            elementVolumeSlider = new ElementVolumeSlider(x, y, 132, 18, selected.volume);
            elementVolumeSlider.active = !selected.locked;
            addRenderableWidget(elementVolumeSlider);
            BlackGoldButton channelButton = new BlackGoldButton(x, y + 22, 64, 18,
                Component.literal("声道：" + com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media
                    .ControlConsoleMediaSettings.audioChannelLabel(selected.channelIndex)), button -> {
                    editSelected("切换音源声道", item -> item.channelIndex =
                            com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleMediaSettings
                                    .nextAudioChannel(item.channelIndex));
                    init();
                }, GOLD_DIM);
            elementMaxDistanceBox = addUnboundedInspectorBox(x + 68, y + 22, 64, "距离", selected.maxDistance, true,
                value -> editSelected("设置音源距离", item -> item.maxDistance = value));
            elementMaxDistanceBox.active = channelButton.active = !selected.locked;
            addRenderableWidget(channelButton);
            BlackGoldButton audioEnabledButton = new BlackGoldButton(x, y + 44, 64, 18,
                Component.literal(selected.enabled ? "音源：开" : "音源：关"),
                button -> { editSelected("切换音源", item -> item.enabled = !item.enabled); init(); }, GOLD_DIM);
            audioEnabledButton.active = !selected.locked;
            addRenderableWidget(audioEnabledButton);
            BlackGoldButton autoMixButton = new BlackGoldButton(x + 68, y + 44, 64, 18,
                Component.literal(selected.autoMixJoc ? "自动混合：开" : "自动混合：关"),
                button -> { editSelected("切换自动混合", item -> item.autoMixJoc = !item.autoMixJoc); init(); }, GOLD_DIM);
            autoMixButton.active = !selected.locked;
            addRenderableWidget(autoMixButton);
        } else {
            BlackGoldButton sourceButton = new BlackGoldButton(x, y, 64, 18,
                Component.literal("视频：绑定源"), button -> {
                    editSelected("设置屏幕来源", item -> item.contentMode = "SOURCE"); init();
                }, GOLD_DIM);
            sourceButton.active = !selected.locked;
            addRenderableWidget(sourceButton);
            BlackGoldButton screenEnabledButton = new BlackGoldButton(x + 68, y, 64, 18,
                Component.literal(selected.enabled ? "屏幕：开" : "屏幕：关"),
                button -> { editSelected("切换屏幕", item -> item.enabled = !item.enabled); init(); }, GOLD_DIM);
            screenEnabledButton.active = !selected.locked;
            addRenderableWidget(screenEnabledButton);
            BlackGoldButton qualityButton = new BlackGoldButton(x, y + 22, 132, 18,
                Component.literal("画质：" + com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media
                    .ControlConsoleMediaSettings.videoQualityLabel(selected.channelIndex)), button -> {
                    editSelected("切换屏幕画质", item -> item.channelIndex =
                            com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.ControlConsoleMediaSettings
                                    .nextVideoQualityIndex(item.channelIndex));
                    init();
                }, GOLD_DIM);
            qualityButton.active = !selected.locked;
            addRenderableWidget(qualityButton);
        }
        }

        private EditBox addColorBox(int x, int y, int width, int value,
                java.util.function.IntConsumer responder) {
            EditBox box = new EditBox(font, x, y, width, 18, Component.literal("ARGB"));
            box.setValue(String.format(java.util.Locale.ROOT, "%08X", value));
            box.setResponder(text -> {
                String normalized = text.trim().replaceFirst("^(?i)#|0x", "");
                if (normalized.length() == 8) {
                    try {
                        responder.accept((int) Long.parseLong(normalized, 16));
                    } catch (NumberFormatException ignored) {
                    }
                }
            });
            addRenderableWidget(box);
            return box;
        }

        private static String alignmentLabel(ControlConsoleElement.Alignment alignment) {
            return switch (alignment) {
                case LEFT -> "对齐：左";
                case CENTER -> "对齐：中";
                case RIGHT -> "对齐：右";
            };
        }

        private static ControlConsoleElement.Alignment nextAlignment(ControlConsoleElement.Alignment alignment) {
            return switch (alignment) {
                case LEFT -> ControlConsoleElement.Alignment.CENTER;
                case CENTER -> ControlConsoleElement.Alignment.RIGHT;
                case RIGHT -> ControlConsoleElement.Alignment.LEFT;
            };
        }

        private static String subtitleModeLabel(String mode) {
        return switch (mode) {
            case "FIXED" -> "模式：固定";
            case "SCROLL_MAIN", "SCROLL_TRANSLATION" -> "模式：滚动";
            case "AI_SUBTITLE" -> "模式：AI字幕";
            case "LIVE_TITLE" -> "模式：直播标题";
            case "LIVE_ROOM" -> "模式：房间信息";
            case "LIVE_STATUS" -> "模式：直播状态";
            default -> "模式：静态";
        };
        }

        private static String subtitleTrackLabel(String mode) {
        return switch (mode) {
            case "SCROLL_TRANSLATION" -> "轨道：翻译";
            case "SCROLL_MAIN" -> "轨道：主歌词";
            default -> "轨道：--";
        };
        }

        private static void cycleSubtitleMode(PreviewScreenSpec selected) {
        selected.contentMode = SubtitleLayout.nextDisplayMode(selected.contentMode);
        if ("FIXED".equals(selected.contentMode)
                || com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media.LiveSubtitleMetadata
                        .isLiveMode(selected.contentMode)) {
            selected.followLyrics = false;
        } else {
            selected.followLyrics = true;
        }
        }

    protected void addControlConsoleDocumentWidgets() {
        if (selectedScreen >= 0) {
            return;
        }
        ControlConsoleDocument document = currentConsoleDocument();
        if (document == null) {
            return;
        }
        int panelX = width - CONTROL_RIGHT_PANEL_W;
        int x = panelX + 78;
        addConsoleTextBox(x, 62, 132, document.displayName(), text -> {
            String name = text.trim();
            if (!name.isEmpty() && name.length() <= 64) {
                updateConsoleDraft(name, consoleDraft.hardRangeX(), consoleDraft.hardRangeY(), consoleDraft.hardRangeZ());
            }
        });
        int rangeX = panelX + 12;
        addConsoleRangeBox(rangeX, 108, "X", document.hardRangeX(),
                value -> updateConsoleDraft(consoleDraft.displayName(), value, consoleDraft.hardRangeY(), consoleDraft.hardRangeZ()));
        addConsoleRangeBox(rangeX + 62, 108, "Y", document.hardRangeY(),
                value -> updateConsoleDraft(consoleDraft.displayName(), consoleDraft.hardRangeX(), value, consoleDraft.hardRangeZ()));
        addConsoleRangeBox(rangeX + 124, 108, "Z", document.hardRangeZ(),
                value -> updateConsoleDraft(consoleDraft.displayName(), consoleDraft.hardRangeX(), consoleDraft.hardRangeY(), value));
        consoleAccessModeDraft = consoleAccessModeDraft != null ? consoleAccessModeDraft : document.accessMode();
        addRenderableWidget(new BlackGoldButton(rangeX, 152, 94, 20,
                Component.literal(accessModeLabel(consoleAccessModeDraft)), button -> {
                    consoleAccessModeDraft = nextAccessMode(consoleAccessModeDraft);
                    button.setMessage(Component.literal(accessModeLabel(consoleAccessModeDraft)));
                }, GOLD));
        consoleTrustedPlayersBox = addConsoleTextBox(rangeX, 216, 202,
                document.trustedPlayerIds().stream().map(id -> id.toString()).sorted()
                        .collect(java.util.stream.Collectors.joining(",")), ignored -> {
                });
        addRenderableWidget(new BlackGoldButton(rangeX + 102, 152, 100, 20,
                Component.literal("应用权限"), button -> sendConsoleAccessUpdate(), GOLD));
        if (consoleSaveConflict) {
            addRenderableWidget(new BlackGoldButton(rangeX, 242, 132, 20,
                Component.literal("重新加载服务器版"), button -> reloadAuthoritativeConsoleDocument(), GOLD));
        }
    }

    protected void sendConsoleAccessUpdate() {
        if (controlConsolePos == null || consoleDraft == null || consolePendingOperation != null
                || consoleTrustedPlayersBox == null || consoleAccessModeDraft == null) {
            return;
        }
        Set<UUID> trusted;
        try {
            trusted = parseTrustedPlayerIds(consoleTrustedPlayersBox.getValue());
        } catch (IllegalArgumentException invalid) {
            consoleSaveStatus = "可信玩家 UUID 格式无效";
            return;
        }
        UUID operationId = UUID.randomUUID();
        consoleAccessRollback = consoleDraft;
        consoleDraft = new ControlConsoleDocument(consoleDraft.schemaVersion(), consoleDraft.consoleId(), consoleDraft.revision(),
                consoleDraft.ownerId(), consoleAccessModeDraft, trusted, consoleDraft.displayName(),
                consoleDraft.sourceDimension(), consoleDraft.sourceKind(), consoleDraft.sourceX(), consoleDraft.sourceY(), consoleDraft.sourceZ(),
                consoleDraft.hardRangeX(), consoleDraft.hardRangeY(), consoleDraft.hardRangeZ(),
                consoleElementsSnapshot());
        consolePendingOperation = operationId;
        consolePendingFingerprint = consoleDraftFingerprint(consoleDraft);
        consoleSaveStatus = "正在保存权限…";
        UUID leaseId = com.zhongbai233.net_music_can_play_bili.client.ControlConsoleClient.leaseId(controlConsolePos);
        if (leaseId == null) {
            restoreAccessRollback();
            consolePendingOperation = null;
            consoleSaveStatus = "编辑租约不可用";
            return;
        }
        ClientPacketDistributor.sendToServer(new ControlConsoleAccessPacket(controlConsolePos, leaseId, operationId,
                consoleDraft.revision(), consoleAccessModeDraft, trusted));
    }

    protected static Set<UUID> parseTrustedPlayerIds(String text) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        for (String value : text.trim().split("[,;\\s]+")) {
            if (!value.isBlank()) {
                result.add(UUID.fromString(value));
            }
        }
        if (result.size() > ControlConsoleDocument.MAX_TRUSTED_PLAYERS) {
            throw new IllegalArgumentException("too many trusted players");
        }
        return Set.copyOf(result);
    }

    protected static ControlConsoleDocument.AccessMode nextAccessMode(ControlConsoleDocument.AccessMode mode) {
        return switch (mode) {
            case OWNER_ONLY -> ControlConsoleDocument.AccessMode.TRUSTED;
            case TRUSTED -> ControlConsoleDocument.AccessMode.PUBLIC_EDIT;
            case PUBLIC_EDIT -> ControlConsoleDocument.AccessMode.OWNER_ONLY;
        };
    }

    protected static String accessModeLabel(ControlConsoleDocument.AccessMode mode) {
        return switch (mode) {
            case OWNER_ONLY -> "仅所有者";
            case TRUSTED -> "可信玩家";
            case PUBLIC_EDIT -> "公开编辑";
        };
    }

    protected EditBox addConsoleTextBox(int x, int y, int boxWidth, String value,
            java.util.function.Consumer<String> responder) {
        EditBox box = new EditBox(font, x, y, boxWidth, 18, Component.literal("中控台名称"));
        box.setValue(value);
        box.setResponder(responder);
        addRenderableWidget(box);
        return box;
    }

    protected EditBox addConsoleRangeBox(int x, int y, String axis, double value,
            java.util.function.Consumer<Double> responder) {
        EditBox box = new EditBox(font, x, y, 54, 18, Component.literal("范围" + axis));
        box.setValue(fmt((float) value));
        box.setResponder(text -> {
            if (syncingNumericEditBoxes) {
                return;
            }
            try {
                double parsed = Double.parseDouble(text.trim());
                if (Double.isFinite(parsed) && parsed > 0.0D) {
                    responder.accept(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        return box;
    }

    protected EditBox addUnboundedInspectorBox(int x, int y, int boxW, String label, float value,
            boolean positive, java.util.function.Consumer<Float> onApply) {
        EditBox box = new EditBox(font, x, y, boxW, 18, Component.literal(label));
        box.setValue(fmt(value));
        box.setResponder(text -> {
            if (syncingNumericEditBoxes) {
                return;
            }
            try {
                float parsed = Float.parseFloat(text.trim());
                if (Float.isFinite(parsed) && (!positive || parsed > 0.0F)) {
                    onApply.accept(parsed);
                }
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        return box;
    }

    protected ControlConsoleDocument currentConsoleDocument() {
        ControlConsoleDocument document = controlConsoleDocument();
        if (consoleDraft == null && document != null) {
            consoleDraft = document.withInitialScreenIfPristine();
            if (!consoleElementsLoaded) {
                loadConsoleElements(consoleDraft);
            }
            if (consoleDraft != document) {
                // 旧 revision 0 空文档的正式主屏幕属于待保存变更，不能在自动保存初始化时
                // 被误认为已经存在于服务端。
                consoleSavedFingerprint = documentFingerprint(document);
                consoleObservedFingerprint = consoleSavedFingerprint;
                consoleAutosaveFingerprintInitialized = true;
            }
            if (roamingHistoryPending) {
                roamingHistoryPending = false;
                ConsoleProperties properties = new ConsoleProperties(consoleDraft.displayName(),
                        consoleDraft.hardRangeX(), consoleDraft.hardRangeY(), consoleDraft.hardRangeZ());
                EditorSceneState before = new EditorSceneState(
                        consoleDraft.elements().stream().map(HolographicEditorScreenState::snapshot).toList(),
                        -1, properties);
                EditorSceneState after = snapshotScene();
                if (!before.equals(after)) {
                    editHistory.execute(before, new StateReplacementCommand<>(before, after, "世界漫游编辑"));
                }
            }
        }
        return consoleDraft != null ? consoleDraft : document;
    }

    protected static int documentFingerprint(ControlConsoleDocument document) {
        return java.util.Objects.hash(document.displayName(), document.hardRangeX(), document.hardRangeY(),
                document.hardRangeZ(), document.elements());
    }

    protected void ensureConsoleDocumentLoaded() {
        currentConsoleDocument();
    }

    protected void loadConsoleElements(ControlConsoleDocument document) {
        screens.clear();
        for (ControlConsoleElement element : document.elements()) {
            ElementType type = switch (element.type()) {
                case SCREEN -> ElementType.SCREEN;
                case SUBTITLE -> ElementType.SUBTITLE;
                case AUDIO -> ElementType.AUDIO;
            };
            PreviewScreenSpec restored = new PreviewScreenSpec(element.elementId(), type, element.name(), element.distance(),
                    element.offsetX(), element.offsetY(), element.height(), element.aspect(), element.roll());
            restored.yaw = element.yaw();
            restored.pitch = element.pitch();
                restored.contentMode = element.contentMode();
                restored.text = element.text();
                restored.followLyrics = element.followLyrics();
                restored.showTranslation = element.showTranslation();
                restored.textScale = element.textScale();
                restored.color = element.color();
                restored.volume = element.volume();
                restored.channelIndex = element.channelIndex();
                restored.maxDistance = element.maxDistance();
                restored.autoMixJoc = element.autoMixJoc();
                restored.translationColor = element.translationColor();
                restored.backgroundColor = element.backgroundColor();
                restored.alignment = element.alignment();
                restored.maxWidth = element.maxWidth();
                restored.wrap = element.wrap();
                restored.enabled = element.enabled();
                restored.locked = element.locked();
                restored.scaleX = element.scaleX();
                restored.scaleY = element.scaleY();
                restored.scaleZ = element.scaleZ();
                restored.pivotX = element.pivotX();
                restored.pivotY = element.pivotY();
                restored.pivotZ = element.pivotZ();
                restored.skewXByY = element.skewXByY();
                restored.skewYByX = element.skewYByX();
            screens.add(restored);
        }
        consoleElementsLoaded = true;
        selectedScreen = screens.isEmpty() || selectedScreen < 0
            ? -1
            : Math.min(selectedScreen, screens.size() - 1);
    }

    protected void updateConsoleDraft(String name, double rangeX, double rangeY, double rangeZ) {
        ControlConsoleDocument base = currentConsoleDocument();
        if (base == null) {
            return;
        }
        try {
            edit("设置中控台属性", () -> consoleDraft = new ControlConsoleDocument(base.schemaVersion(),
                    base.consoleId(), base.revision(), base.ownerId(), base.accessMode(), base.trustedPlayerIds(), name,
                    base.sourceDimension(), base.sourceKind(), base.sourceX(), base.sourceY(), base.sourceZ(),
                    rangeX, rangeY, rangeZ, consoleElementsSnapshot()));
        } catch (IllegalArgumentException ignored) {
        }
    }

    protected List<ControlConsoleElement> consoleElementsSnapshot() {
        List<ControlConsoleElement> elements = new ArrayList<>(screens.size());
        for (PreviewScreenSpec screen : screens) {
            ControlConsoleElement.Type type = switch (screen.type) {
                case SCREEN -> ControlConsoleElement.Type.SCREEN;
                case SUBTITLE -> ControlConsoleElement.Type.SUBTITLE;
                case AUDIO -> ControlConsoleElement.Type.AUDIO;
            };
            elements.add(new ControlConsoleElement(screen.elementId, type, screen.name, screen.distance, screen.offsetX,
                    screen.offsetY, screen.height, screen.aspect, screen.yaw, screen.pitch, screen.roll,
                    screen.contentMode, screen.text, screen.followLyrics, screen.showTranslation,
                    screen.textScale, screen.color, screen.volume, screen.channelIndex, screen.maxDistance,
                    screen.autoMixJoc, screen.translationColor, screen.backgroundColor, screen.alignment,
                    screen.maxWidth, screen.wrap, screen.enabled, screen.locked,
                    screen.scaleX, screen.scaleY, screen.scaleZ, screen.pivotX, screen.pivotY, screen.pivotZ,
                    screen.skewXByY, screen.skewYByX));
        }
        return List.copyOf(elements);
    }

        private EditBox addInspectorBox(int x, int y, int labelW, int boxW, String label, float value,
            float min, float max, java.util.function.Consumer<Float> onApply) {
        EditBox box = new EditBox(font, x, y, boxW, 18, Component.literal(label));
        box.setValue(fmt(value));
        box.setResponder(text -> {
            if (syncingNumericEditBoxes) {
                return;
            }
            try {
            onApply.accept(HolographicScreenSettings.clamp(Float.parseFloat(text.trim()), min, max));
            } catch (NumberFormatException ignored) {
            }
        });
        addRenderableWidget(box);
        return box;
        }

    protected void addControlConsoleWidgets() {
        int x = 8;
        int listTop = 34;
        int actionTop = Math.max(listTop + 24, height - 60);
        int visibleRows = Math.max(1, (actionTop - listTop - 4) / 24);
        ensureSelectedConsoleElementVisible(visibleRows);
        int first = Math.min(consoleElementScroll, Math.max(0, screens.size() - 1));
        int last = Math.min(screens.size(), first + visibleRows);
        int y = listTop;
        int addButtonWidth = 44;
        int addButtonGap = 2;
        for (int i = first; i < last; i++) {
            final int index = i;
            addRenderableWidget(new BlackGoldButton(x, y, CONTROL_LEFT_PANEL_W - 16, 20,
                    Component.literal((i == selectedScreen ? "◆ " : "  ") + screens.get(i).type.symbol
                        + " " + screens.get(i).name),
                    button -> {
                        selectElement(index);
                        init();
                    }, GOLD));
            y += 24;
        }
        boolean canAdd = canAddConsoleElement();
        BlackGoldButton addScreen = new BlackGoldButton(x, actionTop, addButtonWidth, 20,
                Component.literal("+ 屏幕"), button -> addConsoleElement(ElementType.SCREEN), GOLD);
        addScreen.active = canAdd;
        addRenderableWidget(addScreen);
        BlackGoldButton addSubtitle = new BlackGoldButton(x + addButtonWidth + addButtonGap, actionTop,
                addButtonWidth, 20, Component.literal("+ 字幕"),
                button -> addConsoleElement(ElementType.SUBTITLE), GOLD);
        addSubtitle.active = canAdd;
        addRenderableWidget(addSubtitle);
        BlackGoldButton addAudio = new BlackGoldButton(x + (addButtonWidth + addButtonGap) * 2, actionTop,
                addButtonWidth, 20, Component.literal("+ 音频"),
                button -> addConsoleElement(ElementType.AUDIO), GOLD);
        addAudio.active = canAdd;
        addRenderableWidget(addAudio);
        BlackGoldButton copy = new BlackGoldButton(x, actionTop + 24, 64, 20, Component.literal("复制"), button -> {
            PreviewScreenSpec selected = selectedScreenOrNull();
            if (selected == null || selected.locked || !canAddConsoleElement()) {
                return;
            }
            edit("复制元素", () -> {
                PreviewScreenSpec duplicate = selected.copyWithName(nextElementName(selected.type));
                screens.add(duplicate);
                selectElement(screens.size() - 1);
            });
            init();
        }, GOLD_DIM);
        PreviewScreenSpec selectedForCopy = selectedScreenOrNull();
        copy.active = selectedForCopy != null && !selectedForCopy.locked && canAddConsoleElement();
        addRenderableWidget(copy);
        BlackGoldButton delete = new BlackGoldButton(x + 68, actionTop + 24, 64, 20,
            Component.literal("删除"), button -> removeSelectedConsoleElement(), 0xFFD04040);
        delete.active = selectedForCopy != null && !selectedForCopy.locked;
        addRenderableWidget(delete);
    }

    protected void ensureSelectedConsoleElementVisible(int visibleRows) {
        int maxScroll = Math.max(0, screens.size() - visibleRows);
        consoleElementScroll = Math.clamp(consoleElementScroll, 0, maxScroll);
        if (selectedScreen >= 0 && selectedScreen < consoleElementScroll) {
            consoleElementScroll = selectedScreen;
        } else if (selectedScreen >= consoleElementScroll + visibleRows) {
            consoleElementScroll = Math.min(maxScroll, selectedScreen - visibleRows + 1);
        }
    }

    protected void addConsoleElement(ElementType type) {
        if (!canAddConsoleElement()) {
            return;
        }
        edit("添加" + type.displayName, () -> {
            screens.add(PreviewScreenSpec.defaultsWithName(type, nextElementName(type)));
            selectElement(screens.size() - 1);
        });
        init();
    }

    protected void removeSelectedConsoleElement() {
        PreviewScreenSpec selected = selectedScreenOrNull();
        if (selected == null || selected.locked) {
            return;
        }
        edit("删除元素", () -> {
            int removedIndex = selectedScreen;
            screens.remove(removedIndex);
            selectedScreen = screens.isEmpty() ? -1 : Math.min(removedIndex, screens.size() - 1);
            consoleElementScroll = Math.min(consoleElementScroll, Math.max(0, screens.size() - 1));
        });
        clearFlyKeys();
        init();
    }

    protected boolean canAddConsoleElement() {
        if (!controlConsoleMode) {
            return true;
        }
        if (screens.size() >= ControlConsoleDocument.MAX_ELEMENTS) {
            return false;
        }
        return true;
    }

    protected String nextElementName(ElementType type) {
        long count = screens.stream().filter(element -> element.type == type).count() + 1L;
        return type.displayName + " " + count;
    }

    protected void clearNumericPanelRefs() {
        numericDistanceBox = null;
        numericOffsetXBox = null;
        numericOffsetYBox = null;
        numericHeightBox = null;
        numericAspectBox = null;
        numericRollBox = null;
        numericYawBox = null;
        numericPitchBox = null;
        numericScaleXBox = null;
        numericScaleYBox = null;
        numericScaleZBox = null;
        numericPivotXBox = null;
        numericPivotYBox = null;
        numericPivotZBox = null;
        numericSkewXByYBox = null;
        numericSkewYByXBox = null;
        elementColorBox = null;
        elementTranslationColorBox = null;
        elementBackgroundColorBox = null;
        elementMaxWidthBox = null;
        consoleTrustedPlayersBox = null;
    }

}
