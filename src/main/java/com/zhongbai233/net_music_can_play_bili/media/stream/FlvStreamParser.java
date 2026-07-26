package com.zhongbai233.net_music_can_play_bili.media.stream;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/**
 * FLV 音视频解复用器。
 *
 * <p>
 * 只负责拆包：从 tag 流中取出 AAC 的 AudioSpecificConfig 与裸帧，以及（按需）H.264 的
 * avcC 序列头与 AVCC 样本；脚本 tag 直接跳过。解码由上层管线完成，这里不引入任何编解码依赖。
 * </p>
 */
public final class FlvStreamParser {
    private static final int TAG_TYPE_AUDIO = 8;
    private static final int TAG_TYPE_VIDEO = 9;
    private static final int TAG_HEADER_SIZE = 11;
    private static final int FLV_HEADER_SIZE = 9;
    private static final int FILTERED_TAG_FLAG = 0x20;
    private static final int SOUND_FORMAT_AAC = 10;
    private static final int AAC_PACKET_TYPE_SEQUENCE_HEADER = 0;
    private static final int AAC_PACKET_TYPE_RAW = 1;
    private static final int VIDEO_CODEC_AVC = 7;
    private static final int VIDEO_FRAME_TYPE_KEY = 1;
    /** Enhanced RTMP/FLV 扩展视频头标志位（FourCC 模式），本模组不处理。 */
    private static final int VIDEO_EX_HEADER_FLAG = 0x80;
    private static final int AVC_PACKET_TYPE_SEQUENCE_HEADER = 0;
    private static final int AVC_PACKET_TYPE_NALU = 1;
    private static final int MAX_TAG_SIZE = 8 * 1024 * 1024;

    /**
     * 逐帧回调。
     *
     * <p>
     * 直播流断开重连后会重新收到序列头，实现方需要能够处理重复的序列头。
     * 视频回调是可选的：{@link #wantsVideo()} 返回 false 时视频 tag 被直接跳过，不产生分配。
     * </p>
     */
    public interface Callback {
        /** AAC AudioSpecificConfig，音频格式的唯一来源。 */
        void onAacSequenceHeader(byte[] audioSpecificConfig) throws IOException, UnsupportedAudioFileException;

        /** 一个完整的 AAC 裸帧及其 FLV 时间戳（毫秒）。 */
        void onAacFrame(byte[] frame, long timestampMillis) throws IOException;

        /** 是否需要视频 tag；返回 false 时视频负载被跳过。 */
        default boolean wantsVideo() {
            return false;
        }

        /** H.264 AVCDecoderConfigurationRecord（与 fMP4 avcC 相同结构）。 */
        default void onAvcSequenceHeader(byte[] avcConfig) throws IOException {
        }

        /**
         * 一个 AVCC 长度前缀格式的 H.264 样本。
         *
         * @param dtsMillis             FLV tag 时间戳（解码顺序）
         * @param compositionTimeMillis 合成时间偏移，pts = dts + cts
         * @param keyframe              FLV FrameType 是否为关键帧
         */
        default void onAvcSample(byte[] sample, long dtsMillis, int compositionTimeMillis, boolean keyframe)
                throws IOException {
        }
    }

    /**
     * 解析整条 FLV 流，直到流结束或 {@code stopped} 返回 true。
     *
     * @return 已投递的 AAC 裸帧数量
     * @throws EOFException                  流在 tag 中途被截断（直播流通常意味着需要重连）
     * @throws UnsupportedAudioFileException 内容不是 FLV，或音频编码不是 AAC
     */
    public long parse(InputStream source, BooleanSupplier stopped, Callback callback)
            throws IOException, UnsupportedAudioFileException {
        BooleanSupplier stop = stopped != null ? stopped : () -> false;
        readFlvHeader(source);

        long frames = 0L;
        while (!stop.getAsBoolean()) {
            byte[] header = readTagHeader(source);
            if (header == null) {
                break;
            }
            int rawType = header[0] & 0xFF;
            if ((rawType & FILTERED_TAG_FLAG) != 0) {
                throw new UnsupportedAudioFileException("FLV 加密 tag 不受支持");
            }
            long dataSize = readUInt24(header, 1);
            if (dataSize > MAX_TAG_SIZE) {
                throw new IOException("FLV tag 过大: " + dataSize);
            }

            int tagType = rawType & 0x1F;
            if (tagType == TAG_TYPE_AUDIO) {
                frames += readAudioTag(source, dataSize, readTimestamp(header), callback);
            } else if (tagType == TAG_TYPE_VIDEO && callback.wantsVideo()) {
                readVideoTag(source, dataSize, readTimestamp(header), callback);
            } else {
                skipFully(source, dataSize);
            }
            // 每个 tag 之后是 4 字节的 PreviousTagSize，解析时不需要校验。
            skipFully(source, 4L);
        }
        return frames;
    }

    private static void readFlvHeader(InputStream source) throws IOException, UnsupportedAudioFileException {
        byte[] header = readFully(source, FLV_HEADER_SIZE);
        if (header[0] != 'F' || header[1] != 'L' || header[2] != 'V') {
            throw new UnsupportedAudioFileException("不是 FLV 流");
        }
        long dataOffset = readUInt32(header, 5);
        if (dataOffset < FLV_HEADER_SIZE) {
            throw new IOException("FLV 头部长度非法: " + dataOffset);
        }
        skipFully(source, dataOffset - FLV_HEADER_SIZE);
        // FLV 头之后是 PreviousTagSize0，恒为 0。
        skipFully(source, 4L);
    }

