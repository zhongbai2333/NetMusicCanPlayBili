package com.zhongbai233.net_music_can_play_bili.client;

import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.zhongbai233.net_music_can_play_bili.item.MP4Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** MP4 手持聚焦渲染的纯客户端状态。 */
public final class MP4FocusState {
    public static final String[] QUALITIES = {
            "8K", "4K", "1080P60", "1080P+", "1080P", "720P", "480P", "360P"
    };
    /** 手持 MP4 旋转时使用的设备空间枢轴：靠近中心的下方握持点。 */
    public static final float ROTATION_PIVOT_U = 0.58F;
    public static final float ROTATION_PIVOT_V = 0.88F;
    /** 完全横屏时按可见宽度的这一比例向左平移设备。 */
    public static final float LANDSCAPE_LEFT_SHIFT_FRACTION = 0.50F;
    public static final int QUEUE_SIZE = 18;
    public static final int PORTRAIT_QUEUE_VISIBLE_ROWS = 6;
    public static final int LANDSCAPE_QUEUE_VISIBLE_ROWS = 3;
    public static final int ROTATION_HINT_DURATION_TICKS = 100;

    private static boolean active;
    private static boolean calibrationMode;
    private static InteractionHand hand = InteractionHand.MAIN_HAND;
    private static int ticks;
    private static long lastFrameTickNanos;
    private static boolean playing;
    private static boolean shuffle;
    private static boolean videoEnabled = true;
    private static boolean landscape;
    private static int qualityIndex = 5;
    private static int selectedQueueIndex;
    private static int queueScrollOffset;
    private static final List<String> queueTitles = new ArrayList<>();
    private static final List<Integer> queueDurations = new ArrayList<>();
    private static float volume = 1.0F;
    private static int repeatMode;
    private static boolean showControls;
    private static boolean playlistOpen;
    private static boolean lyricsEnabled;
    private static boolean subtitleMenuOpen;
    private static boolean qualityMenuOpen;
    private static int subtitleMode;
    private static boolean subtitleAiEnabled;
    private static boolean rotationHintShown;
    private static int rotationHintTicks;
    private static int controlsVisibleTicks;
    private static float dragRotationDegrees;
    private static float previousDragRotationDegrees;
    private static boolean rotationAnimating;
    private static float rotationTargetDegrees;
    private static boolean rotationTargetLandscape;
    private static boolean rotationAnimationBaseLandscape;
    private static float hoverX;
    private static float hoverY;
    private static float targetHoverX;
    private static float targetHoverY;
    private static int hoverTextureX = -1;
    private static int hoverTextureY = -1;
    private static String hoverControl = "NONE";
    private static int pressX = -1;
    private static int pressY = -1;
    private static int pressTicks;
    private static boolean scrubbingProgress;
    private static float mediaProgress = 0.0F;
    private static final int CALIBRATION_POINTS = 9;
    private static final float[] calibrationScreenX = new float[CALIBRATION_POINTS];
    private static final float[] calibrationScreenY = new float[CALIBRATION_POINTS];
    private static int calibrationStep;
    private static int calibrationWidth = -1;
    private static int calibrationHeight = -1;
    private static int calibrationRun;

    private MP4FocusState() {
    }

    public static void activate(InteractionHand focusHand) {
        active = true;
        hand = focusHand;
        ticks = 0;
    }

    public static void setCalibrationMode(boolean value) {
        calibrationMode = value;
    }

    public static boolean calibrationMode() {
        return calibrationMode;
    }

    public static void load(MP4Item.State state) {
        load(state, false);
    }

