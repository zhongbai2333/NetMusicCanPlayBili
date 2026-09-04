package com.zhongbai233.net_music_can_play_bili.client.audio;

import com.github.tartaricacid.netmusic.NetMusic;
import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.github.tartaricacid.netmusic.client.audio.NetMusicAudioStream;
import com.github.tartaricacid.netmusic.init.InitSounds;
import com.zhongbai233.net_music_can_play_bili.bili.BiliPlaybackDiagnostics;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackSessionId;
import com.mojang.logging.LogUtils;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;

/**
 * 共享的 NetMusic 同步媒体 Sound 基类。
 *
 * <p>
 * 现代化唱片机是主播放线路；MP4 复用这里的基础音频流创建、诊断和 session 字段，
 * 仅保留自己的动态声源、音量、重试和生命周期差异。
 * </p>
 */
public abstract class SyncedMediaSound extends AbstractTickableSoundInstance {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<SyncedMediaSound> PENDING_DECODE_ADMISSION = ConcurrentHashMap.newKeySet();
    protected final URL songUrl;
    protected final int tickTimes;
    protected final LyricRecord lyricRecord;
    private final PlaybackSessionId playbackSessionId;
    protected final long startOffsetMillis;
    protected int tick;
    private volatile boolean streamCreationStarted;
    private volatile boolean demandIdleRetired;
    private final DeferredAudioStreamAdmission decodeAdmission = new DeferredAudioStreamAdmission();
    private final Set<OwnedAudioStream> ownedStreams = ConcurrentHashMap.newKeySet();

