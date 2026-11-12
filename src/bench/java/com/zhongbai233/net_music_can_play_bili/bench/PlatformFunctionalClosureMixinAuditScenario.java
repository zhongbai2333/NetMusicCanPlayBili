package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.bridge.SoundManagerBridge;
import com.zhongbai233.net_music_can_play_bili.bridge.SoundEngineBridge;
import com.zhongbai233.net_music_can_play_bili.bridge.LibraryBridge;
import org.spongepowered.asm.mixin.MixinEnvironment;
import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.init.ModAttributes;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.init.ModBlockEntities;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.init.ModMenus;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Requires an actual FML client. This is transformation evidence, not every hook's behavioral acceptance.
 * No external media, login, synthetic frame or audio output is requested by this scenario. */
final class PlatformFunctionalClosureMixinAuditScenario implements BenchClientScenario {
    private record Target(String className, String injectedMethodSuffix) {}
    private static final List<Target> REQUIRED = List.of(
            new Target("com.github.tartaricacid.netmusic.network.message.SetMusicIDMessage", "net_music_can_play_bili$guardBiliWhitelist"),
            new Target("com.github.tartaricacid.netmusic.tileentity.TileEntityMusicPlayer", "net_music_can_play_bili$guardOriginalMusicPlayer"),
            new Target("net.minecraft.client.gui.screens.inventory.AbstractContainerScreen", "net_music_can_play_bili$selectMp4QueueItem"),
            new Target("com.github.tartaricacid.netmusic.client.gui.BigMegaphoneScreen", "net_music_can_play_bili$hookButtons"),
            new Target("net.minecraft.client.gui.components.Button", "net_music_can_play_bili$getOnPress"),
            new Target("com.github.tartaricacid.netmusic.client.gui.CDBurnerMenuScreen", "net_music_can_play_bili$initPageField"),
            new Target("net.minecraft.client.multiplayer.ClientPacketListener", "net_music_can_play_bili$markSingleBlockDirty"),
            new Target("net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent", "net_music_can_play_bili$createMp4QueueTooltip"),
            new Target("net.minecraft.client.player.ClientInput", "net_music_can_play_bili$setMoveVector"),
            new Target("com.github.tartaricacid.netmusic.client.gui.ComputerMenuScreen", "net_music_can_play_bili$initLoginButton"),
            new Target("com.mojang.blaze3d.opengl.GlDevice", "net_music_can_play_bili$bypassIrisProgramOverride"),
            new Target("net.minecraft.client.gui.GuiGraphicsExtractor", "net_music_can_play_bili$guiRenderState"),
            new Target("net.minecraft.client.gui.Gui", "net_music_can_play_bili$hideVanillaCrosshair"),
            new Target("net.minecraft.world.entity.Entity", "net_music_can_play_bili$turnRoamingCamera"),
            new Target("net.minecraft.client.gui.render.GuiRenderer", "net_music_can_play_bili$useOffscreenTarget"),
            new Target("net.minecraft.client.renderer.ItemInHandRenderer", "net_music_can_play_bili$renderMp4AsMap"),
            new Target("net.minecraft.client.KeyboardHandler", "net_music_can_play_bili$consumeRoamingEscape"),
            new Target("com.mojang.blaze3d.audio.Library", "net_music_can_play_bili$context"),
            new Target("com.github.tartaricacid.netmusic.network.client.MusicToClientMessageClient", "net_music_can_play_bili$injectBiliLyrics"),
            new Target("net.minecraft.client.gui.screens.Screen", "net_music_can_play_bili$addRenderableWidget"),
            new Target("net.minecraft.client.sounds.SoundEngine", "net_music_can_play_bili$loaded"),
            new Target("net.minecraft.client.sounds.SoundManager", "net_music_can_play_bili$soundEngine"),
            new Target("com.github.tartaricacid.netmusic.client.audio.NetMusicAudioStream", "ncpbObserveProducerEnd"));
    private static final BenchMetricDescriptor TARGETS = new BenchMetricDescriptor(
            "ncpb.mixin.required_targets_verified", "targets", MetricDirection.HIGHER_IS_BETTER);
    private static final BenchMetricDescriptor OPTIONAL = new BenchMetricDescriptor(
            "ncpb.mixin.minecart_optional_present", "state", MetricDirection.NEUTRAL);
    private boolean verified;