    public static void load(MP4Item.State state, boolean preserveTransientMenus) {
        boolean oldPlaylistOpen = playlistOpen;
        boolean oldSubtitleMenuOpen = subtitleMenuOpen;
        boolean oldQualityMenuOpen = qualityMenuOpen;
        playing = state.playing();
        shuffle = state.shuffle();
        videoEnabled = state.videoEnabled();
        landscape = state.landscape();
        qualityIndex = Math.max(0, Math.min(QUALITIES.length - 1, state.qualityIndex()));
        selectedQueueIndex = Math.max(0, Math.min(QUEUE_SIZE - 1, state.selectedQueueIndex()));
        queueScrollOffset = Math.max(0,
                Math.min(maxQueueScroll(PORTRAIT_QUEUE_VISIBLE_ROWS), state.queueScrollOffset()));
        volume = Math.max(0.0F, Math.min(1.0F, state.volumePerMille() / 1000.0F));
        repeatMode = Math.max(0, Math.min(2, state.repeatMode()));
        playlistOpen = state.playlistOpen();
        lyricsEnabled = state.lyricsEnabled();
        subtitleMode = state.subtitleMode();
        subtitleAiEnabled = state.subtitleAiEnabled();
        if (preserveTransientMenus) {
            playlistOpen = oldPlaylistOpen || playlistOpen;
            subtitleMenuOpen = oldSubtitleMenuOpen;
            qualityMenuOpen = oldQualityMenuOpen;
        } else {
            subtitleMenuOpen = false;
            qualityMenuOpen = false;
        }
        rotationHintShown = state.rotationHintShown();
        mediaProgress = Math.max(0.0F, Math.min(1.0F, state.progressPerMille() / 1000.0F));
        showControls = false;
        controlsVisibleTicks = 0;
        dragRotationDegrees = 0.0F;
        previousDragRotationDegrees = 0.0F;
        rotationAnimating = false;
    }

    public static void loadForHeldRender(MP4Item.State state) {
        if (active) {
            return;
        }
        playing = state.playing();
        shuffle = state.shuffle();
        videoEnabled = state.videoEnabled();
        landscape = state.landscape();
        qualityIndex = Math.max(0, Math.min(QUALITIES.length - 1, state.qualityIndex()));
        selectedQueueIndex = Math.max(0, Math.min(QUEUE_SIZE - 1, state.selectedQueueIndex()));
        queueScrollOffset = Math.max(0,
                Math.min(maxQueueScroll(PORTRAIT_QUEUE_VISIBLE_ROWS), state.queueScrollOffset()));
        volume = Math.max(0.0F, Math.min(1.0F, state.volumePerMille() / 1000.0F));
        repeatMode = Math.max(0, Math.min(2, state.repeatMode()));
        playlistOpen = state.playlistOpen();
        lyricsEnabled = state.lyricsEnabled();
        subtitleMode = state.subtitleMode();
        subtitleAiEnabled = state.subtitleAiEnabled();
        subtitleMenuOpen = false;
        qualityMenuOpen = false;
        rotationHintShown = state.rotationHintShown();
        mediaProgress = Math.max(0.0F, Math.min(1.0F, state.progressPerMille() / 1000.0F));
        dragRotationDegrees = 0.0F;
        previousDragRotationDegrees = 0.0F;
        rotationAnimating = false;
    }

    public static MP4Item.State save() {
        return new MP4Item.State(playing, shuffle, videoEnabled, landscape, qualityIndex, selectedQueueIndex,
                queueScrollOffset, Math.round(volume * 1000.0F), repeatMode, playlistOpen, lyricsEnabled,
                subtitleMode, subtitleAiEnabled, Math.round(mediaProgress * 1000.0F), rotationHintShown);
    }

    public static void loadQueue(List<ItemStack> queue) {
        queueTitles.clear();
        queueDurations.clear();
        for (ItemStack rawStack : queue) {
            ItemStack stack = Objects.requireNonNull(rawStack, "queue stack");
            ItemMusicCD.SongInfo songInfo = ItemMusicCD.getSongInfo(stack);
            if (songInfo == null) {
                continue;
            }
            String title = songInfo.songName == null || songInfo.songName.isBlank()
                    ? "NetMusic 唱片 " + (queueTitles.size() + 1)
                    : songInfo.songName;
            queueTitles.add(title);
            queueDurations.add(Math.max(0, songInfo.songTime));
            if (queueTitles.size() >= QUEUE_SIZE) {
                break;
            }
        }
        ensureSelectedQueueVisible(PORTRAIT_QUEUE_VISIBLE_ROWS);
    }

