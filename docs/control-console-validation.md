# 中控台验证与验收记录

本文记录可重复测试、ModBench 场景、机器环境、实测结果和未满足门槛。稳定规格见 [`control-console-design.md`](control-console-design.md)，实现状态见 [`control-console-implementation-status.md`](control-console-implementation-status.md)。

## 当前验证快照

验证日期：2026-08-05。

### 自动化测试

- Gradle/JUnit：109 suites / 373 tests / 0 failures / 0 errors / 0 skipped。
- 全量结果通过 `test --rerun-tasks --no-configuration-cache --no-daemon` 取得，避免 `--tests` 过滤和配置缓存造成部分结果被误当作全量。
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
- 流体、原版 AO/光照和群系 tint 的不可变快照编译路径。

历史压力观测曾达到 resident/visible 256～384 section，并记录稳定帧计划缓存命中。该数据用于定位独立 buffer 和逐 section 提交瓶颈，不是产品固定容量。

## 测试矩阵

### 单元与属性测试

- 相机 orbit/pan/dolly/fly、正交/透视和统一矩阵。
- picking、Gizmo 和 renderer 使用同一相机快照。
- 变换有限值、矩阵顺序和 hardRange 几何校验。
- schema 迁移、NBT/网络往返、未来版本只读保护。
- revision、operationId、ACL、编辑/消费租约和限流。
- hardRange 六面、棱、角、重入迟滞和 smoothstep 单调性。
- 视频状态、迟到结果防复活、关闭阶段和内存追踪。
- HTTP headers 前取消、body 早停和 race loser 取消。

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

## 尚未满足的门槛

1. 真实 Bilibili 媒体有负载下，最后一个消费者退出后的 100 次 HTTP/decoder/纹理/PBO/OpenAL 联动基线。
2. Bilibili API resolve、登录 generate/poll、二维码图片和 UI 时长探测请求的逐请求取消接线。
3. 多客户端独立消费者和共享引用计数系统验收。
4. Iris/shaderpack、跨维度、断线重连和客户端退出矩阵。
5. terrain MID/FAR 完整材质简化网格、方块实体 renderer、自定义模组 tint 和透明重排。
6. 中控台专属 IDLE/BUFFERING/ERROR 美术与直播元数据字幕。
7. Phase 8 Maven/JiJ、专用服务器加载和第三方宿主验证。

在这些门槛完成前，当前发布声明保持“功能完整 Beta”，不能宣称最终资源生命周期、多人和兼容验收全部完成。
