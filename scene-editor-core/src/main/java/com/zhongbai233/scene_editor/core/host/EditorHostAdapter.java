package com.zhongbai233.scene_editor.core.host;

import java.util.Objects;
import java.util.function.Consumer;

/** 宿主适配契约：核心编辑器不感知 Minecraft、NBT、媒体或网络协议。 */
public interface EditorHostAdapter<D, O> {
    D loadDocument();

    ValidationResult validateDraft(D draft);

    void submitOperations(O operations);

    void renderEnvironment(Object renderContext, D draft);

    default void describeProperties(D draft, Consumer<PropertyDescriptor> sink) {
        Objects.requireNonNull(sink, "sink");
    }

    record ValidationResult(boolean valid, String message) {
        public ValidationResult {
            Objects.requireNonNull(message, "message");
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, "");
        }

        public static ValidationResult rejected(String message) {
            return new ValidationResult(false, message);
        }
    }

    record PropertyDescriptor(String id, String label, String unit) {
        public PropertyDescriptor {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(unit, "unit");
        }
    }
}