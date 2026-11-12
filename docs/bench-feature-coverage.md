# Bench 场景与运行方式

本页列出 `NetMusicBenchProvider` 当前注册的场景和启用条件，描述场景要检查的行为，不表示本次提交已在这些环境中运行通过。日常构建默认关闭 Bench；游戏内场景需要真实客户端和对应的显卡、声卡或网络条件。

`platformTest` 保护存档、协议、权限、凭据及核心播放回归；`test` 覆盖纯 Java 单元行为；界面和玩法仍需[发布前人工检查](testing/manual-release-checklist.md)。本表不承诺每个生产功能都有自动化场景。

## 保留的场景

| 场景 | 启用条件 | 检查的行为 |
| --- | --- | --- |
| `ncpb.platform-mixin-audit` | 默认 | 真实 FML 变换目标与声音桥接注入 |
| `ncpb.deterministic-video-upload` | 默认 | RGBA、YUV420P、NV12/PBO 上传、纹理释放和资源基线 |
| `ncpb.cross-dimension-media-cleanup` | 默认 | respawn 包、加载界面和跨维度媒体清理 |
| `ncpb.real-mp3-sound-engine` | `-PncpbRealMp3Bench=true` | Minecraft streaming channel、5s/12s 两代 seek 的时间窗口、暂停/恢复、静音与范围停止 |
| `ncpb.real-turntable-volume-range-reentry` | 同上 | 唱片机 GUI 音量、服务端解析与同步会话、真实移动离开/返回范围、非静音 PCM 和弹出后的资源收敛 |
| `ncpb.real-bv-playback` | `-PncpbRealBench=true` | BV DASH 音视频、native decode、OpenAL 和资源清理；默认还检查唱片机、投影仪、中控台屏幕离开/返回范围后的休眠与恢复 |
| `ncpb.real-av1-h264-fallback` | 同上 | 注入 AV1 首帧失败后转 H.264，同一逻辑会话和时间线继续 |
| `ncpb.real-av1-hardware-seek` | 同上 | 实际 AV1 硬解、Range seek、PTS 和物理资源收敛 |
| `ncpb.real-live-device-topology` | `-PncpbRealLiveBench=true` | 真实直播流在直播机、投影仪、中控台屏幕/音源和实体音响中的联合加载 |
| `ncpb.multi-client-consumer-lifecycle` | 双客户端 | 独立 lease、同 UUID 断线重连、幸存者不受影响，再错峰退出；服务端观察 2→1→2→1→0 |
| `ncpb.multi-client-real-media-lifecycle` | 双客户端及 `-PncpbRealMediaLifecycle=true` | 两端真实视频/音频、首端退出后另一端继续持有媒体与 lease，再分别回到资源基线；服务端观察 2→1→0 |

## 执行前提

从仓库根目录执行，使用项目要求的 JDK 25。只检查 Bench 编译可运行：

```sh
bash gradlew compileBenchJava -PenableModBench=true
```

编译不证明游戏内播放、GPU/声卡或双客户端行为已经通过。下列命令会启动游戏进程。

真实点播默认 BV 为 `BV1GJ411x7h7`，可用 `-PncpbBenchVideo=<BV号>` 替换。真实 MP3 使用 `-PncpbRealMp3BenchUrl=<地址>`，未指定时使用构建脚本的默认样本地址；BV 场景的范围阶段也需要该 MP3 地址。真实直播默认房间 `8178490`，可用 `-PncpbLiveBenchRoom=<房间号>` 替换。房间未开播、地址失效、登录权限不足或设备不支持相应硬解时，场景可能失败，报告应保留实际原因。

## 单客户端

默认离线场景：

```sh
bash gradlew runBenchClient -PenableModBench=true \
  -PmodBench.scenarios=ncpb.platform-mixin-audit,ncpb.deterministic-video-upload,ncpb.cross-dimension-media-cleanup \
  --no-daemon
```

真实 MP3：

```sh
bash gradlew runBenchClient -PenableModBench=true -PncpbRealMp3Bench=true \
  -PmodBench.scenarios=ncpb.real-mp3-sound-engine,ncpb.real-turntable-volume-range-reentry \
  --no-daemon
```

真实点播与 AV1：

```sh
bash gradlew runBenchClient -PenableModBench=true -PncpbRealBench=true \
  -PmodBench.scenarios=ncpb.real-bv-playback,ncpb.real-av1-h264-fallback,ncpb.real-av1-hardware-seek \
  --no-daemon
```

真实直播：

```sh
bash gradlew runBenchClient -PenableModBench=true -PncpbRealLiveBench=true \
  -PncpbLiveBenchRoom=8178490 -PmodBench.scenarios=ncpb.real-live-device-topology \
  --no-daemon
```

## 双客户端

`runBenchPaired` 启动独立 dedicated server 和两个客户端 JVM。消费者场景需要 BenchMod runner 支持配对客户端在场景内断线后自动重连；该能力由 runner 处理，不需要另传 `expectReconnect` 开关。更换 BenchMod 版本时须确认仍具备这一能力。

```sh
bash gradlew runBenchPaired -PenableModBench=true -PncpbPairedClientCount=2 \
  -PmodBench.scenarios=ncpb.multi-client-consumer-lifecycle --no-daemon
```

真实媒体幸存者场景单独运行：

```sh
bash gradlew runBenchPaired -PenableModBench=true -PncpbPairedClientCount=2 \
  -PncpbRealMediaLifecycle=true \
  -PmodBench.scenarios=ncpb.multi-client-real-media-lifecycle --no-daemon
```

## 仍需人工或设备验收的边界

- B站二维码登录成功需要真人扫码。真实 Bilibili 场景只验证当次可取得的媒体和播放路径，不代替账号登录、凭据持久化或各种权限状态的验收。
- Iris shaderpack、第三方资源包及六平台硬解需要对应环境。单一机器的通过记录不能代表其他 GPU、驱动、操作系统或资产组合。
- 已移除的历史场景不再是当前运行入口。旧测试数、机器环境和测量值见文档归档，不作为当前版本的自动验收结果。