    public static void deactivate() {
        active = false;
        calibrationMode = false;
        ticks = 0;
        rotationHintTicks = 0;
    }

    /** 断连/切世界时清理所有纯客户端 UI 暂存状态，避免重连后继承旧设备界面。 */
    public static void resetAll() {
        active = false;
        calibrationMode = false;
        hand = InteractionHand.MAIN_HAND;
        ticks = 0;
        lastFrameTickNanos = 0L;
        playing = false;
        shuffle = false;
        videoEnabled = true;
        landscape = false;
        qualityIndex = 5;
        selectedQueueIndex = 0;
        queueScrollOffset = 0;
        queueTitles.clear();
        queueDurations.clear();
        volume = 1.0F;
        repeatMode = 0;
        showControls = false;
        playlistOpen = false;
        lyricsEnabled = false;
        subtitleMenuOpen = false;
        qualityMenuOpen = false;
        subtitleMode = 0;
        subtitleAiEnabled = false;
        rotationHintShown = false;
        rotationHintTicks = 0;
        controlsVisibleTicks = 0;
        dragRotationDegrees = 0.0F;
        previousDragRotationDegrees = 0.0F;
        rotationAnimating = false;
        rotationTargetDegrees = 0.0F;
        rotationTargetLandscape = false;
        rotationAnimationBaseLandscape = false;
        hoverX = 0.0F;
        hoverY = 0.0F;
        targetHoverX = 0.0F;
        targetHoverY = 0.0F;
        hoverTextureX = -1;
        hoverTextureY = -1;
        hoverControl = "NONE";
        pressX = -1;
        pressY = -1;
        pressTicks = 0;
        scrubbingProgress = false;
        mediaProgress = 0.0F;
        Arrays.fill(calibrationScreenX, 0.0F);
        Arrays.fill(calibrationScreenY, 0.0F);
        calibrationStep = 0;
        calibrationWidth = -1;
        calibrationHeight = -1;
        calibrationRun = 0;
    }

    public static void tick() {
        if (pressTicks > 0) {
            pressTicks--;
        }
        if (rotationHintTicks > 0) {
            rotationHintTicks--;
        }
        tickControlsVisibility();
    }

    public static void renderTick() {
        long now = System.nanoTime();
        if (now - lastFrameTickNanos < 16_000_000L) {
            return;
        }
        lastFrameTickNanos = now;
        previousDragRotationDegrees = dragRotationDegrees;
        ticks++;
        if (active && playing && !scrubbingProgress) {
            MP4ClientPlayback.syncFocusedUiProgress();
        }
        tickHoverAnimation();
        tickRotationAnimation();
    }

    public static int ticks() {
        return ticks;
    }

    public static boolean showRotationHintIfNotShown() {
        if (!rotationHintShown) {
            rotationHintShown = true;
            rotationHintTicks = ROTATION_HINT_DURATION_TICKS;
            return true;
        }
        return false;
    }

    public static boolean rotationHintShown() {
        return rotationHintShown;
    }

    public static int rotationHintTicks() {
        return rotationHintTicks;
    }

    public static boolean rotationHintVisible() {
        return rotationHintTicks > 0;
    }

    public static void confirmRotationHint() {
        rotationHintTicks = 0;
    }

    public static boolean activeFor(InteractionHand queryHand) {
        return active && hand == queryHand;
    }

    public static InteractionHand hand() {
        return hand;
    }

    public static boolean active() {
        return active;
    }

    public static float progress(float partialTick) {
        if (!active) {
            return 0.0F;
        }
        float value = (ticks + partialTick) / 8.0F;
        return Math.max(0.0F, Math.min(1.0F, value));
    }

    public static boolean playing() {
        return playing;
    }

    public static boolean shuffle() {
        return shuffle;
    }

    public static boolean videoEnabled() {
        return videoEnabled;
    }

    public static String quality() {
        return videoEnabled ? QUALITIES[qualityIndex] : "NO VIDEO";
    }

    public static int videoQualityCeiling() {
        return save().videoQualityCeiling();
    }

    public static boolean landscape() {
        return landscape;
    }

    public static int selectedQueueIndex() {
        return selectedQueueIndex;
    }

