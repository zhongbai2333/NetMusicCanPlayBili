# 中控台验证与验收记录

本文记录可重复测试、ModBench 场景、机器环境、实测结果和仍需扩展的矩阵边界。稳定规格见 [`control-console-design.md`](control-console-design.md)，实现状态见 [`control-console-implementation-status.md`](control-console-implementation-status.md)。

## 当前验证快照

验证日期：2026-08-13。

### 自动化测试

- Gradle/JUnit：229 suites / 874 tests / 0 failures / 0 errors / 0 skipped。
- 全量结果通过隔离目录 `build compileBenchJava -PncpbBuildDirectory=/private/tmp/ncpb-full-after-phase64-20260813 --no-daemon --stacktrace` 取得；同一命令同时通过生产 JAR、embedded native、法律材料、冲突副本、Scene Editor API/JiJ 与双宿主静态去重门槛，避免 `--tests` 过滤或共享 build 产物被误当作全量。
- `git diff --check`：通过；Windows 工作区存在 LF→CRLF 提示，但无 whitespace error。

### Integrated-client ModBench

环境：Windows 11、Java 25、Minecraft 26.1.2、NeoForge 26.1.2.76、RTX 5070 Ti Laptop GPU。

统一报告状态：`PASSED`。

| 场景 | 覆盖 |
|---|---|
| `ncpb.console-consumer-lifecycle` | 100 轮共享中控台 consumer attach/detach |
| `ncpb.editor-gui-lifecycle` | 30 轮真实 Screen 打开、渲染、interaction-tree 快照和关闭 |
| `ncpb.terrain-lod-roundtrip` | terrain NEAR→FAR→NEAR 可逆收敛 |
| `ncpb.media-resource-convergence` | 40 tick 视频/OpenAL/HTTP/自有内存空闲收敛 |
| `ncpb.deterministic-video-upload` | RGBA/YUV420P/NV12 各 30 帧真实 GPU 上传和显式释放 |

报告验收同时检查 samples、JFR、loaded mods、生产 JAR 不含 Bench-only 内容和 artifact bundle。

### Phase 66 真实媒体与双客户端系统门槛

2026-08-13 在 macOS 27、Apple M4、Java 25、Minecraft 26.1.2、NeoForge 26.1.2.76 上又完成四组正式
ModBench 验收；每组总体报告、目标场景和 workload correctness 均为 `PASSED`：

| 场景 | 物理拓扑与已证明门槛 |
|---|---|
| `ncpb.real-media-lifecycle-100` | 一个真实客户端连续 100 轮打开 Bilibili DASH H.264 视频、native decoder、NV12/YUV GPU texture/PBO 和 Bilibili 音频/Stereo OpenAL；每轮移除最后消费者后，HTTP、decoder、关闭诊断、纹理/PBO、OpenAL 与全部自有内存必须先回到捕获基线，下一轮才可开始 |
| `ncpb.multi-client-consumer-lifecycle` | 一个独立 dedicated server 与两个独立客户端 JVM；服务端正式 consumer lease 和在线玩家均严格经历 2→1→0，首个客户端退出后幸存客户端继续持有自己的 lease |
| `ncpb.multi-client-reconnect` | 同一三进程拓扑；客户端 0 主动断线并以相同 UUID 重连、重新取得正式 lease，客户端 1 的 lease 全程不被对端断线破坏 |
| `ncpb.multi-client-real-media-lifecycle` | 两个客户端分别加载真实 Bilibili 视频/音频、actual VideoToolbox H.264、direct NV12/YUV/PBO 与 Stereo OpenAL；客户端 0 独立收敛后退出，客户端 1 继续保持媒体和 lease 60 ticks，再独立关闭并回到自己的基线；Iris/MakeUp shaderpack 在两端均由运行时 API 确认启用 |

100 轮场景在 725 个 MEASURE ticks / 36.250 s 内完成。结构化采样观测到 HTTP active `0..2`、视频帧
`0..368,280 bytes`、GPU PBO `0..368,280 bytes`、首个可听 PCM `0..4,096 samples`、自有资源
`0..4,420,384 bytes`，单轮收敛最大 54 ms。`completed_rounds` 是进入本 tick 前的零基完成计数，因而采样最大值
为 99；最终 `verify` 单独要求精确完成 100 轮、状态回到 `READY`、全部资源等于基线，并要求 Stereo OpenAL
created/cleanup-started/cleanup-completed 都恰好增加 100，不能把 99 的采样最大值误读为少跑一轮。

