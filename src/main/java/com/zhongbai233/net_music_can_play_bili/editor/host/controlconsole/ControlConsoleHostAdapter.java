package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole;

import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleOperation;
import com.zhongbai233.scene_editor.core.host.EditorHostAdapter;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 中控台的可复用宿主适配器。Minecraft 端只需注入网络加载、提交和环境渲染回调。
 */
public final class ControlConsoleHostAdapter
        implements EditorHostAdapter<ControlConsoleDocument, List<ControlConsoleOperation>> {
    private final Supplier<ControlConsoleDocument> loader;
    private final Consumer<List<ControlConsoleOperation>> submitter;
    private final BiConsumer<Object, ControlConsoleDocument> environmentRenderer;

    public ControlConsoleHostAdapter(Supplier<ControlConsoleDocument> loader,
            Consumer<List<ControlConsoleOperation>> submitter,
            BiConsumer<Object, ControlConsoleDocument> environmentRenderer) {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
        this.environmentRenderer = Objects.requireNonNull(environmentRenderer, "environmentRenderer");
    }

    @Override
    public ControlConsoleDocument loadDocument() {
        return Objects.requireNonNull(loader.get(), "loaded document");
    }

    @Override
    public ValidationResult validateDraft(ControlConsoleDocument draft) {
        if (draft == null) {
            return ValidationResult.rejected("文档不能为空");
        }
        if (draft.schemaVersion() != ControlConsoleDocument.CURRENT_SCHEMA_VERSION) {
            return ValidationResult.rejected("不支持的中控台文档版本");
        }
        return ValidationResult.ok();
    }

    @Override
    public void submitOperations(List<ControlConsoleOperation> operations) {
        Objects.requireNonNull(operations, "operations");
        if (operations.isEmpty() || operations.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("operations must contain at least one non-null operation");
        }
        submitter.accept(List.copyOf(operations));
    }

    @Override
    public void renderEnvironment(Object renderContext, ControlConsoleDocument draft) {
        environmentRenderer.accept(Objects.requireNonNull(renderContext, "renderContext"),
                Objects.requireNonNull(draft, "draft"));
    }

    @Override
    public void describeProperties(ControlConsoleDocument draft, Consumer<PropertyDescriptor> sink) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(sink, "sink");
        sink.accept(new PropertyDescriptor("displayName", "名称", ""));
        sink.accept(new PropertyDescriptor("hardRangeX", "硬范围 X", "方块"));
        sink.accept(new PropertyDescriptor("hardRangeY", "硬范围 Y", "方块"));
        sink.accept(new PropertyDescriptor("hardRangeZ", "硬范围 Z", "方块"));
    }
}
