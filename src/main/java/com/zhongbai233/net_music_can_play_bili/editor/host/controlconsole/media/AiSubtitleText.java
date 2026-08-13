package com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.media;

/** Resolves AI subtitle text on the authoritative media tick with deterministic safe fallbacks. */
public final class AiSubtitleText {
    private AiSubtitleText() {
    }

    public static Lines resolve(LineLookup aiPrimary, LineLookup aiTranslation,
            String fallbackPrimary, String fallbackTranslation,
            int mediaTick, boolean showTranslation, String fixedFallback) {
        String primary = mediaTick >= 0 && aiPrimary != null ? normalize(aiPrimary.lineAt(mediaTick)) : "";
        String translation = showTranslation && mediaTick >= 0 && aiTranslation != null
                ? normalize(aiTranslation.lineAt(mediaTick)) : "";
        boolean usedFallback = primary.isBlank() && translation.isBlank();
        if (usedFallback) {
            primary = normalize(fallbackPrimary);
            translation = showTranslation ? normalize(fallbackTranslation) : "";
        }
        if (primary.isBlank() && translation.isBlank()) {
            primary = normalize(fixedFallback);
        }
        return new Lines(primary, translation, usedFallback);
    }

    private static String normalize(String value) {
        return value != null ? value : "";
    }

    @FunctionalInterface
    public interface LineLookup {
        String lineAt(int mediaTick);
    }

    public record Lines(String primary, String translation, boolean usedFallback) {
    }
}
