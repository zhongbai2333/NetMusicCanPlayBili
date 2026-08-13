# 项目整理与真实客户端验证交接

> 更新时间：2026-08-13  
> 主仓库：`/Users/zhongbai233/Documents/GitHub/NetMusicCanPlayBili`  
> BenchMod：`/Users/zhongbai233/Documents/GitHub/BenchMod`  
> 当前分支：`master`  
> 状态：主项目已提交并推送到 `master`，当前发布基线为 `fb6c4f8`；对应 GitHub Actions
> `31677258431` 已成功。

## 重启窗口后的第一条指令

建议在新会话中直接说明：

> 请先阅读 `docs/session-handoff-2026-08-10.md`，检查当前 `git status`；以 `master/fb6c4f8` 为发布基线，
> 优先处理用户实际报告的问题。没有可用物理设备矩阵时，不把扩展兼容认证作为发布阻断项。

## 当前结论

首轮高风险稳定性整理、会话所有权收敛、客户端播放命令乱序缓解、局部聚合类拆分、音频/视频、Pad 与 CDN 配置边界统一、强类型媒体请求 token 与 resolve generation、MP4 会话注册表、source discovery、queue controller、progress persistence 与 audience broadcaster 五个边界拆分，以及真实 BV、真实 MP3/OpenAL、Minecraft SoundEngine streaming channel、真实现代唱片机端到端客户端验证和 AV1 候选首帧双预算均已完成。

当前基线：

- `master` 提交 `fb6c4f8` 已推送，工作树与 `origin/master` 同步；
- 本地 47-task `clean build` 成功，远端 Build & Release `31677258431` 成功；
- CI 产出的六平台通用 JAR 为 `9,342,446 bytes`，低于 10 MB；
- `media-min-v48` 的 Linux/macOS/Windows ARM64/x86_64 六个 hosted runner 均完成真实动态加载、
  runtime identity 和 JNI exports 核验，包括 `macos-15-intel`；
- Java 全量测试通过；
- native importer/architecture/runtime/AV1 smoke Python 工具测试：36/36 通过；
- `git diff --check` 通过；
- ModBench 报告 Schema 验证通过；
- 真实 BV `BV1GJ411x7h7` integrated-client 场景通过；
- 真实 MP3 seek/OpenAL、SoundEngine streaming channel、retained-session transport refresh 与现代唱片机端到端
  integrated-client 场景通过，启用该 opt-in 的全量报告为 12/12 PASSED；
- 真实现代唱片机方块的客户端右键插入/取出 packet 与事务自动提取场景通过；
- 真实现代唱片机插入公网 MP3 后的服务端 resolve、网络同步、客户端 SoundEngine/OpenAL 输出和弹出清理完整链通过；
- 真实跨维度往返的两次 respawn/clone packet、加载 UI、ClientLevel unload 与 exact media cleanup 场景通过；
- 最新默认 integrated-client 全量报告为 8/8 PASSED；
- BenchMod 独立构建与三组 paired/一组 100 轮真实媒体报告通过；BenchMod 是独立仓库，不属于本次
  NetMusicCanPlayBili `fb6c4f8` 推送范围。

阶段记录按发生顺序保留，因此下文 v39/v46、dav1d 和“尚未生成”的表述描述的是当时状态；阶段 71 的 v48
硬件 AV1 + H.264 回退决策覆盖这些历史方案。它们不再是当前待办，也不得据此重新把软件 AV1 带回发布包。

上述基线已覆盖 `ResolveGeneration`、`PlaybackSessionId` 前二十七阶段切片、
`PlaybackSourceId` 前四阶段切片、第 28 阶段真实 MP3 decoder/OpenAL 换代验证、第 29 阶段真实
SoundEngine streaming channel 创建、换代和销毁验证、第 30 阶段真实 pause→快速 resume 输出连续性验证，
第 31 阶段 retained-session 真实 transport refresh 与旧 retry 抑制验证、第 32 阶段播放中客户端世界卸载
清理验证、第 33 阶段取出唱片的服务端 exact-session stop 与真实 SoundEngine/OpenAL 收敛验证、第 34 阶段
静音/超距 exact-session stop、第 35 阶段真实方块右键与事务提取入口验证、第 36 阶段真实跨维度
respawn/UI/world-unload 往返验证、第 37 阶段真实唱片机服务端 resolve/同步/MP3/OpenAL/弹出端到端验证，
第 38 阶段 AV1 playurl 冻结 JSON 候选矩阵、第 39 阶段 AV1 发布能力/法律材料自动门槛、第 40 阶段
AV1 硬解候选首帧时间/packet 双预算、实际硬件 backend 准入和 fallback 关闭屏障、第 41 阶段 native 精确
文件集/SHA-256、第 42 阶段六平台 runtime smoke 接线、第 43 阶段真实 AV1 fMP4 字节夹具、SIDX range
重建和 composition-time 语义，以及第 44 阶段 projection owner/session 双域物理关闭准入、四种 codec policy、
软件 AV1 安全上限和 v39 dav1d/EOF native 实际构建验证，以及第 45 阶段 AV1 五秒持续性能预算、一次性
H.264 锁定回退和请求/实际 codec/backend 用户可见诊断，以及第 46 阶段真实 AV1 delayed-output EOF drain
确定性验证、第 47 阶段中控台 revision 冲突权威文档回包，以及第 48 阶段同步 B站 API/扫码登录请求的
根 future 取消与请求级关闭诊断、第 49 阶段 OpenAL source/buffer 删除失败聚合诊断、第 50 阶段中控台
专属 IDLE/BUFFERING/ERROR 视频状态美术、第 51 阶段纯音频时长探测的请求/响应体精确取消，以及第 52 阶段
直播标题、房间与状态元数据字幕，以及第 53 阶段中控台 schema v6 完整仿射变换、本地/世界 Gizmo、统一
场景命令栈和投影 owner/session 双域物理替换屏障，以及第 54 阶段 B站 AI CC 来源、source/session 共享所有权、
权威媒体时间线、精确取消和人工歌词/固定文本安全降级。

本轮兼容契约说明：

- 中控台 NBT 文档已显式升级到 schema v6；schema v5 及更早文档以恒等 scale/pivot/skew 迁移，原有字段保持
  原义；
- 中控台配置与冲突权威文档 packet 同步增加八个高级变换浮点字段，服务端与客户端必须使用同一当前版本；其他
  MP4/Pad packet 兼容契约未因阶段 53 改动；
- 既有正式运行配置默认值；第 40 阶段只新增默认 `2000ms/256 packets` 的 AV1 硬解候选探测键；opt-in
  真实 MP3 Bench 的公网夹具默认 URL 已为稳定全量回归单独更新；
- 正式构建默认是否启用 BenchMod。

## 已完成的核心改造

### 1. 服务端异步 resolve 准入

涉及：

- `ModernTurntableBlockEntity`
- `TurntableResolveAdmissionPolicy`
- `MP4ResolveIntentRegistry`
- `MP4PlaybackSyncManager`
- `MP4PlaybackControlPacket`

结果：

- 现代唱片机使用 playback intent generation；
- stop、pause、restart、seek、换碟会使旧 resolve 失效；
- MP4 自动发现和手动命令共享 generation/intention 语义；
- 同 URL 连续 seek/restart 的旧 completion 不再覆盖新状态；
- 自动发现 pending resolve 支持去重和精确失效。

### 2. 实际 worker cancellation

新增：

- `util/concurrent/CancellableTaskFuture`

接入：

- `ClientMediaPreparer`
- `ClientMediaPrepareLauncher`
- `ModernTurntablePlaybackCoordinator`
- `ModernTurntableVideoClient`
- `ClientMediaLifecycleHandler`

结果：

- `CompletableFuture.cancel(...)` 会传播到实际 executor worker；
- 音频准备、歌词解析、视频 resolve 在 timeout、replace、stop、world unload 和 disconnect 时被主动中断；
- 使用 exact owner removal，旧 completion 不会删除新任务；
- MP4/Pad 歌词 worker 不再 fire-and-forget，而是归 source/session owner 管理。

### 3. 客户端播放会话所有权

涉及：

- `ClientPlaybackSession`
- `ModernTurntablePlaybackTracker`
- `ModernTurntablePlaybackCoordinator`
- `ModernTurntableVideoClient`
- `VideoBillboardPreview`

结果：

- `ClientPlaybackSession` 支持可替换命名资源槽；
- 同 slot 换代会立即取消旧资源；
- session 取消后迟绑定的资源会立即关闭；
- cancel 与 replace 并发时资源仍能恰好收敛；
- 现代唱片机会话直接拥有 `video-resolve` worker；
- 已移除 `VideoBillboardPreview -> ModernTurntableVideoClient` 的反向生命周期清理；
- 当前清理方向为 coordinator/session → video client → renderer。

### 4. decoder 关闭超时与 zombie 监督

新增：

- `VideoDecoderRestartState`
- `VideoZombieCloseSupervisor`

修改：

- `VideoPlaybackInstance`
- `VideoBillboardPreview`
- `ModernTurntableVideoClient` 诊断输出

结果：

- decoder restart 先进入 `CLOSING`；
- 旧 native decoder 未在 timeout 内关闭时进入 `FAILED_CLOSE`；
- close timeout 后禁止启动第二个 native decoder；
- generation 换代阻止迟到 barrier completion 复活；
- 超时后仍未物理收敛的旧 generation 由 zombie supervisor 跟踪；
- 生命周期诊断包含 active close zombies 和 late close convergences。

### 5. 聚合类局部拆分

新增：

- `VideoResolveRequestOwner<T>`
- `VideoSessionInstanceRegistry<T>`
- `PendingVideoSessionRegistry<T>`
- `VideoConsumerRegistry<T>`
- `LegacyPreviewSessionState<T, R>`
- `LegacyPreviewWorkerLifecycle<W, D>`
- `LegacyPreviewTextureLifecycle<R, Y, P>`
- `VideoResourceDiagnosticsCollector<T>`

结果：

- pending video resolve 的 owner 已从 `ModernTurntableVideoClient` 抽出；
- owner 泛型化，不依赖 Minecraft 类；
- 可独立验证 cancellation、迟绑定和 request metadata；
- `ModernTurntableVideoClient` 的公开 API 保持不变；
- session → `VideoPlaybackInstance` 的并发存储和 disposal 已从 `VideoBillboardPreview` 抽出；
- replacement、session stop、条件淘汰和全局 clear 统一执行一次实例 `stop()`；
- facade 查询与渲染 API 保持不变；
- registry 泛型化，可用无 Minecraft 依赖的对象独立测试精确删除和清理语义；
- pending loading/failure 双 Map 已合并为单 session 状态 registry；
- loading 与 failure 保证互斥，重入 loading 保留首次计时，failure retry 会开启新计时；
- projector 更新、剥离、空引用回收和 session clear 已统一；
- 控制台仍保持 failure 优先、loading 次之的占位状态解析顺序。
- 单播放实例的 projector 集合和 GUI consumer 标志已从 `VideoPlaybackInstance` 的原始字段抽出；
- projector replacement、add/remove、去重、null 过滤和 direct-consumer 状态由 registry 统一管理；
- 渲染和音频范围路径使用不可变 projector 快照，替换期间不会暴露可变集合；
- 全息眼镜 consumer 仍由 `VideoPlaybackInstance` 结合 Minecraft 客户端状态判断，未并入纯 Java registry；
- `VideoBillboardPreview` facade 和 BER 管理集合保持不变；
- legacy singleton preview 的 session、request、projector 快照、primary projector 与 requires-projector
  状态已合并为单个不可变快照；
- start、replace、projector detach/prune、retry 和 full clear 不再分别改写五个松散的 legacy 字段；
- projector 换代时旧快照保持稳定，primary projector 会在 detach/prune 后确定性修复；
- replace close 保留 retry 所需 session/request，但清除旧 projector 绑定；
- legacy decoder 的 start admission、running、generation、worker thread 和 active decoder 所有权已统一；
- stop 会原子剥离 thread/decoder 并使 generation 失效，再沿用既有 interrupt 与异步 close；
- decoder 打开期间发生 stop 时，迟到 decoder 绑定会被拒绝并由 worker `finally` 关闭；
- 旧 generation completion 不会停止或清空 replacement worker/decoder；
- CPU bars 和 ffmpeg 测试图的延迟上传也按 generation 准入，旧任务不会写入新预览纹理；
- legacy RGBA、YUV/NV12 和 packed bench 纹理已由单一生命周期对象持有；
- slot replacement 只释放对应旧资源，full clear 会继续尝试释放全部 slot 且重复 clear 幂等；
- stop、replace、bench release、上传和渲染查询均不再读取三个裸静态纹理字段；
- YUV 固定纹理 ID 仍保持“先关闭旧集合、再创建 replacement”的注册顺序；
- RGBA 尺寸换代仍保持原有 full legacy texture clear 语义，未改变 bench/preview 资源契约；
- 实例 running/failure/projector/GUI 计数和 pending、BER、zombie 外部计数已由纯 Java collector 聚合；
- 公开 `ResourceDiagnostics` 的类型、10 个字段、字段顺序与日志调用者保持不变；
- 空实例状态仍会保留 pending/BER/zombie 计数，collector 输出不受后续源集合变化影响。

注意：

- `VideoBillboardPreview` 现为 3473 行；
- `MP4PlaybackSyncManager` 现为 849 行，session registry、source discovery、queue controller、progress persistence 与 audience broadcaster 五个计划内边界均已抽出；
- 本轮只做安全切口，没有进行大规模重写。

### 6. 配置读取边界

新增：

- `AudioRelayProperties`
- `BiliApiProperties`
- `VideoPipelineProperties`
- `PadMapProperties`
- `PadRenderProperties`
- `PadDiagnosticsProperties`
- `CdnProperties`
- `Fmp4NativeVideoProperties`
- `AudioSyncProperties`
- `Fmp4StreamProperties`
- `MediaCloseProperties`
- `MemoryProperties`
- `AudioStreamProperties`
- `TimelineProperties`
- `VideoFeatureProperties`
- `ClientMediaPrepareProperties`
- `PlaybackRuntimeProperties`
- `ProjectorRenderProperties`
- `OpenAlHrtfProperties`
- `VideoClientProperties`
- `IrisShaderpackProperties`
- `ClientDisplayProperties`

结果：

- Stereo/Dolby relay mute 共享新旧 key precedence；
- legacy/new 视频 pipeline 共享网络错误占位开关；
- RGBA pixel mode/快速 native upload、NV12 PBO/RG8、YUV matrix/shader debug/depth write 已并入 `VideoPipelineProperties`；
- upload 与 YUV 字符串模式统一 trim，matrix 转小写、shader debug 转大写；
- 上传优化开关的非法布尔值回退既有 enabled 默认，YUV depth-write 诊断默认保持关闭；
- `VideoPlaybackInstance` 的 17 个 timing/offscreen/presentation 初始化参数和 8 个运行时阈值已集中；
- audio latency 与 offscreen resume lag 新增规范 `ncpb.*` 主键，同时保留原 `bili.*` 键回退；
- startup drop/decode lead/upload warn/early tolerance/visible lag/prebuffer/loading placeholder 继续按调用时读取；
- 毫秒到纳秒换算位置、queue capacity、source size、Iris placeholder offset 与所有默认值保持不变；
- 非法 timing 数值、非有限 offscreen/placeholder 偏移和非法 loading boolean 会安全回退；
- Pad 地图采样布局和客户端缓存调度的 33 个属性键已从 `PadMapSampler`、`PadMapClientCache` 集中；
- `map_view_width` 继续优先于旧 `map_size`，width/height 继续从 view size 与 overscan 派生；
- chunks-per-tick、重试、recenter、室内检测、zoom、缓存上限和磁盘缓存开关由不可变配置快照发布；
- dirty chunks、update interval 和 unknown retry 的既有最小值钳制保持不变；
- Pad 离屏缩放、GUI 拖动/旋转阈值、刷新/FPS、手持偏移、地图纹理和性能日志的 14 个当前属性键已集中；
- `pad.offscreen_scale` 继续优先于旧 `mp4.offscreen_scale`，离屏 renderer 与 handheld profile 共享同一解析语义；
- pan/yaw、playback refresh 和 map layer tick 的既有最小值钳制保持不变；
- Pad renderer/GUI 的非法数值、非有限 float 和非法 boolean 不再导致类初始化失败或静默改变默认行为；
- `pad.video.debug_log` 已由客户端准备、同步、手持解码、物品 renderer 和服务端网络处理共享；
- `pad.map.server_self_test` 的启动注册与实际执行判定共享同一严格布尔解析；
- 公共 Pad 诊断属性类不依赖客户端类型，保持 dedicated server 类加载安全；
- CDN selector 的启用/竞速、probe 大小/超时、候选数、持久化节流、后台竞速和首选 host 已集中；
- CDN fallback group 上限使用同一属性边界，既有最小值钳制保持不变；
- 两个历史 `ncpb.ncpb...` typo race 键继续作为 legacy fallback 支持；
- 首选 CDN host 继续 trim 并转为小写，非法布尔/数值统一回退兼容默认值；
- `Fmp4NativeVideoDecoder` 的 16 个 buffering/recovery/range-seek 参数已集中为 decoder 与 seek 两份不可变配置快照；
- seek auto offset、no-copy drop guard、stream recovery attempts 和 segment-base cache entries 新增规范 `ncpb.*` 主键，并保留原 `bili.*` 键回退；
- pending frames、init probe、moof scan、seek attempts 和 segment cache 的容量下限为 1，preroll、close-fragment、epsilon、recovery attempts 和 drop guard 的安全下限为 0；
- native decoder 的非法布尔/数值和非有限 seek 浮点值统一回退兼容默认值，原默认值与毫秒到纳秒换算位置保持不变；
- Stereo/Dolby 共用的 5 个音频同步阈值已集中，4 个旧 `bili.*` 键新增规范 `ncpb.bili.*` 主键并保留 legacy fallback；
- audio catch-up/output-lag/flush-ahead 的既有非负钳制和默认值保持不变，`catchUpStartTicks=Long.MAX_VALUE` 时 full threshold 不再因加一溢出；
- fMP4 stream buffered-payload 上限已集中，既有 64 MiB 默认值和 1 MiB 最小值保持不变，非法值安全回退默认值；
- 异步 media close 的线程数/队列容量、OpenAL soft/hard/retry 和视频 close soft/hard 共 7 个属性已集中；
- close executor 的容量下限继续为 1，hard timeout 继续不小于 soft timeout，OpenAL retry 继续保持正值；
- close diagnostics 的毫秒到纳秒换算改用饱和转换，超大配置不会再因乘法溢出变成负数；
- `MediaCloseProperties` 不依赖客户端或 native 类型，保持 dedicated server 类加载安全；
- memory diagnostics/protection 的 2 个开关、报告/采样周期和 8 个熔断参数共 12 个属性已集中；
- diagnostics/protection 开关改用严格布尔解析，recovery ratio 改用有限 double 解析，非法值统一回退兼容默认值；
- report/sample/cooldown 最小值继续为 1000/500/5000ms，0 MiB 限额继续关闭对应指标；
- MiB 到 bytes、毫秒到纳秒和下一次采样/报告 deadline 均使用饱和计算，超大配置不会溢出为负数；
- `MemoryProperties` 不依赖客户端、Minecraft 或 native 类型，公共资源 tracker 保持 dedicated server 类加载安全；
- HTTP audio format/range-race/cache、Dolby prebuffer/queue、直播视频样本总线和流恢复共 10 个参数已集中；
- audio segment-base cache 新增规范 `ncpb.bili.*` 主键，并保留原 `bili.*` 键回退；
- format wait、race candidates/timeout、segment cache、Dolby queue 和 live bus 的既有有效下限保持不变；
- Dolby prebuffer 与恢复 attempts/interval 统一非负，processed queue 在极端配置下仍至少保留一个槽位；
- `AudioStreamProperties` 不依赖客户端、Minecraft 或 native 类型，保持 dedicated server 类加载安全；
- shared media clock、turntable timeline 和 handheld audio anchor 的 12 个参数已集中；
- hard-sync/max-correction 新增规范 `ncpb.media.*` 主键，继续按 `ncpb.media.*`、`bili.media.*`、旧 `ncpb.turntable.*` 三级优先级解析；
- smooth/visual correction ratio 统一有限 double 解析并钳制到 0..1，非法与非有限值回退兼容默认值；
- hard-sync、correction、audio lag/lead 均保持非负，clock prune 继续至少 1000ms；
- clock prune 毫秒到纳秒使用饱和换算，超大配置不会溢出为负数；
- advanced/bench feature gates、动态 boolean/int/long/string override 与 native hwaccel 已并入 `VideoFeatureProperties`；
- real bench 注册/managed 开关与 FFmpeg decoder override 也已并入 `VideoFeatureProperties`，可选 bench source set 复用同一严格解析边界；
- advanced feature 关闭时仍忽略全部高级覆盖，`os.name` 继续只用于运行环境的自动 hwaccel 候选识别；
- feature boolean 改用严格解析，字符串统一 trim 并忽略空白，非法 typed override 回退调用方默认值；
- `HandheldVideoPipelineConfig` 的 11 个动态-prefix pipeline 参数已统一使用 `NcpbSystemProperties`；
- handheld max frames、frame wait 和 queue capacity 保持正值，其余 timing 阈值保持非负；
- handheld 毫秒到纳秒改用饱和换算，超大配置不会溢出为负数；
- 客户端媒体准备线程数，以及现代唱片机、MP4 与 Pad 的 3 个准备超时属性已并入 `ClientMediaPrepareProperties`；
- 准备线程数继续至少为 1，3 个超时继续至少为 3 秒，既有 2/20/12/12 默认值保持不变；
- 媒体准备属性的非法数值统一回退兼容默认值，0 和负值统一钳制到安全下限；
- 直播/现代唱片机的 4 个 watchdog 参数、OpenAL pacing 提前容差和 2 个漂移诊断阈值已并入 `PlaybackRuntimeProperties`；
- watchdog 毫秒到 tick 的既有整数换算保持不变，直播 stall 继续至少 100 tick，点播 startup/no-progress 继续至少 20 tick；
- OpenAL pacing 和 A/V 漂移诊断新增规范 `ncpb.*` 主键，并保留原 `bili.*` 键回退；结束宽限和诊断阈值统一非负；
- 视频投影边界的 2 个参数，以及歌词滚动/同步和边界计算的 7 个参数已并入 `ProjectorRenderProperties`；
- 歌词滚动插值半衰期新增规范 `ncpb.*` 主键并保留原 `bili.*` 键回退，既有 35ms 默认值保持不变；
- 投影几何的非法和非有限 double 统一回退兼容默认值；边界尺寸、margin、滚动时长和音频延迟统一非负，插值半衰期至少 1ms；
- OpenAL HRTF 的强制启用、禁用和 Channel 模组兼容覆盖 3 个开关已并入 `OpenAlHrtfProperties`；
- HRTF 开关新增 snake_case 规范键并保留原 camelCase 键回退，禁用继续优先于强制启用；
- HRTF 开关统一严格布尔解析，非法规范值会尝试旧键后再回退兼容默认值；
- 现代唱片机、直播和手持视频客户端的启用/线程/FPS/质量/hwaccel/MP4 离屏缩放共 8 个直接读取已并入 `VideoClientProperties`；
- 唱片机解析线程和直播质量新增规范 `ncpb.*` 主键并保留原 `bili.*` 键回退，既有默认值保持不变；
- 唱片机解析线程至少为 1，直播 FPS 至少为 15，直播质量和 MP4 离屏缩放保持正值，手持视频线程至少为 2；
- hwaccel 字符串统一 trim 并忽略空白，非法布尔/数值统一回退兼容默认值；高级 feature gate 下的参数语义未改变；
- Iris YUV compatibility 的 4 个动态开关和 program/shader-key 两个动态字符串已并入 `IrisShaderpackProperties`；
- custom YUV shader disable 新增规范 `ncpb.*` 主键并保留原 `bili.*` 键回退，其他键与默认值保持不变；
- Iris 开关统一严格布尔解析，program/shader-key 统一 trim 并按 `Locale.ROOT` 转大写，空白 program 回退兼容默认值；
- Iris 参数继续按调用时读取，shaderpack 状态切换和诊断路径的动态语义保持不变；
- `VideoBillboardPreview` 的 world anchor、YUV immediate、visibility、render backend 和 projector teleport 共 15 个初始化参数已扩展并入 `VideoPipelineProperties`；
- pipeline chase window 的 legacy preview 调用点也已复用既有动态属性访问，不再直接调用 `Long.getLong`；
- occlusion cache 新增规范 `ncpb.*` 主键并保留原 `bili.*` 键回退，stage/coords/pose/backend 统一 trim 并转小写；
- 非法布尔、数值和非有限距离统一回退兼容默认值；dot/edge scale 钳制到有效范围，距离平方和毫秒到纳秒使用饱和换算；
- 中控台视频健康检查、全息眼镜世界屏幕和 MP4 投影输入 3 个客户端显示属性已并入 `ClientDisplayProperties`；
- 中控台与全息屏幕继续在 renderer 类加载时生成配置快照，MP4 投影输入继续按调用时动态读取；健康检查周期继续至少为 100ms；
- 客户端显示开关改用严格布尔解析，非法布尔/数值统一回退兼容默认值；
- Bili 启动凭证、Web Cookie、UA/轮换、音频偏好、直播离线重试和视频 codec policy 共 7 个属性已并入
  `BiliApiProperties`；
- 配置文件继续覆盖启动属性快照，音频偏好继续按请求动态读取并统一 trim/小写；直播离线重试继续至少 10 秒；
- 秒到毫秒和退避 deadline 改用饱和换算，极端配置与接近 `Long.MAX_VALUE` 的时钟值不会溢出为已过期时间；
- 真实播放与渲染压力 bench 的 NV12 RG8 开关已复用 `VideoPipelineProperties`，不再保留独立宽松布尔解析；
- `NcpbSystemProperties` 新增非空字符串 current/legacy/default 解析；
- `NcpbSystemProperties` 新增有限 float 解析，非法值、`NaN` 和 `Infinity` 会回退到兼容默认值；
- 使用既有 `NcpbSystemProperties` 处理非法值、legacy fallback 和默认值；
- 默认行为保持不变。

### 7. 客户端播放命令乱序准入

新增：

- `ModernTurntableCommandAdmissionPolicy`

修改：

- `ModernTurntablePlaybackCoordinator`

结果：

