package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import com.zhongbai233.net_music_can_play_bili.media.Fmp4ToMp4Converter;
import com.zhongbai233.net_music_can_play_bili.media.codec.Eac3NativeDecoder;
import com.zhongbai233.net_music_can_play_bili.media.stream.BlockingAudioPipe;
import com.zhongbai233.net_music_can_play_bili.media.sync.PlaybackRequest;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 根据已解析的容器元数据选择强类型音频解码管线。 */
public final class AudioPipelineFactory {
    private AudioPipelineFactory() {
    }

    public static Selection selectFmp4(Fmp4ToMp4Converter.ParseResult parseResult, String discoveredCodecs,
            BlockingAudioPipe fallbackPipe, AtomicBoolean closed, PlaybackRequest request,
            float startOffsetSeconds) throws IOException {
        boolean openAlOutput = request != null;
        var sourcePos = request != null ? request.pos() : null;
        String sessionId = request != null ? request.sessionId() : "";
        UUID ownerId = request != null ? request.ownerId() : null;
        float timelineStartOffsetSeconds = request != null ? request.startOffsetSeconds() : startOffsetSeconds;

        if ("ec-3".equals(parseResult.audioCodec)) {
            if (!openAlOutput || !Eac3NativeDecoder.isNativeAvailable()) {
                return new Unsupported("EC-3 requires modern turntable Dolby playback and native decoder support");
            }
            return new Supported(new DolbyEc3Pipeline("fMP4", closed, sourcePos, startOffsetSeconds,
                    timelineStartOffsetSeconds, sessionId, ownerId));
        }
        if (parseResult.flacDfLa != null) {
            return new Supported(openAlOutput
                    ? new FlacOpenALPipeline(parseResult.flacDfLa.clone(), closed, sourcePos, startOffsetSeconds,
                            timelineStartOffsetSeconds, sessionId, ownerId)
                    : new FlacPcmPipeline(parseResult.flacDfLa.clone(), fallbackPipe));
        }
        if (parseResult.asc != null) {
            return new Supported(openAlOutput
                    ? new AacOpenALPipeline(parseResult.asc.clone(), closed, sourcePos, startOffsetSeconds,
                            timelineStartOffsetSeconds, sessionId, ownerId)
                    : new AacPcmPipeline(parseResult.asc.clone(), fallbackPipe));
        }
        return new Unsupported("unsupported fMP4 audio codec: "
                + (discoveredCodecs != null ? discoveredCodecs : ""));
    }

    public sealed interface Selection permits Supported, Unsupported {
    }

    public record Supported(AudioDecodePipeline pipeline) implements Selection {
        public Supported {
            if (pipeline == null) {
                throw new IllegalArgumentException("pipeline must not be null");
            }
        }
    }

    public record Unsupported(String reason) implements Selection {
        public Unsupported {
            reason = reason != null && !reason.isBlank() ? reason : "unsupported audio pipeline";
        }
    }
}