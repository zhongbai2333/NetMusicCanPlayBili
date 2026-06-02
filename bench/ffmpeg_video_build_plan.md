# FFmpeg 视频解码器构建计划

## 当前状态

`build_eac3.sh` 只编译了 E-AC-3 音频解码器：
```bash
--enable-decoder=eac3
--enable-parser=ac3
```

需要新增视频解码能力。

## B站视频编码格式

B站 DASH 视频流使用三种编码：

| codecid | 编码 | FFmpeg 解码器 | 性能 | 复杂度 |
|---------|------|--------------|------|--------|
| 7  | AVC (H.264) | `h264` | 中等 | 低 |
| 12 | HEVC (H.265) | `hevc` | 较高 | 中 |
| 13 | AV1 | `av1` (libdav1d) | 最高 | 高 |

**建议首期目标**: H.264（最广泛，B站大多数非4K视频用这个）

## FFmpeg configure 新增项

### 方案 A: 最小 H.264 解码（推荐起步）

```bash
./configure \
    --disable-everything \
    # === 现有音频 ===
    --enable-decoder=eac3 \
    --enable-parser=ac3 \
    # === 新增视频 ===
    --enable-decoder=h264 \
    --enable-parser=h264 \
    # H.264 依赖
    --enable-hwaccel=h264_vaapi \   # 可选：硬件加速
    --enable-hwaccel=h264_dxva2 \   # Windows 硬解
    # === 工具 ===
    --enable-swscale \              # 缩放（降采样需要）
    --enable-avutil \
    # === 其他 (保持不变) ===
    --disable-programs --disable-doc \
    --disable-avdevice --disable-avformat \
    --disable-avfilter --disable-network \
    --disable-encoders --disable-muxers \
    --disable-protocols --disable-filters \
    --enable-small --disable-debug \
    --enable-shared --enable-w32threads \
    --disable-pthreads \
    --extra-ldflags="-static-libgcc -static-libstdc++"
```

### 方案 B: 完整视频解码（H.264 + H.265 + AV1）

```bash
# 新增
--enable-decoder=h264,hevc,av1
--enable-parser=h264,hevc,av1
--enable-bsf=h264_mp4toannexb,hevc_mp4toannexb
--enable-swscale
```

### 库体积预估

| 方案 | 新增库 | 增量大小 (Windows x64) |
|------|--------|----------------------|
| 仅 H.264 | 无额外 lib | ~500KB |
| H.264 + H.265 | 无额外 lib | ~800KB |
| H.264 + H.265 + AV1 | libdav1d | ~2MB |

> FFmpeg 内置的 h264/hevc 解码器不需要额外库。

## JNI 层修改

`eac3_jni.c` 需要改为通用视频+音频解码器：

### 需要的结构体

```c
typedef struct {
    AVCodecContext *video_ctx;    // 视频解码上下文
    AVCodecContext *audio_ctx;    // 现有音频上下文
    AVFrame *video_frame;         // 解码后的视频帧
    AVPacket *packet;
    struct SwsContext *sws_ctx;   // 颜色空间转换 + 缩放
    uint8_t *rgb_buffer;          // RGBA 输出缓冲
    int target_width;
    int target_height;
} DecoderHandle;
```

### 需要的 JNI 函数

```c
// 打开解码器（支持视频 + 可选音频）
JNIEXPORT jlong JNICALL Java_..._decoderOpen(
    JNIEnv *, jclass,
    jint codecType,    // 0=audio, 1=video
    jint targetWidth,  // 输出宽度 (0=原始)
    jint targetHeight  // 输出高度 (0=原始)
);

// 喂入数据包（支持视频 + 音频分离）
JNIEXPORT void JNICALL Java_..._enqueuePacket(
    JNIEnv *, jclass,
    jlong handle,
    jbyteArray data,
    jint offset,
    jint length
);

// 获取解码后的视频帧 RGBA
JNIEXPORT jbyteArray JNICALL Java_..._getVideoFrame(
    JNIEnv *, jclass,
    jlong handle
);

// 获取解码后的音频 PCM (兼容现有)
JNIEXPORT jobjectArray JNICALL Java_..._getAudioSamples(
    JNIEnv *, jclass,
    jlong handle
);
```

## 实施步骤

1. **Python 验证** (当前阶段 - bench 分支)
   - [x] `bench/bili_video_research.py` — DASH 流分析 + FFmpeg 性能测试
   - [ ] 验证 H.264 解码的 CPU 开销（在 MC 运行环境下）

2. **FFmpeg 构建**
   - [ ] 修改 `build_eac3.sh` → `build_media.sh`，加入 h264 解码器
   - [ ] 添加 swscale（颜色空间转换和缩放）
   - [ ] 编译各平台 native 库

3. **JNI 层**
   - [ ] 扩展 `eac3_jni.c`，新增视频解码函数
   - [ ] 实现 RGBA 帧输出（带降采样）
   - [ ] 线程安全：解码线程 + 渲染线程分离

4. **Java 层**
   - [ ] 新增 `VideoNativeDecoder.java`（参考 `Eac3NativeDecoder.java`）
   - [ ] 修改 `BiliApiClient`，新增 `getBestVideoUrl()` 方法
   - [ ] 视频流下载/缓存层（复用音频的 HTTP 客户端）

5. **渲染层**
   - [ ] 先做字符模拟渲染（不动 GL）
   - [ ] 后续做 GL 直接渲染