    public static int queueScrollOffset() {
        return queueScrollOffset;
    }

    public static int queueSize() {
        return queueTitles.size();
    }

    public static String queueTitle(int index) {
        if (index >= 0 && index < queueTitles.size()) {
            return queueTitles.get(index);
        }
        return "无曲目";
    }

    public static long selectedTrackDurationMillis() {
        if (selectedQueueIndex >= 0 && selectedQueueIndex < queueDurations.size()) {
            return Math.max(0L, queueDurations.get(selectedQueueIndex)) * 1000L;
        }
        return 0L;
    }

    public static boolean lyricsEnabled() {
        return lyricsEnabled;
    }

    public static boolean subtitleMenuOpen() {
        return subtitleMenuOpen;
    }

    public static boolean qualityMenuOpen() {
        return qualityMenuOpen;
    }

    public static int subtitleMode() {
        return subtitleMode;
    }

    public static boolean subtitlePrimaryMode() {
        return subtitleMode == 0;
    }

    public static boolean subtitleAiEnabled() {
        return subtitleAiEnabled;
    }

    public static float volume() {
        return volume;
    }

    public static float mediaProgress() {
        return mediaProgress;
    }

    public static void setScrubbingProgress(boolean value) {
        scrubbingProgress = value;
    }

    public static int repeatMode() {
        return repeatMode;
    }

    public static boolean showControls() {
        return !landscape || !playing || showControls || playlistOpen || subtitleMenuOpen || qualityMenuOpen;
    }

    public static boolean controlsVisible() {
        return showControls();
    }

    public static boolean playlistOpen() {
        return playlistOpen;
    }

    public static float dragRotationDegrees() {
        return dragRotationDegrees;
    }

    public static float dragRotationDegrees(float partialTick) {
        float t = Math.max(0.0F, Math.min(1.0F, partialTick));
        return previousDragRotationDegrees + (dragRotationDegrees - previousDragRotationDegrees) * t;
    }

    public static void setDragRotationDegrees(float degrees) {
        dragRotationDegrees = clampRotation(degrees);
        previousDragRotationDegrees = dragRotationDegrees;
    }

    public static void resetDragRotationDegrees() {
        dragRotationDegrees = 0.0F;
        previousDragRotationDegrees = 0.0F;
        rotationAnimating = false;
    }

    public static void animateRotationTo(float targetDegrees, boolean targetLandscape) {
        rotationTargetDegrees = clampRotation(targetDegrees);
        rotationTargetLandscape = targetLandscape;
        rotationAnimationBaseLandscape = landscape;
        landscape = targetLandscape;
        rotationAnimating = true;
    }

    public static boolean rotationAnimating() {
        return rotationAnimating;
    }

    public static void cancelRotationAnimation() {
        rotationAnimating = false;
        previousDragRotationDegrees = dragRotationDegrees;
    }

    public static void settleRotationForClose() {
        if (rotationAnimating) {
            landscape = rotationTargetLandscape;
        }
        dragRotationDegrees = 0.0F;
        previousDragRotationDegrees = 0.0F;
        rotationAnimating = false;
    }

    public static float deviceRotationDegrees(float partialTick) {
        float dragRotation = dragRotationDegrees(partialTick);
        boolean baseLandscape = rotationAnimating ? rotationAnimationBaseLandscape : landscape;
        return baseLandscape ? -90.0F + dragRotation : dragRotation;
    }

    public static boolean rotationInTransition(float partialTick) {
        float rotation = Math.abs(normalizeDegrees(deviceRotationDegrees(partialTick)));
        return rotation > 18.0F && rotation < 72.0F;
    }

    public static boolean visualLandscape(float partialTick) {
        float rotation = Math.abs(normalizeDegrees(deviceRotationDegrees(partialTick)));
        return rotation >= 45.0F;
    }

    public static float landscapeTransformProgress(float partialTick) {
        float rotation = Math.abs(normalizeDegrees(deviceRotationDegrees(partialTick)));
        float t = Math.max(0.0F, Math.min(1.0F, rotation / 90.0F));
        return t * t * (3.0F - 2.0F * t);
    }

