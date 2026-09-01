package com.zhongbai233.net_music_can_play_bili.bili;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.http.HttpResponse;

/** Strict, bounded-diagnostic parser for Bilibili HTTP API envelopes. */
final class BiliApiResponseParser {
    private static final int SUMMARY_LIMIT = 160;

    private BiliApiResponseParser() {
    }

    static JsonObject parse(HttpResponse<String> response, String apiName) throws BiliApiResponseException {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        return parse(apiName, response.statusCode(), contentType, response.body());
    }

    static JsonObject parse(String apiName, int statusCode, String contentType, String responseBody)
            throws BiliApiResponseException {
        String name = apiName == null || apiName.isBlank() ? "unknown" : apiName.trim();
        String type = contentType == null || contentType.isBlank() ? "unknown" : contentType.trim();
        if (statusCode < 200 || statusCode >= 300) {
            throw new BiliApiResponseException("B站 " + name + " API 返回 HTTP " + statusCode
                    + "，Content-Type=" + type + "，响应摘要=" + summary(responseBody));
        }
        if (responseBody == null || responseBody.isBlank()) {
            throw new BiliApiResponseException("B站 " + name + " API 返回空响应：HTTP " + statusCode
                    + "，Content-Type=" + type);
        }

        JsonObject body;
        try {
            JsonElement parsed = JsonParser.parseString(responseBody);
            if (!parsed.isJsonObject()) {
                throw new BiliApiResponseException("B站 " + name + " API 返回的 JSON 不是对象：HTTP "
                        + statusCode + "，Content-Type=" + type + "，响应摘要=" + summary(responseBody));
            }
            body = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException error) {
            throw new BiliApiResponseException("B站 " + name + " API 返回非法 JSON：HTTP " + statusCode
                    + "，Content-Type=" + type + "，响应摘要=" + summary(responseBody), error);
        }
        if (!body.has("code") || body.get("code").isJsonNull() || !body.get("code").isJsonPrimitive()) {
            throw new BiliApiResponseException("B站 " + name + " API 响应缺少 code：HTTP " + statusCode
                    + "，Content-Type=" + type + "，响应摘要=" + summary(responseBody));
        }
        return body;
    }

    private static String summary(String responseBody) {
        if (responseBody == null) {
            return "<null>";
        }
        String normalized = responseBody.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        if (normalized.isEmpty()) {
            return "<blank>";
        }
        return normalized.length() <= SUMMARY_LIMIT
                ? normalized : normalized.substring(0, SUMMARY_LIMIT) + "…";
    }
}
