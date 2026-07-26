package com.zhongbai233.net_music_can_play_bili.media.stream;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlvStreamParserTest {
    private static final int TAG_AUDIO = 8;
    private static final int TAG_VIDEO = 9;
    private static final int TAG_SCRIPT = 18;
    private static final int SOUND_FORMAT_AAC = 10;
    private static final int SOUND_FORMAT_MP3 = 2;
    private static final byte[] ASC = { (byte) 0x12, (byte) 0x10 };

    @Test
    void decodesSequenceHeaderAndFramesWhileSkippingOtherTags() throws Exception {
        byte[] flv = flv(9, List.of(
                tag(TAG_SCRIPT, 0, new byte[] { 1, 2, 3 }),
                aacTag(0, 0, ASC),
                tag(TAG_VIDEO, 20, new byte[] { 7, 7, 7, 7 }),
                aacTag(20, 1, new byte[] { 10, 20, 30 }),
                aacTag(43, 1, new byte[] { 40, 50 })));

        RecordingCallback callback = new RecordingCallback();
        long frames = new FlvStreamParser().parse(new ByteArrayInputStream(flv), () -> false, callback);

        assertEquals(2L, frames);
        assertArrayEquals(ASC, callback.sequenceHeaders.get(0));
        assertEquals(1, callback.sequenceHeaders.size());
        assertArrayEquals(new byte[] { 10, 20, 30 }, callback.frames.get(0));
        assertArrayEquals(new byte[] { 40, 50 }, callback.frames.get(1));
        assertEquals(List.of(20L, 43L), callback.timestamps);
    }

    @Test
    void skipsExtendedFlvHeaderBytes() throws Exception {
        byte[] flv = flv(13, List.of(aacTag(0, 0, ASC), aacTag(0, 1, new byte[] { 1 })));

        RecordingCallback callback = new RecordingCallback();
        long frames = new FlvStreamParser().parse(new ByteArrayInputStream(flv), () -> false, callback);

        assertEquals(1L, frames);
        assertArrayEquals(ASC, callback.sequenceHeaders.get(0));
    }

    @Test
    void readsExtendedTimestampByte() throws Exception {
        int timestamp = 0x01_00_00_05;
        byte[] flv = flv(9, List.of(aacTag(0, 0, ASC), aacTag(timestamp, 1, new byte[] { 9 })));

        RecordingCallback callback = new RecordingCallback();
        new FlvStreamParser().parse(new ByteArrayInputStream(flv), () -> false, callback);

        assertEquals(List.of((long) timestamp), callback.timestamps);
    }

    @Test
    void stopsWhenCallerRequestsStop() throws Exception {
        byte[] flv = flv(9, List.of(
                aacTag(0, 0, ASC),
                aacTag(0, 1, new byte[] { 1 }),
                aacTag(23, 1, new byte[] { 2 }),
                aacTag(46, 1, new byte[] { 3 })));

        RecordingCallback callback = new RecordingCallback();
        long frames = new FlvStreamParser().parse(new ByteArrayInputStream(flv),
                () -> callback.frames.size() >= 2, callback);

        assertEquals(2L, frames);
    }

    @Test
    void rejectsContentThatIsNotFlv() {
        byte[] notFlv = { 0, 0, 0, 0x20, 'f', 't', 'y', 'p', 'i', 's', 'o', '5', 0, 0, 0, 0 };

        assertThrows(UnsupportedAudioFileException.class,
                () -> new FlvStreamParser().parse(new ByteArrayInputStream(notFlv), () -> false,
                        new RecordingCallback()));
    }

    @Test
    void rejectsNonAacAudioWithReadableMessage() {
        byte[] flv = flv(9, List.of(tag(TAG_AUDIO, 0, new byte[] { (byte) (SOUND_FORMAT_MP3 << 4), 0, 1, 2 })));

        UnsupportedAudioFileException error = assertThrows(UnsupportedAudioFileException.class,
                () -> new FlvStreamParser().parse(new ByteArrayInputStream(flv), () -> false,
                        new RecordingCallback()));

        assertNotNull(error.getMessage());
        assertTrue(error.getMessage().contains("MP3(2)"), error.getMessage());
    }

    @Test
    void rejectsEncryptedTags() {
        byte[] encrypted = tag(TAG_AUDIO | 0x20, 0, new byte[] { (byte) (SOUND_FORMAT_AAC << 4), 1, 5 });
        byte[] flv = flv(9, List.of(encrypted));

        assertThrows(UnsupportedAudioFileException.class,
                () -> new FlvStreamParser().parse(new ByteArrayInputStream(flv), () -> false,
                        new RecordingCallback()));
    }

    @Test
    void reportsTruncatedTagAsEndOfStream() {
        byte[] flv = flv(9, List.of(aacTag(0, 0, ASC), aacTag(0, 1, new byte[] { 1, 2, 3, 4, 5, 6 })));
        byte[] truncated = Arrays.copyOf(flv, flv.length - 5);

        assertThrows(EOFException.class,
                () -> new FlvStreamParser().parse(new ByteArrayInputStream(truncated), () -> false,
                        new RecordingCallback()));
    }

    @Test
    void endsCleanlyAtTagBoundary() throws Exception {
        byte[] flv = flv(9, List.of(aacTag(0, 0, ASC)));

        RecordingCallback callback = new RecordingCallback();
        assertEquals(0L, new FlvStreamParser().parse(new ByteArrayInputStream(flv), () -> false, callback));
        assertEquals(1, callback.sequenceHeaders.size());
    }

    @Test
    void parsesAvcSequenceHeaderAndSamplesWhenVideoWanted() throws Exception {
        byte[] avcC = { 1, 100, 0, 40, (byte) 0xFF, (byte) 0xE1, 0, 2, 0x67, 0x42, 1, 0, 2, 0x68, (byte) 0xCE };
        byte[] flv = flv(9, List.of(
                aacTag(0, 0, ASC),
                videoTag(1, 0, 0, 0, avcC),
                videoTag(1, 40, 1, 0, new byte[] { 0, 0, 0, 2, 0x65, 0x11 }),
                videoTag(2, 80, 1, -24, new byte[] { 0, 0, 0, 2, 0x41, 0x22 }),
                aacTag(80, 1, new byte[] { 9 })));

        VideoRecordingCallback callback = new VideoRecordingCallback();
        long audioFrames = new FlvStreamParser().parse(new ByteArrayInputStream(flv), () -> false, callback);

        assertEquals(1L, audioFrames);
        assertEquals(1, callback.avcConfigs.size());
        assertArrayEquals(avcC, callback.avcConfigs.get(0));
        assertEquals(2, callback.samples.size());
        assertEquals(List.of(40L, 80L), callback.dts);
        assertEquals(List.of(0, -24), callback.cts);
        assertEquals(List.of(Boolean.TRUE, Boolean.FALSE), callback.keyframes);
        assertArrayEquals(new byte[] { 0, 0, 0, 2, 0x65, 0x11 }, callback.samples.get(0));
    }

    @Test
    void ignoresEnhancedFlvAndNonAvcVideoTags() throws Exception {
        byte[] enhanced = tag(TAG_VIDEO, 0, new byte[] { (byte) 0x90, 'a', 'v', '0', '1', 1, 2 });
        byte[] hevcClassic = tag(TAG_VIDEO, 0, new byte[] { 0x1C, 1, 0, 0, 0, 9 });
        byte[] flv = flv(9, List.of(aacTag(0, 0, ASC), enhanced, hevcClassic, aacTag(23, 1, new byte[] { 5 })));

        VideoRecordingCallback callback = new VideoRecordingCallback();
        assertEquals(1L, new FlvStreamParser().parse(new ByteArrayInputStream(flv), () -> false, callback));
        assertTrue(callback.avcConfigs.isEmpty());
        assertTrue(callback.samples.isEmpty());
    }

    private static class RecordingCallback implements FlvStreamParser.Callback {
        private final List<byte[]> sequenceHeaders = new ArrayList<>();
        private final List<byte[]> frames = new ArrayList<>();
        private final List<Long> timestamps = new ArrayList<>();

        @Override
        public void onAacSequenceHeader(byte[] audioSpecificConfig) {
            sequenceHeaders.add(audioSpecificConfig);
        }

        @Override
        public void onAacFrame(byte[] frame, long timestampMillis) {
            frames.add(frame);
            timestamps.add(timestampMillis);
        }
    }

    private static final class VideoRecordingCallback extends RecordingCallback {
        private final List<byte[]> avcConfigs = new ArrayList<>();
        private final List<byte[]> samples = new ArrayList<>();
        private final List<Long> dts = new ArrayList<>();
        private final List<Integer> cts = new ArrayList<>();
        private final List<Boolean> keyframes = new ArrayList<>();

        @Override
        public boolean wantsVideo() {
            return true;
        }

        @Override
        public void onAvcSequenceHeader(byte[] avcConfig) {
            avcConfigs.add(avcConfig);
        }

        @Override
        public void onAvcSample(byte[] sample, long dtsMillis, int compositionTimeMillis, boolean keyframe) {
            samples.add(sample);
            dts.add(dtsMillis);
            cts.add(compositionTimeMillis);
            keyframes.add(keyframe);
        }
    }

    /** frameType 1=key 2=inter；packetType 0=sequence header 1=NALU。 */
    private static byte[] videoTag(int frameType, int timestamp, int packetType, int compositionTime, byte[] payload) {
        byte[] data = new byte[payload.length + 5];
        data[0] = (byte) ((frameType << 4) | 7);
        data[1] = (byte) packetType;
        int cts = compositionTime & 0xFFFFFF;
        data[2] = (byte) ((cts >>> 16) & 0xFF);
        data[3] = (byte) ((cts >>> 8) & 0xFF);
        data[4] = (byte) (cts & 0xFF);
        System.arraycopy(payload, 0, data, 5, payload.length);
        return tag(TAG_VIDEO, timestamp, data);
    }

    private static byte[] aacTag(int timestamp, int packetType, byte[] payload) {
        byte[] data = new byte[payload.length + 2];
        data[0] = (byte) (SOUND_FORMAT_AAC << 4);
        data[1] = (byte) packetType;
        System.arraycopy(payload, 0, data, 2, payload.length);
        return tag(TAG_AUDIO, timestamp, data);
    }

    private static byte[] tag(int type, int timestamp, byte[] data) {
        byte[] tag = new byte[11 + data.length];
        tag[0] = (byte) type;
        writeUInt24(tag, 1, data.length);
        writeUInt24(tag, 4, timestamp & 0xFFFFFF);
        tag[7] = (byte) ((timestamp >>> 24) & 0xFF);
        System.arraycopy(data, 0, tag, 11, data.length);
        return tag;
    }

    private static byte[] flv(int dataOffset, List<byte[]> tags) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('F');
        out.write('L');
        out.write('V');
        out.write(1);
        out.write(0x05);
        writeUInt32(out, dataOffset);
        for (int i = 9; i < dataOffset; i++) {
            out.write(0);
        }
        writeUInt32(out, 0);
        for (byte[] tag : tags) {
            out.writeBytes(tag);
            writeUInt32(out, tag.length);
        }
        return out.toByteArray();
    }

    private static void writeUInt24(byte[] target, int offset, int value) {
        target[offset] = (byte) ((value >>> 16) & 0xFF);
        target[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        target[offset + 2] = (byte) (value & 0xFF);
    }

    private static void writeUInt32(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
