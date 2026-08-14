package com.zhongbai233.net_music_can_play_bili.bench;

import static com.zhongbai233.net_music_can_play_bili.bench.NetMusicBenchProvider.requireBlockEntity;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.Config;
import com.zhongbai233.net_music_can_play_bili.bili.BiliLiveStreamResolver;
import com.zhongbai233.net_music_can_play_bili.client.LiveStreamerVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.audio.ClientAudioOutputRegistry;
import com.zhongbai233.net_music_can_play_bili.client.renderer.ControlConsoleRenderer;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoBillboardPreview;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleDocument;
import com.zhongbai233.net_music_can_play_bili.editor.host.controlconsole.document.ControlConsoleElement;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.server.BiliWhitelistManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class RealLiveDeviceTopologyScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor CONSUMERS = new BenchMetricDescriptor(
            "ncpb.real_live.consumers", "count", MetricDirection.NEUTRAL);
    private static final BenchMetricDescriptor AUDIO_MILLIS = new BenchMetricDescriptor(
            "ncpb.real_live.audio_millis", "ms", MetricDirection.HIGHER_IS_BETTER);
    private final String roomId = System.getProperty("ncpb.live.real_bench.room", "8178490").trim();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicReference<BiliLiveStreamResolver.LiveRoom> resolvedRoom = new AtomicReference<>();
    private final AtomicBoolean fixtureReady = new AtomicBoolean();
    private final AtomicBoolean roomAddedByBench = new AtomicBoolean();
    private UUID playerId;
    private BlockPos livePos;
    private BlockPos projectorPos;
    private BlockPos speakerPos;
    private BlockPos consolePos;
    private int stableTicks;

    @Override
    public void setup(BenchClientContext context) {
        if (!BiliLiveStreamResolver.isValidRoomId(roomId)) {
            throw new AssertionError("Invalid real-live Bench room: " + roomId);
        }
        playerId = context.player().getUUID();
        BlockPos origin = context.player().blockPosition().offset(2, 0, 2).immutable();
        livePos = origin;
        projectorPos = origin.offset(2, 0, 0);
        speakerPos = origin.offset(0, 0, 2);
        consolePos = origin.offset(2, 0, 2);
        CompletableFuture.runAsync(() -> {
            try {
                BiliLiveStreamResolver.LiveRoom room = BiliLiveStreamResolver.resolve(roomId);
                if (!room.isLive() || room.streams().isEmpty()) {
                    throw new IOException("Bilibili room " + roomId
                            + " is currently offline or returned no playable streams");
                }
                resolvedRoom.set(room);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
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
                if (Config.enableLinkWhitelist && !BiliWhitelistManager.isAllowed(server, "live:" + roomId)) {
                    BiliWhitelistManager.AddResult added = BiliWhitelistManager.add(server,
                            "https://live.bilibili.com/" + roomId, player);
                    if (added.status() != BiliWhitelistManager.AddResult.Status.ADDED) {
                        throw new AssertionError("Could not temporarily whitelist real-live room: " + added);
                    }
                    roomAddedByBench.set(true);
                }
                for (BlockPos pos : List.of(livePos, projectorPos, speakerPos, consolePos)) {
                    level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
                }
                level.setBlockAndUpdate(livePos, ModBlocks.LIVE_STREAMER.get().defaultBlockState());
                level.setBlockAndUpdate(projectorPos, ModBlocks.VIDEO_PROJECTOR.get().defaultBlockState());
                level.setBlockAndUpdate(speakerPos, ModBlocks.SPEAKER.get().defaultBlockState());
                level.setBlockAndUpdate(consolePos, ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());

                VideoProjectorBlockEntity projector = requireBlockEntity(level, projectorPos,
                        VideoProjectorBlockEntity.class);
                projector.setPreferredQuality(80);
                projector.linkTo(livePos);
                SpeakerBlockEntity speaker = requireBlockEntity(level, speakerPos, SpeakerBlockEntity.class);
                speaker.setChannelIndex(SpeakerBlockEntity.CH_L);
                speaker.setVolume(1.0F);
                speaker.linkTo(livePos);
                ControlConsoleBlockEntity console = requireBlockEntity(level, consolePos,
                        ControlConsoleBlockEntity.class);
                console.linkTo(level.dimension().identifier().toString(), livePos,
                        ControlConsoleDocument.SourceKind.LIVE_STREAMER);
                List<ControlConsoleElement> elements = List.of(
                        ControlConsoleElement.defaultScreen(),
                        new ControlConsoleElement(ControlConsoleElement.Type.AUDIO, "直播音频", 1.4F,
                                0.0F, 0.0F, 0.25F, 1.0F, 0.0F, 0.0F, 0.0F));
                if (!console.replaceDocument(console.document().revision(), "Live Bench " + roomId,
                        32.0D, 16.0D, 32.0D, elements)) {
                    throw new AssertionError("Could not install console screen/audio elements");
                }
                LiveStreamerBlockEntity live = requireBlockEntity(level, livePos, LiveStreamerBlockEntity.class);
                if (!live.setRoomId(level, roomId, player)) {
                    throw new AssertionError("Live streamer rejected Bench room " + roomId);
                }
                live.startLive(level, player);
                if (!player.teleportTo(level, consolePos.getX() + 0.5D, consolePos.getY() + 1.0D,
                        consolePos.getZ() + 3.0D, Set.<Relative>of(), 180.0F, 0.0F, true)) {
                    throw new AssertionError("Could not place player inside console range");
                }
                fixtureReady.set(true);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        if (!fixtureReady.get() || resolvedRoom.get() == null || !clientFixturesReady(context)
                || !context.environment().readiness().ready() || context.frames().sampleCount() < 2) {
            return BenchClientStepResult.CONTINUE;
        }
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        throwIfFailed();
        if (!(context.level().getBlockEntity(livePos) instanceof LiveStreamerBlockEntity live)
                || !live.isPlaying()) {
            return BenchClientStepResult.CONTINUE;
        }
        ClientAudioOutputRegistry.AudioTimeline audio = ClientAudioOutputRegistry.getAudioTimeline(livePos);
        String session = playbackSessionId(audio);
        if (audio.combinedMillis() < 0L || audio.relayRegisteredCount() < 2 || session.isBlank()) {
            return BenchClientStepResult.CONTINUE;
        }
        LiveStreamerVideoClient.sync(livePos, session);
        if (!VideoBillboardPreview.isSessionRunning(session)
                || !VideoBillboardPreview.currentProjectorFrame(projectorPos).hasFrame()
                || !ControlConsoleRenderer.consumerLeaseDiagnostic(consolePos).active()) {
            return BenchClientStepResult.CONTINUE;
        }
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        ClientAudioOutputRegistry.AudioTimeline audio = ClientAudioOutputRegistry.getAudioTimeline(livePos);
        String session = playbackSessionId(audio);
        boolean loaded = audio.combinedMillis() >= 0L && audio.relayRegisteredCount() >= 2
                && !session.isBlank() && VideoBillboardPreview.isSessionRunning(session)
                && VideoBillboardPreview.currentProjectorFrame(projectorPos).hasFrame()
                && ControlConsoleRenderer.consumerLeaseDiagnostic(consolePos).active();
        if (!loaded) {
            stableTicks = 0;
            return BenchClientStepResult.CONTINUE;
        }
        context.metrics().record(CONSUMERS, 4);
        context.metrics().record(AUDIO_MILLIS, audio.combinedMillis());
        return ++stableTicks >= 40 ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        BiliLiveStreamResolver.LiveRoom room = resolvedRoom.get();
        if (room == null || !room.isLive() || room.streams().isEmpty() || stableTicks < 40
                || !clientFixturesReady(context)) {
            throw new AssertionError("Real-live topology did not remain loaded: room=" + room
                    + " stableTicks=" + stableTicks);
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        LiveStreamerVideoClient.clear();
        ClientAudioOutputRegistry.cleanup();
        var server = context.minecraft().getSingleplayerServer();
        if (server != null) {
            server.execute(() -> {
                if (server.overworld().getBlockEntity(livePos) instanceof LiveStreamerBlockEntity live) {
                    live.stopLive();
                }
                for (BlockPos pos : List.of(livePos, projectorPos, speakerPos, consolePos)) {
                    server.overworld().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
                if (roomAddedByBench.get()) {
                    try {
                        BiliWhitelistManager.remove(server, "live:" + roomId);
                    } catch (IOException ignored) {
                    }
                }
            });
        }
    }

    private boolean clientFixturesReady(BenchClientContext context) {
        return context.level().getBlockEntity(livePos) instanceof LiveStreamerBlockEntity
                && context.level().getBlockEntity(projectorPos) instanceof VideoProjectorBlockEntity projector
                && livePos.equals(projector.getLinkedTurntablePos())
                && context.level().getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker
                && livePos.equals(speaker.getLinkedTurntablePos())
                && context.level().getBlockEntity(consolePos) instanceof ControlConsoleBlockEntity console
                && console.document().sourceKind() == ControlConsoleDocument.SourceKind.LIVE_STREAMER;
    }

    private static String playbackSessionId(ClientAudioOutputRegistry.AudioTimeline audio) {
        var sessionId = audio.playbackSessionId();
        return sessionId.isEmpty() ? "" : sessionId.get().value();
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("Real-live device topology failed for room " + roomId, error);
        }
    }
}