真实媒体双客户端报告中，两端 `real_media_loaded` 恒为 1、`real_media_iris` 恒为 1，owned bytes 均从
4,420,384 回到 0；服务端 warmup 的玩家和 lease 恒为 2，measure 中两者均覆盖 2→1→0。三个角色分别写入
`paired-server`、`remote-client-0`、`remote-client-1` 报告，避免把同一进程内的两个逻辑对象冒充物理多客户端。

这些结果关闭了本文原先列出的单机 100 轮、双客户端独立所有权、断线重连、客户端退出及固定
Iris/shaderpack 真实媒体组合门槛。它们仍只是 Apple M4 + VideoToolbox + 固定 Iris/MakeUp 资产的一组设备证据，
不能外推为所有 GPU、驱动、操作系统、模组或资源包，也不替代 AV1 六平台 v39 发布矩阵。

## GPU 上传证据

确定性场景使用 640×360 本地帧，无 Bilibili 网络和 decoder 依赖：

- 命中 YUV texture staging；
- 命中 Y/UV 两条 NV12 PBO 路径；
- staging 峰值 345,600 bytes；
- 三槽 PBO 峰值 1,036,800 bytes；
- 释放后 tracked bytes 回到场景进入前基线；
- 最新 90 次上传均值 0.737 ms，P95 1.558 ms，P99/最大值包含首次资源创建尖峰。

耗时是该机器上的单次观测，不是跨设备硬阈值。结构化门槛是路径命中、指标存在、显式释放并回归基线。

## HTTP 取消与收敛证据

请求级诊断记录 started、headers received、body published、cancel requested、body close/EOF 和 terminal。诊断有容量上限且只保存标量。

本地 JDK HTTP server 测试覆盖：

1. server 已接收请求但尚未发送 headers 时中断调用线程；
2. body 发布后读取 1 字节并提前关闭；
3. 双 server 首字节竞速中 winner 返回后中断 loser；
4. loser 根 `sendAsync` future 被取消；
5. active request 回到测试前基线；
6. winner 主动早停与 loser 失败取消分别进入正确终态。

当前已接入：HTTP Range/分片/seek、CDN 首包 race、音频小范围 Range race、HTTP-FLV 建连和长连接 body。

ModBench 的 40 次媒体资源收敛采样中，`ncpb.http.active_requests` 的最小值、最大值和各分位均为 0。

## Terrain 验证记录

已验证：

- hardRange 权威覆盖与世界高度裁剪；
- UNKNOWN/NEAR/MID/FAR 切换和 NEAR→FAR→NEAR 可逆重评；
- chunk unload tombstone、CPU pending 清理和 GPU allocation 释放；
- generation/epoch/source identity 拒绝迟到编译；
- 共享 `UberGpuBuffer`、TLSF allocation 和分层批量提交；
- 流体、原版 AO/光照和群系 tint 的不可变快照编译路径；
- MID 4³/FAR 8³ 代表材质聚合的确定性选择与 4/8 单元边界；
- 主线程冻结注册方块 tint layer，后台编译不回调活动世界或模组颜色逻辑；
- 方块实体 render state 的主线程提取与 PIP 提交边界；
- 透明层按 PIP 相机变化仅重排 quad index、不重建世界快照或顶点的策略。

历史压力观测曾达到 resident/visible 256～384 section，并记录稳定帧计划缓存命中。该数据用于定位独立 buffer 和逐 section 提交瓶颈，不是产品固定容量。

### Phase 56 terrain PIP 实机证据

2026-08-13 在 macOS 27、Apple M4、Java 25、Minecraft 26.1.2、NeoForge 26.1.2.76 上，仅运行
`ncpb.terrain-lod-roundtrip` 的 integrated-client 报告总体与场景均为 `PASSED`，且
`client.environment.valid=true`。场景在 void world 中由 integrated server 放置不覆盖既有方块的草方块、蓝色染色玻璃和
箱子夹具，客户端真实打开 PIP，并在退出前切换到非 terrain state 触发 GPU session release。

