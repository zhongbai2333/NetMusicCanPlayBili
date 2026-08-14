package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.github.tartaricacid.netmusic.init.InitItems;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.block.ModernTurntableBlock;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class TurntableBlockInteractionScenario implements BenchClientScenario {
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean serverTaskPending = new AtomicBoolean();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private final AtomicBoolean handReady = new AtomicBoolean();
    private final AtomicBoolean firstInsertObserved = new AtomicBoolean();
    private final AtomicBoolean rightClickEjectObserved = new AtomicBoolean();
    private final AtomicBoolean secondInsertObserved = new AtomicBoolean();
    private final AtomicBoolean automationExtracted = new AtomicBoolean();
    private BlockPos turntablePos;
    private ItemStack pendingHand = ItemStack.EMPTY;
    private final AtomicReference<String> lastObservation = new AtomicReference<>("not started");
    private UUID playerId;
    private int phase;
    private int phaseTicks;

    @Override
    public void setup(BenchClientContext context) {
        playerId = context.player().getUUID();
        turntablePos = context.player().blockPosition().offset(2, 0, 2).immutable();
        submitServer(context, (level, player) -> {
            level.setBlockAndUpdate(turntablePos, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
            turntable(level).setVolumePerMille(0);
            setupComplete.set(true);
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        return setupComplete.get() && clientTurntable(context) != null
                && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        phaseTicks++;
        switch (phase) {
            case 0 -> {
                if (!serverTaskPending.get()) {
                    prepareHand(context, disc("bench right-click eject"));
                    advanceTo(1, "waiting for first disc hand sync");
                }
            }
            case 1 -> {
                if (handReady.get()) {
                    interact(context);
                    advanceTo(2, "first use-item-on packet sent");
                }
            }
            case 2 -> {
                if (firstInsertObserved.get() && !serverTaskPending.get()) {
                    prepareHand(context, new ItemStack(Items.STICK));
                    advanceTo(3, "waiting for ordinary-item hand sync");
                } else if (!firstInsertObserved.get()) {
                    probeServer(context, (level, player) -> {
                        ModernTurntableBlockEntity turntable = turntable(level);
                        boolean blockHasDisc = level.getBlockState(turntablePos)
                                .getValue(ModernTurntableBlock.HAS_DISC);
                        boolean blockPlaying = level.getBlockState(turntablePos)
                                .getValue(ModernTurntableBlock.PLAYING);
                        lastObservation.set("first insert: entityHasDisc=" + turntable.hasDisc()
                                + " entityPlaying=" + turntable.isPlaying() + " blockHasDisc=" + blockHasDisc
                                + " blockPlaying=" + blockPlaying + " serverHand="
                                + player.getMainHandItem().getItem());
                        if (turntable.hasDisc() && turntable.isPlaying() && blockHasDisc && blockPlaying) {
                            firstInsertObserved.set(true);
                        }
                    });
                }
            }
            case 3 -> {
                if (handReady.get()) {
                    interact(context);
                    advanceTo(4, "right-click eject packet sent");
                }
            }
            case 4 -> {
                if (rightClickEjectObserved.get() && !serverTaskPending.get()) {
                    prepareHand(context, disc("bench automation extract"));
                    advanceTo(5, "waiting for second disc hand sync");
                } else if (!rightClickEjectObserved.get()) {
                    probeServer(context, (level, player) -> {
                        ModernTurntableBlockEntity turntable = turntable(level);
                        boolean blockHasDisc = level.getBlockState(turntablePos)
                                .getValue(ModernTurntableBlock.HAS_DISC);
                        boolean blockPlaying = level.getBlockState(turntablePos)
                                .getValue(ModernTurntableBlock.PLAYING);
                        lastObservation.set("right-click eject: entityHasDisc=" + turntable.hasDisc()
                                + " entityPlaying=" + turntable.isPlaying() + " blockHasDisc=" + blockHasDisc
                                + " blockPlaying=" + blockPlaying + " serverHand="
                                + player.getMainHandItem().getItem());
                        if (!turntable.hasDisc() && !turntable.isPlaying() && !blockHasDisc && !blockPlaying) {
                            rightClickEjectObserved.set(true);
                        }
                    });
                }
            }
            case 5 -> {
                if (handReady.get()) {
                    interact(context);
                    advanceTo(6, "second use-item-on packet sent");
                }
            }
            case 6 -> {
                if (secondInsertObserved.get() && !serverTaskPending.get()) {
                    extractThroughAutomation(context);
                    advanceTo(7, "automation extraction submitted");
                } else if (!secondInsertObserved.get()) {
                    probeServer(context, (level, player) -> {
                        ModernTurntableBlockEntity turntable = turntable(level);
                        lastObservation.set("second insert: entityHasDisc=" + turntable.hasDisc()
                                + " entityPlaying=" + turntable.isPlaying() + " serverHand="
                                + player.getMainHandItem().getItem());
                        if (turntable.hasDisc() && turntable.isPlaying()) {
                            secondInsertObserved.set(true);
                        }
                    });
                }
            }
            case 7 -> {
                ModernTurntableBlockEntity turntable = clientTurntable(context);
                lastObservation.set("client extraction convergence: extracted=" + automationExtracted.get()
                        + " entity=" + (turntable != null ? "hasDisc=" + turntable.hasDisc()
                                + " playing=" + turntable.isPlaying() : "missing"));
                if (automationExtracted.get() && turntable != null
                        && !turntable.hasDisc() && !turntable.isPlaying()
                        && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.HAS_DISC)
                        && !context.level().getBlockState(turntablePos).getValue(ModernTurntableBlock.PLAYING)) {
                    return BenchClientStepResult.COMPLETE;
                }
            }
            default -> throw new AssertionError("Unexpected turntable interaction phase " + phase);
        }
        if (phaseTicks > 100) {
            throw new AssertionError("Modern turntable interaction stalled in phase " + phase
                    + " after " + phaseTicks + " ticks; " + lastObservation.get());
        }
        return BenchClientStepResult.CONTINUE;
    }

    private void advanceTo(int nextPhase, String observation) {
        phase = nextPhase;
        phaseTicks = 0;
        lastObservation.set(observation);
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (!firstInsertObserved.get() || !rightClickEjectObserved.get()
                || !secondInsertObserved.get() || !automationExtracted.get()) {
            throw new AssertionError("Modern turntable interactions did not all complete: firstInsert="
                    + firstInsertObserved + " rightClickEject=" + rightClickEjectObserved + " secondInsert="
                    + secondInsertObserved + " automationExtract=" + automationExtracted);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        context.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        var server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    level.getEntitiesOfClass(ItemEntity.class, new AABB(turntablePos).inflate(3.0D))
                            .forEach(ItemEntity::discard);
                    level.setBlockAndUpdate(turntablePos, Blocks.AIR.defaultBlockState());
                }
            });
        }
    }

    private void prepareHand(BenchClientContext context, ItemStack stack) {
        pendingHand = stack.copy();
        handReady.set(false);
        context.player().setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
        submitServer(context, (level, player) -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
            handReady.set(true);
        });
    }

    private void interact(BenchClientContext context) {
        handReady.set(false);
        context.player().setItemInHand(InteractionHand.MAIN_HAND, pendingHand.copy());
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(turntablePos), Direction.UP,
                turntablePos, false);
        context.minecraft().gameMode.useItemOn(context.player(), InteractionHand.MAIN_HAND, hit);
    }

    private void extractThroughAutomation(BenchClientContext context) {
        submitServer(context, (level, player) -> {
            ModernTurntableBlockEntity turntable = turntable(level);
            ItemResource resource = turntable.getItemHandler().getResource(0);
            try (Transaction transaction = Transaction.openRoot()) {
                int blocked = turntable.getItemHandler().extract(0, resource, 1, transaction);
                if (blocked != 0) {
                    throw new AssertionError("Default after-playback extraction removed a playing disc");
                }
            }
            turntable.cycleExtractionMode();
            resource = turntable.getItemHandler().getResource(0);
            try (Transaction transaction = Transaction.openRoot()) {
                int extracted = turntable.getItemHandler().extract(0, resource, 1, transaction);
                if (extracted != 1) {
                    throw new AssertionError("Always extraction mode removed " + extracted + " disc(s)");
                }
                transaction.commit();
            }
            if (turntable.hasDisc() || turntable.isPlaying()
                    || level.getBlockState(turntablePos).getValue(ModernTurntableBlock.HAS_DISC)
                    || level.getBlockState(turntablePos).getValue(ModernTurntableBlock.PLAYING)) {
                throw new AssertionError("Automation extraction left turntable state active");
            }
            automationExtracted.set(true);
        });
    }

    private void probeServer(BenchClientContext context, ServerAction action) {
        submitServer(context, action);
    }

    private void submitServer(BenchClientContext context, ServerAction action) {
        if (!serverTaskPending.compareAndSet(false, true)) {
            return;
        }
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            serverTaskPending.set(false);
            failure.compareAndSet(null, new IllegalStateException("Integrated server is unavailable"));
            return;
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                action.run(level, player);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                serverTaskPending.set(false);
            }
        });
    }

    private ModernTurntableBlockEntity turntable(ServerLevel level) {
        if (level.getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable) {
            return turntable;
        }
        throw new AssertionError("Modern turntable block entity is missing at " + turntablePos);
    }

    private ModernTurntableBlockEntity clientTurntable(BenchClientContext context) {
        return context.level().getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity turntable
                ? turntable : null;
    }

    private static ItemStack disc(String name) {
        ItemStack stack = new ItemStack(InitItems.MUSIC_CD.get());
        return ItemMusicCD.setSongInfo(new ItemMusicCD.SongInfo(
                "https://example.test/bench.mp3", name, 120, false), stack);
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Modern turntable block interaction failed", error);
        }
    }

    @FunctionalInterface
    private interface ServerAction {
        void run(ServerLevel level, ServerPlayer player) throws Exception;
    }
}
