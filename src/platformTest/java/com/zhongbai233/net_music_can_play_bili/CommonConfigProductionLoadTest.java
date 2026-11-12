package com.zhongbai233.net_music_can_play_bili;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real TOML parsing, spec correction/cache reload and production onLoad; no FML event bus or user files. */
class CommonConfigProductionLoadTest {
    private Field loaded; private Object previous; private boolean debug,whitelist; private String contact;
    @BeforeEach void capture() throws Exception {
        loaded=ModConfigSpec.class.getDeclaredField("loadedConfig");loaded.setAccessible(true);previous=loaded.get(Config.SPEC);
        debug=Config.enableDebugLog;whitelist=Config.enableLinkWhitelist;contact=Config.linkWhitelistContactPlaceholder;
    }
    @AfterEach void restore() throws Exception {
        loaded.set(Config.SPEC,previous);Config.SPEC.afterReload();Config.enableDebugLog=debug;Config.enableLinkWhitelist=whitelist;Config.linkWhitelistContactPlaceholder=contact;
    }
    private AtomicInteger attach(CommentedConfig config) throws Exception {
        AtomicInteger saves=new AtomicInteger();
        // ILoadedConfig is sealed. Mock its final permitted FML carrier, not an invalid anonymous implementation.
        var carrier=(IConfigSpec.ILoadedConfig)mock(Class.forName("net.neoforged.fml.config.LoadedConfig"));
        when(carrier.config()).thenReturn(config);doAnswer(call -> {saves.incrementAndGet();return null;}).when(carrier).save();
        Config.SPEC.acceptConfig(carrier);return saves;
    }
    @Test void missingKeysAreCorrectedToRealDefaultsBeforeProductionLoad() throws Exception {
        CommentedConfig config=CommentedConfig.inMemory();AtomicInteger saves=attach(config);Config.onLoad(null);
        assertFalse(Config.enableDebugLog);assertFalse(Config.enableLinkWhitelist);assertEquals("OP4",Config.linkWhitelistContactPlaceholder);
        assertEquals(1,saves.get());assertTrue(Config.SPEC.isCorrect(config));
    }
    @Test void actualTomlValuesReachPublishedRuntimeFields() throws Exception {
        var config=new TomlParser().parse("enableDebugLog = true\nenableLinkWhitelist = true\nlinkWhitelistContactPlaceholder = \"测试管理员\"\n");
        attach(config);Config.onLoad(null);assertTrue(Config.enableDebugLog);assertTrue(Config.enableLinkWhitelist);assertEquals("测试管理员",Config.linkWhitelistContactPlaceholder);
    }
    @Test void genuineLoadingAndRepeatedReloadingEventsPublishLatestCorrectedSpecOnly()throws Exception{
        var config=new TomlParser().parse("enableDebugLog = false\nenableLinkWhitelist = true\nlinkWhitelistContactPlaceholder = \"first\"\n");
        var saves=attach(config);assertEquals(1,saves.get(),"acceptConfig corrects absent spec comments once");assertTrue(Config.SPEC.isCorrect(config));var modConfig=mock(ModConfig.class);
        Config.onLoad(new ModConfigEvent.Loading(modConfig));assertFalse(Config.enableDebugLog);assertTrue(Config.enableLinkWhitelist);assertEquals("first",Config.linkWhitelistContactPlaceholder);
        config.set("enableDebugLog",true);config.set("enableLinkWhitelist",false);config.set("linkWhitelistContactPlaceholder","second");Config.SPEC.afterReload();
        var reload=new ModConfigEvent.Reloading(modConfig);Config.onLoad(reload);Config.onLoad(reload);
        assertTrue(Config.enableDebugLog);assertFalse(Config.enableLinkWhitelist);assertEquals("second",Config.linkWhitelistContactPlaceholder);assertEquals(1,saves.get(),"Loading and duplicate Reloading must not add saves beyond initial spec correction");
        config.set("linkWhitelistContactPlaceholder","third");Config.SPEC.afterReload();Config.onLoad(new ModConfigEvent.Reloading(modConfig));assertEquals("third",Config.linkWhitelistContactPlaceholder);assertEquals(1,saves.get());
    }
    @Test void invalidTypesAreCorrectedBeforeWhitelistAuthorizationUsesThem() throws Exception {
        var config=CommentedConfig.inMemory();config.set("enableDebugLog",99);config.set("enableLinkWhitelist","invalid");config.set("linkWhitelistContactPlaceholder",42);
        AtomicInteger saves=attach(config);Config.onLoad(null);assertFalse(Config.enableDebugLog);assertFalse(Config.enableLinkWhitelist);assertEquals("OP4",Config.linkWhitelistContactPlaceholder);assertEquals(1,saves.get());assertTrue(Config.SPEC.isCorrect(config));
    }
}
