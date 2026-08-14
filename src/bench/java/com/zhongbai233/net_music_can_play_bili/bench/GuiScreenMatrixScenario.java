package com.zhongbai233.net_music_can_play_bili.bench;

import com.zhongbai233.bench.api.BenchMetricDescriptor;
import com.zhongbai233.bench.api.MetricDirection;
import com.zhongbai233.bench.api.neoforge.client.BenchClientContext;
import com.zhongbai233.bench.api.neoforge.client.BenchClientScenario;
import com.zhongbai233.bench.api.neoforge.client.BenchClientStepResult;
import com.zhongbai233.bench.api.neoforge.client.BenchGuiSession;
import com.zhongbai233.net_music_can_play_bili.blockentity.ModernTurntableBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.ControlConsoleBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LiveStreamerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.LyricProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.SpeakerBlockEntity;
import com.zhongbai233.net_music_can_play_bili.blockentity.VideoProjectorBlockEntity;
import com.zhongbai233.net_music_can_play_bili.gui.HolographicScreenConfigTestScreen;
import com.zhongbai233.net_music_can_play_bili.gui.ControlConsoleGuideScreen;
import com.zhongbai233.net_music_can_play_bili.gui.LiveStreamerScreen;
import com.zhongbai233.net_music_can_play_bili.gui.LyricProjectorScreen;
import com.zhongbai233.net_music_can_play_bili.gui.MP4FocusScreen;
import com.zhongbai233.net_music_can_play_bili.gui.MediaToolBindingScreen;
import com.zhongbai233.net_music_can_play_bili.gui.MediaToolReportScreen;
import com.zhongbai233.net_music_can_play_bili.gui.ModernTurntableScreen;
import com.zhongbai233.net_music_can_play_bili.gui.PadFocusScreen;
import com.zhongbai233.net_music_can_play_bili.gui.PadMapScreen;
import com.zhongbai233.net_music_can_play_bili.gui.SpeakerScreen;
import com.zhongbai233.net_music_can_play_bili.gui.VideoPlaceholderDebugScreen;
import com.zhongbai233.net_music_can_play_bili.gui.VideoProjectorScreen;
import com.zhongbai233.net_music_can_play_bili.gui.WhitelistReviewScreen;
import com.zhongbai233.net_music_can_play_bili.gui.WhitelistPreviewScreen;
import com.zhongbai233.net_music_can_play_bili.init.ModBlocks;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistReviewPacket;
import com.zhongbai233.net_music_can_play_bili.network.WhitelistPreviewPacket;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolBindingMenu;
import com.zhongbai233.net_music_can_play_bili.menu.MediaToolReportMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class GuiScreenMatrixScenario implements BenchClientScenario {
    private static final BenchMetricDescriptor SCREENS = new BenchMetricDescriptor(
            "ncpb.gui_matrix.screens", "count", MetricDirection.NEUTRAL);
    private final List<ScreenCase> screens = new ArrayList<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean setupComplete = new AtomicBoolean();
    private BenchGuiSession gui;
    private BlockPos origin;
    private UUID playerId;
    private int index;
    private long openedAtFrame;

    @Override
    public void setup(BenchClientContext context) {
        playerId = context.player().getUUID();
        origin = context.player().blockPosition().offset(2, 0, 2).immutable();
        screens.add(new ScreenCase("turntable", ModernTurntableScreen.class,
                () -> new ModernTurntableScreen(origin)));
        screens.add(new ScreenCase("video-projector", VideoProjectorScreen.class,
                () -> new VideoProjectorScreen(origin.offset(1, 0, 0))));
        screens.add(new ScreenCase("lyric-projector", LyricProjectorScreen.class,
                () -> new LyricProjectorScreen(origin.offset(2, 0, 0))));
        screens.add(new ScreenCase("speaker", SpeakerScreen.class,
                () -> new SpeakerScreen(origin.offset(3, 0, 0))));
        screens.add(new ScreenCase("live-streamer", LiveStreamerScreen.class,
                () -> new LiveStreamerScreen(origin.offset(4, 0, 0))));
        screens.add(new ScreenCase("control-console", ControlConsoleGuideScreen.class,
                () -> new ControlConsoleGuideScreen(origin.offset(5, 0, 0))));
        screens.add(new ScreenCase("mp4-focus", MP4FocusScreen.class,
                () -> new MP4FocusScreen(InteractionHand.MAIN_HAND)));
        screens.add(new ScreenCase("pad-focus", PadFocusScreen.class,
                () -> new PadFocusScreen(InteractionHand.MAIN_HAND)));
        screens.add(new ScreenCase("pad-map", PadMapScreen.class,
                () -> new PadMapScreen(InteractionHand.MAIN_HAND)));
        screens.add(new ScreenCase("holographic-editor", HolographicScreenConfigTestScreen.class,
                HolographicScreenConfigTestScreen::new));
        screens.add(new ScreenCase("video-placeholder", VideoPlaceholderDebugScreen.class,
                VideoPlaceholderDebugScreen::new));
        screens.add(new ScreenCase("whitelist-review", WhitelistReviewScreen.class,
                () -> new WhitelistReviewScreen(new WhitelistReviewPacket(List.of()))));
        screens.add(new ScreenCase("whitelist-preview", WhitelistPreviewScreen.class,
                () -> new WhitelistPreviewScreen(new WhitelistPreviewPacket(UUID.randomUUID(),
                        "Offline Bench Preview", "", "", "", 16, 9, 1, 7, 0, 0L, false))));
        screens.add(new ScreenCase("media-tool-binding", MediaToolBindingScreen.class,
                () -> new MediaToolBindingScreen(new MediaToolBindingMenu(700, context.player().getInventory()),
                        context.player().getInventory(), Component.literal("Media Binding Bench"))));
        screens.add(new ScreenCase("media-tool-report", MediaToolReportScreen.class,
                () -> new MediaToolReportScreen(new MediaToolReportMenu(701, context.player().getInventory()),
                        context.player().getInventory(), Component.literal("Media Report Bench"))));
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
                level.setBlockAndUpdate(origin, ModBlocks.MODERN_TURNTABLE.get().defaultBlockState());
                level.setBlockAndUpdate(origin.offset(1, 0, 0),
                        ModBlocks.VIDEO_PROJECTOR.get().defaultBlockState());
                level.setBlockAndUpdate(origin.offset(2, 0, 0),
                        ModBlocks.LYRIC_PROJECTOR.get().defaultBlockState());
                level.setBlockAndUpdate(origin.offset(3, 0, 0), ModBlocks.SPEAKER.get().defaultBlockState());
                level.setBlockAndUpdate(origin.offset(4, 0, 0),
                        ModBlocks.LIVE_STREAMER.get().defaultBlockState());
                level.setBlockAndUpdate(origin.offset(5, 0, 0),
                        ModBlocks.CONTROL_CONSOLE.get().defaultBlockState());
                setupComplete.set(true);
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        });
    }

    @Override
    public BenchClientStepResult stabilize(BenchClientContext context) {
        throwIfFailed();
        if (!setupComplete.get() || !fixturesReady(context)) {
            return BenchClientStepResult.CONTINUE;
        }
        if (context.minecraft().screen == null) {
            openCurrent(context);
        }
        return context.frames().sampleCount() > openedAtFrame
                ? BenchClientStepResult.COMPLETE : BenchClientStepResult.CONTINUE;
    }

    @Override
    public BenchClientStepResult warmup(BenchClientContext context) {
        return BenchClientStepResult.COMPLETE;
    }

    @Override
    public BenchClientStepResult measure(BenchClientContext context) {
        throwIfFailed();
        ScreenCase current = screens.get(index);
        if (!current.type().isInstance(context.minecraft().screen)) {
            throw new AssertionError("GUI matrix expected " + current.name() + " but found "
                    + context.minecraft().screen);
        }
        if (context.frames().sampleCount() <= openedAtFrame) {
            return BenchClientStepResult.CONTINUE;
        }
        if (gui == null || !gui.active()) {
            throw new AssertionError("GUI automation session was not active for " + current.name());
        }
        gui.snapshot();
        gui.close();
        gui = null;
        context.minecraft().setScreen(null);
        index++;
        context.metrics().record(SCREENS, index);
        if (index >= screens.size()) {
            return BenchClientStepResult.COMPLETE;
        }
        openCurrent(context);
        return BenchClientStepResult.CONTINUE;
    }

    @Override
    public void verify(BenchClientContext context) {
        throwIfFailed();
        if (index != screens.size() || context.minecraft().screen != null) {
            throw new AssertionError("GUI matrix did not close every Screen: " + index + "/" + screens.size());
        }
    }

    @Override
    public void teardown(BenchClientContext context) {
        context.minecraft().setScreen(null);
        if (gui != null) {
            gui.close();
            gui = null;
        }
        var server = context.minecraft().getSingleplayerServer();
        if (server != null && origin != null) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level() instanceof ServerLevel level) {
                    for (int offset = 0; offset < 6; offset++) {
                        level.setBlockAndUpdate(origin.offset(offset, 0, 0), Blocks.AIR.defaultBlockState());
                    }
                }
            });
        }
    }

    private boolean fixturesReady(BenchClientContext context) {
        return context.level().getBlockEntity(origin) instanceof ModernTurntableBlockEntity
                && context.level().getBlockEntity(origin.offset(1, 0, 0)) instanceof VideoProjectorBlockEntity
                && context.level().getBlockEntity(origin.offset(2, 0, 0)) instanceof LyricProjectorBlockEntity
                && context.level().getBlockEntity(origin.offset(3, 0, 0)) instanceof SpeakerBlockEntity
                && context.level().getBlockEntity(origin.offset(4, 0, 0)) instanceof LiveStreamerBlockEntity
                && context.level().getBlockEntity(origin.offset(5, 0, 0)) instanceof ControlConsoleBlockEntity;
    }

    private void openCurrent(BenchClientContext context) {
        ScreenCase current = screens.get(index);
        context.minecraft().setScreen(current.factory().get());
        gui = context.automation().beginGuiSession(current.type());
        openedAtFrame = context.frames().sampleCount();
    }

    private void throwIfFailed() {
        Throwable error = failure.get();
        if (error != null) {
            throw new AssertionError("GUI Screen matrix fixture failed", error);
        }
    }

    private record ScreenCase(String name, Class<? extends Screen> type,
            java.util.function.Supplier<? extends Screen> factory) {
    }
}
