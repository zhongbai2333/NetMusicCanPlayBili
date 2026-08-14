package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Fetches, selects, and converts Bilibili CC subtitles into NetEase lyric JSON. */
final class BiliSubtitleApi {
    private BiliSubtitleApi() {
    }

    static String getBilingualLyric(BiliApiClient.VideoInfo info, BiliApiClient.SubtitlePreference preference)
            throws Exception {
        List<BiliApiClient.SubtitleInfo> candidates = selectCandidates(getAll(info), preference);
        BiliApiClient.SubtitleInfo chinese = null;
        BiliApiClient.SubtitleInfo english = null;
        BiliApiClient.SubtitleInfo other = null;
        for (BiliApiClient.SubtitleInfo subtitle : candidates) {
            if (chinese == null && isChinese(subtitle.lan())) {
                chinese = subtitle;
            } else if (english == null && isEnglish(subtitle.lan())) {
                english = subtitle;
            } else if (other == null) {
                other = subtitle;
            }
        }
        BiliApiClient.SubtitleInfo primary = chinese != null ? chinese : english != null ? english : other;
        if (primary == null) {
            return null;
        }
        BiliApiClient.SubtitleInfo translation;
        if (primary == chinese) {
            translation = english != null ? english : other;
        } else if (primary == english) {
            translation = chinese != null ? chinese : other;
        } else {
            translation = chinese != null ? chinese : english;
        }
        String primaryLrc = convertSubtitleJsonToLrc(getText(primary.normalizedUrl()));
        String translatedLrc = translation != null
                ? convertSubtitleJsonToLrc(getText(translation.normalizedUrl()))
                : null;
        return buildNetEaseLyricJson(primaryLrc, translatedLrc);
    }

    static List<BiliApiClient.SubtitleInfo> selectCandidates(List<BiliApiClient.SubtitleInfo> all,
            BiliApiClient.SubtitlePreference preference) {
        Objects.requireNonNull(preference, "preference");
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<BiliApiClient.SubtitleInfo> candidates = new ArrayList<>();
        for (BiliApiClient.SubtitleInfo subtitle : all) {
            if (subtitle == null || subtitle.normalizedUrl().isBlank()) {
                continue;
            }
            boolean accept = switch (preference) {
                case HUMAN_ONLY -> !subtitle.isAiGenerated();
                case HUMAN_OR_AI -> true;
                case AI_ONLY -> subtitle.isAiGenerated();
            };
            if (accept) {
                candidates.add(subtitle);
            }
        }
        if (preference == BiliApiClient.SubtitlePreference.HUMAN_OR_AI) {
            candidates.sort((left, right) -> Boolean.compare(left.isAiGenerated(), right.isAiGenerated()));
        }
        return List.copyOf(candidates);
    }

    static List<BiliApiClient.SubtitleInfo> getAll(BiliApiClient.VideoInfo info) throws Exception {
        List<BiliApiClient.SubtitleInfo> subtitles = getFromPlayerApi(info);
        return subtitles.isEmpty() ? getFromViewApi(info) : subtitles;
    }

    static String buildPlaceholder(BiliApiClient.VideoInfo info, String note) {
        String title = info.displayTitle();
        String artists = !info.staffNames().isEmpty() ? String.join(" | ", info.staffNames()) : "";
        if (artists.length() > 30) {
            artists = artists.substring(0, 29) + "\u2026";
        }
        int titleBudget = 52 - (artists.isEmpty() ? 0 : 5 + artists.length());
        if (title.length() > titleBudget) {
            title = title.substring(0, Math.max(8, titleBudget - 1)) + "\u2026";
        }
        StringBuilder original = new StringBuilder(formatLrcTime(0)).append(title);
        if (!artists.isEmpty()) {
            original.append(" By. ").append(artists);
        }
        original.append('\n');
        String translation = formatLrcTime(0) + "\uff08" + note + "\uff09\n";
        return buildNetEaseLyricJson(original.toString(), translation);
    }

    static boolean isAiLanguage(String language) {
        return language != null && language.trim().toLowerCase(Locale.ROOT).startsWith("ai-");
    }

    private static List<BiliApiClient.SubtitleInfo> getFromPlayerApi(BiliApiClient.VideoInfo info) throws Exception {
        Map<String, String> params = signedSubtitleParams(info);
        String url = "https://api.bilibili.com/x/player/wbi/v2?" + BiliWbiSigner.buildQuery(params);
        return parseSubtitleList(getJson(url), "subtitles");
    }

    private static List<BiliApiClient.SubtitleInfo> getFromViewApi(BiliApiClient.VideoInfo info) throws Exception {
        Map<String, String> params = signedSubtitleParams(info);
        String url = "https://api.bilibili.com/x/web-interface/view?" + BiliWbiSigner.buildQuery(params);
        return parseSubtitleList(getJson(url), "list");
    }

