package com.zhongbai233.net_music_can_play_bili.client;

import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * Perceptual calibration trigger for matching internal bench timestamps against
 * real screen/audio capture.
 *
 * <p>
 * The Python side records screen + audio before the user presses Enter. This
 * command writes a trigger file,
 * flashes the HUD white for a short interval, and plays a short UI click. The
 * capture script then detects the
 * trigger timestamp, first visible white frame, and first audio peak in the
 * same host clock domain.
 * </p>
 */
@EventBusSubscriber(modid = NetMusicCanPlayBili.MODID, value = Dist.CLIENT)
public final class PerceptualBenchTrigger {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_FLASH_MILLIS = Integer.getInteger("bili.playback.perceptual.flash_ms", 220);
    private static final Path TRIGGER_PATH = Path.of(System.getProperty("bili.playback.perceptual.trigger_file",
            "perceptual-bench-trigger.json"));

    private static volatile long flashUntilNanos;

    private PerceptualBenchTrigger() {
    }

    public static Path triggerPath() {
        return TRIGGER_PATH;
    }

    public static void trigger(String label) {
        long nowNanos = System.nanoTime();
        long wallMillis = System.currentTimeMillis();
        String safeLabel = sanitize(label == null || label.isBlank() ? "manual" : label);
        String runId = safeLabel + "-" + wallMillis;
        flashUntilNanos = nowNanos + Math.max(1L, DEFAULT_FLASH_MILLIS) * 1_000_000L;
        writeTrigger(runId, safeLabel, nowNanos, wallMillis);
        PlaybackLatencyBench.markUser("perceptual-trigger:" + runId);
        playClick();
        LOGGER.info("感知Bench触发: runId={} label={} triggerFile={} nanoTime={} wall={} flashMs={}", runId,
                safeLabel, TRIGGER_PATH.toAbsolutePath(), nowNanos, wallMillis, DEFAULT_FLASH_MILLIS);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(Component.literal("感知Bench已触发: " + runId));
        }
    }

    private static void writeTrigger(String runId, String label, long nanoTime, long wallMillis) {
        try {
            Path parent = TRIGGER_PATH.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String json = "{\n"
                    + "  \"runId\": \"" + escape(runId) + "\",\n"
                    + "  \"label\": \"" + escape(label) + "\",\n"
                    + "  \"nanoTime\": " + nanoTime + ",\n"
                    + "  \"wallMillis\": " + wallMillis + ",\n"
                    + "  \"wallIso\": \"" + Instant.ofEpochMilli(wallMillis) + "\",\n"
                    + "  \"flashMillis\": " + DEFAULT_FLASH_MILLIS + "\n"
                    + "}\n";
            Files.writeString(TRIGGER_PATH, json, StandardCharsets.UTF_8);
        } catch (IOException error) {
            LOGGER.warn("感知Bench触发文件写入失败: {}", error.toString());
        }
    }

    private static void playClick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) {
            return;
        }
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 2.0F, 1.0F));
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        long now = System.nanoTime();
        if (now > flashUntilNanos) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options.hideGui) {
            return;
        }
        GuiGraphicsExtractor graphics = event.getGuiGraphics();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        graphics.fill(0, 0, width, height, 0xFFFFFFFF);
    }

    private static String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "-");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
