package com.zhongbai233.net_music_can_play_bili.client.renderer.item;

import com.zhongbai233.net_music_can_play_bili.NetMusicCanPlayBili;
import com.zhongbai233.net_music_can_play_bili.client.MP4HandheldVideoClient;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.Nv12TextureSet;
import com.zhongbai233.net_music_can_play_bili.client.renderer.video.VideoYuvTextureSet;
import com.zhongbai233.net_music_can_play_bili.client.sync.HandheldVideoFrame;
import com.zhongbai233.net_music_can_play_bili.media.codec.Fmp4NativeVideoDecoder;
import net.minecraft.resources.Identifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** MP4 手持视频层按设备隔离的 NV12 纹理上传器。 */
final class MP4Nv12VideoLayer implements AutoCloseable {
    private static final Map<UUID, MP4Nv12VideoLayer> LAYERS = new ConcurrentHashMap<>();

    private final Identifier yTextureId;
    private final Identifier uvTextureId;
    private Nv12TextureSet textureSet;
    private long uploadedSequence = -1L;
    private int uploadedWidth;
    private int uploadedHeight;

    private MP4Nv12VideoLayer(UUID deviceId) {
        String suffix = deviceId.toString().replace('-', '_');
        this.yTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/mp4_video_" + suffix + "_y");
        this.uvTextureId = Identifier.fromNamespaceAndPath(NetMusicCanPlayBili.MODID,
                "dynamic/mp4_video_" + suffix + "_uv");
    }

    static MP4Nv12VideoLayer forDevice(UUID deviceId) {
        if (deviceId == null) {
            throw new IllegalArgumentException("MP4 video layer requires a device id");
        }
        return LAYERS.computeIfAbsent(deviceId, MP4Nv12VideoLayer::new);
    }

    static void releaseAll() {
        LAYERS.values().forEach(layer -> layer.close());
        LAYERS.clear();
    }

    static void release(UUID deviceId) {
        if (deviceId == null) {
            return;
        }
        MP4Nv12VideoLayer layer = LAYERS.remove(deviceId);
        if (layer != null) {
            layer.close();
        }
    }

    boolean uploadLatest(UUID deviceId) {
        HandheldVideoFrame frame = MP4HandheldVideoClient.latestFrame(deviceId);
        if (frame == null || frame.format() != Fmp4NativeVideoDecoder.DecodedFrame.Format.NV12) {
            return false;
        }
        long sequence = MP4HandheldVideoClient.frameSequence(deviceId);
        if (textureSet != null && sequence == uploadedSequence
                && uploadedWidth == frame.width() && uploadedHeight == frame.height()) {
            return true;
        }
        ensureTextureSet();
        boolean uploaded = frame.buffer() != null
                ? textureSet.upload(frame.buffer(), frame.byteLength(), frame.width(), frame.height())
                : textureSet.upload(frame.data(), frame.width(), frame.height());
        if (!uploaded) {
            return false;
        }
        uploadedSequence = sequence;
        uploadedWidth = frame.width();
        uploadedHeight = frame.height();
        return true;
    }

    VideoYuvTextureSet textureSet() {
        return textureSet;
    }

    private void ensureTextureSet() {
        if (textureSet != null) {
            return;
        }
        textureSet = new Nv12TextureSet(yTextureId, uvTextureId, yTextureId,
                "mp4_video_" + yTextureId.getPath().replace('/', '_'));
    }

    @Override
    public void close() {
        if (textureSet != null) {
            textureSet.close();
            textureSet = null;
        }
        uploadedSequence = -1L;
        uploadedWidth = 0;
        uploadedHeight = 0;
    }
}