    private static Map<String, String> signedSubtitleParams(BiliApiClient.VideoInfo info) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("aid", String.valueOf(info.aid()));
        params.put("cid", String.valueOf(info.cid()));
        info.videoId().putViewParam(params);
        return BiliWbiSigner.signParams(params);
    }

    private static List<BiliApiClient.SubtitleInfo> parseSubtitleList(JsonObject root, String listKey) {
        if (!root.has("data") || root.get("data").isJsonNull()) {
            return List.of();
        }
        JsonObject data = root.getAsJsonObject("data");
        if (!data.has("subtitle") || data.get("subtitle").isJsonNull()) {
            return List.of();
        }
        JsonObject subtitle = data.getAsJsonObject("subtitle");
        JsonArray array = subtitle.has(listKey) && !subtitle.get(listKey).isJsonNull()
                ? subtitle.getAsJsonArray(listKey)
                : null;
        if (array == null) {
            return List.of();
        }
        List<BiliApiClient.SubtitleInfo> subtitles = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject item = element.getAsJsonObject();
            String language = item.has("lan") ? item.get("lan").getAsString() : "unknown";
            String url = item.has("subtitle_url") ? item.get("subtitle_url").getAsString() : "";
            if (!url.isBlank()) {
                subtitles.add(new BiliApiClient.SubtitleInfo(language, url, subtitleIsAi(item, language)));
            }
        }
        return List.copyOf(subtitles);
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET();
        BiliRequestHeaders.applyWebApiHeaders(builder);
        HttpResponse<String> response = BiliApiClient.sendApi(BiliWbiSigner.HTTP, builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "bili-api-json");
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String getText(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET();
        BiliRequestHeaders.applyWebApiHeaders(builder);
        return BiliApiClient.sendApi(BiliWbiSigner.HTTP, builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), "bili-api-text").body();
    }

    private static String convertSubtitleJsonToLrc(String subtitleJson) {
        JsonObject root = JsonParser.parseString(subtitleJson).getAsJsonObject();
        if (!root.has("body") || !root.get("body").isJsonArray()) {
            return null;
        }
        StringBuilder lrc = new StringBuilder();
        for (JsonElement element : root.getAsJsonArray("body")) {
            JsonObject line = element.getAsJsonObject();
            String content = line.has("content") && !line.get("content").isJsonNull()
                    ? line.get("content").getAsString().trim()
                    : "";
            if (!content.isEmpty()) {
                double from = line.has("from") && !line.get("from").isJsonNull()
                        ? line.get("from").getAsDouble()
                        : 0.0D;
                lrc.append(formatLrcTime(from)).append(content).append('\n');
            }
        }
        return lrc.isEmpty() ? null : lrc.toString();
    }

    private static String buildNetEaseLyricJson(String original, String translation) {
        JsonObject result = new JsonObject();
        result.addProperty("code", 200);
        boolean hasTranslation = translation != null && !translation.isBlank();
        String primary = hasTranslation ? translation : original;
        if (primary != null && !primary.isBlank()) {
            JsonObject lrc = new JsonObject();
            lrc.addProperty("lyric", primary);
            result.add("lrc", lrc);
        }
        if (hasTranslation && original != null && !original.isBlank()) {
            JsonObject translated = new JsonObject();
            translated.addProperty("lyric", original);
            result.add("tlyric", translated);
        }
        return result.toString();
    }

    private static boolean subtitleIsAi(JsonObject item, String language) {
        return isAiLanguage(language) || jsonTruthy(item, "ai_status") || jsonTruthy(item, "ai_type");
    }

    private static boolean jsonTruthy(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) {
            return false;
        }
        var value = object.getAsJsonPrimitive(key);
        if (value.isBoolean()) {
            return value.getAsBoolean();
        }
        if (value.isNumber()) {
            return value.getAsInt() != 0;
        }
        String normalized = value.getAsString().trim().toLowerCase(Locale.ROOT);
        return !normalized.isEmpty() && !"0".equals(normalized) && !"false".equals(normalized)
                && !"none".equals(normalized);
    }

    private static boolean isChinese(String language) {
        if (language == null) {
            return false;
        }
        String normalized = normalizeLanguage(language);
        return normalized.startsWith("zh") || normalized.startsWith("yue")
                || normalized.contains("hans") || normalized.contains("hant");
    }

    private static boolean isEnglish(String language) {
        return language != null && normalizeLanguage(language).startsWith("en");
    }

    private static String normalizeLanguage(String language) {
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("ai-") ? normalized.substring(3) : normalized;
    }

    private static String formatLrcTime(double seconds) {
        int totalMilliseconds = (int) Math.round(seconds * 1000.0D);
        int minutes = totalMilliseconds / 60_000;
        int remainingSeconds = totalMilliseconds % 60_000 / 1000;
        int milliseconds = totalMilliseconds % 1000;
        return String.format("[%02d:%02d.%03d]", minutes, remainingSeconds, milliseconds);
    }
}
