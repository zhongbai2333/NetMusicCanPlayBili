package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.client.HeadphoneClientState;
import com.zhongbai233.net_music_can_play_bili.client.HolographicGlassesClient;
import com.zhongbai233.net_music_can_play_bili.init.ModItems;
import com.zhongbai233.net_music_can_play_bili.item.HolographicGlassesItem;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkData;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.MediaBindingData.MediaSource;
import com.zhongbai233.net_music_can_play_bili.server.MediaBindingCleanupService;
import com.zhongbai233.net_music_can_play_bili.server.MediaEquipmentBindingService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class WearableBindingTopologyScenario implements BenchClientScenario {
    private static final int HEADPHONE_SLOT = 35;
    private static final int GLASSES_SLOT = 34;
    private static final BenchMetricDescriptor BINDINGS = new BenchMetricDescriptor(
            "ncpb.wearable_topology.bindings", "count", MetricDirection.NEUTRAL);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicInteger phase = new AtomicInteger();
    private UUID playerId;
    private UUID mp4Id;
    private UUID padId;
    private BlockPos turntablePos;
    private BlockPos projectorPos;
    private ItemStack originalHead = ItemStack.EMPTY;
    private ItemStack originalHeadphoneSlot = ItemStack.EMPTY;
    private ItemStack originalGlassesSlot = ItemStack.EMPTY;

    @Override
    public void setup(BenchClientContext context) {
        playerId = context.player().getUUID();
        mp4Id = UUID.randomUUID();
        padId = UUID.randomUUID();
        turntablePos = context.player().blockPosition().offset(3, 0, 3).immutable();
        projectorPos = turntablePos.offset(1, 0, 0).immutable();
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new AssertionError("Integrated server is unavailable");
        }
        server.execute(() -> setupBindings(server));
    }

    private void setupBindings(net.minecraft.server.MinecraftServer server) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !(player.level() instanceof ServerLevel level)) {
                throw new IllegalStateException("Integrated server player is unavailable");
            }
            originalHead = player.getItemBySlot(EquipmentSlot.HEAD).copy();
            originalHeadphoneSlot = player.getInventory().getItem(HEADPHONE_SLOT).copy();
            originalGlassesSlot = player.getInventory().getItem(GLASSES_SLOT).copy();
            ItemStack headphones = new ItemStack(ModItems.INVISIBLE_HEADPHONES.get());
            ItemStack glasses = new ItemStack(ModItems.HOLOGRAPHIC_GLASSES.get());
            player.getInventory().setItem(HEADPHONE_SLOT, headphones);
            player.getInventory().setItem(GLASSES_SLOT, glasses);

            MediaSource turntable = MediaBindingCleanupService.turntableSource(level, turntablePos);
            MediaSource mp4 = MediaBindingCleanupService.mp4Source(mp4Id);
            MediaSource pad = MediaBindingCleanupService.padSource(padId);
            MediaSource projector = MediaSource.projector(level.dimension(), projectorPos);
            requireBound(MediaEquipmentBindingService.bind(player, headphones, turntable), "headphone/turntable");
            requireBound(MediaEquipmentBindingService.bind(player, headphones, mp4), "headphone/MP4");
            requireBound(MediaEquipmentBindingService.bind(player, glasses, turntable), "glasses/turntable");
            requireBound(MediaEquipmentBindingService.bind(player, glasses, mp4), "glasses/MP4");
            requireBound(MediaEquipmentBindingService.bind(player, glasses, pad), "glasses/Pad");
            if (!HolographicGlassesItem.addOrUpdateBoundMedia(glasses, projector)) {
                throw new AssertionError("Fourth holographic projector binding was rejected");
            }
            if (!HolographicGlassesItem.addOrUpdateBoundMedia(glasses, mp4)
                    || HolographicGlassesItem.readScreenBindings(glasses).size()
                            != HolographicGlassesItem.MAX_BOUND_MEDIA) {
                throw new AssertionError("Duplicate holographic binding changed the four-slot topology");
            }
            if (HolographicGlassesItem.addOrUpdateBoundMedia(glasses, MediaSource.mp4(UUID.randomUUID()))) {
                throw new AssertionError("Holographic glasses accepted a fifth media binding");
            }
            var stats = MediaBindingCleanupService.countTargetBindings(player, mp4);
            if (stats.headphoneCount() != 1 || stats.holographicCount() != 1
                    || !AudioLinkIndex.hasHeadphoneLinkedToMp4(mp4Id)
                    || !turntablePos.equals(AudioLinkData.readHeadphoneTurntable(headphones))) {
                throw new AssertionError("Wearable binding/index topology is incomplete: " + stats);
            }
            player.setItemSlot(EquipmentSlot.HEAD, headphones);
            player.getInventory().setItem(HEADPHONE_SLOT, ItemStack.EMPTY);
            syncInventory(player);
            phase.set(1);
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        if (phase.get() < 1 || !HeadphoneClientState.equipped()
                || !HeadphoneClientState.handlesTurntable(turntablePos)
                || !HeadphoneClientState.handlesMediaDevice(mp4Id)) {
            return BenchClientStepResult.CONTINUE;
        }
        return context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        int current = phase.get();
        if (current == 1) {
            runOnServer(context, player -> {
                ItemStack glasses = player.getInventory().getItem(GLASSES_SLOT);
                player.getInventory().setItem(HEADPHONE_SLOT, player.getItemBySlot(EquipmentSlot.HEAD));
                player.setItemSlot(EquipmentSlot.HEAD, glasses);
                player.getInventory().setItem(GLASSES_SLOT, ItemStack.EMPTY);
                syncInventory(player);
                phase.set(2);
            });
            return BenchClientStepResult.CONTINUE;
        }
        if (current == 2) {
            if (!HolographicGlassesClient.active() || !HolographicGlassesClient.handlesTurntable(turntablePos)
                    || HolographicGlassesClient.screenBindings().size()
                            != HolographicGlassesItem.MAX_BOUND_MEDIA) {
                return BenchClientStepResult.CONTINUE;
            }
            runOnServer(context, player -> {
                MediaSource mp4 = MediaBindingCleanupService.mp4Source(mp4Id);
                var cleared = MediaBindingCleanupService.clearTargetBindings(player, mp4);
                if (cleared.headphoneCount() != 1 || cleared.holographicCount() != 1
                        || AudioLinkIndex.hasHeadphoneLinkedToMp4(mp4Id)) {
                    throw new AssertionError("Target unlink did not clear both wearable owners: " + cleared);
                }
                phase.set(3);
            });
            return BenchClientStepResult.CONTINUE;
        }
        if (current == 3) {
            if (HolographicGlassesClient.screenBindings().size() != 3
                    || HolographicGlassesClient.screenBindings().stream()
                            .anyMatch(binding -> mp4Id.equals(binding.deviceId()))) {
                return BenchClientStepResult.CONTINUE;
            }
            runOnServer(context, player -> {
                var glassesClear = MediaBindingCleanupService.clearEquipmentBindings(
                        player, player.getItemBySlot(EquipmentSlot.HEAD));
                var headphoneClear = MediaBindingCleanupService.clearEquipmentBindings(
                        player, player.getInventory().getItem(HEADPHONE_SLOT));
                if (glassesClear.holographicCount() != 3 || headphoneClear.headphoneCount() != 1) {
                    throw new AssertionError("Full wearable cleanup mismatch: glasses=" + glassesClear
                            + " headphones=" + headphoneClear);
                }
                player.setItemSlot(EquipmentSlot.HEAD, originalHead.copy());
                player.getInventory().setItem(HEADPHONE_SLOT, originalHeadphoneSlot.copy());
                player.getInventory().setItem(GLASSES_SLOT, originalGlassesSlot.copy());
                syncInventory(player);
                phase.set(4);
            });
            return BenchClientStepResult.CONTINUE;
        }
        if (current == 4 && testBindingsAbsentOnClient()) {
            context.metrics().record(BINDINGS, HolographicGlassesItem.MAX_BOUND_MEDIA + 2);
            phase.set(5);
            return BenchClientStepResult.COMPLETE;
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (phase.get() != 5 || !testBindingsAbsentOnClient()
                || AudioLinkIndex.hasHeadphoneLinkedToMp4(mp4Id)) {
            throw new AssertionError("Wearable topology did not converge after cleanup");
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        runOnServer(context, player -> {
            MediaBindingCleanupService.clearEquipmentBindings(player, player.getItemBySlot(EquipmentSlot.HEAD));
            MediaBindingCleanupService.clearEquipmentBindings(
                    player, player.getInventory().getItem(HEADPHONE_SLOT));
            player.setItemSlot(EquipmentSlot.HEAD, originalHead.copy());
            player.getInventory().setItem(HEADPHONE_SLOT, originalHeadphoneSlot.copy());
            player.getInventory().setItem(GLASSES_SLOT, originalGlassesSlot.copy());
            AudioLinkIndex.updatePlayerHeadphones(player);
            syncInventory(player);
        });
    }

    private void runOnServer(BenchClientContext context, Consumer<ServerPlayer> action) {
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            failure.compareAndSet(null, new AssertionError("Integrated server disappeared"));
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                action.accept(player);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Wearable binding topology failed: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage(), error);
        }
    }

    private boolean testBindingsAbsentOnClient() {
        if (HeadphoneClientState.handlesTurntable(turntablePos)
                || HeadphoneClientState.handlesMediaDevice(mp4Id)
                || HeadphoneClientState.handlesMediaDevice(padId)) {
            return false;
        }
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        MediaSource turntable = MediaSource.turntable(level.dimension(), turntablePos);
        MediaSource projector = MediaSource.projector(level.dimension(), projectorPos);
        MediaSource mp4 = MediaSource.mp4(mp4Id);
        MediaSource pad = MediaSource.pad(padId);
        return HolographicGlassesClient.screenBindings().stream().noneMatch(binding ->
                mp4.equals(binding.source()) || pad.equals(binding.source())
                        || turntable.equals(binding.source()) || projector.equals(binding.source()));
    }

    private static void requireBound(MediaEquipmentBindingService.BindResult result, String label) {
        if (!result.bound() || !result.handledAbility()) {
            throw new AssertionError(label + " was not handled by the formal binding service: " + result);
        }
    }

    private static void syncInventory(ServerPlayer player) {
        player.getInventory().setChanged();
        if (player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
        }
        player.inventoryMenu.broadcastChanges();
    }
}