结构化报告记录：4³ 材质单元最大 37、8³ 单元最大 5、冻结 tint 单元最大 1、材质 section 上传 6、透明 section
上传 1、PIP 相机换位后的透明 quad 重排 2、方块实体提交 218、渲染失败 0。相机换位前后截图分别为
`artifacts/screenshots/terrain-material-pip-before.png` 与 `terrain-material-pip-after.png`，二者 SHA-256 不同并已人工复核材质
远景、范围框、透明/方块实体夹具和视角变化。报告位于
`build/modBench/raw-results/default/client/summary.json`，bundle 位于 `build/modBench/bundles/default/client/`。

### Phase 58 terrain 第三方兼容矩阵

2026-08-13 在同一 Apple M4 环境中，opt-in `ncpb.terrain-lod-roundtrip` 进一步加载固定版本的 Iris/Sodium、
Biomes O' Plenty 及依赖、Colossal Chests 及依赖、Accurate Textures 26.1.2 和 MakeUp Ultra Fast 9.5c。
报告总体/场景均为 `PASSED`；结构化指标确认外部 tint、外部 fluid state、外部 block-entity render state 最大值均为
1，资源包和 shaderpack active 全程为 1，材质上传最大 6、透明上传 2、透明重排 4、方块实体提交 69、失败 0。
Iris 与资源管理器日志分别确认 MakeUp 和 Accurate Textures 确实生效，不能由“文件存在”替代。

固定资产清单、下载/哈希校验器和复现命令见 `tools/terrain_compat_matrix_assets.json`、
`tools/fetch_terrain_compat_matrix_assets.py` 与交接文档 Phase 58。外部二进制不随仓库或生产 JAR 分发。

## 测试矩阵

### 单元与属性测试

- 相机 orbit/pan/dolly/fly、正交/透视和统一矩阵。
- picking、Gizmo 和 renderer 使用同一相机快照。
- 变换有限值、角度规范化、非均匀 scale/pivot/skew 矩阵顺序和变换后 hardRange 四角校验。
- 本地/世界旋转四元数组合、世界非均匀缩放平面基分解、仿射矩形拾取，以及拖动/数值/内容/集合/漫游编辑命令事务。
- schema 迁移、NBT/网络往返、未来版本只读保护。
- revision、operationId、ACL、编辑/消费租约和限流。
- hardRange 六面、棱、角、重入迟滞和 smoothstep 单调性。
- 视频状态、迟到结果防复活、关闭阶段和内存追踪。
- HTTP headers 前取消、body 早停和 race loser 取消。
- 白名单纯音频时长探测在响应头前取消 exact 根请求，并在 body 已发布后关闭 exact 响应流；条目换代和 Screen 移除不接受迟到结果。
- AI CC 的人工/AI 来源筛选、source/session 共享任务、最后消费者精确取消、换会话迟到结果拒绝、不可用与失败区分、
  权威 media tick 选行、翻译开关，以及人工歌词/固定文本安全降级。
- terrain 代表材质聚合的频率/高度/输入顺序决胜、4³/8³ LOD，以及透明层相机位移/旋转重排判定。

### 集成测试

- 唱片机和直播机绑定。
- 多屏共享同一视频资源。
- 多音源共享 PCM 并具有独立空间参数。
- 一名消费者退出不影响其他消费者。
- 未加载 terrain 显示 UNKNOWN 且不强载 chunk。
- GUI/terrain/media 资源关闭后回归基线。

### 安全测试

- 未授权、超距、跨维度和错误方块类型不能修改文档。
- 拒绝过期 revision、重复 operationId、非法枚举、NaN/Infinity 和超大快照。
- 客户端不能提交播放 URL、session 或自称范围资格。

## 仍需扩展的矩阵边界

原先三项系统门槛已由 Phase 66 关闭；跨维度资源清理也已有独立真实 integrated-client 场景。尚未取得的证据是
跨操作系统/GPU/驱动和更广模组组合的重复矩阵，而不是当前固定组合中的功能缺口。terrain 与真实媒体目前分别有
vanilla 和一组固定第三方资源包、自定义 tint/流体/方块实体、Iris/MakeUp shaderpack 证据；该单一 Apple GPU
组合仍不能外推为所有模组、资源包或硬件。AV1 六平台 v39 native bundle、硬解输出重排序和设备资源基线继续由
`av1-video-migration-plan.md` 跟踪，不能用本节 H.264 生命周期验收替代。
