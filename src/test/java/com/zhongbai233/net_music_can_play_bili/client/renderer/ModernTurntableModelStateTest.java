package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ModernTurntableModelStateTest {
    private static final String ASSET_ROOT = "/assets/net_music_can_play_bili/";

    @Test
    void hasDiscSelectsNoTurntableModelWithoutDependingOnPlayback() {
        JsonObject blockstate = resource("blockstates/modern_turntable.json");
        Map<String, String> models = new HashMap<>();
        for (var entry : blockstate.getAsJsonArray("multipart")) {
            JsonObject part = entry.getAsJsonObject();
            JsonObject when = part.getAsJsonObject("when");
            assertFalse(when.has("playing"), "播放只应控制动态唱片旋转，不应切换机身模型");
            String key = when.get("facing").getAsString() + ":" + when.get("has_disc").getAsString();
            models.put(key, part.getAsJsonObject("apply").get("model").getAsString());
        }

        assertEquals(8, models.size());
        for (String facing : new String[] { "north", "east", "south", "west" }) {
            assertEquals("net_music_can_play_bili:block/modern_turntable", models.get(facing + ":false"));
            assertEquals("net_music_can_play_bili:block/modern_turntable_playing", models.get(facing + ":true"));
        }
    }

    @Test
    void idleModelAddsExactlyEightSharedPivotTurntableStrips() {
        JsonArray idle = resource("models/block/modern_turntable.json").getAsJsonArray("elements");
        JsonArray withDisc = resource("models/block/modern_turntable_playing.json").getAsJsonArray("elements");
        assertEquals(8, idle.size() - withDisc.size());

        int sharedPivotStrips = 0;
        for (var entry : idle) {
            JsonObject rotation = entry.getAsJsonObject().getAsJsonObject("rotation");
            if (rotation == null || !rotation.has("origin")) {
                continue;
            }
            JsonArray origin = rotation.getAsJsonArray("origin");
            if (origin.get(0).getAsDouble() == 9.7449D
                    && origin.get(1).getAsDouble() == 3.6199D
                    && origin.get(2).getAsDouble() == 7.7449D) {
                sharedPivotStrips++;
            }
        }
        assertEquals(8, sharedPivotStrips, "无唱片模型必须保留八根共枢轴薄转盘条");
    }

    private static JsonObject resource(String path) {
        var stream = ModernTurntableModelStateTest.class.getResourceAsStream(ASSET_ROOT + path);
        assertNotNull(stream, "缺少资源：" + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException failure) {
            throw new AssertionError("读取资源失败：" + path, failure);
        }
    }
}