    public static float hoverX() {
        return hoverX;
    }

    public static float hoverY() {
        return hoverY;
    }

    public static int hoverTextureX() {
        return hoverTextureX;
    }

    public static int hoverTextureY() {
        return hoverTextureY;
    }

    public static boolean hoverControl(String control) {
        return hoverControl.equals(control);
    }

    public static String hoverControlName() {
        return hoverControl;
    }

    public static int pressX() {
        return pressX;
    }

    public static int pressY() {
        return pressY;
    }

    public static int pressTicks() {
        return pressTicks;
    }

    public static void pressFeedback(int textureX, int textureY) {
        pressX = textureX;
        pressY = textureY;
        pressTicks = 8;
    }

    public static int calibrationStep() {
        return calibrationStep;
    }

    public static int calibrationRun() {
        return calibrationRun;
    }

    public static int calibrationPoints() {
        return CALIBRATION_POINTS;
    }

    public static boolean calibrationComplete(int width, int height) {
        return calibrationStep >= CALIBRATION_POINTS && calibrationWidth == width && calibrationHeight == height;
    }

    public static void resetCalibration(int width, int height) {
        calibrationStep = 0;
        calibrationWidth = width;
        calibrationHeight = height;
    }

    public static void finishCalibrationRun() {
        calibrationRun++;
    }

    public static void recordCalibrationPoint(int width, int height, float screenX, float screenY) {
        if (calibrationWidth != width || calibrationHeight != height) {
            resetCalibration(width, height);
        }
        if (calibrationStep >= CALIBRATION_POINTS) {
            return;
        }
        calibrationScreenX[calibrationStep] = screenX;
        calibrationScreenY[calibrationStep] = screenY;
        calibrationStep++;
    }

    public static float calibrationScreenX(int index) {
        return calibrationScreenX[index];
    }

    public static float calibrationScreenY(int index) {
        return calibrationScreenY[index];
    }

    public static void togglePlaying() {
        playing = !playing;
        if (playing && landscape) {
            hideControls();
        } else {
            showControlsTemporarily();
        }
    }

    public static void setPlaying(boolean value) {
        playing = value;
        if (playing && landscape) {
            hideControls();
        } else {
            showControlsTemporarily();
        }
    }

    public static void toggleShuffle() {
        shuffle = !shuffle;
        showControlsTemporarily();
    }

    public static void cyclePlaybackMode() {
        if (!shuffle && repeatMode == 0) {
            shuffle = true;
            repeatMode = 0;
        } else if (shuffle) {
            shuffle = false;
            repeatMode = 1;
        } else if (repeatMode == 1) {
            shuffle = false;
            repeatMode = 2;
        } else {
            shuffle = false;
            repeatMode = 0;
        }
        showControlsTemporarily();
    }

    public static void toggleVideo() {
        videoEnabled = !videoEnabled;
        showControlsTemporarily();
    }

    public static void toggleOrientation() {
        landscape = !landscape;
        showControls = false;
        dragRotationDegrees = 0.0F;
        previousDragRotationDegrees = 0.0F;
        rotationAnimating = false;
    }

    public static void setLandscape(boolean value) {
        if (landscape != value) {
            landscape = value;
            showControls = false;
        }
        dragRotationDegrees = 0.0F;
        previousDragRotationDegrees = 0.0F;
        rotationAnimating = false;
    }

    public static void toggleControls() {
        if (!landscape) {
            showControls = !showControls;
            return;
        }
        if (controlsVisible()) {
            showControls = false;
            controlsVisibleTicks = 0;
            playlistOpen = false;
            subtitleMenuOpen = false;
            qualityMenuOpen = false;
        } else {
            showControlsTemporarily();
        }
    }

    public static void showControlsTemporarily() {
        showControls = true;
        controlsVisibleTicks = 90;
    }

    private static void hideControls() {
        showControls = false;
        controlsVisibleTicks = 0;
        playlistOpen = false;
        subtitleMenuOpen = false;
        qualityMenuOpen = false;
    }

