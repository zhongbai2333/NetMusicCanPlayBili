package com.zhongbai233.net_music_can_play_bili.client.renderer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlConsoleModelResourceTest {
    @Test
    void blockstateUsesTheBlockbenchModel() {
        JsonObject blockstate = read("assets/net_music_can_play_bili/blockstates/control_console.json");
        assertEquals("net_music_can_play_bili:block/control_console",
                blockstate.getAsJsonObject("variants").getAsJsonObject("").get("model").getAsString());
    }

    @Test
    void itemUsesTheDedicatedControlConsoleModel() {
        JsonObject item = read("assets/net_music_can_play_bili/items/control_console.json");
        assertEquals("net_music_can_play_bili:item/control_console",
                item.getAsJsonObject("model").get("model").getAsString());
        JsonObject model = read("assets/net_music_can_play_bili/models/item/control_console.json");
        assertEquals("net_music_can_play_bili:block/control_console", model.get("parent").getAsString());
    }

    @Test
    void modelHasExpectedTextureAndCrossBlockHeight() {
        JsonObject model = read("assets/net_music_can_play_bili/models/block/control_console.json");
        assertEquals("net_music_can_play_bili:block/control_console",
                model.getAsJsonObject("textures").get("0").getAsString());
        assertEquals(10, model.getAsJsonArray("elements").size());
        double maxY = model.getAsJsonArray("elements").asList().stream()
                .mapToDouble(element -> element.getAsJsonObject().getAsJsonArray("to").get(1).getAsDouble())
                .max().orElseThrow();
        assertTrue(maxY > 16.0D, "the model is intended to extend above its host block");
        assertEquals(29.0D, maxY, 1.0e-6D);
    }

    @Test
    void blockLootDropsTheControlConsoleItem() {
        JsonObject loot = read("data/net_music_can_play_bili/loot_table/blocks/control_console.json");
        assertEquals("minecraft:block", loot.get("type").getAsString());
        assertEquals("net_music_can_play_bili:control_console",
                loot.getAsJsonArray("pools").get(0).getAsJsonObject()
                        .getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
    }

    private static JsonObject read(String path) {
        var stream = ControlConsoleModelResourceTest.class.getClassLoader().getResourceAsStream(path);
        assertTrue(stream != null, "missing resource: " + path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception exception) {
            throw new AssertionError("cannot read resource: " + path, exception);
        }
    }
}