- `tryStart(...)` 前先执行纯 Java command admission；
- 客户端可观测的方块实体 authoritative session 与 incoming session 一致时允许换代；
- 方块实体明确播放不同 session 时丢弃迟到命令；
- authoritative 状态暂不可用且 tracker 无冲突时保留兼容 fallback；
- tracker 已有不同 session 时阻止旧命令反向替换；
- 直播入口保持原行为；
- 未修改 packet codec 或字段格式。

限制：没有 packet 级 source generation，当前策略是兼容缓解，不代表建立了完整网络全序。

### 8. MP4 会话注册表边界

新增：

- `MP4PlaybackSessionRegistry<K, S>`

修改：

- `MP4PlaybackSyncManager`

结果：

- active session 与 missing-source grace timestamp 由同一 registry 持有；
- 会话 replacement、stop、队列变更、自动发现迁移、tick 更新和停服 clear 已统一接入；
- 会话放入、替换和删除会同步清除旧 missing grace；
- tick 使用不可变 entry 快照，不再直接修改 `ConcurrentHashMap` iterator；
- tick、队列重映射、resume 和 source migration 使用同实例条件替换/删除；
- 旧 tick 或旧 discovery 状态无法覆盖、删除等值但已换代的新 session；
- 自动发现启动 resolve 前会再次检查 active session，收窄发现与手动 start 之间的竞态窗口；
- 服务器停止时 session 与 missing grace 会一起清空；
- runtime progress 已由独立 persistence 边界持有；
- packet、NBT、公开 API 与默认行为保持不变。

### 9. MP4 播放源发现边界

新增：

- `MP4PlaybackSourceDiscovery`
- `MP4PlaybackSourceObservationPolicy`

修改：

- `MP4PlaybackSyncManager`

结果：

- 周期性玩家背包、附近掉落物和打开容器扫描已从 manager 移出；
- 容器 open/close 事件统一委托给 discovery 边界；
- active source refresh、missing source relocation 和菜单容器迁移已集中；
- 玩家、掉落物、方块容器和实体容器的 START/KEEP/MIGRATE 判定由纯 Java policy 负责；
- source migration 继续使用 session registry 的同实例条件替换，不会覆盖已换代会话；
- 同一维度内被多个玩家范围覆盖的掉落物每轮只扫描一次；
- relocation 去重按维度隔离，不会混淆跨世界相同 entity id；
- manager 仅通过回调保留 resolve/start 与 network broadcast 责任；
- manager 从约 1550 行降至约 1310 行；
- packet、NBT、播放范围、发现周期和迁移优先级保持不变。

### 10. MP4 队列控制边界

新增：

- `MP4PlaybackQueueController`
- `MP4PlaybackQueuePolicy`

修改：

- `MP4PlaybackSyncManager`

结果：

- 队列编辑后的 KEEP、REMAP、STOP 决策已从 manager 抽出；
- 当前歌曲仍存在时保持 elapsed/session identity，只按新位置重映射 queue index；
- 当前歌曲被删除时统一执行 exact session removal、resolve invalidation、stop、进度归零和设备状态更新；
- 自然播完后的单曲循环、顺序前进、列表循环和停止由纯 Java policy 决定；
- controller 统一负责下一曲状态写入、runtime progress、容器 dirty 标记和 discovery restart；
- queue item URL 与时长读取、设备队列优先级也集中到 controller；
- manager 的公开 `reconcileQueueChange(...)` facade 保持不变；
- manager 从约 1310 行降至约 1205 行；
- packet、NBT、repeat mode 数值、进度和选中索引兼容语义保持不变。

### 11. MP4 进度持久化边界

新增：

- `MP4PlaybackProgressPersistence`
- `MP4PlaybackProgressPolicy`

修改：

- `MP4PlaybackSyncManager`

结果：

- runtime progress Map 已从 manager 移入 persistence 对象；
- current elapsed 按 active session、runtime、SavedData、fallback 的原优先级解析；
- queue-specific elapsed 只有 queue index 匹配时才使用 runtime；
- 自动恢复目标统一保留媒体结尾前 50ms 的保护区；
- runtime record、单设备 flush、全量 flush、设备状态同步和停止进度写回已集中；
- level save 与 server stop 的 flush 路径统一委托 persistence；
- server stop 完成 flush 后清空 runtime Map，避免同 JVM 下次开服继承旧状态；
- source level 已不可用时仍保留 normalized runtime entry，并安全跳过世界状态写入；
- manager 的公开 progress 查询与记录 facade 保持不变；
- manager 从约 1205 行降至约 1075 行；
- `MP4PlaybackSavedData.Entry`、Codec、NBT 字段和默认值保持不变。

### 12. MP4 观众与网络广播边界

新增：

- `MP4PlaybackAudienceBroadcaster`

修改：

- `MP4PlaybackSyncManager`
- `MediaAudienceRoutingPolicy`

结果：

- full sync、timeline、stop packet 的构造和发送已从 manager 集中到 broadcaster；
- source discovery 与 queue controller 通过 broadcaster 回调发布，不再依赖 manager 内部发送实现；
- 公共附近观众、在线 owner 与耳机监听者三类路由由统一策略决定；
- 普通 MP4/player source 在已有耳机投递时继续抑制附近公共重复投递；
- Pad player source 继续只向未收到耳机路由的在线 owner 投递公共 packet，并抑制附近 stop；
- stop 仍会送达 owner 和已索引耳机监听者，外部耳机切换时仍只停止范围内非关联监听者；
- 耳机失联、跨维度、超距停止、自动解绑和提示语义保持不变；
- 播放白名单拒绝继续使用 exact session removal，并使对应 resolve intent 失效后广播 stop；
- fallback stop packet 与安全 host 日志也已移入 broadcaster；
- manager 的公开 `stopExternalPlaybackForLinkedHeadphones(...)` facade 保持不变；
- manager 从约 1075 行降至 849 行；
- packet codec/字段、NBT、同步范围、公开 API 和默认行为保持不变。

### 13. 强类型媒体请求 token

新增：

- `MediaRequestToken`

修改：

- `OneShotRequestRegistry`
- `PlaybackSync`
- `HttpAudioStreamHandler`
- `SyncedMediaPlaybackLauncher`
- `ModernTurntablePlaybackCoordinator`
- `ModernTurntableSound`

结果：

- 一次性请求 registry 内部改用 `MediaRequestToken` 作为 key，并提供强类型 register/consume/contains/cancel API；
- 空白、超过 128 字符或含 `&`、`#`、`=` 等 URL fragment 保留字符的 token 会在边界被拒绝；
- `PlaybackSync` 新增强类型写入和 `Optional<MediaRequestToken>` 解析，原字符串入口继续兼容；
- HTTP 注册结果与客户端 launch 结果不再用空字符串表示“无 token”，改用 `Optional`；
- 现代唱片机首次播放、直播播放与流恢复的取消回调均持有强类型 token；
- `nmb_request=<字符串>` URL 格式、一次性消费、TTL、反序到达和显式取消语义保持不变；
- 未修改 packet codec、NBT 或媒体 URL 的外部序列化格式。

### 14. 强类型 resolve generation

新增：

- `ResolveGeneration`

修改：

- `MP4ResolveIntentRegistry`
- `ModernTurntableBlockEntity`
- `TurntableResolveAdmissionPolicy`
- `VideoResolveRequestOwner`
- `ModernTurntableVideoClient`
- `LiveStreamerBlockEntity`
- `LiveStatusProbePolicy`

结果：

- MP4 resolve intent、现代唱片机服务端 resolve、客户端视频 resolve 和直播状态请求四条运行期所有权链均改用强类型 generation；
- generation 明确拒绝负数，初始值保留为 0，正常请求从 1 开始，`Long.MAX_VALUE` 后回到 1，避免有符号溢出；
- registry 与客户端全局序列使用原子强类型换代，准入和 exact removal 统一使用值相等比较；
- `VideoResolveRequestOwner` 不再暴露裸 `long requestId`，日志边界才展开为数值；
- 持久化到 NBT 的 `seekGeneration`、直播单次探测身份 `liveStatusProbeId` 和其他非 resolve generation 未迁移；
- 未修改 packet codec、NBT、媒体 URL 或 session ID 的外部序列化格式。

### 15. 强类型 playback session ID（前二十六阶段）

新增：

- `PlaybackSessionId`
- `MP4PlaybackRuntimeProgress`

修改：

- `PlaybackSync`
- `ClientPlaybackSession`
- `ModernTurntablePlaybackTracker`
- `ClientPlaybackCommand`
- `PlaybackRequest`
- `VideoSessionInstanceRegistry`
- `PendingVideoSessionRegistry`
- `LegacyPreviewSessionState`
- `VideoZombieCloseSupervisor`
- `SyncedStreamRecoveryRegistry`
- `LiveVideoSampleBus`
- `ClientMinecartAudioAnchors`
- `LiveStreamerVideoClient`
- `MP4PlaybackSyncManager`
- `MP4DeviceStateStore`
- `MP4PlaybackProgressPersistence`
- `MP4PlaybackQueueController`
- `VideoBillboardPreview`
- `VideoPlaybackInstance`
- `PreviewVideoPlaybackAnchor`
- `VideoAudioPresenceRegistry`
- `ModernTurntableVideoClient`
- `ModernTurntablePlaybackDiagnostics`
- `TurntableVideoPlaybackAnchor`
- `LiveVideoPlaybackAnchor`
- `MediaTimelineClock`
- `ModernTurntableTimeline`
- `HandheldMediaPlayback`
- `ClientMediaTimelineView`
- `VideoCloseDiagnostics`
- `SyncedMediaSound`
- `ModernTurntableSound`
- `LiveStreamerSound`
- `ClientMediaMovingSound`
- `HttpAudioStreamHandler`
- `MP4HandheldVideoClient`
- `ControlConsoleRenderer`
- `VideoProjectorRenderer`
- `ClientAudioOutputRegistry`
- `ClientMediaPlayback`
- `MediaVideoTimeline`

结果：

- session ID 明确限制为非空、最长 128 字符且不含 `&`、`#`、`=` 等 fragment 分隔字符；
- `PlaybackSync` 新增强类型写入和解析 API，原字符串 API 与 `Metadata.sessionId()` 继续兼容；
- `PlaybackSync.Metadata` 会把无效 session 规范化为空身份，URL fragment 格式保持 `nmb_session=<字符串>`；
- 客户端播放生命周期内部持有 `PlaybackSessionId`，tracker 在字符串入口统一解析后再做 exact-match；
- 客户端网络命令快照与音频管线请求内部改持有 `Optional<PlaybackSessionId>`；
- 两个快照均保留原字符串构造器和 `sessionId()` 访问器，现有调用方与二进制方法描述符继续兼容；
- 视频实例、loading/failure placeholder、legacy preview 快照和 zombie close key 均改持有强类型 session；
- 无 session 的 legacy preview 继续使用 `Optional.empty()` 表达，未改变 bench/preview 的空身份语义；
- 断链恢复注册表的 map key、registration 与 recovery request 已强类型化，字符串注册/报告/注销入口继续兼容；
- 直播视频样本总线 registry、矿车音频锚点，以及直播视频决策/画质缓存均按强类型 session 隔离；
- 总线伪 URL 仍使用字符串序列化，lookup 时才解析；非法 session 不再进入上述运行时索引；
- 断链恢复注册表改用 JDK 日志入口，不再因游戏日志桥接缺失而阻止纯单元测试加载；
- MP4 服务端 active session 直接持有非空强类型 session，packet start 入口会拒绝非法 session；
- MP4 权威设备状态 entry 以 `Optional<PlaybackSessionId>` 表达播放/空闲状态，原字符串构造器与访问器保持兼容；
- MP4 runtime progress 已与 `MP4PlaybackSavedData.Entry` 分离，运行时使用独立强类型快照，flush 时才展开字符串；
- 队列 advance/remap/stop 与进度、设备状态同步链直接传递强类型可选 session，不再在内部往返拼接字符串；
- MP4 packet codec、SavedData codec 与既有 `sessionId` 字段格式保持不变，非法外部 session 规范化为空身份；
- 视频实例与 GUI 预览锚点内部持有非空 `PlaybackSessionId`，公开时间线和诊断仍通过字符串 facade 暴露；
- 视频实例创建入口对非空 session 统一先执行 `PlaybackSessionId.parse(...)`，非法输入直接拒绝，不会进入实例或 pending registry；
- 音频能力 registry 的真实 map key 已改为 `PlaybackSessionId`，同时保留字符串与 typed publish/presence/forget 入口，非法 session 不会写入缓存；
- legacy 空 session 仍只走 `VideoBillboardPreview` 的旧预览路径，不会构造要求非空身份的 `VideoPlaybackInstance`；
- 现代唱片机视频客户端的 active set、唱片机映射、画质、resolve generation/owner 与决策去重缓存均改为强类型 session；
- 现代唱片机播放诊断的周期日志节流表也按强类型 session 隔离，日志、视频 facade 与 timeline 清理边界继续展开为字符串；
- 现代唱片机视频同步直接复用 `PlaybackSync.Metadata.playbackSessionId()`，非法 session 无法进入音频准入或异步 resolve 所有权缓存；
- 现代唱片机与直播视频锚点内部以 `Optional<PlaybackSessionId>` 保存身份，legacy 空 session 保持为空可选值；
- 共享 `MediaTimelineClock`、现代唱片机视觉平滑状态与 timeline snapshot 新增强类型身份访问器，原 `sessionId()` facade 保持不变；
- 手持媒体 playback 与 `ClientMediaTimelineView` 会在入口解析 session，视觉平滑缓存直接保存强类型身份，非法非空字符串不再被当成有效会话；
- 直播视频音频时间线补齐 `sessionId()` facade，音频输出身份不匹配时继续拒绝提供可听进度；
- `VideoCloseDiagnostics` 的 active/completed operation 内部改持 `Optional<PlaybackSessionId>`，真实视频实例直接走 typed begin 入口；
- 诊断 timeout 文本与 `Snapshot.latestSessionId` 仍在边界展开为截断字符串，legacy 字符串入口继续兼容，非法值规范化为空身份；
- 共享 `SyncedMediaSound` 基类内部 session 字段改为非空 `PlaybackSessionId`，构造时统一拒绝空白或非法身份；
- 现代唱片机、直播与 MP4/Pad 动态声音三个子类不再直接读取字符串字段，恢复注册和失败上报直接传递强类型身份；
- `SyncedMediaSound.sessionId()` 继续作为 public 字符串 facade，并满足 `ClientMediaSoundHandle` 原有契约；
- `HttpAudioStreamHandler.ActiveStreamControl` 内部改持 `Optional<PlaybackSessionId>`，同源新会话关闭旧流时直接比较强类型身份；
- HTTP request registry 直接复用 `PlaybackRequest.playbackSessionId()`，恢复上报不再从字符串重复解析；
- URL request token、管线构造参数与诊断日志仍在边界展开为字符串，未改变 NetMusic handler 兼容契约；
- `MP4HandheldVideoClient.PlaybackKey` 的 session component 改为 `Optional<PlaybackSessionId>`，active/resolving/failed/ended 视频缓存直接比较强类型身份；
- handheld decoder、Pad 类型判断、日志与 profile API 仍通过 key 的字符串 facade 取值，`PlaybackKey.EMPTY` 继续表达无会话哨兵；
- `VideoBillboardPreview` 的 BER projector submission 与 immediate-pose 两个内部复合 key 改持非空 `PlaybackSessionId`；
- BER/immediate 公开字符串入口统一解析后再写缓存，非法 session 不再形成渲染键；控制台快照与渲染日志仍输出字符串；
- `ControlConsoleRenderer` 与 `VideoProjectorRenderer` 的可变渲染状态改持 `Optional<PlaybackSessionId>`，
  并直接调用 `VideoBillboardPreview` 的 typed BER/immediate-pose 入口；旧字符串入口仍保持兼容；
- `ClientAudioOutputRegistry.AudioEntry` 与 `AudioTimeline` 改持 `Optional<PlaybackSessionId>`，
  同时保留字符串构造器、`sessionId()` 与 `audioSessionId()` facade；
- `HandheldMediaPlayback` 的 record component 改持强类型可选身份，`ClientMediaPlayback` 直接传递
  `ActivePlayback.playbackSessionId()`，避免内部重新解析字符串；
- `MediaTimelineClock.TimelineSnapshot` 与 `ModernTurntableTimeline.TimelineSnapshot` 的 record component
  改持强类型可选身份，并保留原字符串构造器与 `sessionId()` facade；
- `MediaVideoTimeline` 以 `playbackSessionId()` 作为内部主契约，turntable/live/preview timeline 直接传递强类型身份；
  live 音频输出准入也改为比较 typed `Optional`，`sessionId()` 继续作为默认字符串 facade；
- packet、NBT、URL fragment、声音/视频 facade、公开控制台快照和诊断输出仍使用字符串，作为明确兼容边界保留；
- `PlaybackSessionId`、`PlaybackSync`、客户端 session tracker、两个命令/请求快照、四个视频所有权边界与
  四个流媒体运行时索引、MP4 服务端 session/设备/进度链、视频实例、音频能力缓存、视频锚点、共享时间线、
  视频关闭诊断、共享音频 Sound 身份、HTTP active stream 控制、手持视频缓存、投影内部渲染键、客户端音频输出、
  手持播放快照及视频时间线契约均已完成完整 Gradle 回归。

### 16. 强类型 playback source ID（前四阶段）

新增：

- `PlaybackSourceId`
- `MP4PlaybackSourceSessionRegistry`

修改：

- `MP4ResolveIntentRegistry`
- `ClientMediaPlaybackRegistry`
- `ClientMediaSoundRegistry`
- `ClientMediaAudioRouting`
- `ClientMediaPrepareLauncher`
- `ClientMediaRetryHandler`
- `ClientMediaTimelineView`
- `MP4PlaybackSyncManager`
- `MP4PlaybackAudienceBroadcaster`
- `MP4PlaybackSourceDiscovery`
- `MP4PlaybackQueueController`
- `MP4PlaybackProgressPersistence`
- `MP4HandheldVideoClient`
- `MP4ItemScreenRenderer`
- `PadItemScreenRenderer`
- `MP4Nv12VideoLayer`
- `MP4RgbaVideoLayer`
- `MP4DeviceStateStore`
- `MP4DeviceLocationIndex`
- `MP4Client`
- `PadDocumentStore`
- `PadPlaybackControlPacket`
- `PadClient`
- `PadDeviceHolderTracker`
- `PadStatePacket`
- `MP4DeviceHolderTracker`
- `MP4StatePacket`
- `AudioLinkIndex`

结果：

- source ID 以非空 UUID 为内部值，字符串解析仍保持标准 UUID 表示，packet/NBT 的 UUID 字段不变；
- MP4 resolve intent 的内部 active key 已由裸 UUID 改为 `PlaybackSourceId`；
- 客户端播放状态、活动声音、本地私有音频路由和视觉平滑状态的内部 source key 已强类型化；
- prepare/lyrics owner 同时持有强类型 source 与 `Optional<PlaybackSessionId>`；
- 音频起播和 retry owner 不再拼接 `sourceId + ":" + sessionId`，改用强类型复合 record，按 source 值精确清理；
- `ActivePlayback` 内部 session component 已改为 `Optional<PlaybackSessionId>`，保留字符串构造器与 `sessionId()` facade；
- 服务端 session registry 通过 UUID-compatible facade 委托给 `MP4PlaybackSessionRegistry<PlaybackSourceId, Session>`，session 与 missing-source grace 共享强类型 source key；
- 服务端 runtime progress map 已强类型化，写入 `MP4PlaybackSavedData` 时才展开为 UUID；
- 手持视频 state/profile、MP4/Pad GUI texture 与 NV12/RGBA layer 缓存均按强类型 source 隔离；
- 服务端 MP4 权威设备状态 runtime map 与尽力而为位置索引已强类型化，SavedData 和物流图键保持原兼容格式；
- 客户端 MP4 状态、更新时间、队列、耳机链接、pending selection 与 pending state-sync 六张缓存共享强类型 source key；
- 客户端 pending state-sync 遍历在 packet 发送边界才展开 UUID，缓存 prune 继续按当前物品集合精确淘汰；
- Pad 服务端文档 runtime map、pending playback start，以及客户端 document/pending/index 三张缓存已改用强类型 source key；
- Pad 与 MP4 的 holder `(player, source)`、客户端状态同步 `(player, source)` 复合键，其设备分量已强类型化；
- 耳机与 MP4 的双向运行时索引已改用强类型媒体设备键，玩家与耳机所有者身份继续保持 UUID；
- 所有现有 UUID/String 公开入口、声音/视频调用方、packet、NBT 与持久化格式保持不变；
- `PadDocumentSavedData`、`MP4PlaybackSavedData`、packet codec 与 NBT 仍保留 UUID 兼容格式，只在运行时缓存边界转换。

### 17. packet-compatible typed session accessor

修改：

- `ClientMediaSyncPayload`
- `ClientMediaTimelinePayload`
- `ClientMediaSyncHandler`
- `ClientMediaPlaybackRegistry`
- `ClientMediaPrepareLauncher`

结果：

- 两个协议中立 payload 接口新增 `playbackSessionId()` 默认 accessor，统一把 packet 保留的字符串字段解析为
  `Optional<PlaybackSessionId>`；既有 `sessionId()` 方法描述符和 packet wire codec 不变；
- 客户端同步、轻量时间线校时、active playback 注册以及 prepare/lyrics owner 的 exact-match 比较均直接使用
  typed `Optional`，非法/空白 session 在运行时边界被视为空身份，不再在每个调用点重复解析；
- `ClientMediaPrepareLauncher` 不会为无效 session 建立异步 prepare owner，避免空身份进入 pending registry；
- 未修改 `MP4PlaybackSyncPacket`、`MP4PlaybackTimelinePacket` 的 record component、网络字段顺序或编码长度限制。

### 18. typed client sound lifecycle identity

修改：

- `ClientMediaSoundHandle`
- `SyncedMediaSound`
- `ClientMediaSoundRegistry`
- `ClientMediaPlaybackRegistry`
- `ClientMediaRetryHandler`
- `ClientMediaMovingSound`
- `Mp4ClientMediaSyncPolicy`

结果：

- 协议中立 sound handle 新增 `playbackSession()` typed accessor，`sessionId()` 继续作为字符串兼容 facade；
- 共享 `SyncedMediaSound` 直接暴露其构造时已验证的 typed identity，不再为 registry/rebuild 比较重复解析；
- active sound 注册和 exact finish 新增 `PlaybackSessionId` overload，同时校验 active playback 与 sound handle
  都属于同一有效 session；非法或过期声音身份不能写入或移除当前索引；
- playback registry 的 finish/current-check 新增 typed overload，字符串入口只在边界解析一次；
- stream retry pending key、首次准入与延迟回调二次准入均直接比较 typed identity，确保会话换代后旧 retry 不会执行；
- MP4 sound rebuild 改为比较 payload 与 sound handle 的 typed `Optional`；packet、声音生命周期接口的既有
  字符串方法和对外回调参数保持不变。

### 19. retry ownership 竞态收口

新增：

- `ClientMediaRetryRegistry`

修改：

- `ClientMediaRetryHandler`
- `ClientMediaSyncHandler`

结果：

- stream retry pending ownership 从 handler 静态集合提取为独立强类型 registry，以 `(PlaybackSourceId,
  PlaybackSessionId)` 复合键提供 one-shot admission、exact forget、按 source 清理与全局 clear；
- 同一 source/session 的并发失败只有一个 retry owner，避免多个错误回调重复发送 seek；
- retry owner 写入后立即二次核对 active session；若 replacement 恰好插入在首次检查与 owner 写入之间，
  会 exact rollback 旧 owner 并拒绝调度；
- 延迟 retry 回调发现 active session 已换代时会精确移除旧 owner，不会清除 replacement session；
- 新 full sync 接受 replacement session 后主动清理该 source 的旧 pending retry，避免旧 key 残留到设备 stop；
- 其他 source 的 retry ownership 不受换代清理影响，packet、播放 session 和 transport generation 语义不变。

### 20. prepare/lyrics replacement 主动取消

新增：

- `ClientMediaPrepareOwnerRegistry`

修改：

- `ClientMediaPrepareLauncher`
- `ClientMediaSyncHandler`

结果：

- 客户端音频 prepare 与歌词任务共享独立强类型 owner registry，以 `(PlaybackSourceId,
  PlaybackSessionId, headphoneRouted)` 作为 exact key；
- prepare 使用并发 one-owner admission，歌词任务原子替换时会立即取消旧 owner；
- replacement full sync 注册新 active session 后、调度新 prepare 前，会主动取消该 source 的旧 prepare/lyrics
  owner 和实际 `CancellableTaskFuture` worker，不再等待旧任务完成或超时；
- 旧任务迟到完成只能 exact-remove 自己，不能移除 replacement owner；按 source 清理不会影响其他设备；
- stop/切世界的既有清理入口继续委托同一 registry，重复 clear 保持幂等，packet 与媒体 URL 契约不变。

### 21. replacement session 主动摘除旧 sound

修改：

- `ClientMediaSoundRegistry`
- `ClientMediaSyncHandler`
- `ClientMediaSoundRegistryTest`

结果：

- replacement full sync 注册新 active session 并取消旧 retry/prepare owner 后，会在调度新 prepare 前按 source
  原子摘除 session 不匹配的旧 sound，不再等待下一次声音 tick 自行发现换代；
- registry remapping callback 只负责精确删除，实际 `discardWithoutFinishing()` 在 map mutation 之外执行，避免把
  声音关闭、恢复注册注销等生命周期副作用带入并发 map 回调；
- 同 session sound 会继续保留，其他 source 完全隔离；迟到旧 session 的 exact finish 不能移除 replacement sound；
- 旧 sound 使用 discard-only 语义停止，不会触发旧 session finish 去清除刚注册的新 active playback；
- packet、声音管理器 facade、session 字符串字段与媒体 URL 契约均保持不变。

### 22. 迟到 sound factory 准入与 exact rollback

修改：

- `ClientMediaSoundHandle`
- `ClientMediaSoundLifecyclePolicy`
- `ClientMediaSoundRegistry`
- `ClientMediaMovingSound`
- `MP4MediaSoundLifecyclePolicy`
- `PadMediaSoundLifecyclePolicy`
- `ClientMediaSoundRegistryTest`

结果：

- sound registry 新增返回明确准入结果的 `tryRegister` typed/string 入口；既有 `register(void)` 与 lifecycle
  `registerSound(void)` 方法描述符继续保留，外部兼容实现可沿用原入口；
- sound 注册在 source 键级原子 mutation 内再次核对 active session，并在写入后执行二次核对；replacement
  插入 check→write 或 write→return 窗口时，迟到旧 factory 会 exact rollback 自己，不能覆盖或删除新 sound；
