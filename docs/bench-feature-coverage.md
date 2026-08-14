# Bench 功能覆盖矩阵

本表描述 `src/bench` 中可执行场景对用户功能域的覆盖。默认场景必须离线、确定性、无需人工输入；
真实 Bilibili、真实 MP3、双客户端和外部模组/资源包矩阵通过对应 Gradle 开关启用。

| 功能域 | 主要场景 | 覆盖内容 |
| --- | --- | --- |
| Scene Editor JiJ | `ncpb.scene-editor-library-smoke`、`ncpb.editor-gui-lifecycle` | core/Minecraft adapter 加载、编辑会话、真实 Screen 生命周期 |
| 唱片机方块 | `ncpb.turntable-block-interactions` | 插入、右键弹出、播放状态、自动化事务提取 |
| 唱片机真实音频 | `ncpb.real-turntable-mp3-end-to-end`、三个 `real-mp3-*` | 服务端解析、同步包、MP3 解码、OpenAL、seek/retry/静音/范围停止 |
| 固定媒体设备 | `ncpb.device-link-config-matrix` | 唱片机、视频投影仪、歌词投影仪、音响、直播机、中控台的真实 BE 创建与同步 |
| 设备链接与配置 | `ncpb.device-link-config-matrix` | 视频质量/投影参数、歌词模式/AI、7.1.4 声道/JOC/音量、中控源绑定；运行期从唱片机 A 重绑到 B，并核对客户端链接和服务端反向索引迁移 |
| 耳机/全息眼镜 | `ncpb.wearable-binding-topology` | 正式绑定服务、唱片机/MP4/Pad/投影仪拓扑、客户端耳机路由、全息屏幕绑定、四槽上限、按目标解绑和反向索引清理 |
| 音响 relay | `ncpb.device-link-config-matrix` | 音响到唱片机的服务端反向索引、客户端音频 relay 注册以及 7.1.4/JOC/音量同步 |
| 播放中迟到投影仪 | `ncpb.device-link-config-matrix`、`ncpb.playback-session-races` | BE 更新后的客户端消费者登记，以及同一会话重试/替换状态机 |
| 媒体 GUI | `ncpb.gui-screen-matrix` | 15 个离线安全生产 Screen 逐一打开、真实渲染、自动化快照、关闭；包括 MP4、Pad、地图、绑定/报告和白名单审核/预览 |
| 手持 MP4/Pad | `ncpb.handheld-media-contracts`、`ncpb.playback-session-races` | 两种屏幕几何/缩放、Pad 逻辑会话身份、媒体会话竞态 |
| Pad 地图/地形 | `ncpb.gui-screen-matrix`、`ncpb.terrain-lod-roundtrip` | 地图 Screen、采样/缓存使用路径、LOD/PIP/GPU/透明层与资源收敛 |
| Bilibili 直播 | `ncpb.live-stream-contracts`、`ncpb.real-live-device-topology` | 房间号/链接/占位 URL、元数据 owner、健康重连与指数退避；真实 8178490 直播流的直播机、投影仪、中控台屏幕/音频元素和实体音响联合加载 |
| 真实 Bilibili 点播 | `ncpb.real-bv-playback` | 真实 BV 分 P、DASH 音视频、AV1/H.264 候选、native decode、OpenAL、GPU 上传与清理 |
| AV1 回退/硬解 seek | `ncpb.real-av1-h264-fallback`、`ncpb.real-av1-hardware-seek`、`ncpb.frozen-real-av1-hardware-seek` | AV1 首帧失败转 H.264、同会话 continuity、Range seek、PTS、固定样本 |
| GPU 视频上传 | `ncpb.deterministic-video-upload` | RGBA、YUV420P、NV12/PBO 上传与纹理释放 |
| 资源生命周期 | `ncpb.media-resource-convergence`、`ncpb.real-media-lifecycle-100` | HTTP、native、GPU/PBO、OpenAL、owned memory 的静止基线 |
| 多客户端 | 三个 `ncpb.multi-client-*` | 独立消费者 lease、断开、重连、真实媒体 survivor |
| 跨维度 | `ncpb.cross-dimension-media-cleanup` | respawn 包、加载 UI、跨维度媒体精确清理 |
| 中控台 | `ncpb.console-consumer-lifecycle`、`ncpb.device-link-config-matrix`、`ncpb.real-live-device-topology` | 消费者 attach/detach、source binding、客户端消费者登记、GUI/lease，以及真实直播的屏幕+音频元素 |
| 白名单 | `ncpb.whitelist-management-lifecycle`、`ncpb.gui-screen-matrix` | 真实服务端增加/删除、直播机启动拦截、审核列表非空快照、预览 Screen、CSV 服务端生成与客户端落盘；场景结束恢复配置并清理临时条目 |

## 当前不能完全自动化的边界

- B站二维码登录成功需要真人扫码，Bench 不能伪造成功态；这是唯一不进入离线 GUI 矩阵的生产 Screen，
  默认矩阵不发起登录网络请求。登录后的 Cookie/API
  使用由真实 Bilibili 场景覆盖，二维码生成与扫码仍保留为发布前人工检查。
- Minecart Revolution、Iris shaderpack、第三方资源包以及六平台硬解属于外部环境矩阵；缺少对应模组、
  shaderpack、资源包或机器时不能由默认客户端虚构通过。`ncpb.terrain-lod-roundtrip` 的 compat 模式和
  真实 AV1 场景用于具备环境时执行。
- “覆盖”表示每个生产功能域至少存在一个带断言的场景，并不等同于每个平台、每个 CDN、每个 GPU 驱动组合均已实机验证。

## 快速执行

```bash
bash gradlew runBenchClient \
  -PenableModBench=true \
  -PmodBench.scenarios=ncpb.device-link-config-matrix,ncpb.wearable-binding-topology,ncpb.gui-screen-matrix,ncpb.handheld-media-contracts,ncpb.live-stream-contracts,ncpb.whitelist-management-lifecycle \
  --no-daemon
```

真实 Bilibili 直播四设备拓扑默认使用房间 `8178490`，只在显式开启时注册：

```bash
bash gradlew runBenchClient \
  -PenableModBench=true \
  -PncpbRealLiveBench=true \
  -PncpbLiveBenchRoom=8178490 \
  -PmodBench.scenarios=ncpb.real-live-device-topology \
  --no-daemon
```

房间当时未开播或 B站未返回可播地址时，该真实网络场景会明确失败，不会用假帧代替。真实点播仍使用
`ncpb.real-bv-playback`，默认 BV 为 `BV1GJ411x7h7`。