    public static void updateHover(float x, float y) {
        targetHoverX = Math.max(-1.0F, Math.min(1.0F, x));
        targetHoverY = Math.max(-1.0F, Math.min(1.0F, y));
    }

    public static void updateHoverTarget(int textureX, int textureY, String control) {
        hoverTextureX = textureX;
        hoverTextureY = textureY;
        hoverControl = control == null ? "NONE" : control;
    }

    public static void clearHoverTarget() {
        hoverTextureX = -1;
        hoverTextureY = -1;
        hoverControl = "NONE";
        if (calibrationMode) {
            return;
        }
        targetHoverX = 0.0F;
        targetHoverY = 0.0F;
    }

    private static void tickHoverAnimation() {
        hoverX += (targetHoverX - hoverX) * 0.32F;
        hoverY += (targetHoverY - hoverY) * 0.32F;
        if (Math.abs(hoverX) < 0.01F) {
            hoverX = 0.0F;
        }
        if (Math.abs(hoverY) < 0.01F) {
            hoverY = 0.0F;
        }
        if (Math.abs(targetHoverX) < 0.01F) {
            targetHoverX = 0.0F;
        }
        if (Math.abs(targetHoverY) < 0.01F) {
            targetHoverY = 0.0F;
        }
    }

    private static float clampRotation(float degrees) {
        return Math.max(-100.0F, Math.min(100.0F, degrees));
    }

    private static void tickRotationAnimation() {
        if (!rotationAnimating) {
            return;
        }
        float delta = rotationTargetDegrees - dragRotationDegrees;
        dragRotationDegrees += delta * 0.28F;
        if (Math.abs(delta) < 0.25F) {
            landscape = rotationTargetLandscape;
            showControls = false;
            dragRotationDegrees = 0.0F;
            previousDragRotationDegrees = 0.0F;
            rotationAnimating = false;
            if (active) {
                MP4Client.syncFocusedStateToServer();
            }
        }
    }

    private static void tickControlsVisibility() {
        if (!landscape || !playing || playlistOpen || subtitleMenuOpen || qualityMenuOpen) {
            return;
        }
        if (controlsVisibleTicks > 0) {
            controlsVisibleTicks--;
            if (controlsVisibleTicks == 0) {
                showControls = false;
            }
        }
    }

    private static float normalizeDegrees(float degrees) {
        float result = degrees;
        while (result <= -180.0F) {
            result += 360.0F;
        }
        while (result > 180.0F) {
            result -= 360.0F;
        }
        return result;
    }

    public static void setVolume(float v) {
        volume = Math.max(0.0F, Math.min(1.0F, v));
        showControlsTemporarily();
    }

    public static void setMediaProgress(float progress) {
        mediaProgress = Math.max(0.0F, Math.min(1.0F, progress));
        showControlsTemporarily();
    }

    public static void setMediaProgressFromPlayback(float progress) {
        mediaProgress = Math.max(0.0F, Math.min(1.0F, progress));
    }

    public static void adjustVolume(double delta) {
        setVolume(volume + (float) delta * 0.05F);
    }

