package com.zhongbai233.scene_editor.core.scene;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable, host-owned collection of scene elements. Serialization and schema versions remain the host's concern. */
public final class SceneDocument<E extends SceneElement> {
    private final List<E> elements;
    private final Map<UUID, E> byId;

    public SceneDocument(List<? extends E> elements) {
        Objects.requireNonNull(elements, "elements");
        LinkedHashMap<UUID, E> indexed = new LinkedHashMap<>();
        for (E element : elements) {
            E checked = Objects.requireNonNull(element, "element");
            UUID id = Objects.requireNonNull(checked.id(), "element id");
            if (indexed.putIfAbsent(id, checked) != null) {
                throw new IllegalArgumentException("duplicate scene element id: " + id);
            }
            String typeId = Objects.requireNonNull(checked.typeId(), "element typeId");
            if (typeId.isBlank()) {
                throw new IllegalArgumentException("element typeId must not be blank");
            }
            Objects.requireNonNull(checked.transform(), "element transform");
        }
        this.byId = Map.copyOf(indexed);
        this.elements = List.copyOf(indexed.values());
    }

    public static <E extends SceneElement> SceneDocument<E> empty() {
        return new SceneDocument<>(List.of());
    }

    public List<E> elements() {
        return elements;
    }

    public Optional<E> element(UUID id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }

    public SceneDocument<E> withElement(E element) {
        Objects.requireNonNull(element, "element");
        LinkedHashMap<UUID, E> updated = new LinkedHashMap<>(byId);
        updated.put(element.id(), element);
        return new SceneDocument<>(List.copyOf(updated.values()));
    }

    public SceneDocument<E> withoutElement(UUID id) {
        Objects.requireNonNull(id, "id");
        if (!byId.containsKey(id)) {
            return this;
        }
        LinkedHashMap<UUID, E> updated = new LinkedHashMap<>(byId);
        updated.remove(id);
        return new SceneDocument<>(List.copyOf(updated.values()));
    }
}
