package com.zhongbai233.net_music_can_play_bili.bili;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;

/**
 * Applies a short linear fade to seeked 16-bit PCM to suppress decoder startup
 * transients.
 */
final class PcmFadeInAudioInputStream extends AudioInputStream {
    private final int channels;
    private final long fadeFrames;
    private long framesRead;

    private PcmFadeInAudioInputStream(AudioInputStream source, long fadeFrames) {
        super(source, source.getFormat(), AudioSystem.NOT_SPECIFIED);
        this.channels = source.getFormat().getChannels();
        this.fadeFrames = Math.max(1L, fadeFrames);
    }

    static AudioInputStream wrap(AudioInputStream source, int fadeMillis) {
        AudioFormat format = source.getFormat();
        if (fadeMillis <= 0 || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED
                || format.getSampleSizeInBits() != 16 || format.isBigEndian()
                || format.getChannels() <= 0 || format.getFrameSize() != format.getChannels() * 2
                || format.getSampleRate() <= 0f) {
            return source;
        }
        long fadeFrames = Math.max(1L, Math.round(format.getSampleRate() * fadeMillis / 1000.0D));
        return new PcmFadeInAudioInputStream(source, fadeFrames);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read <= 0 || framesRead >= fadeFrames) {
            return read;
        }
        int frameSize = channels * 2;
        int frameCount = read / frameSize;
        for (int frame = 0; frame < frameCount; frame++) {
            long absoluteFrame = framesRead + frame;
            if (absoluteFrame >= fadeFrames) {
                break;
            }
            double gain = absoluteFrame / (double) fadeFrames;
            int frameOffset = offset + frame * frameSize;
            for (int channel = 0; channel < channels; channel++) {
                int sampleOffset = frameOffset + channel * 2;
                int sample = (short) ((buffer[sampleOffset] & 0xFF) | (buffer[sampleOffset + 1] << 8));
                int faded = (int) Math.round(sample * gain);
                buffer[sampleOffset] = (byte) faded;
                buffer[sampleOffset + 1] = (byte) (faded >> 8);
            }
        }
        framesRead += frameCount;
        return read;
    }
}