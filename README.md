# NetMusicCanPlayBili

NetMusicCanPlayBili（NCPB）是 NetMusic 的 NeoForge 附属模组，为 Minecraft 增加 Bilibili 视频、直播与游戏内影音设备。

## 功能

- 将 BV、av 或视频链接刻录为 NetMusic 唱片，支持分 P 选择。
- 使用现代化唱片机、直播机、歌词投影仪、视频投影仪和音响搭建影音系统。
- MP4 提供随身播放队列；Pad 提供地图媒体点位、草稿编辑和锁定副本。
- 中控台支持屏幕、字幕和空间音源编辑；耳机与全息眼镜提供个人收听、观看入口。
- 服务端提供链接白名单、审核记录和权限控制。

详细操作见[游玩指南](docs/play-guide.md)。

## 安装

| 项目 | 当前要求 |
| --- | --- |
| Minecraft | 26.1.2 |
| NeoForge | 26.x，开发基线为 26.1.2.76 |
| Java | 25 |
| NetMusic | 1.5.1 或更高，必装 |
| Curios | 12 或更高，可选 |

将 NCPB 和 NetMusic 放入 `mods`。多人游戏的客户端与服务端都需安装 NCPB 和 NetMusic；每个模组在两端的版本应保持一致。

当前源码版本为 `0.7.10-beta`，构建配置以 [gradle.properties](gradle.properties) 和[模组依赖声明](src/main/templates/META-INF/neoforge.mods.toml)为准。发布文件见 [Releases](https://github.com/zhongbai2333/NetMusicCanPlayBili/releases)。

内置解码库覆盖 Linux、macOS、Windows 的 x86_64/ARM64。AV1 依赖平台硬件解码能力，失败时尝试 H.264；Linux 另有系统库要求。安装前可查阅[媒体能力与限制](docs/release-native-capabilities.md)。

## 快速上手

1. 打开 NetMusic 电脑，按需扫码登录 Bilibili。
2. 在 CD 刻录机中输入 BV、av 或视频链接，制作唱片。
3. 将唱片放入现代化唱片机或 MP4，或在 Pad 中创建媒体点位。
4. 投影仪、音响等设备的绑定与配置见[游玩指南](docs/play-guide.md#搭建影音系统)。

## 构建与检查

使用 JDK 25，在仓库根目录运行：

```sh
bash gradlew build platformTest
```

`build` 执行普通单测和生产包校验；`platformTest` 用于保护存档、协议、权限等平台契约，默认构建不包含它。

需要编译游戏内 Bench 时使用：

```sh
bash gradlew -PenableModBench=true compileBenchJava
```

这条命令只编译场景。真实音视频、多人和界面验收按[发布前人工检查单](docs/testing/manual-release-checklist.md)执行。Windows 可将 `bash gradlew` 替换为 `gradlew.bat`。

开发入口与维护边界见[开发说明](docs/development.md)。

## 文档

- [文档索引](docs/README.md)：当前说明和历史档案的统一入口。
- [游玩指南](docs/play-guide.md)：设备、命令、配置与故障排查。
- [测试门槛](docs/testing/architecture-split-gates.md)与[人工检查单](docs/testing/manual-release-checklist.md)。
- [Bench 场景](docs/bench-feature-coverage.md)：真实客户端、网络和硬件环境中的可选验证。
- [媒体能力与第三方许可](docs/release-native-capabilities.md)。
- [Scene Editor 集成](docs/scene-editor-publishing.md)：独立编辑器库与 JiJ 打包边界。

## 隐私与许可

Bilibili 登录凭据保存在游戏目录下的本机加密凭据库。报告问题时不要提供 Cookie、凭据文件或未经检查的完整日志；收集信息的方法见[配置与隐私](docs/play-guide.md#配置与隐私)。

项目源码使用 [MIT](LICENSE) 许可。内嵌第三方组件保留各自许可，见[第三方声明](src/main/resources/META-INF/THIRD-PARTY-NOTICES.md)及[媒体能力说明](docs/release-native-capabilities.md)。
