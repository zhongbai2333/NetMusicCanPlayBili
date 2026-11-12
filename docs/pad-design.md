# Pad 当前设计

Pad 是地图式定点媒体触发器。媒体库用稳定 `mediaId` 标识唱片，地图点位用 `pointId` 绑定媒体，播放入口按 `pointId → mediaId → disc` 查找；它不使用 MP4 的队列选择模型。玩家操作见[游玩指南](play-guide.md)，验收见[人工检查单](testing/manual-release-checklist.md)。

## 草稿与发布副本

草稿可添加、移除媒体，拖入媒体创建点位，并调整点位的位置、可见性、触发方式、半径、循环和音量。
发布通过 `PadPublishPacket` 生成共享同一 `deviceId` 的锁定副本，草稿继续保留。锁定状态属于具体物品，不能通过修改锁定副本编辑媒体库或点位。

同 ID 的草稿与副本共享权威文档。`author` 是文档显示信息，不是独立的作者 ACL；服务端按玩家持有的对应设备和草稿状态校验编辑。绑定耳机或全息眼镜沿用现有媒体设备绑定服务。

## 文档与持久化

| 内容 | 实现 |
| --- | --- |
| 文档和媒体/点位字段约束 | `item/pad/PadDocument`、`PadMediaEntry`、`PadTriggerPoint` |
| 运行期文档与 SavedData 桥接 | `network/PadDocumentStore` |
| 世界保存 | `network/PadDocumentSavedData` |
| 物品身份、锁定状态和文档镜像 | `item/PadItem` |

世界 `SavedData` 保存以 `deviceId` 为键的权威文档，物品也保留标题、媒体、点位和版本等镜像。恢复和读取会比较 `updatedAtMillis`、`sequence`，运行期条目与物品副本通过 `PadDocumentStore` 协调；修改保存逻辑时同时检查物品副本、世界存储和旧数据迁移。

媒体编号删除后不重排；点位引用不存在的媒体时，不应悄悄重绑到别的唱片。媒体和点位容量以 `PadDocument` 的约束为准，文档不再另列一套初版配额。

## 编辑同步

客户端通过 `PadStatePacket` 提交合并后的编辑，服务端通过 `PadStateMirrorPacket` 发送权威镜像。
`PadDeviceHolderTracker` 负责持有者扫描、镜像和自动触发；客户端编辑版本与服务端版本共同防止旧快照覆盖新内容。

网络 handler 需要保留持有设备、锁定状态、限流、版本和输入约束校验。具体注册和方向见[网络包架构](network-packet-architecture.md)。

## 手动与进入半径触发

- 手动点位由玩家点击触发。
- 锁定副本的 `ENTER_RADIUS` 已实现：持有者扫描从半径内的自动点位中选择最近者，并限制重复切换。
- 隐藏点位在锁定副本的地图上不显示，但仍可参与自动触发。
- 点位半径用于触发判断，不等同于中控台 hardRange 的消费退出规则。

`PadPlaybackControlPacket` 和 `PadPlaybackRetryPacket` 负责播放/刷新准入，Pad 有自己的解析意图所有权。媒体播放最终经 `ServerMediaPlayback` 进入共享服务端播放协调，客户端复用现有音视频链路。

回调必须验证仍为当前点位、媒体、设备和请求；迟到结果不能启动已停止或已替换的播放。刷新直链可保留逻辑会话，替换实际传输。Pad 标题不可用时仍保留点位名称回退。

## 地图和表面交互

Pad 的地图与媒体界面渲染在物品表面，`PadFocusScreen` 接收输入并映射到表面坐标；当前交互不以独立全屏地图窗口为入口。媒体库拖动用于创建点位，拖动点位用于修改世界坐标。

地图显示、采样和缓存分别由现有客户端地图组件负责，主要入口是 `PadMapClientCache`、`PadMapSampler` 和物品表面 renderer。世界读取、任务换代、磁盘缓存作用域和迟到写入不能混入 GUI 绘制路径。

通用小地图的 region、rolling texture、radar 或道路识别方案只属于[历史设计参考](archive/minimap-mod-architecture.md)，不能据此声称 Pad 已实现这些功能。当前验收关注地图刷新、楼层/室内切换、发布副本、隐藏点位和播放，而非旧阶段编号。

## 维护边界

保留独立的文档/设备身份和异步解析所有权；复用已有媒体同步、绑定和播放组件。不要为 Pad 再建一套播放器或按点位拆出大量协调接口。

涉及存档、设备身份或同步规则时运行对应平台测试；交互、地图显示和真实播放按[开发与验证](development.md)及人工检查单执行。
