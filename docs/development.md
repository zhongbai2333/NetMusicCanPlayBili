# 开发与验证

本文记录当前开发入口、构建与验证分工。产品操作见[游玩指南](play-guide.md)，逐次变更、旧方案和旧机器上的实测结果见[历史档案](archive/README.md)。

## 本地构建

使用 JDK 25。Gradle toolchain、依赖和主模组版本以 `build.gradle`、`gradle.properties` 为准。

```sh
bash gradlew build
```

默认 `build` 执行纯 Java `test` 和生产 JAR 校验。`platformTest` 使用 Minecraft/NeoForge 运行时类型，按需单独执行；默认构建不包含它。Bench 默认关闭，普通构建不要求开启游戏、声卡或显卡。

构建产物可通过 `-PncpbBuildDirectory=/tmp/ncpb-verification-build` 放到独立目录。报告位于所用构建目录的 `reports/tests/test` 和 `reports/tests/platformTest`。

## 选择验证范围

根据改动类型选择验证入口，完成一组相关改动后统一运行。测试用于保护行为和必要回归，不为 getter、转发、文件排版或当前私有拆分方式补测试。

| 改动 | 主要验证 |
| --- | --- |
| 解析、时间线、取消和纯策略 | 相关 `test`，随后正常 `build` |
| 存档、网络协议、权限、凭据 | 相关 `platformTest`，提交前执行完整平台测试 |
| GUI、设备链接和玩法 | [人工检查单](testing/manual-release-checklist.md) |
| 真实媒体、OpenAL、GPU、多人进程 | [Bench 场景和开关](bench-feature-coverage.md)；仅在具备所需环境时执行 |

```sh
bash gradlew test
bash gradlew platformTest --tests '*MP4*'
# 完成一组修改后，提交前统一验证：
bash gradlew build platformTest
git diff --check
```

具体门槛见[测试分工](testing/architecture-split-gates.md)。提交前检查工作区，确认本次改动范围、删除和新增文件；不把临时日志、凭据、构建产物或仅属于本机的配置加入提交。临时绕过项和未执行的验证应写入交付说明。

修改 `src/bench` 时，至少编译相应源码；实际场景另按开关运行：

```sh
bash gradlew compileBenchJava -PenableModBench=true
```

## 开发定位

| 功能 | 当前入口 |
| --- | --- |
| 本地网络协议、注册和信任边界 | [网络包架构](network-packet-architecture.md)；`network/ModernTurntableNetwork` |
| MP4/Pad 服务端播放 | `MP4PlaybackSyncManager` 编排；`MP4PlaybackCommands` 处理手动命令；`MP4PlaybackTerminationApplication` 协调结束 |
| 会话、位置与进度 | `MP4PlaybackSourceSessionRegistry`、`MP4PlaybackSourceDiscovery`、`MP4PlaybackProgressPersistence` |
| 客户端音频会话 | `ModernTurntablePlaybackCoordinator`、`ClientPlaybackSession`、`ClientMediaPlaybackRegistry` 与 `ClientMediaSoundRegistry` |
| Pad 文档、发布和地图 | [Pad 当前设计](pad-design.md)；`PadDocumentStore`、`PadDocumentSavedData`、`PadMapClientCache`、`PadMapSampler` |
| 中控台 | [稳定设计](control-console-design.md)；`ControlConsoleBlockEntity`、`ControlConsoleConfigPacket` 和场景编辑器宿主 |
| 视频 native 与发布能力 | [能力说明](release-native-capabilities.md)、`src/main/resources/native/README.md` |
| Scene Editor 依赖和 JiJ | [发布接线](scene-editor-publishing.md) |

修改这些组件时保持既有边界：旧异步结果不能覆盖新会话；条件替换/移除保留对象身份语义；时间线从统一时钟读取；运行期资源的逻辑取消与物理关闭分别收敛。不要仅因类较大就继续拆文件或添加接口。

## 发布

CI 先执行正常 `build`，再显式执行 `platformTest`；tag 发布在这些步骤成功后打包。日常本地构建与发布验收的范围不同。

发布前执行[人工检查单](testing/manual-release-checklist.md)的快速档；播放链路变更、Minecraft 升级或大规模重构执行完整档。构建、离线测试和历史单机报告分别说明其证据范围，不能替代当前设备上的真实验证。

生产 JAR 的 native 文件集、许可证、Scene Editor JiJ、QR 编码源码子集和分发体积由构建门槛检查。发布能力和可选物理设备矩阵见[发布能力说明](release-native-capabilities.md)。
