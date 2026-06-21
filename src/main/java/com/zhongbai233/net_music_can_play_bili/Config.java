package com.zhongbai233.net_music_can_play_bili;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.BooleanValue ENABLE_DEBUG_LOG = BUILDER
            .comment("是否启用详细的 B站 API 调试日志")
            .define("enableDebugLog", false);

    private static final ModConfigSpec.BooleanValue ENABLE_LINK_WHITELIST = BUILDER
            .comment("是否启用服务端 BV/av 号与 NetMusic 第三方链接白名单")
            .define("enableLinkWhitelist", false);

    private static final ModConfigSpec.ConfigValue<String> LINK_WHITELIST_CONTACT_PLACEHOLDER = BUILDER
            .comment("玩家无权自行添加白名单时，在拒绝提示中显示的联系人名称")
            .define("linkWhitelistContactPlaceholder", "OP4");

    static final ModConfigSpec SPEC = BUILDER.build();

    public static boolean enableDebugLog;
    public static boolean enableLinkWhitelist;
    public static String linkWhitelistContactPlaceholder;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        enableDebugLog = ENABLE_DEBUG_LOG.get();
        enableLinkWhitelist = ENABLE_LINK_WHITELIST.get();
        linkWhitelistContactPlaceholder = LINK_WHITELIST_CONTACT_PLACEHOLDER.get();
    }
}
