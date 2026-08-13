package com.zhongbai233.scene_editor.core.session;

import com.zhongbai233.scene_editor.core.camera.EditorCameraState;
import com.zhongbai233.scene_editor.core.command.CommandStack;
import com.zhongbai233.scene_editor.core.projection.EditorViewport;
import com.zhongbai233.scene_editor.core.scene.SceneDocument;
import com.zhongbai233.scene_editor.core.scene.SceneElement;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Explicitly owned editor session state. It intentionally owns no renderer, window, network or serialization object.
 */
public final class EditorSession<E extends SceneElement> implements AutoCloseable {
    private final CommandStack<SceneDocument<E>> commands;
    private SceneDocument<E> document;
    private EditorCameraState camera;
    private EditorViewport viewport;
    private UUID selectedElementId;
    private boolean closed;

    private EditorSession(SceneDocument<E> document, EditorCameraState camera, EditorViewport viewport,
            int historyCapacity) {
        this.document = Objects.requireNonNull(document, "document");
        this.camera = Objects.requireNonNull(camera, "camera");
        this.viewport = Objects.requireNonNull(viewport, "viewport");
        this.commands = new CommandStack<>(historyCapacity);
    }

    public static <E extends SceneElement> EditorSession<E> open(SceneDocument<E> document,
            EditorCameraState camera, EditorViewport viewport, int historyCapacity) {
        return new EditorSession<>(document, camera, viewport, historyCapacity);
    }

    public SceneDocument<E> document() {
        requireOpen();
        return document;
    }

    public void document(SceneDocument<E> document) {
        requireOpen();
        this.document = Objects.requireNonNull(document, "document");
        if (selectedElementId != null && document.element(selectedElementId).isEmpty()) {
            selectedElementId = null;
        }
    }

    public CommandStack<SceneDocument<E>> commands() {
        requireOpen();
        return commands;
    }

    public EditorCameraState camera() {
        requireOpen();
        return camera;
    }

    public void camera(EditorCameraState camera) {
        requireOpen();
        this.camera = Objects.requireNonNull(camera, "camera");
    }

    public EditorViewport viewport() {
        requireOpen();
        return viewport;
    }

    public void resize(EditorViewport viewport) {
        requireOpen();
        this.viewport = Objects.requireNonNull(viewport, "viewport");
    }

    public Optional<UUID> selectedElementId() {
        requireOpen();
        return Optional.ofNullable(selectedElementId);
    }

    public void select(UUID elementId) {
        requireOpen();
        Objects.requireNonNull(elementId, "elementId");
        if (document.element(elementId).isEmpty()) {
            throw new IllegalArgumentException("scene element is not part of this document: " + elementId);
        }
        selectedElementId = elementId;
    }

    public void clearSelection() {
        requireOpen();
        selectedElementId = null;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        selectedElementId = null;
        commands.clear();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("editor session is closed");
        }
    }
}
