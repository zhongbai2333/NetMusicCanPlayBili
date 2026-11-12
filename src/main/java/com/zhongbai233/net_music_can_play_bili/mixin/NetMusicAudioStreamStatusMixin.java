package com.zhongbai233.net_music_can_play_bili.mixin;

import com.github.tartaricacid.netmusic.client.audio.NetMusicAudioStream;
import com.zhongbai233.net_music_can_play_bili.client.audio.NetMusicStreamStatus;
import com.zhongbai233.net_music_can_play_bili.media.stream.BufferedAudioReadBoundary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Mixin(value = NetMusicAudioStream.class, remap = false)
public abstract class NetMusicAudioStreamStatusMixin implements NetMusicStreamStatus {
    @Shadow private volatile Throwable failed;
    @Shadow @Final private int frameSize;
    @Shadow @Final private ConcurrentLinkedQueue<ByteBuffer> audioDataQueue;
    @Shadow @Final private AtomicBoolean loading;
    @Shadow protected abstract void loadAudioData();
    @Unique private volatile boolean ncpbInputEnded;

    @Redirect(method = "pumpBuffers", at = @At(value = "INVOKE",
            target = "Ljavax/sound/sampled/AudioInputStream;read([B)I"))
    private int ncpbObserveProducerEnd(AudioInputStream stream, byte[] bytes) throws IOException {
        int read = stream.read(bytes);
        if (read == -1) ncpbInputEnded = true;
        return read;
    }
    @Override public Throwable ncpbReadFailure() { return failed; }
    @Override public ByteBuffer ncpbReadBuffered(int size) {
        return BufferedAudioReadBoundary.readUpstream(audioDataQueue, size, frameSize,
                () -> ncpbInputEnded, loading::get, this::loadAudioData);
    }
}
