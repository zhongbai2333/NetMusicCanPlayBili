package com.zhongbai233.net_music_can_play_bili.testsupport;

import com.github.tartaricacid.netmusic.init.InitDataComponent;
import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import com.zhongbai233.net_music_can_play_bili.block.ControlConsoleBlock;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.block.LiveStreamerBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import java.util.Set;
import com.zhongbai233.net_music_can_play_bili.item.PadItem;
import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.HeadlessRegistrySetup;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.util.flag.FeatureFlagLoader;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/** Real vanilla/NeoForge registries for headless serialization tests. No client or server is launched. */
public final class PlatformBootstrap {
    private static boolean initialized;
    private static MP4Item mp4;
    private static PadItem pad;
    private static ControlConsoleBlock console;
    private static ModernTurntableBlock turntable;
    private static LiveStreamerBlock liveStreamer;

    private PlatformBootstrap() { }

    public static synchronized void initialize() {
        if (initialized) return;
        // A JUnit JVM has no FML launcher. Only its environment lookup is replaced; registries/items are real.
        try (MockedStatic<FMLEnvironment> environment = Mockito.mockStatic(FMLEnvironment.class);
             MockedStatic<FeatureFlagLoader> featureFlags = Mockito.mockStatic(FeatureFlagLoader.class)) {
        environment.when(FMLEnvironment::isProduction).thenReturn(true);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        GameData.unfreezeData();
        HeadlessRegistrySetup.initializeVanillaRegistrySync();
        Identifier songInfoId = InitDataComponent.SONG_INFO.getId();
        Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, songInfoId,
                DataComponentType.<ItemMusicCD.SongInfo>builder()
                        .persistent(ItemMusicCD.SongInfo.CODEC)
                        .networkSynchronized(ItemMusicCD.SongInfo.STREAM_CODEC).build());
        Identifier discId = InitItems.MUSIC_CD.getId();
        Registry.register(BuiltInRegistries.ITEM, discId, new ItemMusicCD(discId));
        Identifier mp4Id = Identifier.fromNamespaceAndPath("net_music_can_play_bili", "mp4");
        mp4 = Registry.register(BuiltInRegistries.ITEM, mp4Id,
                new MP4Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, mp4Id))));
        Identifier padId = Identifier.fromNamespaceAndPath("net_music_can_play_bili", "pad");
        pad = Registry.register(BuiltInRegistries.ITEM, padId,
                new PadItem(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, padId))));
        Identifier consoleId = ModBlocks.CONTROL_CONSOLE.getId();
        console = Registry.register(BuiltInRegistries.BLOCK, consoleId, new ControlConsoleBlock(consoleId));
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModBlockEntities.CONTROL_CONSOLE.getId(),
                new BlockEntityType<>(ControlConsoleBlockEntity::new, Set.of(console)));
        Identifier turntableId = ModBlocks.MODERN_TURNTABLE.getId();
        turntable = Registry.register(BuiltInRegistries.BLOCK, turntableId, new ModernTurntableBlock(turntableId));
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModBlockEntities.MODERN_TURNTABLE.getId(),
                new BlockEntityType<>(ModernTurntableBlockEntity::new, Set.of(turntable)));
        Identifier liveId = ModBlocks.LIVE_STREAMER.getId();
        liveStreamer = Registry.register(BuiltInRegistries.BLOCK, liveId, new LiveStreamerBlock(liveId));
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ModBlockEntities.LIVE_STREAMER.getId(),
                new BlockEntityType<>(LiveStreamerBlockEntity::new, Set.of(liveStreamer)));
        GameData.freezeData();
        // 26.1 constructs holder components at data-loading time; apply the genuine initializers and defaults.
        var componentContext = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(componentContext).forEach(components -> components.apply());
        initialized = true;
        }
    }

    public static void init() { initialize(); }
    public static ItemStack mp4Stack() { return mp4(); }
    public static ItemStack disc(String url, int durationSeconds) { return disc(url, url, durationSeconds); }

    public static ItemStack mp4() { initialize(); return new ItemStack(mp4); }
    public static ItemStack pad() { initialize(); return new ItemStack(pad); }
    public static ItemStack disc(String url, String title, int durationSeconds) {
        initialize();
        return ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(url, title, durationSeconds, false),
                new ItemStack(InitItems.MUSIC_CD.get()));
    }
    public static ControlConsoleBlockEntity console(BlockPos position) {
        initialize();
        return new ControlConsoleBlockEntity(position, console.defaultBlockState());
    }
    public static ModernTurntableBlockEntity turntable(BlockPos position) {
        initialize(); return new ModernTurntableBlockEntity(position, turntable.defaultBlockState());
    }
    public static LiveStreamerBlockEntity liveStreamer(BlockPos position) {
        initialize(); return new LiveStreamerBlockEntity(position, liveStreamer.defaultBlockState());
    }
    public static RegistryAccess registries() {
        initialize();
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }
}