- `ClientMediaMovingSound` 先建立 recovery registration，再请求 admission；拒绝或注册异常时会在构造完成前
  注销自己的 recovery generation 并标记 stopped；即使 factory 随后返回该实例，`getStream` 也会在创建
  `NetMusicAudioStream` 前观察 stopped 状态并终止；
- discard/finish 共享原子一次性门闩，确保 recovery unregister 与逻辑 session finish 在并发回调下最多执行一次；
- 同 session 重复 factory 会由后注册 handle 接管索引，并在 map mutation 外主动 discard 被替换 handle；
- packet、NBT、媒体 URL、公开 session 字符串 facade 与原 lifecycle void 方法均保持兼容。

### 23. stop/world-unload active sound 即时释放

修改：

- `ClientMediaSoundRegistry`
- `ClientMediaPlaybackSessions`
- `ClientMediaPrepareLauncher`

新增：

- `ClientMediaPlaybackSessionsTest`

结果：

- sound registry 新增 `removeAndDiscard` 与 `clearAndDiscard` 生命周期入口，同时保留原 `remove/clear` 纯索引
  兼容语义；实际 discard 继续在 concurrent map mutation 外执行；
- 单 source stop 先从 playback registry 失效 active session，再原子摘除并立即 discard 对应 sound，不再等待声音
  下一 tick 观察 session 消失；其他 source 的 active playback 与 sound 均不受影响；
- 切世界、退出连接和内存保护全局 cleanup 先清空全部 active playback，再 drain 并 discard 所有 sound handle；
  结合 factory admission 二次核对，清理窗口内的迟到 factory 无法重新进入索引；
- 重复 stop/clear 不会重复执行 handle 释放，carrier stop/clear hook 的既有调用语义保持不变；
- `ClientMediaPrepareLauncher` 的单一后台歌词失败日志改用 JDK logger，移除共享生命周期 facade 在纯测试环境
  类加载时对 Minecraft LogUtils 的静态依赖，日志等级与诊断字段保持不变。

### 24. retry dispatch 拒绝与异常收敛

新增：

- `ClientMediaRetryDispatch`
- `ClientMediaRetryDispatchTest`

修改：

- `ClientMediaRetryPolicy`
- `ClientMediaRetryHandler`
- `Mp4ClientMediaRetryPolicy`
- `PadClientMediaRetryPolicy`
- `ClientMediaPlaybackRegistry`
- `ClientMediaSoundRegistry`
- `ClientMediaSoundRegistryTest`

结果：

- retry policy 新增返回实际发送准入结果的 `tryScheduleRetry`；原 `scheduleRetry(void)` 方法描述符继续保留，
  兼容 policy 默认仍沿用原发送成功语义；
- MP4/Pad retry 在客户端连接不可用、本地没有目标设备或 Pad session 无法解析 point ID 时明确返回拒绝，
  不再把“没有发送 packet”误当成等待 replacement session；
- delayed dispatch 被拒绝或抛出运行时异常时，会 exact-remove 原 `(source, session)` pending owner，并仅 finish
  仍匹配的本地旧 session；其他 source 与已经到达的 replacement session 均不受影响；
- exact session finish 新增 `finishAndDiscard` 路径，在 map mutation 外立即停止匹配 sound；即使 retry 回调早于
  sound 下一 tick，也不会留下已脱离索引但仍可能播放的 handle；
- retry packet 成功发送时继续保留 pending owner，直到服务端权威 full sync 接管；本阶段仍保持
  MP4/Pad packet codec、用户 SEEK 行为以及当时成功 retry 使用 SEEK 刷新直链的兼容流程；MP4 成功路径已在
  后续第 25 阶段迁移为独立附加 payload；
- 拒绝路径清掉本地 active session 后，后续权威 full sync 可以按正常新接收路径重新 prepare，不会再被永久
  pending key 立即停止。

### 25. MP4 retry 保留逻辑 session，仅刷新 transport generation

新增：

- `MP4PlaybackRetryPacket`
- `MP4PlaybackRetryAdmission`
- `MP4PlaybackRetryAdmissionTest`

修改：

- `ModernTurntableNetwork`
- `MP4PlaybackControlPacket`
- `MP4PlaybackSyncManager`
- `Mp4ClientMediaRetryPolicy`
- `ClientMediaRetryHandler`
- `ClientMediaSyncHandler`
- `ClientMediaRetryRegistryTest`

结果：

- MP4 stream retry 不再伪装成用户 `SEEK`，改发独立的 play-to-server payload；字段仅包含 `deviceId`、最大
  128 字符的 `expectedSessionId` 与 `targetMillis`，既有 control packet codec、action ID 和用户操作保持不变；
- 服务端在启动 resolve 前按当前 active session 精确核对设备、预期 `PlaybackSessionId`、队列索引和原始曲目；
  无活动 session、旧 session 或曲目已变化的请求不会启动刷新；
- retry resolve 仅在该设备没有 pending command/discovery resolve 时进入 MP4 统一 generation registry，不能
  反向覆盖已经开始的用户 seek/restart；其后到达的 seek、restart、stop 或新 resolve 会使 retry completion
  失效，迟到结果不能覆盖 replacement session；
- 最终提交再次核对 resolve generation 与 active session，并使用 session registry 的 exact replace；成功时保留
  原 `PlaybackSessionId`，只替换新直链、时间锚点与客户端下一轮 prepare 生成的 `MediaRequestToken`；
- 服务端刷新沿用 active session 的最新音量与物理 source，不会把 resolve 期间的 volume/source 变化回滚；
- 客户端同 session full sync 会 exact-remove 对应 `(PlaybackSourceId, PlaybackSessionId)` pending retry 后再判断并
  重建已停止 sound；其他 source 或同 source 的其他 session pending owner 不受影响；
- Pad retry 在本阶段仍使用原 `SEEK` 流程；这一历史限制已由后续第 26 阶段的 exact resolve owner 与独立
  retry payload 消除。

### 26. Pad exact resolve owner 与 retained-session retry

新增：

- `PadResolveIntentRegistry`
- `PadPlaybackSessionIds`
- `PadPlaybackRetryPacket`
- `PadResolveIntentRegistryTest`
- `PadPlaybackSessionIdsTest`

修改：

- `PadPlaybackControlPacket`
- `PadDocumentStore`
- `ModernTurntableNetwork`
- `MP4PlaybackSyncManager`
- `PadClientMediaRetryPolicy`
- `PadClientMediaSessionIds`

结果：

- 移除 Pad 仅按 source 去重的 `PENDING_STARTS`，start/seek/restart 与 transport retry 统一进入带
  `ResolveGeneration` 的 exact intent registry；intent 同时绑定 owner、设备、点位、媒体和原始 URL；
- stop/pause、新 start/seek/restart、Pad 文档更新、owner 登出和 server stopping 均会失效对应旧 resolve；迟到
  completion 会重新读取当前 Pad stack/document 并精确核对 point/media/source，不能启动已删除或已换曲的点位；
- 用户命令始终替换同设备的 pending retry；retry 仅在没有 pending 用户命令时准入，不能反向覆盖用户操作；
- 最终 start/refresh 与 intent invalidation 在 registry monitor 下串行化，关闭 check→commit 窗口；提交抛出异常时
  也会在 `finally` 中释放 exact owner，不会永久阻塞后续 retry；
- Pad stream retry 不再伪装成用户 `SEEK`，改发独立附加 payload，携带 device、point、expected session 与
  target time；既有 `PadPlaybackControlPacket` codec/action ID 和用户 SEEK 行为保持不变；
- retry 在服务端开始和最终提交时都精确核对 active session、point/media 与 raw URL；成功时保留原逻辑
  `PlaybackSessionId`，只刷新直链、时间锚点和客户端下一轮 prepare 的 transport generation；
- Pad session ID 由统一 helper 创建和解析，格式严格验证 device UUID、point UUID 与非负 generation，客户端不再
  各自做宽松字符串切片；
- 新增 13 个 Java 用例，覆盖 session round-trip/畸形输入、命令与 retry 优先级、stop/logout 失效、stale final
  commit 拒绝以及成功/异常 commit 的 exact owner 清理。

### 27. retained-session delayed retry 准入与 integrated-client 换代矩阵

修改：

- `ClientMediaRetryRegistry`
- `ClientMediaRetryHandler`
- `ClientMediaRetryRegistryTest`
- `NetMusicBenchProvider`

结果：

- 修复 authoritative 同-session full sync 已清除 pending retry 后，先前排队的 delayed callback 仍只按 active
  session 判断、因逻辑 session 保留而多发送一次旧 retry 的窗口；
- retry dispatch 现在通过 registry 的 `dispatchIfPending` 在同一 monitor 下重新核对 exact
  `(PlaybackSourceId, PlaybackSessionId)` owner，full sync 的 `forget` 与 timer dispatch 串行化；refresh 先赢时旧
  callback 不执行，dispatch 先赢时服务端既有 expected-session/generation 准入仍负责最终裁决；
- 新增 `ncpb.playback-session-races` integrated-client 场景，直接驱动生产 `ClientMediaSyncHandler`、playback
  registry、retry handler 和 sound registry，覆盖 start 后连续三次 seek、retained-session transport refresh、
  pause、resume 与最终 stop；
- 场景验证最终 seek session 与 21 秒时间锚点获胜、refresh 保留逻辑 session 并更新到 24 秒、旧 delayed retry
  发送次数为 0、6 个换代/停止 sound handle 均恰好释放一次，最终 active playback/sound/retry owner 全部归零；
- 该场景使用确定性 test handle 验证客户端所有权和状态机，不宣称覆盖真实 decoder/OpenAL；真实 MP3 起播、
  decoder/OpenAL source 换代和 native close 仍保留在后续真实媒体验证中。

### 28. 真实 MP3 seek、OpenAL 输出换代与 exact cleanup

修改：

- `StereoOpenALHandler`
- `OpenALTappedAudioInputStream`
- `ClientAudioOutputRegistry`
- `AudioStreamProperties`
- `NetMusicBenchProvider`
- `build.gradle`

新增/扩展测试：

- `StereoOpenALHandlerPcmQualityTest`
- `AudioStreamPropertiesTest`

结果：

- `StereoOpenALHandler` 新增只读 diagnostic、首段 PCM 标量质量和全局 lifecycle 快照，能够观测实例创建、
  cleanup 开始/完成、active 实例，以及首段 samples、peak、RMS 和 clipping；cleanup 继续保持幂等，并在
  `finally` 中记录完成；
- `OpenALTappedAudioInputStream` 在首段 PCM 到达时保存质量快照，`close()` 串行化并暴露只读关闭状态；
  `ClientAudioOutputRegistry` 可按 owner 读取当前 Stereo 输出快照；
- 新增 opt-in integrated-client 场景 `ncpb.real-mp3-seek`。场景下载真实公网 MP3，由生产
  `HttpAudioStreamHandler` 从文件头建立 Layer III 解码状态，在 PCM 域跳过目标前内容并补偿下载/解码启动
  延迟，再驱动真实 `StereoOpenALHandler` / `OpenALSpatialAudio`；
- 第一输出以 5 秒 offset 建立，验证首段 PCM 非静音、有限且无严重 clipping；随后以 12 秒 offset 创建第二
  输出并替换第一输出，验证旧 stream/output 已关闭；
- 场景最终要求两个 Stereo handler 的 cleanup 各开始并完成恰好一次，同时等待 active output、OpenAL native
  delete、native close operation 和 `AUDIO_STAGING` 全部回到基线；
- 下载、解码和 stream 读取在 daemon worker 上执行；客户端 tick 只负责轮询、推进 OpenAL 和断言，较慢网络
  不会把同步 I/O 放到客户端线程；
- 场景通过 `-PncpbRealMp3Bench=true` 显式启用，可用 `-PncpbRealMp3BenchUrl=...` 覆盖媒体 URL；默认不注册，
  避免普通离线回归依赖公网；
- 该场景覆盖真实 MP3 解码和直接 OpenAL pipeline 的两段 seek/换代，不经过 Minecraft SoundEngine channel
  创建与销毁，不能宣称覆盖完整声音引擎 channel 生命周期。

### 29. 真实 MP3 SoundEngine channel 创建、换代与销毁

修改：

- `OpenALTappedAudioInputStream`
- `StereoOpenALHandler`
- `StereoOpenALHandlerPcmQualityTest`
- `NetMusicBenchProvider`

结果：

- 新增 opt-in integrated-client 场景 `ncpb.real-mp3-sound-engine`，与真实 MP3 seek 场景共同由
  `-PncpbRealMp3Bench=true` 启用；
- 场景通过 `SoundManager.play` 让 Minecraft `SoundEngine` 分配真实 streaming handle/channel，再经过测试专用
  `BenchSound extends SyncedMediaSound`、生产 `SyncedMediaSound.getStream`、NetMusic `NetMusicAudioStream`、
  `HttpAudioStreamHandler`、真实 MP3 decode-from-head/PCM seek、`OpenALTappedAudioInputStream` 和
  `StereoOpenALHandler`/`OpenALSpatialAudio`；通过 NeoForge `PlayStreamingSourceEvent` 确认 channel 已实际挂载；
- 第一段以 5 秒 offset 启动，验证有效 PCM 和 OpenAL 输出后停止；等待第一 channel、tapped stream 和 OpenAL
  输出完全退役，再以 12 秒 offset 启动 replacement sound 并重复验证；
- 场景最终要求 streaming channel 恰好启动 2 次，tapped stream 恰好创建/关闭 2 次，Stereo handler 的创建、
  cleanup start/complete 恰好各 2 次；SoundManager active、native close/delete、输出 registry 和
  `AUDIO_STAGING` 均回到基线；
- `StereoOpenALHandler` 的 PCM 质量观测从单次 `read` 快照改为最多 4096 samples 的有界累积窗口，避免
  SoundEngine 首次只读取一个 4-byte frame 时把有效音频误判为静音；窗口不保留原 PCM 数组；
- 新增小块 PCM 累积和窗口上限测试；`OpenALTappedAudioInputStream` 新增全局只读 lifecycle 快照，并确保
  `close()` 即使清理抛错也在 `finally` 中记录完成；
- 该场景覆盖生产 SoundEngine/getStream/streaming channel 链路，但仍不等同于从真实唱片机方块和网络 packet
  入口触发的完整游戏交互链。

## 新增或扩展的测试

新增测试：

- `TurntableResolveAdmissionPolicyTest`
- `MP4ResolveIntentRegistryTest`
- `CancellableTaskFutureTest`
- `VideoZombieCloseSupervisorTest`
- `VideoResolveRequestOwnerTest`
- `AudioRelayPropertiesTest`
- `VideoPipelinePropertiesTest`
- `ModernTurntableCommandAdmissionPolicyTest`
- `VideoSessionInstanceRegistryTest`
- `PendingVideoSessionRegistryTest`
- `VideoConsumerRegistryTest`
- `LegacyPreviewSessionStateTest`
- `LegacyPreviewWorkerLifecycleTest`
- `LegacyPreviewTextureLifecycleTest`
- `VideoResourceDiagnosticsCollectorTest`
- `MP4PlaybackSessionRegistryTest`
- `MP4PlaybackSourceObservationPolicyTest`
- `MP4PlaybackQueuePolicyTest`
- `MP4PlaybackProgressPolicyTest`
- `PadMapPropertiesTest`
- `PadRenderPropertiesTest`
- `PadDiagnosticsPropertiesTest`
- `CdnPropertiesTest`
- `VideoPlaybackInstancePropertiesTest`
- `Fmp4NativeVideoPropertiesTest`
- `AudioSyncPropertiesTest`
- `Fmp4StreamPropertiesTest`
- `MediaClosePropertiesTest`
- `MemoryPropertiesTest`
- `AudioStreamPropertiesTest`
- `TimelinePropertiesTest`
- `VideoFeaturePropertiesTest`
- `HandheldVideoPipelineConfigTest`
- `ClientMediaPreparePropertiesTest`
- `PlaybackRuntimePropertiesTest`
- `ProjectorRenderPropertiesTest`
- `OpenAlHrtfPropertiesTest`
- `VideoClientPropertiesTest`
- `IrisShaderpackPropertiesTest`
- `VideoBillboardPropertiesTest`
- `ClientDisplayPropertiesTest`
- `BiliApiPropertiesTest`
- `MediaRequestTokenTest`
- `ResolveGenerationTest`
- `PlaybackSessionIdTest`
- `PlaybackSourceIdTest`
- `MP4PlaybackSourceSessionRegistryTest`
- `SyncedStreamRecoveryRegistryTest`
- `ClientMinecartAudioAnchorsTest`
- `MP4PlaybackRuntimeProgressTest`
- `ClientMediaTimelineViewTest`
- `ClientAudioOutputRegistryTest`
- `ClientMediaPayloadIdentityTest`
- `ClientMediaSoundRegistryTest`
- `ClientMediaRetryRegistryTest`
- `ClientMediaPrepareOwnerRegistryTest`
- `ClientMediaPlaybackSessionsTest`
- `ClientMediaRetryDispatchTest`
- `MP4PlaybackRetryAdmissionTest`
- `PadResolveIntentRegistryTest`
- `PadPlaybackSessionIdsTest`
- `StereoOpenALHandlerPcmQualityTest`

扩展：

- `ClientPlaybackSessionTest`
- `MediaAudienceRoutingPolicyTest`
- `NcpbSystemPropertiesTest`
- `AudioSyncPolicyTest`
- `LiveOfflineBackoffTest`
- `OneShotRequestRegistryTest`
- `PlaybackSyncTest`
- `LiveVideoSampleBusTest`
- `VideoAudioPresenceRegistryTest`
- `MediaTimelineClockTest`

已覆盖：

- generation admission；
- generation 非负约束、不可变递增和最大值回绕；
- same-URL resolve 反序；
- worker cancel propagation；
- session late binding；
- named slot replacement；
- concurrent cancel/replace；
- FAILED_CLOSE/zombie convergence；
- 配置 key precedence 和默认值兼容；
- authoritative/tracker session command admission；
- source 状态暂不可用时的兼容 fallback；
- 迟到旧命令的反向替换抑制；
- session instance replacement disposal；
- exact removal 不删除 replacement；
- 条件淘汰与全局 clear 的一次且仅一次释放；
- pending loading/failure 互斥转换与计时；
- pending projector 更新、剥离与空 session 回收；
- clearLoading 不误删 failure 状态；
- projector replacement 清理旧引用并过滤 null/重复位置；
- projector add/remove/detach 与 GUI consumer 独立状态转换；
- projector 快照不可修改，并在 registry replacement 后保持稳定；
- legacy preview session/request/projector 状态的原子快照发布；
- legacy projector replacement、primary promotion、无效引用 prune；
- replacement clear 保留 retry identity，full clear 删除全部 identity；
- media request token 的 trim、安全字符、长度限制、非可信解析与随机生成；
- typed registry 的反序消费、一次性消费、过期、取消和字符串兼容入口；
- typed token 与 session/minecart URL fragment 共存，且 transfer 不携带 one-shot token；
- playback source UUID 包装、字符串往返、无效解析与 null 拒绝；
- legacy worker 的单 generation admission 与 worker/decoder 精确绑定；
- stop 原子 detach、stop 后迟绑定拒绝和 cooperative completion；
- 旧 generation completion 不覆盖 replacement generation；
- RGBA/YUV/packed 纹理 slot replacement 的精确释放；
- same-instance replacement 不误释放当前资源；
- null replacement、full clear 与重复 clear 的一次且仅一次释放；
- 空实例 resource diagnostics 与外部计数保留；
- running/failure/projector/GUI 独立状态聚合；
- collector 快照不随源集合后续修改而变化。
- MP4 session replacement 清除旧 missing-source grace；
- stale exact replace/remove 不影响已换代 session；
- session entry/value 快照不可修改，且不随后续 registry 变化；
- registry clear 同时清除 session 和 missing grace。
- 未知物理源触发 START，匹配源保持 KEEP，位置变化触发 MIGRATE；
- player source 保留既有“同类型即保持”的兼容策略；
- item entity、block position/slot 和 container entity/slot 的身份字段分别验证；
- source identity 比较忽略播放时间线字段，但能识别物理位置变化。
- 断链恢复 registration 换代后的 stale exact unregister 不删除 replacement；
- 恢复回调 request 同时暴露强类型 session 与兼容字符串 facade；
- 非法 session 无法注册、查找或触发恢复；
- 直播样本总线的强类型/字符串 lookup 与 URL facade 往返一致；
- 矿车音频锚点按强类型 session 注册、查询和清理。
- 视频实例/预览锚点的 typed session accessor 与 legacy 字符串时间线 facade；
- 非法视频/音频 session 在创建或发布入口被拒绝，不进入实例、pending 或音频能力 registry。
- turntable/live 锚点、共享 timeline clock/snapshot 与手持 timeline view 的 typed/string facade 一致性；
- 无 session 的 legacy 时间线继续使用空身份，非法手持 session 不会进入视觉平滑状态。
- MP4 runtime progress 的强类型 session/string facade、非法 session 规范化与持久化前数值钳制。
- 队列未变时 KEEP，歌曲移动时 REMAP，歌曲删除时 STOP 并钳制选中索引；
- 重复 URL 继续选择首个匹配项；
- 单曲循环重播当前索引，顺序模式前进一项；
- 列表循环在末尾回到零，普通末尾和空队列停止。
- current elapsed 的 runtime、persisted、fallback 优先级；
- queue index 不匹配时拒绝使用 runtime progress；
- progress-per-mille 到毫秒的恢复换算；
- 目标时间负值、正常值和媒体末尾 50ms guard 的钳制；
- elapsed 到 per-mille 的 0..1000 边界。
- 非 player source 和普通 player source 的附近投递/耳机抑制路由；
- Pad 公共 packet 只发送给未收到耳机路由的在线 owner；
- Pad player stop 只抑制附近路由，不改变 owner/耳机 stop 目标。
- Pad 地图布局默认值、旧 `map_size` fallback 与新键优先级；
- 派生地图宽高和既有缓存调度最小值钳制；
- Pad zoom 的非法/非有限 float 与磁盘缓存非法 boolean 回退；
- 通用系统属性有限 float 的 current/legacy/default 解析顺序。
- Pad renderer/GUI、地图纹理和性能日志默认参数；
- Pad offscreen 当前键与旧 MP4 键的优先级；
- renderer 非有限 float、非法值回退和既有最小值钳制。
- Pad 视频调试与地图服务端自测开关的默认关闭、独立启用和非法值回退。
- CDN selector/fallback 默认值、有效下限和首选 host 规范化；
- CDN typo legacy race 键兼容与 current 键优先级；
- 通用系统属性字符串 trim、空白跳过和 legacy/default 回退。
- 视频上传/NV12/YUV 默认开关和字符串模式；
- pixel mode trim、YUV matrix/shader debug 大小写规范化；
- 上传/YUV 非法布尔和空白模式的兼容默认回退。
- 客户端显示属性默认值、显式覆盖、非法值回退和健康检查 100ms 下限。
- Bili API/播放属性默认值、字符串规范化、非法值回退、直播重试 10 秒下限与饱和换算。
- 直播离线退避 deadline 在 `Long.MAX_VALUE` 附近不会溢出并提前放行。
- real bench/managed/decoder override 默认值、显式覆盖和非法值回退；bench 与生产 NV12 RG8 解析语义一致。
- replacement session 主动摘除并 discard 旧 sound，同 session sound 保留且其他 source 不受影响；
- 迟到旧 session exact finish 不会移除 replacement sound。
- stale sound factory 会被拒绝、注销并停止，不能写入当前索引；
- check→write 窗口内发生 session replacement 时，迟到 factory 不会覆盖 replacement sound；
- 同 session 重复 factory 确定性接管索引并 discard 前一 handle。
- 单 source stop 即时 discard 对应 sound，重复 stop 幂等且不影响其他 source；
- 全局 clear drain 所有 sound，重复 clear 时每个 handle 仍只释放一次。
- retry dispatch 成功时保留 ownership，明确拒绝或异常时各执行一次 rollback；
- retry rollback 的 exact finish 会立即 discard 当前 sound，迟到旧 finish 不能影响 replacement。
- MP4 retry 只有 expected session、队列与曲目全部匹配时才能提交，并保留原逻辑 session；
- seek generation、replacement session 或同 session 队列/曲目变化会拒绝迟到 retry completion；
- retained-session full sync 只清除 exact pending retry，不影响同 source 的其他 session owner。
- `VideoPlaybackInstance` timing/offscreen/presentation 默认值；
- audio latency、offscreen resume lag 的 current/legacy 键优先级；
- 非有限阈值回退和 8 个运行时参数的逐次读取语义。

## BenchMod 本机部署状态

BenchMod 已 clone：

- `/Users/zhongbai233/Documents/GitHub/BenchMod`

版本：

- `0.1.0-SNAPSHOT`
- Minecraft `26.1.2`
- NeoForge `26.1.2.76`

注意事项：

1. BenchMod 仓库只跟踪 `gradle-wrapper.properties`，没有 `gradle-wrapper.jar`；直接执行 BenchMod 的 `./gradlew` 会失败。
2. 本次使用 NetMusicCanPlayBili 的 Gradle 9.5.0 wrapper，通过 `-p` 驱动 BenchMod。
3. 已发布到 Maven Local 的模块：
   - `bench-api-core`
   - `bench-report-schema`
   - `bench-api-neoforge-26.1`
   - `bench-runtime-neoforge-26.1`
   - `bench-gradle-plugin`
4. `bench-network-core` 和 `bench-network-proxy` 当前没有 `publishToMavenLocal`，不需要为本场景发布。
5. BenchMod 仓库 `git status --short` 为空，没有源码改动。

如需重新发布 BenchMod，在 BenchMod 目录使用 NetMusic wrapper：

```bash
sh ../NetMusicCanPlayBili/gradlew -p "$PWD" \
  ':bench-api-core:publishToMavenLocal' \
  ':bench-report-schema:publishToMavenLocal' \
  ':bench-api-neoforge-26.1:publishToMavenLocal' \
  ':bench-runtime-neoforge-26.1:publishToMavenLocal' \
  ':bench-gradle-plugin:publishToMavenLocal'
```

## NetMusic 的 Bench 接线

`build.gradle` 在 `-PenableModBench=true` 时：

- 应用 `com.zhongbai233.minecraft-bench`；
- 将 NetMusic 本体作为 `runtimeOnly` 加入 Bench 客户端，满足 FML 依赖；
- 向 `benchClient` 显式透传 `ncpb.video.real_bench.*`；
- 增加模块级 `--enable-native-access=net_music_can_play_bili`；
- 默认构建和正式发布不启用上述 Bench runtime 接线。

`VideoBenchTest` 在 managed 模式下不会自动抢跑真实 bench。

