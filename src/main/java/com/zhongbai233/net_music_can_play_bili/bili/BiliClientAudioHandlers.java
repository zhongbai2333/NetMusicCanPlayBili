package com.zhongbai233.net_music_can_play_bili.bili;

import com.github.tartaricacid.netmusic.client.api.AudioStreamHandlerManager;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

public final class BiliClientAudioHandlers {
    private static final Logger LOGGER = LogUtils.getLogger();

    private BiliClientAudioHandlers() {
    }

    public static void register() {
        AudioStreamHandlerManager.registerHandler(new BiliHttpAudioStreamHandler());
        LOGGER.info("BiliHttpAudioStreamHandler registered with NetMusic");
    }
}