    @Override public void setup(BenchClientContext context) { verified = false; }
    @Override public BenchClientStepResult stabilize(BenchClientContext context) {
        return context.environment().readiness().ready() && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }
    @Override public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }
    @Override public BenchClientStepResult measure(BenchClientContext context) {
        if (!ModList.get().isLoaded(NetMusicCanPlayBili.MODID)
                || !ModList.get().isLoaded("netmusic")) {
            throw new AssertionError("Required physical FML mods are not loaded");
        }
        requireRegistry(ModAttributes.ATTRIBUTES, BuiltInRegistries.ATTRIBUTE);
        requireRegistry(ModBlocks.BLOCKS, BuiltInRegistries.BLOCK);
        requireRegistry(ModBlockEntities.BLOCK_ENTITY_TYPES, BuiltInRegistries.BLOCK_ENTITY_TYPE);
        requireRegistry(ModItems.ITEMS, BuiltInRegistries.ITEM);
        requireRegistry(ModItems.TABS, BuiltInRegistries.CREATIVE_MODE_TAB);
        requireRegistry(ModMenus.MENU_TYPES, BuiltInRegistries.MENU);
        for (Target target : REQUIRED) {
            try { requireInjected(target); }
            catch (ClassNotFoundException missingTarget) {
                throw new AssertionError("Required runtime Mixin target is missing: " + target, missingTarget);
            }
        }
        // Audit target processing in the real transformer after every required target was requested.
        MixinEnvironment.getCurrentEnvironment().audit();
        Object manager = context.minecraft().getSoundManager();
        if (!(manager instanceof SoundManagerBridge bridge)) {
            throw new AssertionError("Actual SoundManager has no injected bridge");
        }
        Object engine = bridge.net_music_can_play_bili$soundEngine();
        if (!(engine instanceof SoundEngineBridge engineBridge)) {
            throw new AssertionError("Actual SoundEngine has no injected bridge");
        }
        if (!(engineBridge.net_music_can_play_bili$library() instanceof LibraryBridge)) {
            throw new AssertionError("Actual sound Library has no injected bridge");
        }
        int optionalPresent = 0;
        try {
            requireInjected(new Target("ml.mypals.minecartrevolution.events.MinecartInteractionEventHandler",
                    "net_music_can_play_bili$clearConsumedProjectorLink"));
            optionalPresent = 1;
        } catch (ClassNotFoundException absentOptionalMod) {
            // Absence is recorded, never counted as passing Minecart Revolution compatibility.
        }
        JsonObject proof = new JsonObject();
        proof.addProperty("proof", "Actual FML registries, transformed hook presence and live sound bridges");
        proof.addProperty("boundary", "Does not prove every hook invocation, rendering or native audio/media playback");
        proof.addProperty("requiredTargetCount", REQUIRED.size());
        proof.addProperty("minecartRevolutionPresent", optionalPresent == 1);
        JsonArray targets = new JsonArray();
        for (Target target : REQUIRED) {
            JsonObject item = new JsonObject();
            item.addProperty("class", target.className());
            item.addProperty("injectedMethodSuffix", target.injectedMethodSuffix());
            targets.add(item);
        }
        proof.add("transformedTargets", targets);
        JsonObject registries = new JsonObject();
        registries.addProperty("attributes", ModAttributes.ATTRIBUTES.getEntries().size());
        registries.addProperty("blocks", ModBlocks.BLOCKS.getEntries().size());
        registries.addProperty("blockEntities", ModBlockEntities.BLOCK_ENTITY_TYPES.getEntries().size());
        registries.addProperty("items", ModItems.ITEMS.getEntries().size());
        registries.addProperty("creativeTabs", ModItems.TABS.getEntries().size());
        registries.addProperty("menus", ModMenus.MENU_TYPES.getEntries().size());
        proof.add("boundEntries", registries);
        try { context.artifacts().write("platform-mixin-audit.json", "application/json", proof.toString()); }
        catch (IOException error) { throw new AssertionError("Cannot persist actual Mixin audit evidence", error); }
        context.metrics().record(TARGETS, REQUIRED.size());
        context.metrics().record(OPTIONAL, optionalPresent);
        verified = true;
        return BenchClientStepResult.COMPLETE;
    }
    @Override public void verify(BenchClientContext context) {
        if (!verified) throw new AssertionError("Real FML/Mixin audit did not execute");
    }
    private static void requireRegistry(DeferredRegister<?> deferred, Registry<?> actual) {
        if (deferred.getEntries().isEmpty()) throw new AssertionError("Expected physical registry has no entries");
        for (var entry : deferred.getEntries()) {
            if (entry.get() == null || actual.getValue(entry.getId()) != entry.get()) {
                throw new AssertionError("FML registry has no matching bound production entry: " + entry.getId());
            }
        }
    }
    private static void requireInjected(Target target) throws ClassNotFoundException {
        Class<?> transformed = Class.forName(target.className(), false,
                PlatformFunctionalClosureMixinAuditScenario.class.getClassLoader());
        boolean present = Arrays.stream(transformed.getDeclaredMethods())
                .anyMatch(method -> method.getName().endsWith(target.injectedMethodSuffix()));
        if (!present) throw new AssertionError("Mixin hook missing on transformed target: " + target);
    }
}