    public static void cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3;
        showControlsTemporarily();
    }

    public static void togglePlaylist() {
        playlistOpen = !playlistOpen;
        if (playlistOpen) {
            subtitleMenuOpen = false;
            qualityMenuOpen = false;
        }
        ensureSelectedQueueVisible(landscape ? LANDSCAPE_QUEUE_VISIBLE_ROWS : PORTRAIT_QUEUE_VISIBLE_ROWS);
        showControlsTemporarily();
    }

    public static void toggleLyrics() {
        subtitleMenuOpen = !subtitleMenuOpen;
        if (subtitleMenuOpen) {
            playlistOpen = false;
            qualityMenuOpen = false;
        }
        showControlsTemporarily();
    }

    public static void toggleQualityMenu() {
        qualityMenuOpen = !qualityMenuOpen;
        if (qualityMenuOpen) {
            playlistOpen = false;
            subtitleMenuOpen = false;
        }
        showControlsTemporarily();
    }

    public static void selectQualityIndex(int index) {
        qualityIndex = Math.max(0, Math.min(QUALITIES.length - 1, index));
        qualityMenuOpen = false;
        showControlsTemporarily();
    }

    public static void selectSubtitleMode(int mode) {
        subtitleMode = Math.max(0, Math.min(1, mode));
        lyricsEnabled = true;
        showControlsTemporarily();
    }

    public static void disableSubtitle() {
        lyricsEnabled = false;
        showControlsTemporarily();
    }

    public static void toggleSubtitleAi() {
        subtitleAiEnabled = !subtitleAiEnabled;
        lyricsEnabled = true;
        showControlsTemporarily();
    }

    public static void nextQuality() {
        qualityIndex = (qualityIndex + 1) % QUALITIES.length;
        qualityMenuOpen = false;
        showControlsTemporarily();
    }

    public static void previousTrack() {
        if (queueSize() <= 0) {
            selectedQueueIndex = 0;
            mediaProgress = 0.0F;
            showControlsTemporarily();
            return;
        }
        selectedQueueIndex = Math.max(0, selectedQueueIndex - 1);
        mediaProgress = 0.0F;
        ensureSelectedQueueVisible(landscape ? LANDSCAPE_QUEUE_VISIBLE_ROWS : PORTRAIT_QUEUE_VISIBLE_ROWS);
        showControlsTemporarily();
    }

    public static void nextTrack() {
        if (queueSize() <= 0) {
            selectedQueueIndex = 0;
            mediaProgress = 0.0F;
            showControlsTemporarily();
            return;
        }
        selectedQueueIndex = Math.min(queueSize() - 1, selectedQueueIndex + 1);
        mediaProgress = 0.0F;
        ensureSelectedQueueVisible(landscape ? LANDSCAPE_QUEUE_VISIBLE_ROWS : PORTRAIT_QUEUE_VISIBLE_ROWS);
        showControlsTemporarily();
    }

    public static void scrollQueue(double scrollY) {
        int visibleRows = landscape ? LANDSCAPE_QUEUE_VISIBLE_ROWS : PORTRAIT_QUEUE_VISIBLE_ROWS;
        queueScrollOffset = Math.max(0,
                Math.min(maxQueueScroll(visibleRows), queueScrollOffset + (scrollY < 0 ? 1 : -1)));
        showControlsTemporarily();
    }

    public static void selectVisibleQueueRow(int row) {
        int visibleRows = landscape ? LANDSCAPE_QUEUE_VISIBLE_ROWS : PORTRAIT_QUEUE_VISIBLE_ROWS;
        if (row < 0 || row >= visibleRows) {
            return;
        }
        if (queueSize() <= 0) {
            selectedQueueIndex = 0;
            mediaProgress = 0.0F;
            showControlsTemporarily();
            return;
        }
        selectedQueueIndex = Math.max(0, Math.min(queueSize() - 1, queueScrollOffset + row));
        mediaProgress = 0.0F;
        ensureSelectedQueueVisible(visibleRows);
        showControlsTemporarily();
    }

    public static void selectQueueIndexForPlayback(int index) {
        if (queueSize() <= 0) {
            selectedQueueIndex = 0;
            mediaProgress = 0.0F;
            return;
        }
        selectedQueueIndex = Math.max(0, Math.min(queueSize() - 1, index));
        mediaProgress = 0.0F;
        ensureSelectedQueueVisible(landscape ? LANDSCAPE_QUEUE_VISIBLE_ROWS : PORTRAIT_QUEUE_VISIBLE_ROWS);
        showControlsTemporarily();
    }

    private static void ensureSelectedQueueVisible(int visibleRows) {
        if (selectedQueueIndex < queueScrollOffset) {
            queueScrollOffset = selectedQueueIndex;
        } else if (selectedQueueIndex >= queueScrollOffset + visibleRows) {
            queueScrollOffset = selectedQueueIndex - visibleRows + 1;
        }
        queueScrollOffset = Math.max(0, Math.min(maxQueueScroll(visibleRows), queueScrollOffset));
    }

    private static int maxQueueScroll(int visibleRows) {
        return Math.max(0, queueSize() - Math.max(1, visibleRows));
    }
}