`BiliRealVideoPlaybackBench` 新增只读状态：

- `RunState`
- `RunSnapshot`
- `snapshot()`

`NetMusicBenchProvider` 新增：

- `ncpb.real-bv-playback`
- `ncpb.playback-session-races`

该场景只有在以下条件同时满足时通过：

- 真实 Bilibili video info 请求成功；
- DASH playurl 成功；
- 至少一个画质阶段解码出真实帧；
- GPU 上传成功；
- decoder close operation 归零；
- HTTP active request 归零；
- video instance/pending resolve 归零；
- close zombie 归零；
- `MemoryResourceTracker` 所有 category 当前占用归零。

## 真实 BV 验证结果

BV：`BV1GJ411x7h7`

视频：

- 标题：`【官方 MV】Never Gonna Give You Up - Rick Astley`
- CID：`137649199`
- 时长：213 秒
- 画质：q16 / 360P
- 分辨率：640×360
- 编码：AV1
- 源帧率：25 FPS

环境：

- Apple M4，10 logical processors
- macOS 27.0，aarch64
- Java 25.0.2
- Minecraft 26.1.2
- NeoForge 26.1.2.76
- native FFmpeg/JNI bundle：`git-2026-08-10-3dbd669`
- 硬件后端：VideoToolbox
- 输出：NV12 planes，UV=RG8，shader upload

链路结果：

- Bilibili video info 成功；
- DASH 获取耗时约 172 ms；
- 首个 CDN host Range 请求返回 403；
- 成功 fallback 到 `upos-sz-mirrorzos.bilivideo.com`；
- 解码 12/12 帧；
- 预热 2 帧，测量 10 帧；
- direct frames：10；
- heap frames：0；
- 平均解码/取帧约 0.01 ms；
- 最大解码约 0.05 ms；
- 平均上传约 0.19 ms；
- 最大上传约 0.29 ms；
- 平均处理循环约 6.58 ms/帧；
- measured loop 约 151.9 FPS；
- NV12 上传约 0.33 MiB/帧，约为 RGBA 的 37.5%；
- `ncpb.real-bv-playback`：PASSED；
- active close：0；
- 最终资源归零检查通过。

说明：wall loop FPS 包含 API、CDN 403/fallback 和初始缓存等待，不代表稳定解码能力；稳定处理应参考 measured loop。

## 真实 MP3 seek / OpenAL 验证结果

当前默认媒体：`https://www.learningcontainer.com/wp-content/uploads/2020/02/Kalimba.mp3`

早期记录使用 SoundHelix Song 1；2026-08-12 将 opt-in Bench 默认源切换到上述 LearningContainer 文件。
SoundHelix 在同一 JVM 连续多次 Range 请求后会偶发长时间无响应，导致后续场景在 30 秒格式等待上限处失败；
新默认源连续 8 次 802375-byte Range 探测均成功，并在不放宽任何 PCM、seek、channel 或资源收敛断言的前提下
完成 integrated-client 10/10 全量验证。仍可用 `-PncpbRealMp3BenchUrl=...` 覆盖媒体 URL。

定向场景连续执行两次，均为 PASSED：

```bash
sh ./gradlew runBenchClient \
  -PenableModBench=true \
  -PmodBench.scenarios=ncpb.real-mp3-seek \
  -PncpbRealMp3Bench=true \
  --stacktrace
```

第一次：

- 5 秒 seek：effective 10.802 秒，PCM peak `0.11907959`，RMS `0.01989908`，clipping `0`；
- 12 秒 seek：effective 14.530 秒，PCM peak `0.09698486`，RMS `0.02046109`，clipping `0`。

第二次在更慢网络下仍通过：

- 5 秒 seek：effective 13.189 秒；
- 12 秒 seek：effective 17.795 秒；
- 两段首段 PCM 均非静音且 clipping 为 `0`，旧输出换代和最终资源收敛断言均通过。

最新默认 integrated-client 全量运行同样为 PASSED，10/10 场景全部通过。`ncpb.real-mp3-seek` 的既有数据：

- 5 秒 seek：effective 10.564 秒，setup 5.575 秒，首段 peak `0.07543945`、RMS `0.017634441`、
  clipping `0`；
- 12 秒 seek：effective 14.457 秒，setup 2.463 秒，首段 peak `0.0652771`、RMS `0.015400911`、
  clipping `0`；
- 两个 Stereo handler 均关闭且各 cleanup 恰好一次；pending native delete 最大值和最终值均为 `0`；
- active output、native close operation 和 `AUDIO_STAGING` 最终均回到基线。

effective time 包含为维持媒体时间线同步而加入的网络/解码启动延迟，因此会随公网速度变化。质量断言针对解码后
PCM 标量，避免把“成功创建 stream”误当作无白噪音/无失真验证。覆盖范围是生产 MP3 decoder 到直接 OpenAL
pipeline；SoundEngine channel 覆盖见下一节。

## 真实 MP3 SoundEngine channel 验证结果

最终加强版默认 integrated-client 全量运行中的 `ncpb.real-mp3-sound-engine` 为 PASSED：

- 第一段 5 秒 offset：effective 6.695 秒，setup 1.721 秒，4096-sample 窗口中的 peak `0.034942627`、
  RMS `0.0089741245`、clipping `0`；
- 第二段 12 秒 offset：effective 14.698 秒，setup 2.744 秒，4096-sample 窗口中的 peak `0.03970337`、
  RMS `0.013007001`、clipping `0`；
- `PlayStreamingSourceEvent` 计数最大值和最终值均为 2，证明两个真实 streaming channel 均已挂载；
- 两个 Stereo handler 均由 `Sound engine` 线程关闭，分别处理约 704 和 702 blocks；
- 场景内部 exact lifecycle 断言确认 tapped stream 创建/关闭各 2 次、Stereo handler 创建和 cleanup
  start/complete 各 2 次；SoundManager、输出 registry、native close/delete 和 staging memory 最终收敛；
- 报告采样中 sound active 最大值 1、最终值 0，tap/OpenAL active 最大值 1、最终值 0。

定向运行命令：

```bash
sh ./gradlew runBenchClient \
  -PenableModBench=true \
  -PmodBench.scenarios=ncpb.real-mp3-sound-engine \
  -PncpbRealMp3Bench=true \
  --stacktrace
```

该验证覆盖 `SoundManager.play` 到真实 streaming channel、生产 getStream/MP3/OpenAL pipeline 以及 channel
换代/销毁，不覆盖真实唱片机方块、服务端 resolve 和网络 packet 入口的完整交互链。

## 第 30 阶段：真实 SoundEngine pause→快速 resume 连续性

生产修复：

- `ClientPauseChangeEvent.Post` 把 Minecraft pause 状态传给 `ClientAudioOutputRegistry`；
- registry 将状态同步给 Stereo、Dolby 和 speaker relay，并让暂停期间新注册或重建的输出继承当前状态；
- `OpenALSpatialAudio` 显式暂停自建 bed/object sources，保留 queued buffer 和媒体时间线；恢复时只继续
  paused source，或仍有 queued buffer 的 stopped source；
- pump、flush 和设备丢失后重建均保持 pause 语义，cleanup 不再把全局 pause 状态意外复位；
- OpenAL 常量映射后的动作由纯 Java `OpenAlPauseStatePolicy` 决定，普通单测不依赖 LWJGL classpath。

`ncpb.real-mp3-sound-engine` 第二条真实 streaming channel 现在包含约 750ms pause hold，并断言：

- SoundManager 生产 pause/resume 操作和自建 OpenAL 输出同时进入 pause/resume；
- 暂停期间输出位置冻结在 756ms（连续 16 个采样均不变），恢复后的首个采样为 799ms，最终推进到 1108ms；
- channel starts 始终为 2，tapped stream 和 Stereo handler 创建数也始终为 2，没有为 resume 重建管线；
- SoundManager active sound、tapped stream、OpenAL handler、native close/delete 和 `AUDIO_STAGING` 最终仍收敛。

定向运行与启用真实 MP3 opt-in 的默认全量运行均为 PASSED；最新全量报告为 10/10 PASSED。场景直接调用
`SoundManager.pauseAllExcept()` / `resume()` 及 registry pause 入口，不打开真实 `PauseScreen`，因为 BenchMod
环境 guard 会将真实暂停菜单判为 INCONCLUSIVE；生产事件订阅已由编译覆盖，但真实菜单 UI 入口仍属于人工验证范围。

## 第 31 阶段：retained-session 真实 transport refresh 与旧 retry 抑制

新增 opt-in integrated-client 场景 `ncpb.real-mp3-retained-retry`，在同一个 `PlaybackSessionId` 下依次提交
initial 与 refreshed 两个 transport URL，并直接走生产链：

```text
ClientMediaSyncHandler
→ ClientMediaPrepareLauncher
→ SyncedMediaPlaybackLauncher
→ HttpAudioStreamHandler request token
→ SoundManager streaming channel
→ real MP3 decode
→ OpenAL
```

第一条真实 channel 输出有效 PCM 后，场景将其标记为 transport failure 并登记 750ms delayed retry；在 timer
触发前发送同-session authoritative refresh。场景断言 exact retry owner 被立即清除，refreshed transport 新建
第二条真实 channel，旧 retry 最终 dispatch 仍为 0，且逻辑 session 没有变化。

最新 10/10 全量运行中的实测结果：

- initial transport：目标约 5.010 秒，effective 8.520 秒，首段 peak `0.039611816`、RMS `0.007733369`、
  clipping `0`；
- refreshed transport：目标约 12.001 秒，慢网下 effective 26.169 秒，首段 peak `0.020935059`、
  RMS `0.004800470`、clipping `0`；
- prepare 最大值 2、streaming channel starts 最大值 2、retry dispatch 最小值和最大值均为 0；
- prepare 总数 2、sound rebuild 总数 1，两个 launch URL 不同，第一条 sound exact discard 恰好 1 次；
- tapped stream 与 Stereo handler 各创建并关闭 2 个，两个 sound 最终各 discard 1 次；
- stop 后 active playback/sound/retry owner、SoundManager active sound、OpenAL/native close/delete 与
  `AUDIO_STAGING` 全部回到基线。

定向运行和启用真实 MP3 opt-in 的默认全量运行均为 PASSED。该场景补齐了 retained-session retry 的真实新
transport prepare、旧 timer 抑制和 SoundEngine/OpenAL 换代；仍不覆盖真实唱片机方块与服务端 packet 入口。

## 第 32 阶段：播放中客户端世界卸载清理

修复了一个真实生命周期缺口：`ClientMediaLifecycleHandler` 之前只在 logout 或 `Minecraft.level == null` 时执行
全量清理。维度切换可能让旧 `ClientLevel` 直接替换为另一个非空 level，因此新增 `LevelEvent.Unload` 客户端入口，
在旧世界卸载时立即清除 lease、playback/sound、prepare/lyrics/retry owner、视频、OpenAL 输出和相关缓存。

`ncpb.real-mp3-retained-retry` 已进一步扩展：refreshed transport 的第二条真实 SoundEngine/OpenAL channel 输出
有效 PCM 后，先登记另一条 750ms delayed retry，再以当前真实 `ClientLevel` 调用生产 world-unload 入口。场景断言：

- unload 返回时 exact retry owner、active playback 和 sound registry 已清除；
- 当前 SoundManager sound 被 discard，tapped stream 与 Stereo handler 完成关闭；
- 等待超过 retry delay 后 dispatch 仍为 0，不会在新世界抢回旧 session；
- native close/delete、OpenAL output 与 `AUDIO_STAGING` 全部回到基线；
- 两条真实 transport 的 prepare、PCM 质量、channel 创建和 exact cleanup 断言继续成立。

定向真实 MP3 运行与启用真实 MP3 opt-in 的默认全量运行均为 PASSED，最新报告仍为 10/10 PASSED。此验证直接
驱动生产 `LevelEvent.Unload` handler 和真实媒体资源；第 36 阶段又补齐了服务器实际传送、客户端 respawn packet、
加载 UI 与旧世界 unload 的完整往返冒烟。

## 第 33 阶段：取出唱片的 exact-session stop

修复了现代唱片机停止路径仅依赖客户端方块状态自检的问题。此前取出唱片后，已启动的 sound 通常会在下一 tick
停止，但异步 prepare 可能在方块状态更新竞态中迟到提交一条 streaming channel。现在新增
`ModernTurntableStopPacket(pos, sessionId)`：

- 服务端在清空播放字段前冻结旧 `PlaybackSessionId`，向本轮已同步及当前 96 格范围内玩家发送 stop；
- 手动取出、自动提取、播放结束、暂停、重播和方块移除均复用同一通知路径；
- 客户端通过 `ModernTurntablePlaybackTracker.finish(pos, sessionId)` 原子取消该 session 绑定的 prepare、request
  token、sound、video、歌词和恢复任务；
- stop 使用 exact-session 判定，迟到的旧唱片 stop 不会终止刚换上的新唱片；纯 Java
  `ModernTurntableStopPolicy` 让该竞态可在普通 JUnit classpath 验证；
- `syncNearbyPlayers` 现在同时维护 `syncedPlayers`，确保曾收到 start 的听众即使刚走出范围也能收到 stop。

`ncpb.real-mp3-sound-engine` 在原有两条真实 channel 与 pause→resume 验证后，进一步把第二条 sound 绑定到现代
唱片机 tracker：先注入第一 session 的 stale stop，断言第二条 channel 继续活跃；再注入第二 session 的 exact
eject stop，断言 SoundManager sound、tapped stream、Stereo OpenAL handler、native close/delete 与
`AUDIO_STAGING` 全部收敛，且 channel/stream/handler 创建数仍为 2，没有为停止重建。

定向真实 MP3 运行和启用真实 MP3 opt-in 的默认全量运行均 PASSED，最新报告为 10/10。服务端 packet 注册、受众选择与各 stop 调用点由编译覆盖；本场景直接驱动 packet 的
客户端落点和真实音频资源，没有在 Bench 世界放置方块并模拟右键/漏斗，因此真实交互 packet 冒烟仍保留在完整
唱片机入口待办中。

## 第 34 阶段：静音与超距 exact-session stop

补齐了仍会让客户端在无声状态下继续解码的两个入口：

- 服务端每 20 tick 对比 `syncedPlayers` 与当前 96 格范围受众，离开范围的玩家立即收到当前 session 的
  `ModernTurntableStopPacket`；重新进入时沿用既有 sync，从服务端当前时间 offset 重建，不从头播放；
- 删除原先每 3 秒直接清空 `syncedPlayers` 的全量重同步逻辑，避免丢失“刚离开范围”的受众身份；
- 旁观玩家即使不激活方块实体 ticker，也由全局 spectator 同步入口完成同样的离场 exact stop；
- 播放中音量从非零变为 0 时，服务端时间线继续推进，但向已同步和当前范围受众广播 exact stop，并禁止静音期间
  继续下发 start；从 0 恢复为非零时，从当前 offset 向范围内玩家重新同步；
- `ModernTurntableAudiencePolicy` 与 `ModernTurntableVolumePolicy` 将集合差异和静音/恢复状态转换提取为纯 Java
  策略，覆盖离场、重入、重复音量、clamp、停止态音量调整等边界。

真实 `ncpb.real-mp3-sound-engine` 场景现在用第一条 channel 验证静音 exact stop，用第二条 channel 在
pause→resume 后验证 stale stop 保活和超距 exact stop。定向运行 PASSED；两条 SoundEngine channel、两个 tapped
stream 与两个 Stereo OpenAL handler 均恰好创建/关闭两次，native close/delete 与 `AUDIO_STAGING` 回到基线，
没有用“音量设为 0”保留后台解码。

## 第 35 阶段：真实方块右键与事务自动提取入口

新增默认 integrated-client 场景 `ncpb.turntable-block-interactions`，在真实服务端世界放置注册的现代唱片机
方块与方块实体，并覆盖生产入口：

- 客户端通过 `gameMode.useItemOn(...)` 发送真实交互 packet，手持 NetMusic `MUSIC_CD` 完成第一次插入；
- 改为手持普通木棍再次右键，确认走 `ModernTurntableBlock.useItemOn` 的生产取出分支，而不是测试辅助方法；
- 再次通过真实 packet 插入第二张唱片；
- 对 NeoForge `ResourceHandler` 打开根事务，确认默认 `AFTER_PLAYBACK` 在播放中返回 0、不允许提取；
- 切换为 `ALWAYS` 后重新提取并 commit，确认恰好取出一张唱片；
- 每一步同时断言方块实体的 `hasDisc/isPlaying` 与 `HAS_DISC/PLAYING` blockstate 一致，最终客户端同步也收敛；
- 场景把唱片机音量设为 0 并使用 `example.test` 夹具 URL，不依赖公网；teardown 清空双端手持物、丢弃物和方块；
- 每个子阶段带 100 tick 卡死保护和服务端状态快照，避免异步任务竞态退化为整场景超时。

定向运行 PASSED；随后启用真实 MP3 opt-in 的默认全量运行也为 10/10 PASSED，证明新增方块入口场景与既有
真实 MP3 seek、SoundEngine exact stop、retained-session retry 和资源收敛场景可以在同一 JVM 中连续通过。

构建期间还确认云同步会实时恢复 `build/classes` 下带空格的冲突副本并拖住 Gradle 输出快照。`build.gradle`
新增可选 `-PncpbBuildDirectory=/tmp/...`，仅在显式提供时把构建产物放到仓库外；默认本地和 CI 构建目录不变。

## 第 36 阶段：真实跨维度 respawn/UI 与媒体清理

新增默认 integrated-client 场景 `ncpb.cross-dimension-media-cleanup`，由集成服务器把真实玩家从主世界传送到
下界，再传回原位置。场景不直接调用生命周期 handler，而是完整经过服务端 teleport、客户端 respawn packet、
`LocalPlayer` clone、`LevelLoadingScreen` 和两个旧 `ClientLevel` 的真实 unload 事件。

两次传送前分别登记独立的客户端 playback/sound session；每次旧世界卸载后均断言 active playback 与 sound
registry 立即清空，对应 sound exact discard 恰好一次。场景还使用 BenchMod 的预期 GUI 会话声明加载界面是被测
状态，因此不会把合法的维度加载 UI 误判为环境干扰。临时目标平台在 teardown 中清除，失败时也会尝试把玩家
送回原维度和原坐标。

定向运行 PASSED；随后未启用公网媒体的默认 integrated-client 全量运行 8/8 PASSED，证明跨维度往返后其它
客户端状态与报告环境均能收敛。

## 第 37 阶段：真实唱片机公网 MP3 端到端播放链

新增 opt-in integrated-client 场景 `ncpb.real-turntable-mp3-end-to-end`。场景在真实服务端世界放置注册的现代
唱片机，通过客户端 `gameMode.useItemOn(...)` 插入指向公网 MP3 的 NetMusic 唱片，完整经过服务端
`MusicPlayResolverManager.resolve`、方块实体权威会话、`MusicToClientMessage` 网络同步、客户端 Mixin、
`ModernTurntablePlaybackCoordinator`、Minecraft SoundEngine streaming channel 和 Stereo OpenAL 输出。

场景同时断言服务端与客户端使用同一个非空 `PlaybackSessionId`、只创建一条 `ModernTurntableSound` streaming
channel、首段 PCM 至少采集 1024 samples 且非静音/有限/无 clipping。随后改用木棍发送真实取出 packet，验证
服务端方块实体和 `HAS_DISC/PLAYING` blockstate 双端收敛，并要求 sound、tracker、tapped stream、Stereo handler、
native delete/close 与 audio staging memory 回到精确基线；两个 handler lifecycle 均恰好创建和清理一次。

为使集成场景能只读观察按方块位置拥有的真实 Stereo 输出，`ClientAudioOutputRegistry` 新增
`getStereoSnapshot(BlockPos)` 诊断 accessor，不改变输出注册、替换或清理语义。

定向运行 PASSED；随后启用真实 MP3 opt-in 的完整 integrated-client 回归 12/12 PASSED，报告环境保持 valid，
证明默认生命周期/跨维度场景、三条既有真实 MP3 场景与新端到端入口在同一 JVM 中连续执行无状态串扰。

## 第 38 阶段：AV1 playurl 冻结 JSON 候选矩阵

`BiliApiClient` 将生产 `playurl` 响应映射抽为无网络、无全局注册副作用的 `parseVideoStreams` 边界，线上请求仍在
解析、候选规划完成后统一登记 SegmentBase 与 CDN alternates，网络参数、日志和最终选择语义保持不变。

新增 `src/test/resources/bili/av1-playurl-matrix.json` 冻结响应夹具和两个 Java 用例，覆盖：

- 8K/4K 只有 AV1、较低 1080P 有 H.264 回退；
- 同画质 AV1/H.264/HEVC 混合，AV1 优先且 HEVC 明确拒绝；
- 无 AV1 时选择 H.264，以及只有 HEVC 时明确失败；
- 未显式请求的 HDR/杜比等特殊画质不会进入普通画质候选；
- `baseUrl/base_url`、`backupUrl/backup_url` 两套真实字段映射；
- AV1 与 H.264 的 CDN 列表和 `segment_base` ranges 保持逐流隔离，不发生交叉污染。

AV1 迁移计划的阶段 1 冻结 JSON 待办由此关闭；未把本机 macOS ARM64 的成功加载外推成六平台动态加载结论，
剩余工作仍是各目标平台依赖审计和真实 B站 AV1/设备矩阵。

## 第 39 阶段：AV1 发布能力与法律材料门槛

新增单一来源 `docs/release-native-capabilities.md`，明确发布包的真实能力边界：H.264 软件/硬件解码、AV1 仅
平台硬解且无 dav1d/libaom 软件回退、HEVC 全面排除、Linux VAAPI 宿主依赖，以及不同设备/profile/位深/
分辨率下不能保证 AV1 可用。根 README 链接该说明，GitHub Release notes 生成脚本在每次 tag 发布时直接嵌入
同一文件，避免项目首页、下载页与发布包声明漂移。

新增 Gradle `verifyProductionJarLegalMaterials` 并挂入 `check`。任务构建生产 JAR 后验证以下 9 个条目存在且
非空：AOMedia Patent License、第三方汇总声明、native provenance README，以及六个平台各自的 FFmpeg LGPL
文本；同时验证能力说明包含关键限制，并确认 release workflow 实际读取该文件。定向任务 PASSED。

AV1 迁移计划中的“下载页面能力/许可证说明”和“AOMedia/FFmpeg 材料随 JAR 与下载页提供”两项由此关闭。
这不替代六个平台的真实动态加载、符号/依赖审计或真实 AV1 设备矩阵。

## 第 40 阶段：AV1 候选首帧双预算与安全换挡

`Fmp4NativeVideoDecoder` 新增仅供 AV1 硬解候选使用的有界首帧入口。预算从 lazy worker 启动前开始，默认最多
等待 `2000ms`、成功送入 native 的媒体 sample 最多 `256` 个；首包附带的 config OBU 不额外计数，流恢复也不
重置累计值。每包在紧邻 native send 前取得 exact permit，send 成功后 permit 一直持有到 decoder drain 至
EAGAIN；时间截止会封住下一包，但不会把已经获准的 native 调用追溯为越界发送。第 256 个 packet 会完整 drain：
同包第一帧被 stale/invalid/播放队列拒绝时仍可检查后续输出，只有到 EAGAIN 仍未提交播放队列才失败，且不会
发送第 257 个。native receive 返回时记录 frame-ready 时间，因此截止前产出的帧可在截止后被消费/提交，截止后
产出的帧只关闭并继续 drain。属性入口：

- `ncpb.video.native.av1_first_frame_probe_timeout_ms`，安全范围 `250..10000`；
- `ncpb.video.native.av1_first_frame_probe_max_packets`，安全范围 `1..4096`。

双预算只在 `codecId=13 && DecodeMode.HARDWARE_REQUIRED` 时接入 `VideoPlaybackInstance` 与手持 MP4/Pad
候选循环；H.264、直播、Bench 和未来显式软件 AV1 不受影响。既有 `20s × 2` 会话级首帧 watchdog 保留为第二层
保护。`HARDWARE_REQUIRED` 现在还会核对 native 返回的 actual backend，只接受当前请求列表中的
`d3d11va/dxva2/cuda/qsv/videotoolbox/vaapi`；`cpu/none/unknown` 或未识别名称不再能静默通过。

候选提交点从“拿到 decoded frame”收紧为“frame 成功进入播放队列”。失败候选会先 request close，并等待
`close()` 返回与 `native termination` 两个信号；两者收敛前禁止打开下一 AV1/H.264。投影实例在关闭超时后进入
`FAILED_CLOSE`、失效 generation 并登记 zombie，迟到 termination 只能收敛诊断，不能复活 fallback；手持链路同样
fail closed，不再继续下一个候选。候选关闭过程也进入 `VideoCloseDiagnostics`。

底层关闭生命周期同时完成收敛：普通 GET、init、SIDX、range 与组合流统一进入 tracked input registry；取消后
迟到登记的 stream 也会 exact close，input/decoder close 异常会使 termination exceptional，而不是误报正常退出。
尚未 lazy-start、worker 已退出和 decoder close 重试均由后台物理关闭任务负责；JNI close 使用三次有界退避，最终
失败会进入 fail-closed/zombie 监督，不再无限高频重试。

跨候选之外还增加了跨实例准入：手持端按 `PlaybackSourceId` 保留跨 state clear 的 replacement gate；投影端按稳定
owner key，并额外按 session 聚合物理 handoff，覆盖相同 source 换 session、相同 session 换 owner、registry
remove/clear、GUI replacement 和 stop-during-open。投影 handoff 从实例出生即暴露稳定的
`closeReturned/nativeTermination/decodeExit/renderRelease` 四个 future；旧实例四信号全部正常前，新实例不会
publish/start。exception/cancel/timeout 均 fail closed，迟到 callback 受 intent epoch 约束，不能复活已超时或被更新
intent 取代的 decoder；不同 owner 仍按设计允许并行。

新增/扩展纯 Java 回归覆盖：

- 1999/2000ms、255/256 packet 边界和两个预算任一先到；
- 第 256 个 packet 完整 drain、同包 reject→accept、截止期间 in-flight drain 与 frame-ready 时间线性化；
- 仅硬解 AV1 启用预算，属性默认/覆盖/安全上下限；
- actual backend 的硬件 allowlist；
- `close returned + native terminated` 全部满足前禁止打开下一候选，边界超时 fail closed。
- tracked input late-registration、组合流、close failure、decoder close 有界重试；
- 投影四信号稳定 handoff、stop-during-open、跨 generation 聚合、同 owner 多 epoch 组合与并发 single commit；
- 手持跨会话 admission、timeout first-winner、clear/recreate、same-key ABA 与不同 device 独立性。

