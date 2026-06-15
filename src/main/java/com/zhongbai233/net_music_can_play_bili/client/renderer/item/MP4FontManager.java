package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class MP4FontManager {
    private static final String FONT_TEST_TEXT = "MP4 播放队列 歌词 时间 确定 拖动边框旋转 0123456789";
    private static final String[] FONT_CANDIDATES = {
            // 用户系统中可能已经安装的跨平台 CJK 等宽字体。
            "Noto Sans Mono CJK SC",
            "Noto Sans Mono CJK TC",
            "Noto Sans Mono CJK JP",
            "Source Han Mono SC",
            "Source Han Mono CN",
            "Sarasa Mono SC",
            "Sarasa Gothic SC",
            "LXGW WenKai Mono",
            "WenQuanYi Micro Hei Mono",
            // Windows 字体。
            "Microsoft YaHei Mono",
            "Cascadia Mono",
            "Consolas",
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "SimSun",
            // macOS 字体。
            "SF Mono",
            "Menlo",
            "Monaco",
            "PingFang SC",
            "Hiragino Sans GB",
            // Linux 桌面默认字体。
            "DejaVu Sans Mono",
            "Liberation Mono",
            "Ubuntu Mono",
            "Noto Sans Mono",
            "WenQuanYi Micro Hei",
            // Java 逻辑字体：通常由平台字体回退支撑。
            Font.MONOSPACED
    };
    private static Font cachedBaseFont;

    private MP4FontManager() {
    }

    static synchronized Font loadBaseFontOrFallback() {
        if (cachedBaseFont != null) {
            return cachedBaseFont;
        }
        Set<String> installedFamilies = installedFontFamilies();
        Font readableFallback = null;
        for (String family : FONT_CANDIDATES) {
            if (!Font.MONOSPACED.equals(family) && !installedFamilies.contains(family.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Font font = new Font(family, Font.PLAIN, 10);
            if (font.canDisplayUpTo(FONT_TEST_TEXT) < 0) {
                cachedBaseFont = font;
                return cachedBaseFont;
            }
            if (readableFallback == null && canDisplayMost(font)) {
                readableFallback = font;
            }
        }
        cachedBaseFont = readableFallback != null ? readableFallback : new Font(Font.MONOSPACED, Font.PLAIN, 10);
        return cachedBaseFont;
    }

    static void warmup() {
        loadBaseFontOrFallback();
    }

    private static Set<String> installedFontFamilies() {
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(Locale.ROOT);
        Set<String> result = new HashSet<>(families.length);
        Arrays.stream(families)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .forEach(result::add);
        return result;
    }

    private static boolean canDisplayMost(Font font) {
        int missing = 0;
        for (int offset = 0; offset < FONT_TEST_TEXT.length();) {
            int codePoint = FONT_TEST_TEXT.codePointAt(offset);
            if (!font.canDisplay(codePoint)) {
                missing++;
            }
            offset += Character.charCount(codePoint);
        }
        return missing <= 2;
    }
}