package com.zhongbai233.net_music_can_play_bili;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliAudioResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliClientAudioHandlers;
import com.zhongbai233.net_music_can_play_bili.bili.BiliConfig;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableClientEvents;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.network.ModernTurntableNetwork;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(NetMusicCanPlayBili.MODID)
public class NetMusicCanPlayBili {
    public static final String MODID = "net_music_can_play_bili";
    private static final Logger LOGGER = LogUtils.getLogger();

    public NetMusicCanPlayBili(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModItems.TABS.register(modEventBus);
        modEventBus.addListener(ModernTurntableNetwork::register);
        modEventBus.addListener(RegisterCapabilitiesEvent.class, this::registerCapabilities);

        modEventBus.addListener(Config::onLoad);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MusicPlayResolverManager.registerResolver(new BiliAudioResolver());
        LOGGER.info("BiliAudioResolver registered with NetMusic");

        if (FMLEnvironment.getDist() == Dist.CLIENT) {
            ModernTurntableClientEvents.register(modEventBus);
            BiliConfig.load();
            BiliClientAudioHandlers.register();
        }
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.Item.BLOCK,
                ModBlockEntities.MODERN_TURNTABLE.get(),
                (turntable, side) -> turntable.getItemHandler());
    }
}
