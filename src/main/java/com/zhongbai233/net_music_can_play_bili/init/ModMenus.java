package com.zhongbai233.net_music_can_play_bili.init;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolBindingMenu;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU,
            NetMusicCanPlayBili.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<MediaToolBindingMenu>> MEDIA_TOOL_BINDING = MENU_TYPES
            .register("media_tool_binding",
                    () -> new MenuType<>((IContainerFactory<MediaToolBindingMenu>) MediaToolBindingMenu::new,
                            FeatureFlags.DEFAULT_FLAGS));

    public static final DeferredHolder<MenuType<?>, MenuType<MediaToolReportMenu>> MEDIA_TOOL_REPORT = MENU_TYPES
            .register("media_tool_report",
                    () -> new MenuType<>((IContainerFactory<MediaToolReportMenu>) MediaToolReportMenu::new,
                            FeatureFlags.DEFAULT_FLAGS));

    private ModMenus() {
    }
}
