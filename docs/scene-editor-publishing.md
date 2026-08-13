# 通用场景编辑器发布与集成

Scene Editor 已从主模组拆出为独立项目：

- 源码与发布仓库：<https://github.com/zhongbai2333/SceneEditor>
- 当前兼容版本：`1.0.0-beta.3`
- 发布入口：JitPack

## Artifact

| 坐标 | 内容 | NeoForge 运行层 |
|---|---|---|
| `com.github.zhongbai2333.SceneEditor:scene-editor-core:1.0.0-beta.3` | 相机、投影、拾取、Gizmo、场景文档、命令栈和编辑会话 | `LIBRARY` |
| `com.github.zhongbai2333.SceneEditor:scene-editor-minecraft:1.0.0-beta.3` | Minecraft 窗口、输入和 viewport adapter | `GAMELIBRARY` |

每个 publication 都包含主 JAR、sources JAR、Javadoc JAR、POM 和 Gradle module metadata。core 的公开面只依赖
Java 与 JOML；Minecraft adapter 单独进入转换后的 game layer。

## 普通 Maven 宿主

```groovy
repositories {
    maven { url = uri('https://jitpack.io') }
    mavenCentral()
}

dependencies {
    implementation 'com.github.zhongbai2333.SceneEditor:scene-editor-core:1.0.0-beta.3'
}
```

不依赖 Minecraft 或媒体业务的完整示例已迁至 SceneEditor 仓库的 `examples/scene-editor-host`。

## NetMusicCanPlayBili 的 NeoForge JiJ

主模组不再包含 Scene Editor 子项目源码，而是直接编译并内嵌同一份不可变 JitPack release：

```groovy
jarJar(api("com.github.zhongbai2333.SceneEditor:scene-editor-core:${scene_editor_version}")) {
    version {
        strictly '[1.0.0-beta.3,2.0.0)'
        prefer scene_editor_version
    }
}
jarJar(implementation("com.github.zhongbai2333.SceneEditor:scene-editor-minecraft:${scene_editor_version}")) {
    version {
        strictly '[1.0.0-beta.3,2.0.0)'
        prefer scene_editor_version
    }
}
```

最低版本设为 `beta.3`，使 NCPB 与其他 Scene Editor 宿主统一使用包含相机极点钳制、事务修复及共享交互策略的
同一兼容基线。`beta.1` 的运行时 group 常量仍是迁移前值，继续不得参与多宿主版本协商。

`verifySceneEditorJiJ` 检查：

- production 外层 JAR 不包含 `com/zhongbai233/scene_editor/**` class；
- 两个 artifact 各只有一个 JiJ descriptor 和一个 nested JAR；
- artifact version、协商范围、模块名、implementation version 与运行层精确匹配；
- core 为 `LIBRARY`，Minecraft adapter 为 `GAMELIBRARY`。

双宿主 JiJ fixture、普通 Maven 示例和库自身 API/隔离测试均归独立 SceneEditor 仓库维护。主项目只保留真实宿主
集成、自检和最终 production JAR 结构门槛。

## 版本与持久化

Scene Editor 采用独立语义版本。主模组版本、世界数据与物品数据不会随库版本自动提升：

- 中控台当前为 `ControlConsoleDocument.CURRENT_SCHEMA_VERSION = 6`；
- 全息眼镜当前为 `HolographicGlassesItem.PERSISTENCE_SCHEMA_VERSION = 1`；
- core 的 `SceneDocument` 不包含主模组持久化 schema。

本次迁移仅把库的构建与发布边界移出主仓库，不提升 NetMusicCanPlayBili 的 Mod 版本。

## 可重复验证

```bash
# SceneEditor 独立项目：构建、测试并发布到本地 Maven
./gradlew build \
  :scene-editor-core:publishToMavenLocal \
  :scene-editor-minecraft:publishToMavenLocal --no-daemon

# 主项目：解析 JitPack release 并验证 production JiJ
bash gradlew clean build verifySceneEditorJiJ --refresh-dependencies --no-daemon

# 主项目 integrated-client adapter 实际调用
bash gradlew runBenchClient -PenableModBench=true \
  -PmodBench.scenarios=ncpb.scene-editor-library-smoke --no-daemon
```