2026-08-13 使用隔离目录 `/private/tmp/ncpb-build-phase40-final2-20260813` 重跑全量 Java，结果为
791/791 PASSED；关键生命周期/替换定向集为 55/55 PASSED。迁移计划“实现首帧双预算”已关闭；真实 B站 AV1
fMP4 样本、range seek、
GPU surface/内存归零和多设备矩阵仍保持未完成，不能用纯 Java 策略测试替代。

并行 native bundle 复核还确认：本机 macOS ARM64 五库可真实加载，x86_64 五库可在 Rosetta x86 进程加载，
两套 JNI 导出与 `git-2026-08-10-3dbd669` 一致；另外从 Linux ELF version requirements 确认 ARM64 要求
`GLIBC_2.38` symbol version、x86_64 要求 `GLIBC_2.35`。两项 glibc 下限已补入随 JAR 分发的 native README、下载页能力说明
和 Gradle 发布材料门槛。Linux/Windows 四个平台仍只有静态审计，六平台 runner 动态加载与逐文件 hash manifest
仍应作为下一发布门槛，不能标记完成。

## 第 41 阶段：六平台 native 精确文件集与逐文件哈希门槛

新增随源码和生产 JAR 一起分发的 `src/main/resources/native/SHA256SUMS`。该 manifest 覆盖六个平台目录下全部
40 个库/runtime/license 文件；路径集合本身也是契约，不只校验内容。新增 Gradle
`verifyEmbeddedNativeBundle`，同时验证：

- manifest 行格式、重复/绝对/路径穿越；
- Linux/macOS 每平台精确 6 文件、Windows 每平台精确 8 文件，缺失和多余均失败；
- source tree 中每个文件不是 symlink 且 SHA-256 与 manifest 一致；
- 生产 JAR 必须包含 `native/SHA256SUMS`，六个平台 JAR entry 集合精确一致，逐 entry SHA-256 再次一致。

任务挂入 `check`，`verifyProductionJarLegalMaterials` 也要求 manifest 随包；tag release workflow 在正式 build 前
显式运行 `verifyEmbeddedNativeBundle`。`native/README.md` 与下载页能力说明已把逐文件 manifest 定义为权威提取后
证据，后续替换任一二进制必须整体替换相应平台 bundle 并有意更新 manifest，不能继续只凭归档 hash。

2026-08-13 使用隔离目录 `/private/tmp/ncpb-build-native-manifest2-20260813` 执行：

```bash
bash ./gradlew verifyEmbeddedNativeBundle verifyProductionJarLegalMaterials \
  -PncpbBuildDirectory=/private/tmp/ncpb-build-native-manifest2-20260813 \
  --no-daemon --stacktrace
```

结果 `BUILD SUCCESSFUL`。这一阶段关闭“精确文件集/逐文件 SHA-256”发布门槛，但不等于六平台真实动态加载完成：
本机已验证两套 macOS，Linux/Windows 四套仍需在对应架构 runner/设备执行 loader、JNI 导出与依赖审计。

## 第 42 阶段：六平台真实架构 native runtime smoke 接线

新增 `tools/verify_native_runtime.py`。该脚本拒绝 OS/CPU 与目标目录不一致的 runner，然后按生产 loader 顺序加载
Windows runtime、FFmpeg 与两套 JNI 动态库，调用 `av_version_info()` 锁定
`git-2026-08-10-3dbd669`，并用动态链接器逐个解析 5 个 `Eac3Jni` 与 17 个 `VideoJni` 导出。加载本身同时验证
当前 runner 上的二级动态依赖可解析，不能再用 `file`/字符串扫描代替真实 loader 证据。

`build-release.yml` 新增六项 fail-fast=false matrix：

- `ubuntu-24.04` / `linux-x86_64`；
- `ubuntu-24.04-arm` / `linux-arm64`；
- `macos-15-intel` / `macos-x86_64`；
- `macos-15` / `macos-arm64`；
- `windows-2025` / `windows-x86_64`；
- `windows-11-arm` / `windows-arm64`。

Linux runner 显式安装 bundle 直接依赖的 `libva2/libva-drm2/libdrm2`；release publish 现在同时依赖 build 与完整
native smoke matrix，任一平台 loader/version/JNI export 失败都会阻断 tag 发布。runner 标签取自 2026-08-13
[GitHub hosted-runners 官方表](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)，其中
Linux/Windows ARM64 标签仍为 public preview。`macos-15-intel` 是 GitHub 为 x86_64 提供的最后一代 hosted
image，官方计划保留至 2027 年 8 月；`macos-14` 已在 2026 年 7 月进入弃用期，因此 ARM64 job 固定使用
`macos-15`，不再依赖即将退役的标签或会滚动迁移的 `macos-latest`。

两条 macOS build job 同时移除了历史遗留的 `--enable-cross-compile`、`--target-os=darwin` 和
`clang -arch ...` 编译器包装：`macos-15-intel` 必须报告 `runner.arch=X64`，`macos-15` 必须报告
`runner.arch=ARM64`，随后分别执行原生 thin x86_64/arm64 构建；六个平台矩阵均在下载依赖前核对 runner
architecture，标签映射错误会 fail closed。size probe 也使用同样的固定标签和原生模式。Windows ARM64 的
CLANGARM64 target triple 保留为 MSYS2 原生工具链选择，并不从 x64 runner 生成 ARM64 产物。

本机随后用相同的无 cross-compile 配置完成两次 FFmpeg configure/build/install：ARM64 原生输出的
`libavutil/libavcodec/libswscale` 均为 thin arm64；x86_64 使用仅用于本机 Rosetta 模拟 hosted Intel 默认目标的
compiler wrapper，三库均为 thin x86_64。两次均启用 pthread、libdav1d 与 VideoToolbox H.264/AV1，且 configure
参数中不含 `--enable-cross-compile`。`build_media.sh` 也将 `w32threads/-static-libgcc/-static-libstdc++` 收紧到
Windows，macOS/Linux 使用 pthread 和当前 `CC`，避免本地入口重新引入平台错误的链接参数。

本机真实执行结果：macOS ARM64 原生 Python 与 Rosetta x86_64 universal Python 均成功加载对应五库，版本一致，
5+17 JNI 导出全部解析。Linux/Windows 四个平台的 workflow 已接线，但在该改动实际进入 GitHub Actions 并取得
四份 green run 前仍保持“未验证”，不得仅因 YAML 已存在就勾选六平台动态加载目标。

随后新增的 `tools/verify_native_architectures.py` 可在任意 OS 上静态解析全部六目录 34 个二进制的
ELF/Mach-O/PE 共享库类型与目标机器号；当前 v38 嵌入包已通过 `platforms=6 binaries=34`，配套 3/3
正反向测试也通过。它能在 runtime matrix 前拒绝归档映射和机器类型错误，但不能替代目标 runner 的动态链接器、
宿主依赖闭包与 JNI 实际解析，所以 Linux/Windows 的 runtime 结论仍保持未验证。

## 第 43 阶段：真实 B站 AV1 fMP4 冻结夹具与 EOF drain 契约

从公开视频 `BV1qM4y1w716`、CID `455439756` 的 360p AV1 DASH 流冻结两段真实 CDN 字节，而不是手工拼最小 box：

- `0-117149`：完整 `ftyp/moov`、39 项 `sidx` 与首个 5 秒 `moof/mdat`；
- `900893-962851`：`sidx` 第 8 项、从 35 秒开始的完整 `moof/mdat`。

两段以 Base64 测试资源保存，decoded size、source range 和 SHA-256 由
`src/test/resources/bili/real-av1/fixture.json` 固定；仓库不保存签名 URL、cookie、音轨或完整视频。
`tools/fetch_bilibili_av1_fixture.py` 会解析一条新 playurl、严格匹配 quality/codec/profile/尺寸/SegmentBase，
只重取上述范围并校验哈希。本轮公网重取结果与冻结值完全一致：117150 bytes/
`39a7b50c...54c349`，61959 bytes/`c2abab80...5bd215`。

`RealBilibiliAv1FixtureTest` 直接驱动生产 `Fmp4StreamParser`、`Fmp4RangeSeekSupport`、
`Fmp4ToMp4Converter` 和 AV1 config 提取路径，确认：

- `av01/av1C` config OBU、16000 timescale、首 fragment 125 samples/115062 payload bytes；
- 39 项 `sidx` 的 absolute byte ranges，35 秒项精确为 `900893-962851`；
- init + 非连续 35 秒 fragment 可重建 range 输入，`tfdt`/PTS 从 35.000s 到 39.960s；
- 首 fragment PTS 从 0 到 4.960s，样本边界总和与 `mdat` 精确一致。

同时修正 `trun` composition-time 版本语义：version 0 按 unsigned 32-bit，version 1 按 signed 32-bit，新增
decode-order 与 presentation-order 不同的回归。这个修复证明 Java demux/packet PTS 不会在高位 offset 或 B-frame
重排时错误符号扩展，但仍不能替代 native decoder 输出顺序的真实硬件验证。

精确核对 `media-min-v38` 源提交 `3dbd6699...` 的 `video_jni.c` 后确认，现有 `VideoJni.flush()` 只调用
`avcodec_flush_buffers()`，属于 seek/reset 且会丢弃缓存，不是 EOF drain。Java 已增加向后兼容的
`sendEndOfStream` 契约、AV1 首帧 probe 的 EOF drain lease，以及自然 EOF 后排空输出的接线；v38 缺少 JNI symbol
时保持原行为。可直接应用到精确 native 源的补丁位于
`native-patches/media-min-v39-dav1d-eof-drain.patch`，独立 Java/JNI 真实夹具 smoke 位于
`tools/native_av1_smoke/`。补丁已对 v38 精确源码执行 `patch --dry-run` 通过，但在六平台 v39 重建、逐文件 manifest
替换和真实硬件 smoke 前，六平台发布门槛仍保持未完成。

本机 macOS ARM64 运行真实 AV1 JNI smoke 时，VideoToolbox 明确报告当前设备不支持硬件 AV1，首包失败；这是一条
真实“旧设备不支持 AV1”负向设备证据，不得误写成平台 AV1 成功。阶段 43 隔离全量构建目录为
`/private/tmp/ncpb-build-phase43-20260813`，结果 `BUILD SUCCESSFUL`，Java 798/798、native manifest、生产 JAR 法律
材料和 conflict-copy 门槛全部通过；`git diff --check` 与两个 Python 工具语法检查通过。

## 第 44 阶段：projection 双域 handoff、codec policy 与 v39 软件 AV1 实构建

投影实例的物理关闭冲突不再只按 `replacementOwnerKey` 串行化。`ProjectionReplacementGate` 现在在同一 monitor
内同时维护 owner 与 `PlaybackSessionId` 两个 domain epoch：同 owner 换 session、同 session 换 owner都必须等
`closeReturned/nativeTermination/decodeExit/renderRelease` 四信号正常完成；超时、异常、cancel 或任一域被更新均
fail-closed。活跃实例的 born-pending handoff 在 registry publication/start 之前同时写入两个域，remove/clear 也先
保留两域再 stop，旧异步 callback 在 commit 时必须同时复核两域 epoch。新增跨 owner 同 session、跨 session 同
owner、commit-before-publication、session ABA 与并发 exactly-once 回归，关闭了相同 session 纹理 ID 被旧 render
release 误释放新实例的窗口。

`BiliApiClient.VideoStreamPlan` 已实现文档规定的四种纯函数 codec policy：

- `auto`：最多三个代表性 AV1 硬解候选、H.264、受限软件 AV1；
- `prefer-av1`：AV1 硬解、受限软件 AV1、H.264；
- `compatibility`：只探测一条最高 AV1 硬解，随后 H.264，不生成软件候选；
- `h264`：只允许 H.264，无 H.264 时明确失败。

resolver 通过显式 `VideoDecodePreference` 生成 `HARDWARE_REQUIRED/AUTO/SOFTWARE_ONLY`，不再仅按 codecId 猜测。
软件安全矩阵已落为测试过的纯策略：`<=720p60`、`<=1080p30` 可用，`prefer-av1` 额外允许 `1080p60`，
1440p/4K/8K 和未知尺寸/fps 拒绝。当前嵌入的仍是 v38，因此 `BUNDLED_SOFTWARE_AV1_AVAILABLE=false`，生产计划
不会提前暴露实际上不存在的 backend；只有完整 v39 六平台 bundle 一次性替换并通过门槛后才能翻转。

native patch 已扩展为一个不可拆分的 v39 单元：固定 dav1d 1.5.4 commit
`54706fc6bc0cdecab7e9593974a4039cc038fca7`，六平台构建为 PIC static library，硬件请求显式选择 FFmpeg native
`av1`、`none/off` 显式选择 `libdav1d`，新增 EOF marker JNI export，禁止产生 dav1d 动态依赖，并把
BSD-2-Clause 许可证与对应源码纳入每个平台归档/release。补丁对精确 v38 commit 的 `video_jni.c`、
`build_media.sh`、`.github/workflows/build.yml` 三文件 dry-run 全部通过；shell、YAML 和 configure 后 C 语法检查通过。

本机进一步真实构建了 patched macOS ARM64 产物，不是仅做字符串审计。静态 dav1d + FFmpeg + JNI 对冻结 B站
首 fragment 的结果为 `requested=none actual=cpu(libdav1d) packets=125 framesBeforeEofDrain=125
framesDrainedAtEof=0 framesAfterEofDrain=125 firstPts=0 lastPts=4960000000`。四个 Mach-O 均为 thin ARM64、最低
macOS 11.0、无 dav1d dylib 依赖，install name/依赖闭包和 ad-hoc signature 验证通过。该低延迟样本没有 EOF
delayed tail，因此当时只证明 EOF 接口不丢不重 125 帧；第 46 阶段已另用受控真实 decoder delay 覆盖尾帧。
相同 build 的
`videotoolbox` 请求仍在首包报告宿主设备不支持 AV1，维持负向硬件证据。

阶段 44 隔离全量构建目录 `/private/tmp/ncpb-build-phase44-20260813` 为 `BUILD SUCCESSFUL`：Java 806/806、
207 suites、0 failure/error/skipped；native manifest、生产 JAR 法律材料和 conflict-copy 门槛通过，
`git diff --check`、Python 工具语法与 standalone JNI smoke 编译通过。六平台 v39 归档尚未生成/嵌入，不能将
本机软件解码成功外推为六平台发布完成。

## 第 45 阶段：AV1 持续性能预算与用户可见降级诊断

`VideoPerformanceFallbackPolicy` 与 `VideoPerformanceMonitor` 已把迁移方案中的持续性能保护落为独立、可测试
边界。候选首帧提交后开始五秒有效播放观测，暂停和离屏等待时间不计入窗口；采样 actual backend、实际 decode
FPS、平均/p95 native get 耗时、队列饥饿、丢帧比例、音画差绝对值增长，以及 FFmpeg/D3D11 native 内存和
surface 峰值。实际 FPS 低于目标的 80%，或音画差绝对值连续增长并越过集中阈值时才触发；精确 80% 保持播放，
没有 H.264 后备时只记录一次稳定原因，不反复重启。

投影与手持两条正式播放链均已接线，而不只是保留纯策略：投影设置 session 级 H.264 lock 后复用既有
`restartDecoder` 的 `closeReturned/nativeTermination/decodeExit` 屏障，并从当前权威媒体偏移重开；手持端发送非阻塞
close 请求、等待候选 native termination 正常完成、清空旧 AV1 队列后才遍历同次 playurl 中的 H.264。两条链每个
session 最多触发一次，后续候选集合只保留 codec 7，禁止 AV1/H.264 往返振荡；关闭超时/异常继续 fail-closed。

投影仪现有 GUI 的播放状态新增“请求 Q → 实际 Q、codec、actual backend、降级原因”，手持界面复用已有
`statusText` 显示同一组信息。稳定原因区分无 AV1 流、AV1 硬解不可用、AV1 profile/config 不兼容、普通首帧失败、
持续低 FPS、持续增长音画差和无 H.264 后备；高级日志每次 session 只记录一次性能决策，不逐帧刷屏。

阶段 45 隔离全量构建目录 `/private/tmp/ncpb-build-phase45-20260813` 为 `BUILD SUCCESSFUL`：209 suites、
Java 817/817、0 failure/error/skipped；生产 JAR、embedded native、法律材料和 conflict-copy 门槛全部通过，
`git diff --check` 通过。新增/扩展的策略回归覆盖五秒和 80% 精确边界、暂停排除、p95/丢帧、绝对音画差、
无 H.264、session lock、候选只保留 H.264 与 UI reason 分类。真实硬件的五秒测量数据仍属于设备矩阵门槛，不能用
纯 Java 时间推进测试替代。

## 第 46 阶段：真实 AV1 delayed-output EOF drain

`tools/native_av1_smoke/VideoJni` 独立调用器现在同时接受冻结的 Base64 fMP4、原始 fMP4 和 IVF，统一校验实际
backend、逐 packet send/receive、显式 EOF、EOF 后完整帧数、严格递增输出 PTS，以及随后 seek/reset flush 不得
暴露旧帧。默认 v39/libdav1d 对冻结 125 包片段仍为 `125 + 0 = 125`，并额外用六个 FFmpeg FATE AV1 IVF 小样本
确认解析与 EOF 不丢不重；这些低延迟样本都不能单独证明 delayed tail。

为让该边界可重复而不把测试配置混入发布 bundle，新增
`tools/native_av1_smoke/force-dav1d-delay.patch`：它只在主 v39 patch 之后、独立 smoke 构建上识别内部
`dav1d-delay`，固定 8 threads/`max_frame_delay=16`，明确禁止用于 release。overlay 对已应用主补丁的精确
`video_jni.c` dry-run 与真实 apply 均通过；重新编译 JNI 后，对仓库冻结的真实 B站 AV1 首 fragment 得到：

```text
requested=dav1d-delay actual=cpu(libdav1d) packets=125
framesBeforeEofDrain=118 framesDrainedAtEof=7 framesAfterEofDrain=125
firstPts=0 lastPts=4960000000
```

smoke 最终帧数断言为 125，PTS 严格递增，EOF 后 `flush()` 再 receive 返回 0。随后用未加 overlay 的正式 v39
本地产物重跑 `none` 路径仍为 `125 + 0 = 125`，说明测试开关没有改变 release patch，也没有把 reset 当作 EOF。
因此“真实 decoder 确有 pending frame 时，JNI EOF marker 能排空而不丢不重”的单平台功能证据已闭合；六平台
v39 归档整体替换、硬件 AV1 输出重排和设备矩阵仍是独立发布门槛。

阶段 46 隔离目录 `/private/tmp/ncpb-build-phase46-20260813` 的完整 `build` 成功；随后以 `--rerun-tasks`
强制执行全部测试，209 suites、Java 817/817、0 failure/error/skipped。standalone smoke 重新 `javac` 成功，
delay overlay 对精确 patched source 的 dry-run 成功，`git diff --check` 通过。

## 第 47 阶段：中控台 revision 冲突权威文档回包

`ControlConsoleConfigResultPacket` 的 `CONFLICT` 不再只返回 revision/status，而是携带与 revision 精确匹配的完整
`ControlConsoleDocument`。场景快照保存和 ACL 保存共用该契约；其他结果保持无文档的紧凑形态。新增有界文档 codec，
覆盖 schema/稳定 consoleId/owner/ACL/trusted/source kind 与坐标/hardRange/全部元素，解码继续限制 trusted/element
数量、字符串长度、重复 UUID、场景 64 KiB 预算和额外 8 KiB 文档元数据预算。纯核心
`ControlConsoleConflictAuthority` 强制冲突文档非空且 revision 一致，防止服务端或畸形回包制造混合版本。

编辑器收到冲突后仍保留本地草稿，但把回包快照固定为“重新加载服务器版”的来源，不再依赖本地方块实体更新包是否
已经先到。显式重新加载和无本地冲突的远端 revision 推进统一原子安装权威草稿、ACL、元素列表和 autosave
fingerprint，避免加载后误触发一次无意义保存。服务端同时修正锁定元素修改被策略拒绝后仍误记 operationId 并返回
`APPLIED` 的旧分支，现在稳定返回 `REJECTED` 且不推进 revision。

阶段 47 隔离目录 `/private/tmp/ncpb-build-phase47-20260813` 以 `--rerun-tasks` 完整构建成功：210 suites、
Java 819/819、0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，
`git diff --check` 通过。

## 第 48 阶段：B站 API 与扫码登录逐请求取消

新增 `CancellableHttpRequestScope` 统一持有一次 API/UI 生命周期内真正的 `HttpClient.sendAsync` 根 future。
关闭 scope 会原子禁止迟到请求并取消全部在途 future；同步调用不再使用不可观测的 `HttpClient.send`，而是等待同一
根 future，worker 中断时先取消 exact request、恢复线程中断位，再把 `InterruptedException` 交还上层。请求级
`HttpRequestCloseDiagnostics` 继续记录 started、headers、body published、cancel、terminal、字节数和耗时；active
entry 只在 terminal 已写入后移除，因此 `active=0` 现在也是诊断已经收敛的线性化完成点。

`BiliApiClient` 的短链、视频信息、音频/视频 playurl、JSON/文本请求，WBI nav key 和直播解析接口均使用这一契约。
现代唱片机 resolve/音频 prepare 已有的 `CancellableTaskFuture` 会把 session cancellation 变成 worker interrupt；本阶段
进一步把 MP4/Pad 手持视频 resolve、NetMusic B站歌曲 resolver 和直播机状态探测迁移到同一可取消 worker。直播机
超时、停止、方块移除和 NBT 重载会取消 exact probe，而不是只让迟到结果失效；完成回调固定回到服务器线程。

`BiliLoginManager` 现在拥有 generate、single-flight poll 和二维码图片请求的共同 scope。普通扫码界面关闭、MP4
遮罩关闭或重新打开都会关闭旧 manager；回调同时校验 UI generation，取消后既不复活纹理也不把预期取消记录为网络
错误。二维码图片改用受 scope 管理的 byte-array body，解码阶段不再持有无法取消的 HTTP InputStream。

纯 Java 慢速本地 HTTP server 回归覆盖 headers 前 scope close、关闭后迟到准入、同步 worker interrupt、terminal
诊断先于 active 归零、扫码 generate 取消、poll single-flight 与二维码图片同时取消。阶段 48 隔离目录
`/private/tmp/ncpb-build-phase48-20260813` 以 `--rerun-tasks` 完整构建成功：212 suites、Java 825/825、
0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，`git diff --check` 通过。

## 第 49 阶段：OpenAL 单项删除失败诊断

OpenAL native delete batch 不再把每个 source/buffer 的异常静默吞掉后只报告“batch 已返回”。每次非零句柄删除前
清理陈旧 AL error，删除后读取 exact `alGetError()`；Java/LWJGL 异常和非 `AL_NO_ERROR` 均计为该句柄失败，后续
句柄仍继续 best-effort 删除。batch 完成时一次性提交失败 source 数、失败 buffer 数和是否为失败 batch，并输出一条
聚合 warning，避免逐句柄刷屏。

`AudioNativeCloseDiagnostics.Snapshot` 与 `/ncpbc`/低频内存状态文本新增 `failedBatches`、
`sourceDeleteFailures` 和 `bufferDeleteFailures`。计数按 operation 幂等、负值归零并按本批实际请求数量封顶；正常 batch
不污染失败数。删除循环返回仍只表示 OpenAL API 调用已经结束，不宣称驱动物理内存一定同步回落。

阶段 49 隔离目录 `/private/tmp/ncpb-build-phase49-20260813` 以 `--rerun-tasks` 完整构建成功：212 suites、
Java 826/826、0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，
`git diff --check` 通过。

## 第 50 阶段：中控台专属视频状态美术

中控台为 IDLE/BUFFERING/ERROR 保留三张独立资源路径：`control_console_video/idle.png`、
`buffering.png` 和 `error.png`。本阶段最初引入的青色电路面板美术后来确认与项目公共提示图不一致；Phase 75
已用 `tools/generate_loading_ui_preview.py` 重新生成三张 320×180、完全不透明的资源，并让它们分别与公共
idle/loading phase3/network error 使用同一绘制函数和完全相同的输出字节。PNG 不再手工编辑，也不再依赖
独立图像生成流程。

`ControlConsoleVideoArtwork` 冻结状态到资源路径的纯 Java 映射。IDLE、BUFFERING、ERROR 固定使用各自专属静态
纹理；ACTIVE 显式拒绝静态占位并继续显示真实共享视频帧，因此“四态呈现”没有用伪造图片覆盖活动媒体。普通投影仪、
全息眼镜与 Iris 兼容占位纹理不受影响。自动回归校验三张 PNG 均可解码、尺寸精确为 320×180、完全不透明、有视觉
内容且 SHA-256 互不相同，同时校验 ACTIVE 映射 fail-fast；公共图逐对字节一致性由 Phase 75 的生成与校验约束保证。

阶段 50 隔离目录 `/private/tmp/ncpb-build-phase50-20260813` 以 `--rerun-tasks` 完整构建成功：214 suites、
Java 828/828、0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，
`git diff --check` 通过。

## 第 51 阶段：纯音频时长探测逐请求取消

`AudioDurationProbe` 不再把阻塞 `HttpClient.send` 放到不可取消的 common-pool 任务中。每次探测现在由专用 daemon
executor 上的 `CancellableTaskFuture` 持有，并使用 `CancellableHttpTransport` 记录和取消真正的根
`sendAsync` future。根请求在响应头前取消时会中断 exact worker/request；响应头已经发布后，探测 owner 会原子取得
当前 `InputStream` 并在独立关闭 executor 上关闭，避免 UI 线程等待网络流 close。redirect 的旧 body 会先释放再发起
下一请求，迟到 body 在 owner 已取消时也会立即被接管关闭。

`CancellableTaskFuture` 新增幂等 cancellation action，保证关闭响应体先于 worker interrupt 发出，而且多次
`cancel`/`cancelWorker` 只执行一次资源取消。`WhitelistPreviewScreen` 持有 exact 探测 task：切换条目、确认删除、
关闭 Screen 或 Minecraft 直接移除 Screen 都会取消底层请求；回调必须同时匹配 task identity、preview id 和 probe key，
因此旧请求即使迟到也不能覆盖新条目的时长。

自动回归覆盖 128 KiB Range 探测时长估算、redirect body 顺序收敛、响应头前根请求取消、响应体读取中关闭，以及
cancellation action 的重复取消幂等性。阶段 51 隔离目录
`/private/tmp/ncpb-build-phase51-20260813-a` 以 `--rerun-tasks` 完整构建成功：215 suites、Java 833/833、
0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，`git diff --check` 通过。

