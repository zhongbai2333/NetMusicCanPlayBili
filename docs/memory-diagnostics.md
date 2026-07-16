# 内存诊断

本模组提供低频内存遥测，用于区分 JVM 堆、JVM 管理的堆外内存、进程提交量，以及本模组显式分配的 native/PBO 资源。

周期诊断日志默认关闭；媒体内存保护默认开启。保护模式只维护 NCPB 四类私有资源的当前值/峰值，
并每 2 秒读取一次 FFmpeg/D3D11 统计，不执行 MXBean 全量报告或采集调用栈。资源计数只发生在
direct buffer、PBO 和音频 staging 的分配/释放点，不在逐像素或逐采样循环中。

## 开启方式

开发客户端：

```text
gradlew runClient -PncpbMemoryDiagnostics=true
```

调整采样间隔（毫秒，最小 1000）：

```text
gradlew runClient -PncpbMemoryDiagnostics=true -PncpbMemoryReportIntervalMs=3000
```

正式整合包在 JVM 参数中加入：

```text
-Dncpb.memory.diagnostics=true
-Dncpb.memory.report_interval_ms=5000
```

日志位于 `logs/latest.log`，搜索 `NCPB内存`、`NCPB自有内存` 或 `NCPB FFmpeg内存`。

## 自动熔断保护

保护器默认使用以下增长观察水位：

- NCPB 自有 native buffer：512 MiB；
- PBO 逻辑容量：512 MiB；
- FFmpeg `av_malloc` 当前存活量：1024 MiB；
- D3D11VA 逻辑资源容量：2048 MiB；
- D3D11VA surface：256 个。

这些数值不是“达到即熔断”的硬上限。任一指标超过观察水位后，还必须连续 15 个采样周期（默认约
30 秒）显著增长才会熔断；高分辨率帧池、多个独立视频会话等合法但稳定的占用不会触发。字节类
指标每次至少增长观察水位的 1%（上限 8 MiB）才计作一次增长，surface 则必须继续增加。

以 7680×4320 NV12 为例，单帧约 47.5 MiB，单会话的三槽双平面 PBO 上界约 142.4 MiB，解码帧池
还会保留多帧。因此一个或数个 8K 会话超过上述观察水位本身并不代表泄漏。同一 session 绑定多个
投影屏会复用同一个解码会话和纹理，不会按屏幕数量重复分配。多个不同 session 会各自建立资源池，
但正常情况下初始化完成后数值会稳定，从而中断熔断计数。

熔断后会停止并释放世界视频、
手持 MP4/Pad 视频、音频和相关客户端媒体缓存；冷却期内拒绝创建新视频会话。默认冷却 60 秒，
且所有指标都回落到阈值的 65% 以下后才自动恢复。若 Java/direct/纹理分配直接抛出
`OutOfMemoryError`，则不等待第二次采样，立即熔断。

保护不使用进程 committed 作为触发条件，因为 JVM、显卡驱动和内存映射的正常扩容可能造成很大
但可回收的 committed 波动。保护器也不会调用 `System.gc()` 或结束游戏进程。

可通过 JVM 参数覆盖或关闭：

```text
-Dncpb.memory.protection=true
-Dncpb.memory.protection.owned_native_mib=512
-Dncpb.memory.protection.gpu_pbo_mib=512
-Dncpb.memory.protection.ffmpeg_mib=1024
-Dncpb.memory.protection.d3d11_logical_mib=2048
-Dncpb.memory.protection.d3d11_surfaces=256
-Dncpb.memory.protection.consecutive_samples=15
-Dncpb.memory.protection.sample_interval_ms=2000
-Dncpb.memory.protection.cooldown_ms=60000
-Dncpb.memory.protection.recovery_ratio=0.65
```

设置 `-Dncpb.memory.protection=false` 会关闭熔断与 NCPB Java 资源计数；若周期诊断日志开启，计数仍会
保留供报告使用。阈值设为 `0` 可单独禁用对应指标。

## 指标说明

- `heap=used/committed/max`：Java 堆，包含对象、集合、RGBA `byte[]` 等。
- `nonHeap=used/committed`：Metaspace、Code Cache 等 JVM 非堆区域。
- `direct=used/capacity(count)`：JVM `BufferPoolMXBean` 可见的 direct buffer。
- `mapped=used/capacity(count)`：内存映射文件 buffer。
- `processCommit`：进程已提交虚拟内存，不是 RSS，也不是独占物理内存。
- `threads`：当前 JVM 活动线程数。
- `gc=count/time`：JVM 启动以来累计 GC 次数和耗时。
- `nativeExact`：本模组通过 `MemoryUtil` 显式持有的当前 native 字节数。
- `decoderNv12`：native NV12 解码帧池。
- `textureStaging`：Y/UV 纹理上传 staging buffer。
- `audio`：OpenAL 上传 scratch/silence buffer。
- `gpuPboEstimate`：本模组已调用 `glBufferData` 建立的 PBO storage 容量；属于 GPU/驱动视角估算。
- `avHeap`：定制 FFmpeg 中仍存活的 `av_malloc` 家族 requested bytes 及其进程生命周期峰值。
- `alloc/realloc/free`：FFmpeg allocator 的累计成功调用次数。
- `d3d11Textures`：FFmpeg 自建且当前仍存活的 D3D11 texture 对象数及峰值。
- `d3d11Surfaces`：上述 texture array 的逻辑 slice/surface 数及峰值。
- `d3d11LogicalEstimate`：按格式、宽高和 ArraySize 计算的逻辑像素容量，不包含驱动对齐、tiling、metadata、residency 或缓存。

斜杠后的数值是本次进程生命周期内峰值。`peakSum` 是各类别独立峰值之和，不保证这些峰值在同一时刻发生。

## 如何判读

### Java 堆泄漏倾向

重复播放和停止后，`heap used` 跨多轮 GC 仍持续抬升，同时 `nativeExact` 与 `gpuPboEstimate` 能回落。

### 本模组显式 native 泄漏倾向

停止播放或退出世界后，`decoderNv12`、`textureStaging` 或 `audio` 不回到空闲基线，并在每次播放后阶梯式增长。

### GPU/PBO 生命周期问题

PBO 环形缓冲建立后 `gpuPboEstimate` 应稳定；停止会话并释放纹理层后应下降。它不应随播放帧数持续增长。

### FFmpeg、硬解或驱动内部增长

如果 `avHeap` 随 `processCommit` 增长，优先检查 FFmpeg packet/frame/buffer pool 或 codec context 生命周期。如果 `d3d11Textures`、`d3d11Surfaces` 或 `d3d11LogicalEstimate` 阶梯增长，优先检查硬解 frame pool 或 decoder context 生命周期。如果这些指标均稳定而进程/GPU committed 继续增长，重点检查驱动、普通 GPU texture/FBO 或其他模组。

旧版 native bundle 不含统计符号时不会输出 `NCPB FFmpeg内存`；必须整套替换同一次构建的 FFmpeg 与 JNI 动态库。

## 不能直接相加的数值

这些指标来自不同观测层级，不能相加得出“总内存”：

- `nativeExact` 已包含在进程内存表现中；
- `direct` 可能与进程提交重叠；
- PBO/纹理/FBO 可能位于专用显存、共享内存或驱动 backing；
- `processCommit` 不是 RSS；
- Java 标准 API 无法可靠提供 Windows 进程 RSS。

诊断 GPU/内核态增长时，应把此日志与任务管理器、Process Explorer、GPUView/WPR 或厂商显卡工具同时使用。