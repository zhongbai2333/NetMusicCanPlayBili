package com.zhongbai233.net_music_can_play_bili;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliAudioResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliClientAudioHandlers;
import com.zhongbai233.net_music_can_play_bili.bili.BiliConfig;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(NetMusicCanPlayBili.MODID)
public class NetMusicCanPlayBili {
    public static final String MODID = "net_music_can_play_bili";
    private static final Logger LOGGER = LogUtils.getLogger();

    public NetMusicCanPlayBili(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(Config::onLoad);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MusicPlayResolverManager.registerResolver(new BiliAudioResolver());
        LOGGER.info("BiliAudioResolver registered with NetMusic");

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            BiliConfig.load();
            BiliClientAudioHandlers.register();
        }
    }
}
