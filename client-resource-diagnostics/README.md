# Client Resource Diagnostics

独立、仅客户端的 NeoForge 26.1.2 诊断 Mod，用于关联：

- 100ms 进程 committed virtual memory 突增；
- Mojang `GlBuffer` / `GlTexture` 的逻辑容量和生命周期；
- `LevelRenderer.setBlocksDirty` 的调用来源与受影响 section 数。

## 构建与安装

在本目录使用上级 Gradle Wrapper：

```powershell
..\gradlew.bat build
..\gradlew.bat installToMainRun
```

## 命令

- `/clientdiag` 或 `/clientdiag status`：聊天栏显示前 8 个 owner。
- `/clientdiag report`：写入 `run/client-resource-diagnostics/report-*.txt`。
- `/clientdiag reset`：重置 spike、dirty 和峰值窗口，保留当前 GPU 存活资源。

## 覆盖边界

这是相关性诊断器，不是显存计费器。GPU 字节数是纹理/缓冲的逻辑容量；不包括裸 LWJGL
调用、驱动对齐、staging、command buffer、residency、WDDM committed 或第三方 native allocator。
所有表和环形缓冲都有固定上限，tracker 不保留被诊断 GPU 对象的强引用。