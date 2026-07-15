package com.zhongbai233.client_resource_diagnostics;

import java.net.URL;
import java.nio.file.Path;
import java.util.Locale;

final class OwnerResolver {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private OwnerResolver() {
    }

    static String capture() {
        return WALKER.walk(frames -> frames
                .filter(frame -> !isFramework(frame.getClassName()))
                .findFirst()
                .map(frame -> ownerOf(frame.getDeclaringClass()))
                .orElse("unknown"));
    }

    private static boolean isFramework(String name) {
        return name.startsWith("com.zhongbai233.client_resource_diagnostics.")
                || name.startsWith("com.mojang.")
                || name.startsWith("net.minecraft.")
                || name.startsWith("net.neoforged.")
                || name.startsWith("org.spongepowered.")
                || name.startsWith("org.lwjgl.")
                || name.startsWith("java.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.");
    }

    private static String ownerOf(Class<?> type) {
        try {
            URL location = type.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                String file = Path.of(location.toURI()).getFileName().toString();
                if (file.endsWith(".jar")) {
                    return trim(file.substring(0, file.length() - 4).toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception | LinkageError ignored) {
        }
        String name = type.getName();
        int thirdDot = nthIndexOf(name, '.', 3);
        return trim(thirdDot > 0 ? name.substring(0, thirdDot) : name);
    }

    private static int nthIndexOf(String value, char needle, int occurrence) {
        int index = -1;
        for (int i = 0; i < occurrence; i++) {
            index = value.indexOf(needle, index + 1);
            if (index < 0) {
                break;
            }
        }
        return index;
    }

    private static String trim(String value) {
        return value.length() <= 96 ? value : value.substring(0, 96);
    }
}