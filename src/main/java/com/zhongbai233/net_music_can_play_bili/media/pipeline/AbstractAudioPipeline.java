package com.zhongbai233.net_music_can_play_bili.media.pipeline;

import javax.sound.sampled.AudioFormat;

/**
 * Pipeline 基类，消除 container/codec/detail/usesOpenAlOutput 样板重复。
 * 子类只需提供 format() 和 onMdat() 即可。
 */
public abstract class AbstractAudioPipeline implements AudioDecodePipeline {
    private final String container;
    private final String codec;
    private final String detail;
    private final boolean usesOpenAlOutput;

    protected AbstractAudioPipeline(String container, String codec, String detail, boolean usesOpenAlOutput) {
        this.container = container;
        this.codec = codec;
        this.detail = detail;
        this.usesOpenAlOutput = usesOpenAlOutput;
    }

    @Override
    public String container() {
        return container;
    }

    @Override
    public String codec() {
        return codec;
    }

    @Override
    public String detail() {
        return detail;
    }

    @Override
    public boolean usesOpenAlOutput() {
        return usesOpenAlOutput;
    }

    @Override
    public abstract AudioFormat format();
}