## 第 52 阶段：直播房间元数据字幕

直播解析新增 Bilibili `Room/get_info` 房间快照：短房间号经 `room_init` 归一化后，标题、父分区、分区和开播时间
写入最多 128 项、5 分钟 TTL 的有界缓存；房间信息请求失败只降级为空元数据，不阻断既有直播流选择。实时字段解析
覆盖当前生产接口的 `room_id/title/parent_area_name/area_name/live_time`，字符串统一裁剪并限制长度。

`LiveRoomMetadataRegistry` 以 source 坐标和强类型 `PlaybackSessionId` 持有每个直播源唯一快照。音频 handler 首次解析和
断线重连会更新同一 session；FLV worker、`LiveStreamerSound`、世界卸载和客户端总清理都会按 exact session 移除。
旧 session 的迟到 cleanup 不能删除替换 session 的元数据，多个字幕元素只读取同一份 source/session 快照，不会各自
请求 API。

字幕文档/packet/NBT 的既有 `contentMode` 字段新增 `LIVE_TITLE`、`LIVE_ROOM` 和 `LIVE_STATUS` 三个合法值。编辑器模式
按钮可依次选择直播标题、房间号+分区和当前状态；世界 renderer 在元数据尚未到达时稳定显示房间号及“直播中/
等待开播/已停止”回退文本，继续复用既有双面排版、颜色、背景、换行、对齐和 250 ms 范围淡变。

阶段 52 隔离目录 `/private/tmp/ncpb-build-phase52-20260813-a` 以 `--rerun-tasks` 完整构建成功：217 suites、
Java 838/838、0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，
`git diff --check` 通过。

## 第 53 阶段：中控台完整仿射编辑与投影物理替换准入

中控台文档升级为 schema v6。屏幕与字幕元素新增三轴非均匀 scale、三轴 pivot 和二维 skew，旧 schema
以恒等高级变换迁移；字段已经贯通 NBT、配置 packet、冲突权威文档、字段域、快照预算和变换后四角
hardRange 校验。世界 renderer、PIP、灵魂漫游、安全 AABB 与矩形拾取统一使用
`T(position) · T(pivot) · R · H(skew) · S · T(-pivot)`，音源继续只消费位置和声学参数。

编辑器 Gizmo 可切换本地/世界坐标。世界旋转使用四元数前乘，世界非均匀缩放对变换后的平面基执行 QR
分解；分解结果无法落回 schema 的 scale/skew 域时拒绝该步，不用显示轴替换近似真实世界变换。右栏为高级
变换与类型内容分页。编辑历史改为完整不可变场景快照：连续拖动合并为一步，数值、内容、锁定、添加、复制、
删除、预设、中控台名称/hardRange 和漫游返回均进入同一 128 步命令栈。

投影实例替换新增 owner/session 双域准入。逻辑 registry 移除前会保留稳定的 close-returned、native termination、
decode exit 与 render release 四信号；同 owner 的新 session 或同 session 的新 owner 都必须等待四信号正常完成。
超时、取消或 exceptional completion 永久 fail-closed，迟到完成不能复活旧 intent；发布/启动前再次原子校验两个
域的 epoch，并在发布前安装新实例出生即 pending 的 handoff，关闭 stop-during-open 与确定性 session texture ID
复用窗口。

阶段 53 当前隔离目录 `/private/tmp/ncpb-build-phase53-20260813-c` 完整 `clean build` 成功：218 suites、
Java 846/846、0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，
`git diff --check` 通过。

## 第 54 阶段：B站 AI CC 共享会话与安全降级

B站字幕选择新增 `HUMAN_ONLY / HUMAN_OR_AI / AI_ONLY` 三种明确策略，并同时识别 `ai-` 语言标记与 API 的
`ai_status/ai_type` 字段。中控台 `AI_SUBTITLE` 只读取 AI CC，不会在同一模式中静默选择人工轨；旧普通歌词路径
继续优先人工字幕。

客户端新增按绑定源和 `PlaybackSessionId` 键控的共享 AI 字幕 registry。中控台字幕和既有歌词投影仪复用同一
异步请求，最后一个消费者退出、区块卸载或客户端总清理会取消 exact worker/root HTTP future；回调同时校验
session entry 与 task identity，旧会话迟到成功或失败不能覆盖替换会话。加载、就绪、不可用和失败保持独立状态，
并进入 `/ncpbc video status` 的纯标量诊断。

显示层使用与音视频相同的权威 media tick 选取 AI 主轨/翻译轨。AI 轨加载中、不可用或失败时继续显示既有人工
歌词，人工轨也为空时才使用元素固定文本；直播源不会伪造点播 AI CC。核心测试覆盖共享单飞、最后消费者取消、
session 换代、迟到拒绝、不可用/失败区分、时间点选行、翻译开关和两级降级。

阶段 54 隔离目录 `/private/tmp/ncpb-build-phase54-20260813-a` 完整 `clean build` 成功：220 suites、
Java 856/856、0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，
`git diff --check` 通过。

## 第 55 阶段：地形材质远景、冻结 tint、方块实体状态与透明重排

terrain MID/FAR 已从分类线框升级为 4³/8³ 代表材质外露面。每个单元按出现频率、最高 Y 和输入顺序确定性选择
代表 `BlockState`，使用 particle/still 纹理、冻结 tint、packed light 和方向明暗生成简化 cuboid；完全处于 NEAR
核心外的 section 才进入聚合，核心交界继续使用细节路径，避免近远景重叠。UNKNOWN 仍为线框，覆盖过程不强载 chunk。

客户端 tick 主线程会枚举并冻结可见方块已注册的 tint layer，后台 compiler 只读取不可变整数，不回调活动
`ClientLevel` 或模组颜色逻辑。核心范围内最多 128 个方块实体由原 renderer 在主线程提取
`BlockEntityRenderState`，PIP 只消费这些状态；单个不兼容 renderer 异常只跳过对应实体。透明网格保留
`SortState` 和辅助索引空间，PIP 相机矩阵变化时只重排可见 section 的 quad index，不重新捕获世界或重建顶点。

纯 Java 回归新增代表材质决胜/4³/8³ 边界、透明相机重排判定和 frame 防御性复制。阶段 55 最终隔离目录
`/private/tmp/ncpb-build-phase55-final-20260813` 完整 `clean build` 成功：222 suites、Java 861/861、
0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，`git diff --check` 通过。
这些证据证明实现和自动化边界，但不替代真实资源包、模组 tint/流体/方块实体以及连续 PIP 相机运动的视觉矩阵。

## 第 56 阶段：terrain PIP integrated-client 实机闭环

`ncpb.terrain-lod-roundtrip` 已从旧 CPU 快照/`overviewCells` 判定升级为真实 PIP 场景。integrated server 在 void
world 的空位放置 4³ 蓝色染色玻璃、8³ 草方块和核心箱子夹具，客户端等待精确同步后打开 bench-only PIP Screen；
场景验证 4³/8³ 材质快照、冻结 tint、方块实体 render state、真实 GPU 材质/透明上传，并在切换 PIP 相机后要求透明
quad resort 计数前进。前后视角各保存一张截图，退出前切换非 terrain PIP state，真实触发 GPU session release。

macOS 27、Apple M4、Java 25、Minecraft 26.1.2、NeoForge 26.1.2.76 的最终报告总体与场景均为 `PASSED`，
`client.environment.valid=true`。报告最大值为 4³ 单元 37、8³ 单元 5、冻结 tint 1、材质上传 6、透明上传 1、
透明重排 2、方块实体提交 218、渲染失败 0；两张截图 SHA-256 不同且已人工复核。结构化报告与 bundle 分别位于
`build/modBench/raw-results/default/client/summary.json` 和 `build/modBench/bundles/default/client/`。

阶段 56 隔离目录 `/private/tmp/ncpb-build-phase56-20260813-a` 完整 `clean build` 成功：223 suites、
Java 862/862、0 failure/error/skipped；生产 JAR、embedded native、法律材料与 conflict-copy 门槛通过，
`git diff --check` 通过。阶段 56 当时尚缺第三方资源包、自定义 tint/流体/方块实体模组和 Iris/shaderpack 组合矩阵；
该缺口随后由阶段 58 的固定组合补齐。

## 第 57 阶段：Scene Editor 多项目、Maven 与 JiJ 发布闭环

通用编辑器已拆为 `scene-editor-core` 与 `scene-editor-minecraft` 两个同仓 Gradle 子项目。core 仅依赖 Java/JOML，
包含相机、投影、拾取、Gizmo、不可变 `SceneDocument`、`EditorSession` 和命令栈；源扫描与解析图门槛禁止
Minecraft、NeoForge、LWJGL/OpenAL 和主模组业务依赖。公开/受保护 class、字段、构造器和方法 generic signature
已冻结为提交基线。两模块均发布 main/sources/Javadoc/POM/Gradle module metadata，独立嵌套 Gradle 示例只按 Maven
坐标消费 core，并实际完成相机、元素、拾取、撤销和 session close。

主模组 production JAR 只通过 JiJ 内嵌两库，外层不再重复 class，范围固定为
`[1.0.0-beta.1,2.0.0)`。实机发现 Minecraft adapter 不能沿用普通 `LIBRARY`：该层引用转换后的 Minecraft class，
否则客户端 application loader 会再次加载 `Minecraft` 并触发 loader-constraint violation；现已改为 `GAMELIBRARY`，
纯 core 保持 `LIBRARY`。修正后 macOS 27/Apple M4 integrated-client 的
`ncpb.scene-editor-library-smoke` 总体与场景均 `PASSED`、`client.environment.valid=true`，adapter 的窗口、GLFW 输入
和 core session 均真实执行。报告位于
`/private/tmp/ncpb-build-phase57-client-smoke-b/modBench/raw-results/default/client/summary.json`。

另建两个独立 JavaFML fixture 模组，二者都 JiJ 内嵌同一 core。静态门槛确认两个 nested JAR SHA-256 一致、坐标和
范围一致、外层无 core class；专用服务器同时加载两模组，FML 最终向两个宿主提供同一个
`SceneDocument.class` 身份和唯一资源 URL，随后自动 clean halt。ModDevGradle 2.0.141 对 project JiJ 的无效
`version {}` 闭包会污染 `Project.version`，现已全部移除并由生成后 descriptor 精确收窄，主模组版本保持
`0.6.7-beta`。中控台 schema v6 与全息眼镜 persistence schema v1 由宿主持有并经测试锁定，不从 editor artifact/API
版本派生。

阶段 57 最终隔离目录 `/private/tmp/ncpb-build-phase57-full` 完整 `clean build` 成功：228 suites、Java 867/867、
0 failure/error/skipped；production JAR、embedded native、法律材料、conflict-copy、core isolation/API、JiJ 和双宿主
静态去重门槛全部通过，`git diff --check` 通过。Maven publication 位于
`/private/tmp/ncpb-build-phase57-maven/phase8-maven-repository`；双宿主专用服务器日志位于
`/private/tmp/ncpb-build-phase57-jij-dedupe-b/phase8-jij-dedupe-run/logs/latest.log`。发布说明见
`docs/scene-editor-publishing.md`。

## 第 58 阶段：terrain 第三方资源包、模组与 Iris shaderpack 实机矩阵

第三方 terrain 验收已形成独立 opt-in 矩阵，不会把测试模组或二进制带入生产 JAR。固定清单
`tools/terrain_compat_matrix_assets.json` 记录 9 个外部资产的精确版本、文件名、Modrinth CDN URL、字节数和
SHA-512；`tools/fetch_terrain_compat_matrix_assets.py` 使用临时文件下载、逐块校验并原子发布。Gradle 仅在同时传入
`-PenableModBench=true -PenableTerrainCompatMatrix=true` 时把 7 个模组加入 `benchRuntimeMod`，校验全部资产，
并在隔离客户端目录写入资源包选择和 `config/iris.properties`。外部二进制不提交、不 JiJ、也不参与普通开发、测试或发布。

最终固定组合为 Iris 1.11.3、Sodium 0.9.1、Biomes O' Plenty 26.1.2.0.22、GlitchCore 26.1.2.0.2、
TerraBlender 26.1.2.0.3、Colossal Chests 1.8.16-267、CyclopsCore 1.30.2、Accurate Textures 26.1.2
和 MakeUp Ultra Fast 9.5c。最初选择的 Faithful 32x 26.1 包虽然在平台元数据中标记 26.1.x，但其
`pack.mcmeta` 只有 `pack_format=84`，缺少 26.1.2 强制的 `min_format/max_format`，客户端真实拒绝加载；矩阵因此
改用带 `[69]..[84]` 范围且 Modrinth 官方 SHA-512 可核对的 Accurate Textures，而不是绕过兼容检查。

`ncpb.terrain-lod-roundtrip` 在矩阵模式下会要求全部 7 个 mod id 已加载，以 Accurate Textures 提供的 32×32
`grass_block_top` 成为权威资源，且 Iris 公共 API 报告 shaderpack 正在使用。integrated server 放置 BOP
`palm_leaves`、BOP `blood` 流体盆、Colossal Chests `uncolossal_chest` 方块实体及原版透明玻璃；客户端必须在不可变
terrain frame 中观察到 BOP tint、非空 BOP fluid state 和 Colossal Chests render state，并同时观察真实材质/透明
GPU 上传、方块实体提交、PIP 相机变化透明重排及零渲染失败后才通过。

2026-08-13 在 macOS 27、Apple M4、Java 25.0.2、Minecraft 26.1.2、NeoForge 26.1.2.76 上，最终报告总体和场景
均为 `PASSED`。外部 tint/fluid/block entity 最大值均为 1，资源包与 shaderpack active 全程为 1；材质上传最大 6、
透明上传 2、透明重排 4、方块实体提交 69、渲染失败 0。Iris 日志明确记录
`Using shaderpack: MakeUp-UltraFast-9.5c.zip`，资源管理器明确按
`file/Accurate_textures_26.1.2.zip` 重载。两张 PIP 截图 SHA-256 分别为
`36de1c85bbe28eff48b5a25a9a4edb176f0434f2360e74047931401d235fc0aa` 和
`9d8ed3ed51be51747d405a77a5810b160215424c7f24156e958f218ea158f8f3`，已人工复核视角变化、第三方叶片、流体盆、
方块实体和范围框可见。报告位于
`/private/tmp/ncpb-build-terrain-compat/modBench/raw-results/default/client/summary.json`。

复现命令：

```bash
python3 tools/fetch_terrain_compat_matrix_assets.py \
  --output-directory /private/tmp/ncpb-terrain-compat-assets
bash gradlew runBenchClient verifyBenchClientReport \
  -PenableModBench=true \
  -PenableTerrainCompatMatrix=true \
  -PncpbTerrainCompatAssetDirectory=/private/tmp/ncpb-terrain-compat-assets \
  -PncpbBuildDirectory=/private/tmp/ncpb-build-terrain-compat \
  -PmodBench.scenarios=ncpb.terrain-lod-roundtrip \
  --no-daemon
```

这证明指定 Apple GPU 组合下的 terrain PIP 第三方兼容矩阵，不外推为所有资源包、所有模组或其他 GPU/操作系统；
本阶段当时保留的多人、断线重连、客户端退出以及真实媒体有负载 Iris 门槛，已由后续阶段 66 关闭。最终普通生产构建在隔离目录
`/private/tmp/ncpb-build-terrain-compat-full` 完成 `clean build` 及最终 `build` 复跑：228 suites、Java 867/867、
0 failure/error/skipped；production JAR、embedded native、法律材料、Scene Editor API/JiJ、双宿主去重和
conflict-copy 门槛均通过，`git diff --check` 通过。

## 权威验证产物

最新 integrated-client 报告使用隔离构建目录：

- `/tmp/ncpb-build-phase37-full/modBench/raw-results/default/client/summary.json`
- `/tmp/ncpb-build-phase37-full/modBench/raw-results/default/client/report.md`
- `/tmp/ncpb-build-phase37-full/modBench/runs/default/client/logs/latest.log`
- `/tmp/ncpb-build-phase37-full/modBench/bundles/default/client/`

最新 phase37 报告启用真实 MP3 opt-in，12/12 场景全部 PASSED：

- `ncpb.console-consumer-lifecycle`
- `ncpb.editor-gui-lifecycle`
- `ncpb.terrain-lod-roundtrip`
- `ncpb.media-resource-convergence`
- `ncpb.deterministic-video-upload`
- `ncpb.playback-session-races`
- `ncpb.turntable-block-interactions`
- `ncpb.cross-dimension-media-cleanup`
- `ncpb.real-mp3-seek`
- `ncpb.real-mp3-sound-engine`
- `ncpb.real-mp3-retained-retry`
- `ncpb.real-turntable-mp3-end-to-end`

其中新端到端场景观察到服务端权威会话、客户端 `ModernTurntableSound`、一条真实 streaming channel、非静音
PCM 和一次 exact eject cleanup；跨维度场景仍观察到两次 client clone、主世界与下界各一次 unload、至少一次
真实加载 UI，以及两个独立 media session 各一次 exact discard。报告环境保持 valid，总状态 PASSED。

真实 BV 报告曾在启用 real-bench feature 后注册 7 个场景并只执行 `ncpb.real-bv-playback`；其 PASSED 结果、
解码/上传指标与资源归零结论仍见上一节。运行新场景未重复消耗网络做 BV 解码。

## 已执行并通过的验证

默认回归：

```bash
sh ./gradlew build compileBenchJava verifyBenchClientReport \
  -PenableModBench=true \
  -PncpbBuildDirectory=/tmp/ncpb-build-phase37-full \
  --stacktrace
python3 -m unittest tools/test_pad_cache_tools.py
git diff --check
```

结果：

- Gradle：BUILD SUCCESSFUL；
- Java HTML 报告：721 passed，0 failed，0 skipped；
- Python：5 passed；
- ModBench 源集：编译成功；
- diff whitespace：通过。

阶段 40 最新回归使用隔离目录 `/private/tmp/ncpb-build-phase40-final2-20260813`：

```bash
sh ./gradlew test \
  -PncpbBuildDirectory=/private/tmp/ncpb-build-phase40-final2-20260813 \
  --stacktrace
```

结果：791 passed，0 failed，0 skipped。最终 `build`、Bench、Python 与 diff 检查见本文件顶部最新基线；
phase37 的 12/12 integrated-client 报告未因本阶段重复消耗公网媒体重跑。

可单独执行的 BenchMod 源集编译：

```bash
sh ./gradlew compileBenchJava \
  -PenableModBench=true \
  -PncpbBuildDirectory=/tmp/ncpb-build-phase35 \
  --stacktrace
```

结果：BUILD SUCCESSFUL。

播放换代竞态场景：

```bash
sh ./gradlew runBenchClient \
  -PenableModBench=true \
  -PmodBench.scenarios=ncpb.playback-session-races \
  --stacktrace
```

结果：`ncpb.playback-session-races` PASSED，报告总状态 PASSED。

真实 MP3 seek/OpenAL 专用运行参数：

```bash
sh ./gradlew runBenchClient \
  -PenableModBench=true \
  -PmodBench.scenarios=ncpb.real-mp3-seek \
  -PncpbRealMp3Bench=true \
  --stacktrace
```

结果：连续两次 PASSED；启用该 opt-in 后的最新默认 integrated-client 全量运行也为 10/10 PASSED。

真实 MP3 SoundEngine channel 专用运行参数：

```bash
sh ./gradlew runBenchClient \
  -PenableModBench=true \
  -PmodBench.scenarios=ncpb.real-mp3-sound-engine \
  -PncpbRealMp3Bench=true \
  --stacktrace
```

结果：两段 channel 创建、换代、销毁和资源收敛均通过；最新默认 integrated-client 全量运行为 10/10 PASSED。

真实 MP3 retained-session retry 专用运行参数：

```bash
sh ./gradlew runBenchClient \
  -PenableModBench=true \
  -PmodBench.scenarios=ncpb.real-mp3-retained-retry \
  -PncpbRealMp3Bench=true \
  --stacktrace
```

结果：同-session 两次真实 transport prepare、两条 SoundEngine channel、旧 retry 零 dispatch、sound exact
replacement 和最终资源收敛均通过；最新默认 integrated-client 全量运行为 10/10 PASSED。

Bench 报告验证：

```bash
sh ./gradlew verifyBenchClientReport -PenableModBench=true
```

结果：BUILD SUCCESSFUL。

真实 BV 专用运行参数：

```bash
sh ./gradlew runBenchClient \
  -PenableModBench=true \
  -PmodBench.scenarios=ncpb.real-bv-playback \
  -PncpbVideoAdvancedFeatures=true \
  -PncpbVideoBenchFeatures=true \
  -PncpbRealBench=true \
  -PncpbRealBenchManaged=true \
  -PncpbBenchVideo=BV1GJ411x7h7 \
  -PncpbRealBenchFrames=12 \
  -PncpbRealBenchWarmupFrames=2 \
  -PncpbRealBenchQualities=16 \
  -PncpbRealBenchOutputFormat=nv12 \
  -PncpbRealBenchPreview=false \
  -PncpbRealBenchRealtime=false \
  -PncpbNativeHwaccel=auto \
  --stacktrace
```

## 当前工作区注意事项

- NetMusicCanPlayBili 当前发布基线已提交并推送为 `fb6c4f8`；开始新修改前仍应先检查 `git status`，不要覆盖
  用户随后产生的本地改动。
- `build/` 下的 ModBench 报告是构建产物，通常不纳入 Git。
- 当前云同步会持续向仓库内 `build/classes` 恢复带空格的冲突副本；本机验证应显式传
  `-PncpbBuildDirectory=/tmp/ncpb-build-phase40`。该属性未提供时仍使用标准 `build/`，CI/其他工作区不受影响。
- `src/bench` 在 VS Code 默认 Java language-server classpath 中会提示“不在项目 classpath”；这不是源码编译错误。
- `compileBenchJava`、真实 integrated client 和报告验证均已通过。
- BenchMod 仓库当前分支显示为 `main`；NetMusicCanPlayBili 为 `master`。
- NetMusic wrapper 在 shell 中使用 `sh ./gradlew`，因为执行位可能未设置。

## 下一步建议

### P1：`VideoBillboardPreview` 计划内局部拆分已完成

已完成 session instance、pending loading/failure、projector/GUI consumer、legacy session/request/projector、
decoder worker、GPU texture lifecycle 和 resource diagnostics 七个边界。公开 facade 保持不变；后续除非真实
集成验证暴露问题，不再继续扩大这一轮重构范围。

### P1：`MP4PlaybackSyncManager` 计划内局部拆分已完成

session registry、source discovery、queue controller、progress persistence 与 audience broadcaster 五个边界均已完成。
公开 facade、packet/NBT 兼容契约和默认行为保持不变；后续优先用真实竞态集成测试验证这些边界，不再继续扩大本轮 manager 拆分范围。

### P2：内部强类型身份

已完成：

- `MediaRequestToken`：registry、HTTP handler、launch/cancel 链内部均已强类型化，URL 仍序列化为字符串。
- `ResolveGeneration`：四条运行期 resolve/query 所有权链均已强类型化，持久化 generation 保持原格式。

运行时迁移已基本完成：

- `PlaybackSessionId`：`PlaybackSync`、客户端 session tracker、command/request 快照、视频 session
  所有权边界、客户端 active playback 与 prepare/retry 复合键，以及断链恢复、直播样本总线、矿车锚点和
  直播/现代唱片机视频决策缓存、视频锚点、共享/手持时间线、MP4 服务端 active session、权威设备状态、
  runtime progress、视频关闭诊断、共享音频 Sound 身份、HTTP active stream 控制、手持视频 key 与投影内部
  渲染键、客户端音频输出、手持播放状态、渲染状态、内部视频时间线契约，以及客户端 sound handle/registry/
  retry exact-match/pending ownership 与 dispatch 失败收敛、prepare/lyrics owner 链、replacement sound 主动摘除、
  迟到 factory admission 和 stop/world-unload sound 即时释放已迁移；剩余字符串主要集中在
  packet/NBT/URL fragment、bench 输出、公开控制台快照和其他兼容 facade，不应仅为强类型化而改变格式。
- `PlaybackSourceId`：MP4 resolve/session/progress、设备状态/位置与客户端播放/声音/prepare/retry/路由/
  视觉状态、状态镜像、手持视频和 renderer 缓存，以及 Pad 文档/启动/索引、holder/state-sync 复合键和
  耳机反向索引的内部设备键均已迁移；UUID 继续保留在 packet、SavedData、NBT 与公开兼容入口。
- 协议中立客户端 payload 已提供 typed session accessor；packet record 与 wire codec 继续保留字符串字段，
  仅在客户端同步/prepare 运行时边界展开为 `Optional<PlaybackSessionId>`。

先改内部 API，序列化仍保持字符串/UUID，避免 packet 兼容变化。

下一步不再优先扩大身份类型迁移。start/连续 seek、pause/resume 与 retained-session retry 的客户端所有权状态机
已由 integrated-client 换代矩阵覆盖；后续更值得投入的是真实 decoder/OpenAL 媒体链路验证。若继续处理兼容边界，
只新增 typed overload/accessor，并保持 packet/NBT/URL/公开快照序列化不变。

### P2：继续统一系统属性

逐步把直接调用以下 API 的配置迁移到 `NcpbSystemProperties`：

- `System.getProperty`
- `Integer.getInteger`
- `Long.getLong`
- `Boolean.getBoolean`

Pad 地图/渲染/诊断、CDN selector/fallback、视频 upload/NV12/YUV 及
`VideoPlaybackInstance` timing/offscreen 和 native fMP4 decoder/seek 参数已完成。
共享音频同步阈值与 fMP4 stream buffered-payload 上限也已完成。
异步关闭执行器、OpenAL native close 和视频 close diagnostics 属性也已完成。
内存跟踪、周期报告与客户端熔断属性也已完成。
HTTP/audio queue、直播样本总线与 stream recovery 属性也已完成。
shared/turntable/handheld timeline 属性也已完成。
advanced/bench feature flags 与 handheld video pipeline 属性也已完成。
客户端媒体准备线程和现代唱片机/MP4/Pad 准备超时属性也已完成。
播放 watchdog、OpenAL pacing 容差和漂移诊断属性也已完成。
视频投影边界与歌词投影滚动/渲染属性也已完成。
OpenAL HRTF 覆盖开关也已完成。
现代唱片机、直播和手持视频客户端运行属性也已完成。
Iris shaderpack YUV compatibility 动态属性也已完成。
`VideoBillboardPreview` 的世界锚点、可见性、YUV immediate 和渲染后端属性也已完成。
客户端显示层的中控台健康检查、全息眼镜屏幕和 MP4 投影输入属性也已完成。
Bili API 身份/偏好/直播退避属性，以及 real bench/managed/decoder override 和 bench NV12 RG8 共享读取也已完成。

