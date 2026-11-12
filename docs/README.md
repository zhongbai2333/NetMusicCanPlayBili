# 文档索引

这里区分当前使用说明、维护入口与历史材料。当前功能和默认值以源码为准；设计目标和验证状态见各文档标注。旧验收记录仅反映当时的版本、机器与执行范围。

## 安装与使用

| 文档 | 内容 |
| --- | --- |
| [游玩指南](play-guide.md) | 安装、设备操作、MP4、Pad、穿戴设备、命令、隐私和故障排查 |
| [媒体能力与第三方许可](release-native-capabilities.md) | 当前内置解码库、平台前置条件、硬件限制和许可材料 |
| [内存诊断](memory-diagnostics.md) | 诊断开关、指标含义和资源保护 |

## 开发与验证

| 文档 | 内容 |
| --- | --- |
| [开发说明](development.md) | 构建、当前代码入口、维护边界与提交前检查 |
| [测试门槛](testing/architecture-split-gates.md) | 普通单测、平台契约和人工验收的分工 |
| [发布前人工检查单](testing/manual-release-checklist.md) | 游戏内快速档与完整档验收 |
| [Bench 场景](bench-feature-coverage.md) | 当前保留的可选场景、启用条件与运行命令 |
| [网络包架构](network-packet-architecture.md) | 当前本地协议、消息方向与服务端信任边界 |
| [中控台设计](control-console-design.md) | 元素、坐标、权威文档、范围与所有权约束 |
| [Pad 设计](pad-design.md) | 草稿与锁定副本、点位触发、同步和持久化 |
| [Scene Editor 集成](scene-editor-publishing.md) | 库版本、公开接口、持久化版本与 JiJ 结构校验 |

## 历史档案

旧设计方案、逐阶段实现账本和历史机器验收记录已收进[历史档案](archive/README.md)。其中的测试数量、绝对路径、已退休场景和后续计划不作为当前运行说明；需要复现时先核对源码版本与现存入口。