    /** @return tag 头；流在 tag 边界正常结束时返回 null */
    private static byte[] readTagHeader(InputStream source) throws IOException {
        byte[] header = new byte[TAG_HEADER_SIZE];
        int read = 0;
        while (read < TAG_HEADER_SIZE) {
            int n = source.read(header, read, TAG_HEADER_SIZE - read);
            if (n < 0) {
                if (read == 0) {
                    return null;
                }
                throw new EOFException("FLV tag 头在第 " + read + " 字节被截断");
            }
            read += n;
        }
        return header;
    }

    private static long readAudioTag(InputStream source, long dataSize, long timestampMillis, Callback callback)
            throws IOException, UnsupportedAudioFileException {
        if (dataSize < 2L) {
            skipFully(source, dataSize);
            return 0L;
        }

        byte[] prefix = readFully(source, 2);
        int soundFormat = (prefix[0] & 0xF0) >> 4;
        if (soundFormat != SOUND_FORMAT_AAC) {
            throw new UnsupportedAudioFileException("FLV 音频编码不受支持: " + describeSoundFormat(soundFormat));
        }

        int packetType = prefix[1] & 0xFF;
        byte[] payload = readFully(source, dataSize - 2L);
        switch (packetType) {
            case AAC_PACKET_TYPE_SEQUENCE_HEADER -> {
                if (payload.length == 0) {
                    throw new UnsupportedAudioFileException("FLV AAC 序列头为空");
                }
                callback.onAacSequenceHeader(payload);
                return 0L;
            }
            case AAC_PACKET_TYPE_RAW -> {
                if (payload.length == 0) {
                    return 0L;
                }
                callback.onAacFrame(payload, timestampMillis);
                return 1L;
            }
            default -> {
                return 0L;
            }
        }
    }

    /**
     * 读取一个视频 tag，仅投递经典 AVC（H.264）封装。
     *
     * <p>
     * Enhanced FLV（FourCC 扩展头）与其他编码不报错，直接忽略——直播解析已经用
     * {@code codec=0} 请求 AVC 流，出现其他编码说明是服务端异常，不应打断音频。
     * </p>
     */
    private static void readVideoTag(InputStream source, long dataSize, long dtsMillis, Callback callback)
            throws IOException {
        if (dataSize < 5L) {
            skipFully(source, dataSize);
            return;
        }

        byte[] prefix = readFully(source, 5);
        int flags = prefix[0] & 0xFF;
        if ((flags & VIDEO_EX_HEADER_FLAG) != 0 || (flags & 0x0F) != VIDEO_CODEC_AVC) {
            skipFully(source, dataSize - 5L);
            return;
        }
        boolean keyframe = ((flags & 0x70) >> 4) == VIDEO_FRAME_TYPE_KEY;
        int packetType = prefix[1] & 0xFF;
        int compositionTimeMillis = readSInt24(prefix, 2);

        long payloadSize = dataSize - 5L;
        switch (packetType) {
            case AVC_PACKET_TYPE_SEQUENCE_HEADER -> {
                byte[] config = readFully(source, payloadSize);
                if (config.length > 0) {
                    callback.onAvcSequenceHeader(config);
                }
            }
            case AVC_PACKET_TYPE_NALU -> {
                byte[] sample = readFully(source, payloadSize);
                if (sample.length > 0) {
                    callback.onAvcSample(sample, dtsMillis, compositionTimeMillis, keyframe);
                }
            }
            default -> skipFully(source, payloadSize);
        }
    }

    /** FLV 时间戳为 24 位低位加 1 字节高位扩展。 */
    private static long readTimestamp(byte[] header) {
        return readUInt24(header, 4) | ((long) (header[7] & 0xFF) << 24);
    }

    /** 24 位有符号大端整数（AVC CompositionTime）。 */
    private static int readSInt24(byte[] data, int offset) {
        int value = ((data[offset] & 0xFF) << 16)
                | ((data[offset + 1] & 0xFF) << 8)
                | (data[offset + 2] & 0xFF);
        return (value & 0x800000) != 0 ? value - 0x1000000 : value;
    }

    public static String describeSoundFormat(int soundFormat) {
        return switch (soundFormat) {
            case 0 -> "Linear PCM(0)";
            case 1 -> "ADPCM(1)";
            case 2 -> "MP3(2)";
            case 3 -> "Linear PCM little-endian(3)";
            case 4, 5, 6 -> "Nellymoser(" + soundFormat + ")";
            case 7 -> "G.711 A-law(7)";
            case 8 -> "G.711 mu-law(8)";
            case 9 -> "Enhanced FLV 扩展音频头(9)";
            case 11 -> "Speex(11)";
            case 14 -> "MP3 8kHz(14)";
            default -> "SoundFormat(" + soundFormat + ")";
        };
    }

    private static byte[] readFully(InputStream source, long length) throws IOException {
        if (length < 0L || length > MAX_TAG_SIZE) {
            throw new IOException("FLV 负载长度非法: " + length);
        }
        byte[] data = new byte[(int) length];
        int read = 0;
        while (read < data.length) {
            int n = source.read(data, read, data.length - read);
            if (n < 0) {
                throw new EOFException("FLV 负载在第 " + read + "/" + data.length + " 字节被截断");
            }
            read += n;
        }
        return data;
    }

    private static void skipFully(InputStream source, long length) throws IOException {
        long remaining = length;
        byte[] buffer = null;
        while (remaining > 0L) {
            long skipped = source.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (buffer == null) {
                buffer = new byte[8192];
            }
            int n = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                throw new EOFException("FLV 流在跳过 " + length + " 字节时结束");
            }
            remaining -= n;
        }
    }

    private static long readUInt24(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 16)
                | ((long) (data[offset + 1] & 0xFF) << 8)
                | (data[offset + 2] & 0xFF);
    }

    private static long readUInt32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }
}