当前直接读取扫描只剩 `os.name`、`os.arch`、`java.io.tmpdir` 这类 JVM 运行环境识别，以及 `NcpbSystemProperties` 边界自身；已无待迁移的 `ncpb.*` 业务配置读取。

### 后续真实媒体集成验证

第 28、29、30、31、32、33、34、35、36、37 阶段已完成：

- 真实 MP3 在 5 秒和 12 秒两个位置从文件头解码并做 PCM seek；
- 两段 decoder/OpenAL 输出换代、旧 stream/output 关闭和每个 handler exact cleanup；
- 首段 PCM 非静音、有限、无 clipping，以及 native delete/close、active output 和 staging memory 收敛。
- 两段真实 SoundEngine streaming channel 的创建、挂载、换代和销毁，以及 tapped stream/handler exact
  lifecycle 和资源基线收敛；
- 小块读取下首段 PCM 的 4096-sample 有界累积质量观测。
- 第二条真实 SoundEngine channel 的 pause→快速 resume；暂停期间自建 OpenAL source/可听位置冻结，
  恢复后沿原时间线继续，且 channel/stream/handler 不重建、最终 exact cleanup。
- retained-session transport failure 后登记 delayed retry，并在 timer 前由 authoritative 同-session refresh 清除
  exact owner；真实新 transport 完成第二次 prepare、MP3 decode、SoundEngine channel 和 OpenAL 输出换代，
  旧 retry 零 dispatch，全部资源最终回到基线。
- 第二条真实 SoundEngine/OpenAL channel 播放期间触发生产客户端 `LevelEvent.Unload` 清理；卸载前新登记的
  delayed retry 不会迟到 dispatch，playback/sound/prepare/retry/output/native/staging 资源全部收敛。
- 取出唱片/暂停/重播/结束/方块移除在服务端清空状态前发送 exact-session stop；迟到旧 stop 不影响新唱片，
  exact stop 会取消客户端异步资源并关闭真实 SoundEngine/OpenAL channel，最终资源回到基线。
- 静音与离开 96 格同步范围会停止客户端 exact session 并释放媒体资源；恢复音量或重新入场时沿服务端当前时间线
  重建，真实 SoundEngine/OpenAL cleanup 已自动化验证。
- 真实注册唱片机方块的客户端右键插入/取出 packet、再次插入，以及默认拒绝/`ALWAYS` commit 的事务自动提取
  已自动化；方块实体与 `HAS_DISC/PLAYING` blockstate 在双端最终一致。
- 集成服务器主世界→下界→主世界真实往返，客户端两次 respawn/clone、加载 UI 与两个旧世界 unload 均已观察；
  两次传送前登记的 playback/sound session 各 exact cleanup 一次，最终回到原维度且无媒体状态残留。
- 真实唱片机右键插入公网 MP3 后，服务端 resolve、权威 session、网络同步、客户端 coordinator、SoundEngine
  streaming channel 与 Stereo OpenAL 输出完整贯通；真实取出 packet 触发 exact-session stop，全部资源回到基线。

至此原定音频/唱片机真实媒体交互矩阵已闭合。AV1 已冻结真实 fMP4 字节并完成离线顺序 demux、SIDX range、
packet PTS、首帧失败资源归零和持续性能保护；最终发布路径已由阶段 71 收敛为 v48 硬件 AV1 + H.264
硬件/软件回退，六平台 hosted 构建/加载和通用 JAR 体积门槛已经通过。目标 GPU/驱动上的连续播放、seek、输出
重排和物理资源计数改为发布后兼容验证；没有设备时不阻断发布，但不能宣称已完成全平台硬解认证。

### 阶段 59：真实 AV1 启动失败→H.264 同会话回退

- 新增 `ncpb.real-av1-h264-fallback` integrated-client 场景：解析真实 B 站 AV1/H.264 playurl
  计划，保留 AV1 的 codec/画质/尺寸/帧率/硬解模式，仅将其 transport 指向本机拒绝连接端口，
  从而不依赖当前 GPU 是否支持 AV1，但仍会真实创建 codec 13 native context；
- codec 13 启动失败后，生产 `VideoPlaybackInstance` 候选循环先等待其 native termination，再使用同一
  playback session 打开真实 codec 7/VideoToolbox H.264；首帧观测媒体时间约 6.92 秒，未回到 0；
- 候选尚未提交首帧时不再进入离屏解码暂停；否则 provisional AV1 probe frame 可占住 exact ticket，
  使 worker 停在 packet drain 且无法进入下一候选；
- 场景最终验证 session 停止、`VideoCloseDiagnostics`/HTTP active operation、所有自有内存分类、
  RGBA/YUV texture、staging/PBO 以及 native FFmpeg/D3D11 当前值回到场景前基线。

实机命令：

```bash
bash gradlew runBenchClient \
  -PenableModBench=true -PncpbLocalModBench=true \
  -Pmodbench_version=0.1.3-local.1 \
  -PmodBench.scenarios=ncpb.real-av1-h264-fallback \
  -PncpbVideoAdvancedFeatures=true -PncpbVideoBenchFeatures=true \
  -PncpbRealBench=true -PncpbRealBenchManaged=true \
  -PncpbRealMediaLifecycleQuality=16 -PncpbNativeHwaccel=videotoolbox \
  -Dorg.gradle.jvmargs=-Xmx6G --no-daemon
```

结果：`PASSED`，MEASURE 42 ticks / 2.10 s；后续 35 个视频渲染/native 定向 suite 共 172 tests，
0 failures / 0 errors / 0 skipped。这证明当前 macOS ARM64 上的生产回退与资源收敛，不替代六平台
v39 动态加载和目标 GPU/驱动矩阵。

### 阶段 60：v39 Release 资产原子导入门槛

新增 `tools/prepare_native_bundle.py`，输入必须是同一 `media-min-v39` Release 下载的完整资产目录，
输出是一个新的、已验证但尚未覆盖项目资源的 `native/` staging tree。它在写入前会同时验证：

- Release `SHA256SUMS.txt` 的行格式、精确资产集和每个归档/对应源码/补丁/provenance 的 SHA-256；
- `BUILD-PROVENANCE.txt` 中的 tag、冻结的 FFmpeg source commit `669aa5300a4b6ca91dd60632856c6dca1b63de70`、
  upstream base `1f276a42dbd693ef58222e2c1499d45691b49089`、runtime `git-2026-08-13-669aa53`、
  dav1d 1.5.4/固定 commit，以及 Linux ARM64/x86_64 各自的 GLIBC symbol floor；
- 六个平台 archive 不含绝对路径、`..`、symlink/hardlink、重复或跨平台成员；
- 六个平台 archive 内每个非文本成员的真实二进制身份：Linux 必须是目标 `e_machine`
  的 ELF64 little-endian `ET_DYN`，macOS 必须是目标 `cputype` 的 thin 64-bit `MH_DYLIB`，
  Windows 必须是目标 PE Machine 的 PE32+ DLL；ARM/x86 归档映射写反会在导入前失败；
- Linux/macOS 每平台精确 7 文件、Windows 每平台精确 9 文件，且每套同时有
  `FFmpeg-LGPL-2.1.txt` 与 `Dav1d-BSD-2-Clause.txt`；
- FFmpeg/dav1d 对应源码 tarball 必须可解析、路径局限在声明根目录，并包含非空的
  LGPL/dav1d 许可证、构建工作流、JNI 源码和两个 decoder 的关键源文件；provenance 重复键
  与任何空 release 资产均 fail closed；
- 全部通过后才生成已提取文件的 `SHA256SUMS` 和带 archive hash/source provenance 的 `README.md`，
  并用同目录 rename 发布 staging tree；任一错误都不会留下半套输出。

使用方式：

```bash
python3 tools/prepare_native_bundle.py \
  --assets-dir /path/to/media-min-v39-release-assets \
  --output-root /tmp/ncpb-media-min-v39-native \
  --release-tag media-min-v39
```

`tools/test_prepare_native_bundle.py` 覆盖正常 staging、Release hash 漂移、archive 多余文件、symlink、
已存在输出目录、runtime provenance 缺失/重复、对应源码不完整、二进制格式错误，以及 ELF/Mach-O/PE
目标架构错配拒绝，当前 11/11 PASSED。该工具只准备新 tree，不会自动覆盖
`src/main/resources/native`；真正替换仍必须在六平台 Release 全部通过后审阅 diff，再同步翻转 Java
bundled capability、Gradle/release 门槛和下载页能力说明。

### 阶段 61：v38/v39 发布身份与 runtime schema 门槛

将主项目原先分散写死的 `media-min-v38` 判断收敛为由 `native/README.md` 驱动的两套精确 schema：

- v38 仍必须是每平台 FFmpeg 许可证 + 原 17 个 Video JNI 导出，且 runtime version 必须为
  `git-2026-08-10-3dbd669`；
- v39 必须在六个平台同时增加 `Dav1d-BSD-2-Clause.txt`，必须声明精确 runtime version，
  并将 `VideoJni_sendEndOfStream` 作为第 18 个必需导出；
- Gradle 精确文件集/JAR/法律材料检查、tag workflow 与六平台 runtime smoke 共用这一身份，
  不允许 v38 二进制、v39 许可证或 JNI 导出混合过关；
- GLIBC floor 不再对未生成的 v39 沿用推测值；FFmpeg Release 工作流从两份 Linux archive
  的 ELF version need 中求最高值，写入 `BUILD-PROVENANCE.txt`，导入工具再生成 README，
  Gradle 要求下载页声明与 README 完全一致。
- `BiliApiClient.BUNDLED_SOFTWARE_AV1_AVAILABLE` 不再是手工布尔值；
  `generateNativeBundleCapabilities` 从同一 release 身份生成 Java 常量，v38 必为 false、v39 必为 true，
  `NativeBundleCapabilitiesTest` 再与打包资源中的 README 交叉校验，避免二进制与候选策略反向漂移。

当前验证：Python importer 11/11、runtime metadata 5/5、六平台静态 architecture verifier 3/3，且现有 v38
34 个嵌入二进制全部通过格式/共享库类型/机器号审计；现有 v38 的
`verifyEmbeddedNativeBundle` + `verifyProductionJarLegalMaterials` 均为 `BUILD SUCCESSFUL`，本机 macOS ARM64
runtime smoke 精确通过 5 个 EAC3 + 17 个 Video 导出；生成式 capability + Bili 候选策略定向测试与
重跑的两项发布门槛也均为 `BUILD SUCCESSFUL`。随后使用独立构建目录执行未跳过子项目的
`bash gradlew build --stacktrace --no-daemon`，主项目、Scene Editor core/Minecraft、两个 JiJ host、JAR/native/法律门槛
全部通过：229 个 test suite、871 tests、0 failures / 0 errors / 0 skipped。FFmpeg v39 本地候选提交更新为
`669aa53`，尚未推送；六平台 v39 实产物仍是下一道未完成门槛。

### 阶段 62：macOS ARM64 真实 AV1 硬解与默认首帧预算 Range Seek

Gradle 现在将 `ncpbAv1FirstFrameProbeTimeoutMillis` 和 `ncpbAv1FirstFrameProbeMaxPackets`
精确传入普通 client、ModBench client 与 paired bench，默认值仍是 `2000ms/256 packets`。
不再需要用未接线的 Gradle 参数假装放宽预算。

新增 `ncpb.frozen-real-av1-hardware-seek` integrated-client 场景和本机 strict HTTP Range server。
server 直接发送仓库已冻结、SHA-256 锁定的真实 B 站 AV1 init/首 fragment/35 秒 fragment，
不依赖 CDN 时延，同时仍驱动生产 `Fmp4NativeVideoDecoder`、SIDX range、VideoToolbox、
generation restart 和渲染资源清理。场景强制断言：

- actual backend 是 `videotoolbox`，不把请求值当成硬解成功；
- 首次播放从 0 开始，然后精确请求 byte `900893` 并 seek 到 35 秒；
- generation 从 1 换代到 2，提交帧 PTS 单调，最终观测媒体时间 37.04 秒；
- 停止后 decoder/texture/native/HTTP/自有内存和 `VideoCloseDiagnostics`
  全部回到场景前基线，不允许异常 close phase 被当成正常收敛。

默认预算实机命令：

```bash
bash gradlew runBenchClient \
  -PenableModBench=true -PncpbLocalModBench=true \
  -Pmodbench_version=0.1.3-local.1 \
  -PmodBench.scenarios=ncpb.frozen-real-av1-hardware-seek \
  -PncpbVideoAdvancedFeatures=true -PncpbVideoBenchFeatures=true \
  -PncpbRealBench=true -PncpbRealBenchManaged=true \
  -PncpbRealMediaLifecycleQuality=16 -PncpbNativeHwaccel=videotoolbox \
  -Dorg.gradle.jvmargs=-Xmx6G --no-daemon
```

结果：`PASSED`，MEASURE 87 ticks / 4.35 s；VideoToolbox 两次首帧均在默认 2 秒内提交，
HTTP 观测到精确 35 秒 Range，所有资源诊断最终为基线。另一次真实 CDN 场景在显式
`10000ms` 诊断预算下也以 actual VideoToolbox、0→28 秒 seek、generation 1→2 通过；
它用于证明公网 transport 与硬解串联，不用来放宽生产默认首帧预算。

阶段 43 当时 standalone smoke 的“宿主不支持 AV1”是旧工具链下的单次负向观测；
当前生产路径已用 JNI 回传的 actual backend 证明同一 macOS ARM64 环境确实使用 VideoToolbox AV1。
因此迁移计划的单平台“真实 m4s 硬解连续播放/range seek”已关闭；六平台 v39 动态加载、
其余 GPU/驱动设备矩阵和硬解输出重排仍保持未完成。

### 阶段 63：关闭诊断 TOCTOU 收口与真实 AV1 三连跑

把冻结样本中首 fragment 与 35 秒 fragment 之间的稀疏零填充改成合法 MP4 `free` box 后，真实链路曾出现
`DECODE_THREAD_EXITED` 偶发 hard timeout；与此同时 decoder、native bytes、HTTP、纹理与自有内存均已回到零。
连续复跑证明它不是物理解码线程泄漏，而是 `VideoPlaybackInstance.stop()` 的诊断注册 TOCTOU：第一次
`isDone()` 为 false 时把 phase 加入 required set，future 恰好随后完成，第二次 `isDone()` 为 true 又跳过
`whenComplete`，于是一个已经退出的线程仍被标成永久 pending。

`VideoCloseDiagnostics.observe(...)` 现在统一用 `CompletableFuture.whenComplete` 观察已声明 phase；stop 只按最初
required 快照注册，不再二次检查。新增确定性测试先完成 future、再注册 observer，证明该窗口仍会立即收敛。
关闭诊断、物理 handoff 和 owner/session 双域 replacement gate 共 38 项定向测试通过。

修复前的冻结真实 AV1 场景在 `/private/tmp/ncpb-av1-close-repeat1-20260813` 稳定复现唯一 pending phase；
修复后分别在以下三个隔离目录连续运行相同默认 `2000ms/256 packets` 命令，全部 `PASSED`：

- `/private/tmp/ncpb-av1-close-fixed-repeat1-20260813`；
- `/private/tmp/ncpb-av1-close-fixed-repeat2-20260813`；
- `/private/tmp/ncpb-av1-close-fixed-repeat3-20260813`。

每轮都实际使用 `videotoolbox`、完成 generation 1→2 和 0→35 秒 byte-range seek，并在 teardown 前收敛所有
close phase；这关闭的是诊断误报竞态，不把三次本机运行外推为六平台设备矩阵。

### 阶段 64：macOS x86_64 v39 实构建、Rosetta 加载与软件 AV1 解码

在独立 FFmpeg v39 候选工作树 `/private/tmp/ncpb-ffmpeg-v39-ready` 的提交 `669aa53` 上，使用固定的
dav1d 1.5.4 提交 `54706fc6bc0cdecab7e9593974a4039cc038fca7` 和 Temurin 21 x86_64 JDK，完成了
macOS x86_64 的真实发布配置构建。dav1d 由显式 `darwin/x86_64` Meson cross file 构建为带 PIC 的静态库，
FFmpeg/JNI 五个 dylib 均为 thin x86_64、最低 macOS 11.0、使用 `@loader_path` 依赖且通过 ad-hoc
codesign；`libavcodec` 没有动态 dav1d 依赖。导出审计精确得到 5 个 EAC3 JNI 和 18 个 Video JNI，
其中包含 v39 新增的 `VideoJni_sendEndOfStream`。

Rosetta x86_64 原生加载器实际打开全部五个 dylib，并报告：

```text
x86_64 v39 loader smoke passed: ffmpeg=git-2026-08-13-669aa53 eac3=5 video=18
```

同一套二进制随后在真实 x86_64 JVM 中解码仓库冻结的 B 站 AV1 首 fragment；请求软件后端 `none`，JNI
返回实际后端 `cpu(libdav1d)`，125 个 sample packet 全部解出为 125 帧，PTS 从 0 到 4.96 秒严格递增，
EOF 后无陈旧帧。请求 `videotoolbox` 的 Rosetta 运行在首个 packet 明确返回“宿主不支持硬件 AV1”，因此只作为
该宿主组合的负向设备证据，不计入硬解成功矩阵。

结合阶段 44/46 的 macOS ARM64 v39 软件解码、delayed EOF drain，以及阶段 62/63 的生产
VideoToolbox/Range Seek 证据，当前已有两种 macOS 架构的 v39 实构建和软件 AV1 解码证据，以及 ARM64
生产硬解证据。Linux ARM64/x86_64 与 Windows ARM64/x86_64 的 v39 实产物仍未生成；不得把两个 macOS
目录局部导入或与当前 `media-min-v38` 混包。只有六平台 Release 同时通过后，才能用原子 importer 整体替换
`src/main/resources/native` 并翻转 bundled software AV1 capability。

### 阶段 65：v39 可重建补丁与六平台发布契约复核

复查发现主项目保存的 `native-patches/media-min-v39-dav1d-eof-drain.patch` 可以正向应用到 v38，
但不能从当前 `669aa53` 候选完整反向应用；差异是候选后续加入的 FFmpeg runtime version 和两套 Linux
GLIBC symbol floor provenance 未同步进补丁。补丁现已直接从 `3dbd669..669aa53` 的三个权威文件
`.github/workflows/build.yml`、`build_media.sh`、`video_jni.c` 重新生成，SHA-256 为
`603840a29cca70d74f30b169d68a1199247425740a07a8133db7300be721d175`。在全新 v38 archive 上正向
dry-run 三文件全部通过，在候选提交上 `git apply --check --reverse` 也通过，因此临时工作树不再是唯一可重建来源。

FFmpeg v39 与主项目 Release 两份 workflow 均通过 actionlint 1.7.12，0 errors。两套本地 macOS v39
安装输出又按远端 workflow 的实际 package 逻辑生成归档，并交给 `prepare_native_bundle.archive_payload`
验证；ARM64 与 x86_64 都精确包含两个许可证和五个 thin Mach-O dylib，没有额外文件或 symlink。
公开 GitHub API 的只读复核确认远端最新成功运行仍是 `media-min-v38` 第 68 次构建，尚无 v39 tag、run
或 Release；因此这里证明的是“候选与发布契约可执行”，不伪造六平台实产物完成状态。

当前主工作树随后在新的隔离构建目录重新执行 `build compileBenchJava`，主项目、Scene Editor core/Minecraft、
两个 JiJ host、JAR/native/法律材料门槛全部 `BUILD SUCCESSFUL`：229 个 suite、874 tests、0 failures、
0 errors、0 skipped。native importer/architecture/runtime 三组 Python 测试为 19/19 PASSED，Python compile
和 diff whitespace 检查也通过。

### 阶段 66：真实媒体 100 轮与物理双客户端最终门槛

控制台文档原先保留的三项系统级缺口现已在 Apple M4/macOS 27、Java 25、Minecraft 26.1.2、
NeoForge 26.1.2.76 上转化为四个正式 ModBench 场景并全部 `PASSED`。这里的“多客户端”不是同进程中的两个
逻辑对象：`runBenchPaired` 启动一个独立 dedicated server 和两个使用隔离 game directory 的独立客户端 JVM，
三者分别输出 `paired-server`、`remote-client-0`、`remote-client-1` 结构化报告。

`ncpb.real-media-lifecycle-100` 在一个真实 integrated client 内连续执行 100 轮。每轮同时使用真实 Bilibili
DASH H.264 视频、生产 native decoder、direct NV12/YUV texture/PBO、真实 Bilibili 音频和 Stereo OpenAL；
移除最后消费者后必须等待 HTTP active request、decoder/close diagnostics、frame/texture/PBO、OpenAL native delete
以及每类 `MemoryResourceTracker` 自有字节精确回到场景捕获基线，才允许开始下一轮。结构化报告在 725 个
MEASURE ticks / 36.250 秒后通过最终 `verify`：`completedRounds == 100`、状态 `READY`、所有资源等于基线，
Stereo OpenAL created/cleanup-started/cleanup-completed 各恰好增加 100。采样中的零基完成计数最大为 99；这是第
100 轮完成前最后一次 tick 采样，不是少跑一轮。观测峰值为 HTTP 2、视频帧 368,280 bytes、GPU PBO
368,280 bytes、首个可听 PCM 4,096 samples、自有资源 4,420,384 bytes，单轮收敛最大 54 ms。

三组 paired gate 分别证明：

- `ncpb.multi-client-consumer-lifecycle`：服务端正式 lease 和在线玩家严格经历 2→1→0，第一个客户端退出后
  幸存客户端继续持有自己的 lease；
- `ncpb.multi-client-reconnect`：客户端 0 计划断线后以相同 UUID 重连并重新取得正式 lease，客户端 1 的 lease
  在对端离线期间保持，服务端和两端客户端都各自验证稳定窗口；
- `ncpb.multi-client-real-media-lifecycle`：两端都加载真实 Bilibili 视频/音频、actual VideoToolbox H.264、
  direct NV12/YUV/PBO 和 Stereo OpenAL，并由运行时 Iris API 确认 MakeUp shaderpack 正在使用。客户端 0 先独立
  收敛并退出；客户端 1 观察到对端退出后仍保持媒体与 lease 60 ticks，再独立关闭并回到自己的捕获基线。
  两端结构化指标 `real_media_loaded` 与 `real_media_iris` 均恒为 1，owned bytes 都覆盖 4,420,384→0；服务端
  warmup 的玩家/lease 恒为 2，measure 覆盖 2→1→0。

复现输出分别保存在本机隔离目录：

- `/private/tmp/ncpb-real-media-lifecycle-100-20260813`；
- `/private/tmp/ncpb-paired-consumer-20260813`；
- `/private/tmp/ncpb-paired-reconnect-20260813`；
- `/private/tmp/ncpb-paired-real-media-iris-20260813`。

因此控制台验证文档中原先的“真实媒体 100 次资源基线”“多客户端独立引用”“断线重连/客户端退出”和
“真实媒体 Iris/shaderpack”缺口已关闭；此前的跨维度 integrated-client 场景也继续有效。结论严格限定在当前
Apple M4 + VideoToolbox + 固定 Iris/MakeUp/模组资产组合，不能外推为所有 OS、GPU、驱动或资源包，亦不能替代
AV1 六平台 v39 native bundle、硬解输出重排序和设备资源基线。

### 阶段 67：六平台 v39 真实 AV1 软件解码发布门槛

原 `native-runtime-smoke` 六平台 job 只证明目标 runner 能真实加载五/七个动态库、runtime version 正确且 JNI
导出集合完整；一个带正确文件名和符号、但 dav1d 解码接线损坏的 v39 bundle 仍可能通过。新增
`tools/verify_native_av1_smoke.py`，在同一六平台真实 runner 上读取 `native/README.md` 的发布身份：v38 因明确
不含软件 AV1 而输出可见 skip；v39 则必须使用 JDK 21 编译仓库外 source set 的独立 `VideoJni` 调用器，并对冻结
B站首段和 35 秒 seek 段各 125 包 fMP4，以 requested backend `none` 做真实 JNI 解码。门槛精确要求：

- actual backend 必须是 `cpu(libdav1d)`，不能用请求字符串或任意 CPU decoder 冒充；
- 两段各 125 packet 经显式 `sendEndOfStream` 后都必须得到 125 frame；
- PTS 必须严格递增且精确覆盖 0→4.96 秒和 35→39.96 秒；
- 首段 EOF 后 `flush` 再在同一 decoder 送入 seek 段，第二次 EOF/flush 也不得暴露陈旧帧；
- close 后 FFmpeg current bytes 与 D3D11 texture/surface/logical bytes 必须精确回到打开前基线。

主 Release workflow 的六个平台 matrix 已在 runtime load/export 步骤后接入该 gate，并按平台归档结构化 JSON；
脚本/测试也纳入 paths 与 build job。Python 现有 9 项测试覆盖 runner OS/arch 精确匹配、平台硬解名映射、v38 不启动
Java、v39 正确输出、非 dav1d/不完整输出拒绝和 JSON 证据内容；actionlint 1.7.12、Python compile、Java caller compile
和 `git diff --check` 均通过。

门槛还对两套真实本地 v39 产物执行，而非只依赖 mock：macOS ARM64 直接运行、macOS x86_64 使用 Temurin 21
x86 JVM 经 Rosetta 运行，二者都得到：

```text
real AV1 JNI smoke: requested=none actual=cpu(libdav1d) packets=125 framesBeforeEofDrain=125 framesDrainedAtEof=0 framesAfterEofDrain=125 firstPts=0 lastPts=4960000000
```

这使未来六平台 v39 Release 的“真实软件 AV1 解码”成为自动阻断门槛，但当前仓库仍嵌入 v38，远端也尚无 v39
六平台 Release，所以不能据此勾选“六平台 v39 实产物/动态加载完成”。Linux/Windows 四套真实 v39 产物仍需由
权威 FFmpeg workflow 生成并在各自 runner 上通过该 job，随后才能原子导入。

### 阶段 68：v39 provenance 精确锁定与当前树回退证据重跑

再次按最终导入路径审计时发现，`prepare_native_bundle.py` 原先只要求 `source_commit/upstream_base` 是 40 位
十六进制、runtime version 格式合法。这能阻止字段缺失，却不能阻止同名 `media-min-v39` tag 指向另一份未审计
提交后仍被 importer 接受。导入器现将三项身份同时冻结为已经真实构建和审计的值：

