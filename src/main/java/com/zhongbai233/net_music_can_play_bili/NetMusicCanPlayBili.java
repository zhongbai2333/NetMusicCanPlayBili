package com.zhongbai233.net_music_can_play_bili;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.mojang.logging.LogUtils;
import com.zhongbai233.net_music_can_play_bili.bili.BiliAudioResolver;
import com.zhongbai233.net_music_can_play_bili.bili.BiliClientAudioHandlers;
import com.zhongbai233.net_music_can_play_bili.bili.BiliConfig;
import com.zhongbai233.net_music_can_play_bili.client.ModernTurntableClientEvents;
import com.zhongbai233.net_music_can_play_bili.init.ModAttributes;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.init.ModMenus;
import com.zhongbai233.net_music_can_play_bili.network.ModernTurntableNetwork;
import com.zhongbai233.net_music_can_play_bili.media.stream.TempFileByteSpool;
import com.zhongbai233.net_music_can_play_bili.server.NetMusicBiliServerCommands;
import com.zhongbai233.net_music_can_play_bili.server.NetMusicPermissions;
import com.zhongbai233.net_music_can_play_bili.server.PadMapScopeSync;
import com.zhongbai233.net_music_can_play_bili.server.PlaybackAuditManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
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
        ModAttributes.ATTRIBUTES.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModItems.TABS.register(modEventBus);
        ModMenus.MENU_TYPES.register(modEventBus);
        modEventBus.addListener(ModernTurntableNetwork::register);
        modEventBus.addListener(RegisterCapabilitiesEvent.class, this::registerCapabilities);
        NeoForge.EVENT_BUS.addListener(NetMusicPermissions::onPermissionGather);
        NeoForge.EVENT_BUS.addListener(NetMusicBiliServerCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(PadMapScopeSync::onPlayerLoggedIn);
        registerDevelopmentSelfTests();
        NeoForge.EVENT_BUS.addListener(PlaybackAuditManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(PlaybackAuditManager::onPlayerLoggedIn);

        modEventBus.addListener(Config::onLoad);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MusicPlayResolverManager.registerResolver(new BiliAudioResolver());
        TempFileByteSpool.cleanupOrphanedSpoolFiles();
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

    private static void registerDevelopmentSelfTests() {
        if (!Boolean.getBoolean("ncpb.pad.map.server_self_test")) {
            return;
        }
        try {
            Class<?> selfTest = Class.forName(
                    "com.zhongbai233.net_music_can_play_bili.server.PadMapSamplerServerSelfTest");
            var handler = selfTest.getMethod("onServerStarted",
                    net.neoforged.neoforge.event.server.ServerStartedEvent.class);
            NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStartedEvent.class,
                    event -> invokeDevelopmentSelfTest(handler, event));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to register Pad map server self-test", ex);
        }
    }

    private static void invokeDevelopmentSelfTest(java.lang.reflect.Method handler,
            net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        try {
            handler.invoke(null, event);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to run Pad map server self-test", ex);
        }
    }

}
