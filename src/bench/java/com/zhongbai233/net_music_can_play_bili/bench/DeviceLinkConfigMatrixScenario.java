package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.link.AudioLinkIndex;
import com.zhongbai233.net_music_can_play_bili.link.ClientLinkRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class DeviceLinkConfigMatrixScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor DEVICES = new BenchMetricDescriptor(
            "ncpb.device_matrix.devices", "count", MetricDirection.NEUTRAL);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private final List<BlockPos> fixturePositions = new ArrayList<>();
    private BlockPos turntablePos;
    private BlockPos replacementTurntablePos;
    private BlockPos videoPos;
    private BlockPos lyricPos;
    private BlockPos speakerPos;
    private BlockPos livePos;
    private BlockPos consolePos;
    private UUID playerId;
    private final AtomicInteger linkPhase = new AtomicInteger();

    @Override
    public void setup(BenchClientContext context) {
        playerId = context.player().getUUID();
        BlockPos origin = context.player().blockPosition().offset(2, 0, 2);
        turntablePos = fixture(origin, 0);
        videoPos = fixture(origin, 1);
        lyricPos = fixture(origin, 2);
        speakerPos = fixture(origin, 3);
        livePos = fixture(origin, 4);
        consolePos = fixture(origin, 5);
        replacementTurntablePos = fixture(origin, 6);
        var server = context.minecraft().getSingleplayerServer();
        if (server == null) {
            throw new AssertionError("Integrated server is unavailable");
        }
        server.execute(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null || !(player.level() instanceof ServerLevel level)) {
                    throw new IllegalStateException("Integrated server player is unavailable");
                }
                level.setBlockAndUpdate(turntablePos, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
                level.setBlockAndUpdate(videoPos, ModBlocks.VIDEO_PROJECTOR.get().defaultBlockState());
                level.setBlockAndUpdate(lyricPos, ModBlocks.LYRIC_PROJECTOR.get().defaultBlockState());
                level.setBlockAndUpdate(speakerPos, ModBlocks.SPEAKER.get().defaultBlockState());
                level.setBlockAndUpdate(livePos, ModBlocks.LIVE_STREAMER.get().defaultBlockState());
                level.setBlockAndUpdate(consolePos, ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());
                level.setBlockAndUpdate(replacementTurntablePos,
                        ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());

                VideoProjectorBlockEntity video = require(level, videoPos, VideoProjectorBlockEntity.class);
                video.setProjectionYaw(123.0F);
                video.setProjectionPitch(-17.0F);
                video.setProjectionScale(1.5F);
                video.setProjectionHeight(2.25F);
                video.setProjectionDistanceX(0.75F);
                video.setProjectionDistanceZ(-0.5F);
                video.setPreferredQuality(80);
                video.linkTo(turntablePos);

                LyricProjectorBlockEntity lyric = require(level, lyricPos, LyricProjectorBlockEntity.class);
                lyric.setProjectionYaw(231.0F);
                lyric.setProjectionPitch(14.0F);
                lyric.setProjectionScale(0.75F);
                lyric.setProjectionMode(2);
                lyric.setAllowAi(true);
                lyric.linkTo(turntablePos);

                SpeakerBlockEntity speaker = require(level, speakerPos, SpeakerBlockEntity.class);
                speaker.setChannelIndex(SpeakerBlockEntity.CH_LTF);
                speaker.setVolume(1.25F);
                speaker.setAutoMixJoc(true);
                speaker.linkTo(turntablePos);

                ControlConsoleBlockEntity console = require(level, consolePos, ControlConsoleBlockEntity.class);
                console.linkTo(level.dimension().identifier().toString(), turntablePos);
                if (!AudioLinkIndex.hasSpeakerLinkedTo(level, turntablePos)) {
                    throw new AssertionError("Speaker reverse link index was not registered");
                }
                if (!AudioLinkIndex.hasVideoProjectorLinkedTo(level, turntablePos)) {
                    throw new AssertionError("Video-projector reverse link index was not registered");
                }
                setupComplete.set(true);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    private BlockPos fixture(BlockPos origin, int offset) {
        BlockPos pos = origin.offset(offset, 0, 0).immutable();
        fixturePositions.add(pos);
        return pos;
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        if (!setupComplete.get()) {
            return BenchClientStepResult.CONTINUE;
        }
        return clientDevicesReady(context) && context.frames().sampleCount() >= 2
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        int currentPhase = linkPhase.get();
        if (currentPhase < 0) {
            return BenchClientStepResult.CONTINUE;
        }
        if (!clientDevicesReady(context)) {
            return BenchClientStepResult.CONTINUE;
        }
        BlockPos expectedTurntable = currentPhase >= 1 ? replacementTurntablePos : turntablePos;
        VideoProjectorBlockEntity video = require(context.level(), videoPos, VideoProjectorBlockEntity.class);
        LyricProjectorBlockEntity lyric = require(context.level(), lyricPos, LyricProjectorBlockEntity.class);
        SpeakerBlockEntity speaker = require(context.level(), speakerPos, SpeakerBlockEntity.class);
        ControlConsoleBlockEntity console = require(context.level(), consolePos, ControlConsoleBlockEntity.class);
        requireClose(video.getProjectionYaw(), 123.0F, "video yaw");
        requireClose(video.getProjectionPitch(), -17.0F, "video pitch");
        requireClose(video.getProjectionScale(), 1.5F, "video scale");
        if (!expectedTurntable.equals(video.getLinkedTurntablePos()) || video.getPreferredQuality() != 80) {
            throw new AssertionError("Video projector link/quality did not synchronize");
        }
        if (!expectedTurntable.equals(lyric.getLinkedTurntablePos()) || lyric.getProjectionMode() != 2
                || !lyric.getAllowAi()) {
            throw new AssertionError("Lyric projector link/subtitle settings did not synchronize");
        }
        if (!expectedTurntable.equals(speaker.getLinkedTurntablePos())
                || speaker.getChannelIndex() != SpeakerBlockEntity.CH_LTF || !speaker.isAutoMixJoc()) {
            throw new AssertionError("Speaker link/channel settings did not synchronize");
        }
        requireClose(speaker.getVolume(), 1.25F, "speaker volume");
        if (!console.document().hasSourceBinding()
                || !expectedTurntable.equals(new BlockPos(console.document().sourceX(), console.document().sourceY(),
                        console.document().sourceZ()))) {
            throw new AssertionError("Control-console source binding did not synchronize");
        }
        if (!ClientLinkRegistry.getSources(expectedTurntable).contains(videoPos)) {
            throw new AssertionError("Late client projector link was not registered for playback wakeup");
        }
        if (ClientAudioOutputRegistry.getAudioTimeline(expectedTurntable).relayRegisteredCount() < 1) {
            throw new AssertionError("Speaker audio relay was not registered for its turntable");
        }
        if (!ControlConsoleRenderer.consumerLeaseDiagnostic(consolePos).registered()) {
            throw new AssertionError("Control-console consumer was not registered from the block entity");
        }
        if (currentPhase == 0 && linkPhase.compareAndSet(0, -1)) {
            var server = context.minecraft().getSingleplayerServer();
            if (server == null) {
                throw new AssertionError("Integrated server disappeared before device rebind");
            }
            server.execute(() -> {
                try {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || !(player.level() instanceof ServerLevel level)) {
                        throw new IllegalStateException("Integrated server player is unavailable");
                    }
                    require(level, videoPos, VideoProjectorBlockEntity.class).linkTo(replacementTurntablePos);
                    require(level, lyricPos, LyricProjectorBlockEntity.class).linkTo(replacementTurntablePos);
                    require(level, speakerPos, SpeakerBlockEntity.class).linkTo(replacementTurntablePos);
                    require(level, consolePos, ControlConsoleBlockEntity.class).linkTo(
                            level.dimension().identifier().toString(), replacementTurntablePos);
                    if (AudioLinkIndex.hasSpeakerLinkedTo(level, turntablePos)
                            || AudioLinkIndex.hasVideoProjectorLinkedTo(level, turntablePos)
                            || !AudioLinkIndex.hasSpeakerLinkedTo(level, replacementTurntablePos)
                            || !AudioLinkIndex.hasVideoProjectorLinkedTo(level, replacementTurntablePos)) {
                        throw new AssertionError("Server reverse indexes did not move atomically during rebind");
                    }
                    linkPhase.set(1);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                }
            });
            return BenchClientStepResult.CONTINUE;
        }
        if (currentPhase == 1) {
            if (ClientLinkRegistry.getSources(turntablePos).contains(videoPos)
                    || ClientAudioOutputRegistry.getAudioTimeline(turntablePos).relayRegisteredCount() != 0) {
                return BenchClientStepResult.CONTINUE;
            }
            context.metrics().record(DEVICES, fixturePositions.size());
            linkPhase.set(2);
            return BenchClientStepResult.COMPLETE;
        }
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (linkPhase.get() != 2 || !clientDevicesReady(context)) {
            throw new AssertionError("Device matrix did not remain synchronized through verification");
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        ClientLinkRegistry.clear();
        var server = context.minecraft().getSingleplayerServer();
        if (server != null && !fixturePositions.isEmpty()) {
            List<BlockPos> positions = List.copyOf(fixturePositions);
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    positions.forEach(pos -> level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()));
                }
            });
        }
    }

    private boolean clientDevicesReady(BenchClientContext context) {
        BlockPos expectedTurntable = linkPhase.get() >= 1 ? replacementTurntablePos : turntablePos;
        return context.level().getBlockEntity(turntablePos) instanceof ModernTurntableBlockEntity
                && context.level().getBlockEntity(replacementTurntablePos) instanceof ModernTurntableBlockEntity
                && context.level().getBlockEntity(videoPos) instanceof VideoProjectorBlockEntity video
                && expectedTurntable.equals(video.getLinkedTurntablePos())
                && context.level().getBlockEntity(lyricPos) instanceof LyricProjectorBlockEntity lyric
                && expectedTurntable.equals(lyric.getLinkedTurntablePos())
                && context.level().getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker
                && expectedTurntable.equals(speaker.getLinkedTurntablePos())
                && context.level().getBlockEntity(livePos) instanceof LiveStreamerBlockEntity
                && context.level().getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console
                && console.document().hasSourceBinding()
                && ClientAudioOutputRegistry.getAudioTimeline(expectedTurntable).relayRegisteredCount() >= 1
                && ControlConsoleRenderer.consumerLeaseDiagnostic(consolePos).registered();
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Device link/config matrix failed", error);
        }
    }

    private static void requireClose(float actual, float expected, String label) {
        if (Math.abs(actual - expected) > 0.0001F) {
            throw new AssertionError(label + " mismatch: expected=" + expected + " actual=" + actual);
        }
    }

    private static <T> T require(Level level, BlockPos pos, Class<T> type) {
        Object value = level.getBlockEntity(pos);
        if (!type.isInstance(value)) {
            throw new AssertionError(type.getSimpleName() + " is missing at " + pos + ": " + value);
        }
        return type.cast(value);
    }
}