- source commit：`669aa5300a4b6ca91dd60632856c6dca1b63de70`；
- upstream base：`1f276a42dbd693ef58222e2c1499d45691b49089`；
- FFmpeg runtime：`git-2026-08-13-669aa53`。

新增 importer 测试逐项替换成另一组仍“格式正确”的值、同时重算 Release manifest，三种情况都必须因 provenance
identity mismatch fail closed。对应源码门槛也从“六个 FFmpeg/三个 dav1d 关键文件存在且非空”提升为逐文件冻结
SHA-256；即使攻击者替换 `video_jni.c` 等内容并重算 Release manifest，仍会因 source-content mismatch 被拒绝。
测试覆盖这一篡改路径。六平台 Java smoke 另补 Windows DLL 搜索路径：目标 native 目录会在子进程 PATH 中置顶，
使同目录 `libiconv-2.dll`/`libwinpthread-1.dll` 可被依赖解析；Linux/macOS 环境不被误改。对应两项测试通过，
原生工具集合现为 28/28 PASSED。Release 自身的 SHA256SUMS、dav1d commit、GLIBC floor、
归档文件集和二进制架构门槛保持不变。

同时以当前工作树重新运行 `ncpb.real-av1-h264-fallback`。首次报告暴露的是证据质量而非业务失败：场景在同 tick
观测到 H.264 首帧与 ≥5 秒时间线后立即停止，结构化 `media_millis` 在停止前尚未来得及采样，因此可能全为 0，
尽管最终 `verify` 已用内存中的 `observedMediaMillis` 严格断言。指标现持久保存已观测最大媒体时间，重跑总体、场景和
workload correctness 均 `PASSED`：codec max 7、media max 6,440 ms、native bytes `0..3,188,412`，最终
HTTP/close operations、所有自有内存、RGBA/YUV/staging/PBO 以及 native FFmpeg/D3D11 当前值均回到捕获基线。
报告位于 `/private/tmp/ncpb-real-av1-fallback-metrics-20260813`。

该重跑再次证明当前 macOS ARM64 上 AV1 codec 13 context 关闭完成后才打开真实 H.264 VideoToolbox、沿用同一
session 与非零时间线并最终收敛资源；Windows D3D11 surface 和其他 OS/GPU 仍必须由六平台/设备矩阵补齐，不能
据此提前关闭跨平台 checklist。

### 阶段 69：双片段 JNI seek/resource 证据与物理设备工作流

六平台软件 smoke 原先只解首 fragment，并在 EOF 后调用一次 `flush`；它能证明 EOF 和首段 PTS，却不能证明同一
decoder 的 seek 后输出，也没有把 native 当前资源的关闭基线写入可归档证据。独立 `VideoJni` 调用器现解析真实
`tfhd/tfdt/trun` 的 default duration/size、version 0/1 composition offset 和 16,000 timescale：先解码首段，
EOF drain 后 flush，再给同一 handle 送入冻结的 35 秒 range fragment，最后再次 EOF/flush。两段均要求
125 packet/125 frame，输出 PTS 分别严格覆盖 0→4.96 秒、35→39.96 秒，第二段首帧必须晚于第一段末帧。

调用器还在 open 前、解码中、close 后读取 `getNativeMemoryStats`。成功条件要求 FFmpeg current bytes、D3D11
texture current、surface current 和 logical bytes current 四项在 close 后逐项等于基线；峰值只作为测量，不以
“大于零”冒充特定平台资源类型。macOS ARM64 与 Rosetta x86_64 本地正式 v39 软件包均完成双片段实跑，ARM64
输出为：

```text
requested=none actual=cpu(libdav1d) width=682 height=360 fps=25 outputFormat=NV12 packets=125 framesAfterEofDrain=125 firstPts=0 lastPts=4960000000 seekPackets=125 seekFramesAfterEofDrain=125 seekFirstPts=35000000000 seekLastPts=39960000000 baselineResources=0/0/0/0 activeResources=4044992/0/0/0 afterCloseResources=0/0/0/0
```

`verify_native_av1_smoke.py --report` 会记录 UTC、runner OS/arch、v39 release/source/runtime、两份冻结夹具解码后
SHA-256、requested/actual backend、真实 NV12 首帧尺寸/格式、逐段 packet/frame/PTS/decode nanos 和三阶段资源快照。
硬件报告另用 macOS system_profiler、Windows CIM 或 Linux DRM sysfs 记录 GPU 型号、vendor/device 与驱动身份；
主 Release 六平台 job 归档软件
报告；新增 `native-av1-device-validation.yml` 由人工选择六平台之一和专用 self-hosted runner label，`--hwaccel auto`
按平台精确要求 VideoToolbox、D3D11VA 或 VAAPI，并归档 90 天的物理 GPU/驱动报告。当前 Apple 设备请求
VideoToolbox AV1 时明确返回宿主不支持，作为负向设备证据而不是成功项。工作流和 schema 已可执行，但尚未取得
六个平台 v39/目标 GPU 的远端 artifact，因此相关 checklist 继续保持未完成。

新增 `verify_native_av1_device_matrix.py` 只接受精确六份成功报告，逐份重验 runner OS/arch、固定 v39 commit/runtime、
平台 hardware backend、两份夹具 SHA、两段 packet/frame/PTS 和 close 后资源基线，并输出带每份原报告 SHA-256 的
矩阵 summary。4 项测试覆盖完整矩阵、缺平台、CPU 冒充硬解和 D3D11 surface 未归零；它与原生工具测试一同进入
Release build job。该汇总工具把“六个零散 artifact”变成一个可机读发布门槛，但在真实报告到齐前仍不会生成通过证据。

另增 `native-av1-device-matrix.yml`：dispatch 输入是六个平台到专用 self-hosted GPU runner label 的 JSON include；
六个硬件 job `fail-fast=false`，结束后统一下载 artifact、运行矩阵汇总器并归档 summary。单设备 workflow 用于设备
调试/负向证据，六设备 workflow 才是正式发布签核入口。两份 workflow 均通过 actionlint 1.7.12。

### 阶段 70：六平台 v46 原生包发布、逐平台 runtime provenance 与原子导入

FFmpeg `media-min-v44` 的六个平台构建和 Release 虽然成功，但导入器在真实加载时发现
`BUILD-PROVENANCE.txt` 把源码脚本版本 `N-126204-geb53ee4315` 记录成了动态库 runtime；macOS ARM64
实际 `av_version_info()` 为 `git-2026-08-13-eb53ee4`。因此 v44 未进入项目。后续构建在每个平台直接加载
`libavutil` 并上传 runtime metadata；又通过 v45 暴露了 Windows CRLF 和版本格式差异：Windows 两架构返回
`8.0.git`，Linux/macOS 四架构返回提交型版本串。最终 `media-min-v46` 的 Release provenance 保存六个独立
runtime 值，不再把源码版本或某一平台值外推到全部平台。

`media-min-v46` 的六个平台 build 与 Release job 全部成功。导入器冻结 source commit
`9f40947d49527c965986192e64b4d18f065b627a`、upstream base
`b397eba2f0d3d86daf1098d0f27daffccc74fea5`、六个平台实测 runtime、对应源码关键文件 SHA-256、dav1d
1.5.4/固定 commit 和两套 Linux GLIBC floor。Release manifest、六个归档的精确文件集、二进制格式/架构和
逐文件 SHA-256 全部通过后，六个平台目录作为一个不可分割 bundle 替换原 v38，生成新的
`src/main/resources/native/SHA256SUMS` 和 README。

本机 macOS ARM64 对导入后的正式目录完成五库真实加载，得到 5 个 EAC3 和 18 个 Video JNI 导出；软件 AV1
双片段 smoke 的 actual backend 为 `cpu(libdav1d)`，首段与 35 秒 seek 段各 125 packet/125 frame，PTS 精确为
0→4.96 秒和 35→39.96 秒，关闭后 FFmpeg/D3D11 current resources 回到基线。该证据关闭六平台软件 AV1
bundle 的发布/导入门槛，但不替代六种物理 GPU/驱动的硬解设备矩阵；后者继续保持未完成。

六平台 bundle 压入同一个离线 JAR 后产物约 14 MB，其中 native ZIP 数据本身约 9.84 MiB；单 native 数据已经
接近 10 MB，因此在不拆平台、不改成首次联网下载的约束下无法可靠满足单文件 10 MB 上限。项目继续发布一份包含
六个平台的通用 JAR，以保留离线加载、固定哈希与许可证材料的完整性；发布平台需要申请放宽文件大小限制。一次
`clean build` 已通过 47 个任务，包括全部测试、原生包/许可证、Scene Editor JiJ 和冲突副本检查。

### 阶段 71：v48 硬件 AV1 + H.264 回退与通用 JAR 体积闭环

根据 B站点播 DASH 会同时提供兼容 H.264 候选、且发布平台不允许分平台 JAR 的约束，最终发布策略不再携带
AV1 软件解码器。FFmpeg `media-min-v48` 保留原生 AV1 parser/decoder 与 VideoToolbox、D3D11VA、VAAPI
硬件入口；AV1 在 `none`/`off`、实际后端为 CPU、打开失败或首帧失败时由同一次 playurl 候选循环继续到 H.264。
H.264 的平台硬解与 FFmpeg 软件解码均保留。dav1d、libaom、HEVC 和 iconv 从构建、归档、许可证与对应源码门槛
移除；Windows 只保留实际动态依赖的 winpthread。

FFmpeg 提交 `3b3d6f46bbd34049fcac013d743d75e953452431` 的 `media-min-v48` 六个平台 build 与
Release job 全部成功。主项目导入器冻结该提交、六个平台 runtime version、FFmpeg 对应源码关键文件 SHA-256、
Linux GLIBC floor、精确归档文件集与二进制架构，并将六个平台作为一个不可分割 bundle 原子替换。Python 工具
36/36 通过，架构审计得到 32 个目标二进制；本机 macOS ARM64 对正式导入目录完成五库真实加载、FFmpeg runtime
精确匹配以及 5 个 EAC3 / 18 个 Video JNI 导出核验。

主项目 `clean build --no-daemon` 在 47 个任务上成功，包括全部 Java 测试、原生 SHA-256/文件集、法律材料、
生产 JAR、Scene Editor JiJ 与冲突副本门槛。最终仍是一份覆盖六个平台的离线通用 JAR；GitHub CI 实际上传
artifact 大小为 `9,342,446 bytes`（约 8.91 MiB / 9.34 MB），低于 10 MB；无需分平台发布，也无需申请放宽
体积限制。
通用 hosted runner 只证明动态加载、runtime identity 和 JNI exports；真实 AV1 硬解、seek、资源归零仍由独立
self-hosted 物理 GPU 矩阵签核，不能用 hosted runner 缺失 AV1 GPU 的失败冒充 bundle 故障。

### 阶段 72：Scene Editor 独立项目与外部 JiJ

Scene Editor 的两个库模块、普通 Maven 示例和双宿主 JiJ fixture 已迁至独立公开仓库
[zhongbai2333/SceneEditor](https://github.com/zhongbai2333/SceneEditor)。独立项目通过 JitPack 发布
`1.0.0-beta.3`；主项目直接解析并 JiJ `scene-editor-core` 与 `scene-editor-minecraft`，协商范围固定为
`[1.0.0-beta.3,2.0.0)`。该版本统一建模编辑器与 NCPB 的共享交互基线，并带入相机极点钳制、事务语义修复、
多选/Gizmo/线宽及历史会话公共策略；NCPB 现有单选与专用 Gizmo 表示保持不变。

主项目不再保存这两个库的源码、示例或 fixture，只保留实际业务集成、专用服务器自检、integrated-client 场景和
production JAR 的 `verifySceneEditorJiJ` 结构门槛。Scene Editor 库版本独立于主模组版本；本次迁移保持主模组
`0.6.7-beta`，也不改变中控台 schema v6 或全息眼镜 persistence schema v1。

历史 `native-patches` 工作目录也从当前树移除；v39 及此前补丁仍可由 Git 历史追溯，当前正式原生实现与构建来源为
独立 FFmpeg 仓库及已冻结的 `media-min-v48` Release，不再依赖主项目内的旧 patch 文件。

### 阶段 73：投影仪迟到链接唤醒与真实 BV 音视频联合 Bench

`run/logs/debug.log` 的现场运行证明故障发生在解码器之前：播放命令先到时客户端没有任何视频消费者，视频同步按
设计以 `stop-no-projector` 结束；投影仪稍后完成链接注册后，直到退出仍持续为 `video=n/a`，期间没有 playurl、
候选选择、native decoder 或首帧记录。FFmpeg/JNI 已正常加载，CDN 403 也由音频链路成功换源，均不是该故障根因。

`VideoProjectorBlockEntity.loadAdditional` 现在同时比较旧/新链接目标和清晰度。客户端链接从空变为有效、重新绑定、
解绑或质量变化时，均主动调用 `ModernTurntableVideoClient.refreshProjector`；不再依赖投影仪 BER 恰好进入视锥且
客户端唱片机 `isPlaying` 状态可见后才唤醒视频。纯 Java policy 回归覆盖无变化不重启、链接新增/替换/移除都刷新、
质量变化仍刷新。

真实 ModBench 场景 `ncpb.real-bv-playback` 从仅验证视频升级为 DASH 音视频联合门槛，默认视频统一为 Rick Astley
《Never Gonna Give You Up》的 `BV1GJ411x7h7`。场景并行验证真实 Bilibili 视频 DASH → FFmpeg JNI → NV12/PBO
上传，以及真实音频 DASH → AAC/fMP4 → PCM → Stereo OpenAL；音频必须累计至少 1,024 个非静音、有限且不过度
削波的 PCM 质量样本。成功后还必须等待 HTTP、视频/音频 close diagnostics、OpenAL native delete、GPU/自有内存
全部收敛。外部 Bilibili 信息/音频 URL 解析失败和视频/音频解码失败分别报告，避免把网络入口错误混成 codec 错误。

2026-08-13 在 Apple M4 上真实运行通过：Bilibili 返回 640×360、25 fps AV1，actual backend 为
`videotoolbox`，解码并上传 12 帧；音频选择 192K AAC，Stereo OpenAL 实际启动，质量窗口达到 4,096 samples、
累计输入 156,672 samples。首选视频/音频 CDN 均出现 403 后自动切换备用 host 并完成播放。最终场景、workload
correctness 与总报告均为 `PASSED`，active close 最大值为 0。权威报告位于
`build/modBench/raw-results/default/client/summary.json`（构建产物，不提交 Git）。

## Phase 74：Bench 用户功能矩阵（2026-08-13）

- 新增 `ncpb.device-link-config-matrix`：在集成服务端真实放置六类媒体设备，验证 BE 同步、设备参数、
  链接索引、音响客户端 relay、中控台消费者登记和播放中迟到的视频投影仪消费者登记；音响、视频/歌词投影仪、
  中控台还会从唱片机 A 运行期重绑到 B，旧索引/relay 必须清除，新索引/relay 必须建立。
- 新增 `ncpb.wearable-binding-topology`：使用正式服务端绑定 API 为耳机和全息眼镜绑定唱片机、MP4、Pad 与
  视频投影仪，验证客户端耳机路由、眼镜四屏上限、重复绑定幂等、第五槽拒绝、按目标解绑、反向索引和完整清理。
- 新增 `ncpb.gui-screen-matrix`：逐一打开并渲染 15 个离线安全生产 Screen，取得自动化快照后关闭，
  覆盖唱片机、视频/歌词投影仪、音响、直播机、中控台、MP4、Pad、Pad 地图、全息编辑器、视频占位图、
  媒体工具绑定/报告和白名单审核/预览；只有必须发起网络登录并由真人扫码的二维码页留作人工检查。
- 新增 `ncpb.handheld-media-contracts`：锁定 MP4/Pad 屏幕尺寸、离屏缩放和 Pad 会话身份编解码。
- 新增 `ncpb.live-stream-contracts`：锁定直播输入规范化、元数据 owner、健康重连/退避与消费者重绑。
- GUI 矩阵发现 `VideoPlaceholderDebugScreen` 会在单人世界暂停游戏，现已统一改为非暂停，避免媒体时间线因调试页中断。
- 五个新增场景已在真实集成客户端执行，均为 `PASSED`；覆盖边界与执行命令见
  `docs/bench-feature-coverage.md`。

## Phase 75：Range Seek 元数据复用与 UI 提示图单一生成源（2026-08-13）

`run/logs/debug.log` 的现场证据显示 seek 慢点在远端 fMP4 元数据和 CDN 切换，不在 native 解码：视频 SIDX
probe 曾耗时 5,878 ms，音频 SIDX 总耗时 6,524/6,593 ms；相同链路命中可用备用 CDN 时视频 probe 仅
587 ms。视频此前即使 playurl 已提供 `SegmentBase.initialization`，仍固定请求 `0-4194303` 最多 4 MiB
探测 init，坏 CDN 因而会在零 packet 时耗尽 AV1 的 2 秒首帧预算。

视频现在优先按 SegmentBase 精确读取 init byte range，只有元数据缺失/不可解析才回退通用探测。音视频 init
与 SIDX 小范围共享 `Fmp4SeekRangeCache`：10 分钟 TTL、最多 64 项、单项最多 1 MiB；缓存键包含剥离内部
播放参数后的资源 path、原始 query 和精确 byte range，因此等价 CDN host 可复用，更新签名或不同 range 不会
串数据。音视频小范围读取默认并发竞速最多 3 个 CDN、1,500 ms 后才回退串行读取，避免曾成功但当前 403/慢响应
的节点阻塞备用节点。竞速赢家会直接承接本次后续媒体片段 Range，而不只是记录成下一次偏好；CDN 健康排序也给
未观测 host 设置中性基线，修复“未知 host 的 0 分反而压过刚成功 host”的反向排序。seek 片段启动预缓冲由
384 KiB 降为 64 KiB，仍保留后台持续预取，但不再为了首帧额外空等大缓冲。

中控台旧 idle/buffering/error 使用青色电路边框、发光节点和独立字形，与公共 `video_loading` 的暗色面板、
金/红 1px 边框及像素字体不一致。三张图现已纳入 `tools/generate_loading_ui_preview.py`，并与公共提示图由同一
绘制函数生成；脚本同时可原子写出完整 loading/error/progress 资源集。普通提示卡的文字阴影改为不透明黑色，
保持 320×180 全不透明契约；隐私遮罩仍保留其明确需要的透明层。生成后中控 idle/buffering/error 与公共
idle/loading phase3/network error 分别具有完全相同的 SHA-256，确保不是仅凭肉眼近似风格。

定向测试覆盖缓存的等价 CDN host 复用、签名/range 隔离、视频资源尺寸/不透明/互异及解码器生命周期；最终资源
必须通过脚本重新生成后再提交，不再手工编辑 PNG。

真实 `ncpb.real-av1-hardware-seek` 在 Apple M4/VideoToolbox 上最终为 `PASSED`：精确 init `0-1070` 与 SIDX
`1135-1682` 均由竞速胜出的 `upos-sz-mirrorzos.bilivideo.com` 返回；5 秒初始定位的完整 SIDX probe 为
1,007 ms，同会话向前跳到 28 秒时复用元数据并直接沿用赢家 CDN，媒体片段 probe 为 185 ms，首帧等待
295 ms。场景同时通过 AV1 硬解、seek 前后 PTS 单调和 stop 后资源回基线断言。对比修复前现场的 5,878 ms
视频 probe，本次已消除代码侧的 4 MiB init 探测、坏 CDN 串行等待和 seek 大预缓冲；公网 CDN 自身延迟仍是
首次未缓存定位的外部变量。

## Phase 76：发布前代码体检与 Range Seek 完整性收口（2026-08-14）

本轮在不提升版本号、不创建分支的前提下复查全部生产/测试/Bench 源集和当前工作树。Range Seek 的小范围缓存
现在只接收与请求 byte range 精确等长的数据；短读不会进入缓存，缓存的 `byte[]` 也不再向调用方暴露可修改的
内部数组。视频元数据读取同样在返回前校验完整长度，并把短读计入 CDN 健康状态，避免一次残缺响应污染后续
10 分钟内的所有 seek。

`HttpRangeClient.CdnResponse` 现在携带经过参数剥离和 HTTP 重定向后的实际响应 URL。音视频 Range 调用方以该 URL
记录成功/失败、保存赛马赢家并传递后续媒体片段请求；串行 fallback 或重定向命中备用 CDN 后，不会再误把最初的
慢/失败节点标成赢家。CDN 历史评分的过期观测改为收敛到未知主机的中性分，而不是衰减到零后把陈旧失败节点排在
新节点之前。音视频 Range race 的规范属性统一为 `ncpb.bili.media.range_race.*`，旧 audio 属性仍兼容，候选线程数
强制限制在 1～8。

UI 资源生成器改为同目录临时文件加原子替换，避免生成过程被中断时留下半张 PNG。缓存、短读、CDN 评分和属性
兼容性均增加纯 Java 回归；公共 loading、中控台三态图和隐私遮罩的正式资源契约由完整测试继续覆盖。

本机云同步软件会持续向被忽略的 `build/`/`bin/` 恢复形如 `Foo 2.class` 的冲突副本。构建脚本现在禁止 Java
编译任务加载/保存这类被污染的 build-cache 输出，并在实际执行编译前后删除冲突副本；JAR 打包排除及生产 JAR
零冲突副本门槛继续保留。因为同步软件也会在任务间隙污染资源目录，本机发布验证仍必须传
`-PncpbBuildDirectory=/private/tmp/...`。已清除当时 `bin/` 中 1,186 个生成冲突副本；同步软件随后仍可能恢复，
它们均为忽略文件且不会进入发布包。`run/saves` 中三个世界存档冲突副本未自动删除，避免损坏用户存档。

隔离全量命令 `clean build -PenableModBench=true -PncpbBuildDirectory=/private/tmp/ncpb-full-review-20260814
--no-daemon --no-build-cache` 通过：220 suites / 836 tests / 0 failures / 0 errors / 0 skipped；Bench 源集、生产
JAR、embedded native、法律材料、Scene Editor JiJ 和冲突副本门槛全部成功。Python 工具测试 41/41 通过，
全部工具脚本也通过语法编译。产物为单一六平台通用 JAR，大小 8.8 MiB，仍低于 10 MB；无改动重跑可复用
Gradle configuration cache，并在约 2 秒完成。

## Phase 77：运行时音轨接管、静音视频、中控台 UI 与慢 CDN 修复（2026-08-14）

后续 `run/logs/debug.log` 和实机反馈推翻了“只剩外部测试”的判断，因此这些问题按发布阻断重新处理。唱片机
音频 handler 重建时不再清除已经存在的音响/中控台主音轨接管状态；中控台虚拟音源改为按元素身份持有接管
所有权，最后一个虚拟音源注销后才恢复主输出，避免永久静音或新 handler 重新漏出唱片机本体声音。Stereo 与
Dolby 主输出执行追赶 flush 时，会把同一媒体样本基线传给全部 relay；中途新增 relay 也从当前主输出基线开始，
不再像现场日志那样稳定落后约 2.05 秒。

唱片机音量降到 0 现在只改变声音增益，不再发 playback stop、清 session 或停止向附近玩家同步。视频、字幕和
中控台 consumer 因而继续沿用原会话；恢复音量也无需新建播放会话。中控台 BUFFERING 占位帧启用现有双面动态
进度条 overlay；编辑器默认进入“元素内容”而非“高级变换”，音频元素的文本音量框替换为可见的 0～100% 滑条，
声道、距离、启用和自动混合控件同步重新排版。

音频与视频的顺序读取统一经过 `ChunkPrefetchInputStream`。首个 Range 从原来的固定 4 MiB 缩小到启动预缓冲
目标（普通起播默认 768 KiB，seek 默认 64 KiB），首选 CDN 在启动等待窗口内仍为 0 字节且存在备用候选时，取消
当前 exact request/body、清除下载线程中断并直接转到下一 CDN；成功切换后再等待一个有界启动窗口。单 CDN 的
非 B站 URL 仍保留单连接完整 GET 路径。

隔离全量命令 `clean build -PenableModBench=true
-PncpbBuildDirectory=/private/tmp/ncpb-phase77-full-final-20260814 --no-daemon --no-build-cache` 已通过：221 suites /
840 tests / 0 failures / 0 errors / 0 skipped；Bench 源集、生产 JAR、embedded native、法律材料、Scene Editor
JiJ 和冲突副本门槛全部成功。产物为单一六平台通用 JAR，大小 9,202,026 bytes（约 8.78 MiB），仍低于
10 MB。仍需由 `runClient` 实机确认 OpenAL 设备实际出声、主音轨确实静音、进度条动画和真实慢 CDN 切换日志；
纯 Java/构建测试不能替代这四项设备/网络观测。

## 推荐的新会话工作流

1. 阅读本文档；
2. 在 NetMusicCanPlayBili 根目录运行 `git status --short`；
3. 不回退现有改动；
4. 运行一次 `sh ./gradlew build --stacktrace` 确认环境；
5. 复查 phase37 的 12/12 报告、phase38 冻结 JSON、phase39 发布门槛、phase40 首帧双预算、phase41
   精确文件集/SHA-256 manifest、phase42 runtime matrix、phase43 真实 AV1 字节夹具、phase44 双域 handoff/
   codec policy/v39 单平台实构建、phase45 持续性能回退、phase46 delayed-output drain、phase47 中控台冲突
   权威回包、phase53 schema v6 仿射编辑/统一命令栈/投影 owner-session 双域替换准入，以及 phase54 AI CC
   共享会话、时间线和安全降级、phase55 terrain 材质远景/冻结 tint/方块实体状态/透明重排、phase56 terrain
   PIP integrated-client 实机闭环、phase57 Scene Editor Maven/JiJ/客户端/服务端/双宿主去重闭环，以及 phase58 terrain
   第三方资源包/模组/Iris shaderpack 固定矩阵、phase66 真实媒体 100 轮与物理双客户端系统门槛、phase67 六平台 v39
   真实软件 AV1 解码发布门槛、phase69 双片段 seek/resource 与自托管硬件设备证据，以及 phase62/63 macOS ARM64 真实 AV1
   VideoToolbox/默认首帧预算/Range Seek/关闭诊断闭环、phase64 macOS x86_64 v39 实构建和 Rosetta
   软件 AV1 解码，以及 phase71 v48 硬件 AV1/H.264 回退与 10 MB 通用 JAR 闭环；后续按用户问题报告
   修复兼容性。物理设备矩阵仅在能够取得对应硬件时补做，不阻断当前发布；
6. 每批继续运行 Java、Python、`git diff --check`；
7. 涉及真实媒体链路时复用阶段 73 的 `ncpb.real-bv-playback` 音视频联合门槛。
