package com.zhongbai233.net_music_can_play_bili;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLE_DEBUG_LOG = BUILDER
            .comment("Whether to enable detailed Bilibili API debug logging")
            .define("enableDebugLog", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enableDebugLog;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        enableDebugLog = ENABLE_DEBUG_LOG.get();
    }
}
