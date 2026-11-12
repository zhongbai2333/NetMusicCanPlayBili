# 测试分工与门槛

## 验证分工

- `test`：纯 Java 单元测试，随 `build` 执行。
- `platformTest`：使用 Minecraft/NeoForge 注册表和 Codec，保护存档、协议、权限、凭据及核心播放回归；独立于 `check`，需要显式运行。
- `src/bench`：在真实客户端中验证网络、声卡、显卡或多人路径，按改动范围运行，见 [Bench 场景与运行方式](../bench-feature-coverage.md)。
- 界面、玩法及外部环境组合按[发布前人工检查单](manual-release-checklist.md)验收。

## 重构与发布

1. 重构前记录 `test` 和 `platformTest` 的结果，区分已有失败与本次新增回归。
2. 重构后比较基线。行为回归应修复；只锁定内部结构的断言可以调整或删除，但必须说明放弃的保证，保留有实际数据、会话或资源后果的回归。
3. 改动存档、网络包、权限或播放生命周期时，保留对应的必要回归；只有发现新的行为风险时才补测试，不为覆盖数字扩展分支矩阵。
4. 发布前运行 `build` 和 `platformTest`，完成人工检查单的快速档；升级 Minecraft/NeoForge、改动播放链路或大规模重构后完成相关完整档条目。

## 命令

从仓库根目录执行，使用 JDK 25：

```sh
bash gradlew build                 # test、生产 JAR 与构建校验
bash gradlew platformTest          # 独立的平台测试
bash gradlew platformTest --tests '*MP4*'  # 开发时按范围检查，不能替代发布前完整运行
bash gradlew compileBenchJava -PenableModBench=true  # Bench 编译，不启动游戏
```

可加 `-PncpbBuildDirectory=/tmp/ncpb-verification-build` 隔离构建产物。记录实际运行的任务、源码版本和结果；本页不固定测试数量或耗时。测试依赖不得进入生产 JAR。构建通过不等于真实游戏、网络或物理设备验收通过。
