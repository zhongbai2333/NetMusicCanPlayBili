package com.zhongbai233.scene_editor.core.api;

import com.zhongbai233.scene_editor.core.scene.SceneElement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Freezes the complete externally accessible Java signature of the Maven artifact. */
class PublicApiCompatibilityTest {
    private static final String API_PREFIX = "com.zhongbai233.scene_editor.core.";

    @Test
    void publicApiMatchesCommittedCompatibilityBaseline() throws Exception {
        String actual = snapshotPublicApi();
        Path report = Path.of("build", "reports", "api", "scene-editor-core-api.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, actual, StandardCharsets.UTF_8);

        String expected;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/scene-editor-core-api.txt"),
                "missing API baseline")) {
            expected = new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        assertEquals(expected, actual,
                "scene-editor-core public API changed; preserve compatibility or intentionally update the baseline");
    }

    private static String snapshotPublicApi() throws IOException, URISyntaxException, ClassNotFoundException {
        Path classes = Path.of(SceneElement.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> classNames = new ArrayList<>();
        try (var paths = Files.walk(classes)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".class"))
                    .map(classes::relativize)
                    .map(Path::toString)
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.').replace('\\', '.'))
                    .filter(name -> name.startsWith(API_PREFIX))
                    .sorted()
                    .forEach(classNames::add);
        }

        List<String> signatures = new ArrayList<>();
        for (String className : classNames) {
            Class<?> type = Class.forName(className, false, SceneElement.class.getClassLoader());
            if (!externallyAccessible(type.getModifiers())) {
                continue;
            }
            signatures.add("TYPE " + type.toGenericString());
            for (Field field : type.getDeclaredFields()) {
                if (externallyAccessible(field.getModifiers()) && !field.isSynthetic()) {
                    signatures.add("FIELD " + modifiers(field) + " " + field.getDeclaringClass().getName() + "#"
                            + field.getName() + ":" + field.getGenericType().getTypeName());
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (externallyAccessible(constructor.getModifiers()) && !constructor.isSynthetic()) {
                    signatures.add("CTOR " + modifiers(constructor) + " " + constructor.toGenericString());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (externallyAccessible(method.getModifiers()) && !method.isSynthetic() && !method.isBridge()) {
                    signatures.add("METHOD " + modifiers(method) + " " + method.toGenericString());
                }
            }
        }
        signatures.sort(Comparator.naturalOrder());
        return String.join("\n", signatures) + "\n";
    }

    private static boolean externallyAccessible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static String modifiers(Member member) {
        return Modifier.toString(member.getModifiers());
    }
}