    protected SyncedMediaSound(URL songUrl, int timeSecond, LyricRecord lyricRecord, String sessionId,
            long startOffsetMillis) {
        super(InitSounds.NET_MUSIC.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.songUrl = songUrl;
        this.tickTimes = Math.max(1, timeSecond) * 20;
        this.lyricRecord = lyricRecord;
        this.playbackSessionId = PlaybackSessionId.parse(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("synced media sound requires a valid session id"));
        this.startOffsetMillis = Math.max(0L, startOffsetMillis);
    }

    /** Typed session identity for internal sound lifecycle code; callers use the string facade below. */
    protected final PlaybackSessionId playbackSessionId() {
        return playbackSessionId;
    }

    public final Optional<PlaybackSessionId> playbackSession() {
        return Optional.of(playbackSessionId);
    }

    public final String sessionId() {
        return playbackSessionId.value();
    }

    /**
     * Indexed playback intentionally allocates the streaming channel before the first decoded frame and
     * fades in only after the real output is ready. Minecraft otherwise rejects a zero-volume sound before
     * {@link #getStream(SoundBufferLibrary, Sound, boolean)} can run, permanently deadlocking stream readiness.
     */
    @Override
    public final boolean canStartSilent() {
        return true;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        if (isStopped()) {
            retireBeforeStreamReady();
        } else if (!decodeAdmission.isDecided()) {
            PENDING_DECODE_ADMISSION.add(this);
            // Close the add/approve race without waiting for the next client tick to prune the set.
            if (decodeAdmission.isDecided()) {
                PENDING_DECODE_ADMISSION.remove(this);
            }
        }
        return decodeAdmission.future().thenCompose(decision -> {
            if (decision == DeferredAudioStreamAdmission.Decision.ATTACH_DRAINED_STREAM) {
                return CompletableFuture.completedFuture(new DrainedAudioStream());
            }
            return CompletableFuture.supplyAsync(this::openMediaOrTerminalStream, Util.backgroundExecutor());
        });
    }

    private AudioStream openMediaOrTerminalStream() {
        OwnedAudioStream openedStream = null;
        try {
            if (isStopped()) {
                retireBeforeStreamReady();
                return new DrainedAudioStream();
            }
            streamCreationStarted = true;
            onStreamStarting();
            if (isStopped()) {
                retireBeforeStreamReady();
                return new DrainedAudioStream();
            }
            long started = System.currentTimeMillis();
            OwnedAudioStream stream = openedStream = own(new NetMusicAudioStream(songUrl));
            if (isStopped()) {
                closeWithoutFailure(stream);
                retireBeforeStreamReady();
                return new DrainedAudioStream();
            }
            LOGGER.debug("{} audio stream ready: cost={}ms host={}", streamDebugName(),
                    System.currentTimeMillis() - started, songUrl.getHost());
            onStreamReady();
            return stream;
        } catch (CancellationException e) {
            closeWithoutFailure(openedStream);
            retireBeforeStreamReady();
            return new DrainedAudioStream();
        } catch (Exception e) {
            closeWithoutFailure(openedStream);
            BiliPlaybackDiagnostics.markFailed(songUrl, e);
            onStreamFailure(e);
            NetMusic.LOGGER.error("Failed to create {} audio stream for URL: {}", streamDebugName(), songUrl, e);
            // A genuine media error still uses the audible failure cue. It is owned as well because an
            // asynchronous stop can win the race before SoundEngine attaches the completed future.
            try {
                return errorCueStream(e);
            } catch (CompletionException cueFailure) {
                throw cueFailure;
            }
        }
    }

    /** Called by each device tick with the common range policy's current decision. */
    protected final void setDecodeDemand(boolean decodeDemand) {
        if (decodeDemand) {
            decodeAdmission.approveMediaStream();
            PENDING_DECODE_ADMISSION.remove(this);
        }
    }

    protected final boolean streamCreationStarted() {
        return streamCreationStarted;
    }

    /** Refreshes all not-yet-opened streams from the client thread. */
    public static void tickPendingDecodeAdmissions() {
        for (SyncedMediaSound sound : PENDING_DECODE_ADMISSION) {
            if (sound.isStopped()) {
                sound.retireBeforeStreamReady();
                continue;
            }
            sound.refreshDecodeDemand();
            if (sound.decodeAdmission.isDecided()) {
                PENDING_DECODE_ADMISSION.remove(sound);
            }
        }
    }

    public static void cancelPendingDecodeAdmissions() {
        for (SyncedMediaSound sound : PENDING_DECODE_ADMISSION) {
            sound.retireBeforeStreamReady();
        }
    }

    protected abstract void refreshDecodeDemand();

    private AudioStream errorCueStream(Exception cause) {
        try {
            java.io.InputStream errorSound = net.minecraft.client.Minecraft.getInstance()
                    .getResourceManager()
                    .open(com.github.tartaricacid.netmusic.client.audio.NetMusicSound.ERROR_SOUND);
            return own(new net.minecraft.client.sounds.JOrbisAudioStream(errorSound));
        } catch (Exception fallbackError) {
            CompletionException failure = new CompletionException(cause);
            failure.addSuppressed(fallbackError);
            throw failure;
        }
    }

    protected int fallbackLyricTick() {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, Math.round(Math.max(0L, startOffsetMillis) / 50.0D)));
    }

    protected void stopAndFinish() {
        finishSession();
        stop();
        drainAllocatedChannel();
        closeOwnedStreams();
    }

    /** 供播放 tracker 在会话被取消/替换时停止本声音实例。 */
    void stopFromTracker() {
        stopAndFinish();
    }

    /** Retires only the physical stream; the metadata session remains available for on-demand restart. */
    void stopForDemandIdle() {
        demandIdleRetired = true;
        PENDING_DECODE_ADMISSION.remove(this);
        onDemandIdle();
        stop();
        drainAllocatedChannel();
        closeOwnedStreams();
    }

    protected void onDemandIdle() {
    }

    protected void onStreamStarting() {
    }

    protected void onStreamReady() {
    }

    protected void onStreamFailure(Exception error) {
        finishSession();
    }

    protected abstract void finishSession();

    protected abstract String streamDebugName();

    private void retireBeforeStreamReady() {
        PENDING_DECODE_ADMISSION.remove(this);
        if (!demandIdleRetired) {
            finishSession();
        }
        drainAllocatedChannel();
        closeOwnedStreams();
    }

    private void drainAllocatedChannel() {
        PENDING_DECODE_ADMISSION.remove(this);
        decodeAdmission.drainAllocatedChannel();
    }

    private OwnedAudioStream own(AudioStream stream) {
        OwnedAudioStream owned = new OwnedAudioStream(stream);
        ownedStreams.add(owned);
        return owned;
    }

    private void closeOwnedStreams() {
        for (OwnedAudioStream stream : ownedStreams) {
            closeWithoutFailure(stream);
        }
    }

    private void closeWithoutFailure(OwnedAudioStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException closeError) {
            LOGGER.debug("Failed to close cancelled {} audio stream", streamDebugName(), closeError);
        }
    }

    /** A tiny silent buffer makes OpenAL enter PLAYING and then STOPPED so its reserved channel is reclaimed. */
    private static final class DrainedAudioStream implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, 44_100.0F, 16, 1, 2, 44_100.0F, false);
        private static final int SILENT_BYTES = 882; // 10 ms of mono 16-bit PCM at 44.1 kHz.
        private final AtomicBoolean delivered = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public ByteBuffer read(int size) {
            if (closed.get() || size < FORMAT.getFrameSize() || !delivered.compareAndSet(false, true)) {
                return null;
            }
            int byteCount = Math.min(size, SILENT_BYTES);
            byteCount -= byteCount % FORMAT.getFrameSize();
            return ByteBuffer.allocateDirect(byteCount);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /**
     * Makes a decoded stream safe across the stop/future-attachment race. If SoundEngine has already attached it,
     * its later channel cleanup may close the wrapper again; only the first close reaches the underlying stream.
     */
    private static final class OwnedAudioStream implements AudioStream {
        private final AudioStream delegate;
        private final AudioFormat format;
        private final AtomicBoolean closed = new AtomicBoolean();

        private OwnedAudioStream(AudioStream delegate) {
            this.delegate = delegate;
            this.format = delegate.getFormat();
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public ByteBuffer read(int size) throws IOException {
            return closed.get() ? null : delegate.read(size);
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                delegate.close();
            }
        }
    }
}
