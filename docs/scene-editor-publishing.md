# 通用场景编辑器发布与集成

## Artifact

当前兼容线为 `1.0.0-beta.1`：

| 坐标 | 内容 | 运行层 |
|---|---|---|
| `com.zhongbai233.sceneeditor:scene-editor-core:1.0.0-beta.1` | 相机、投影、拾取、Gizmo、场景文档、命令栈和编辑会话 | 普通 Java；NeoForge 中为 `LIBRARY` |
| `com.zhongbai233.sceneeditor:scene-editor-minecraft:1.0.0-beta.1` | Minecraft 窗口、输入和后续客户端适配 | NeoForge `GAMELIBRARY` |

每个 publication 都生成主 JAR、sources JAR、Javadoc JAR、POM 和 Gradle module metadata。当前仓库把验证版本
发布到构建目录内的 Maven repository；连接正式 Maven 远端时复用相同 `mavenJava` publication，不改变坐标。

`scene-editor-core` 的公开面只依赖 Java 与 JOML。构建会同时扫描源文件和解析图，拒绝 Minecraft、NeoForge、
Mojang、LWJGL/OpenAL 及 `NetMusicCanPlayBili` 业务依赖。其公开/受保护 class、字段、构造器和方法签名由
`PublicApiCompatibilityTest` 与提交到仓库的基线逐项比较。

## Maven 宿主

```groovy
repositories {
    maven { url = uri("/path/to/repository") }
    mavenCentral()
}

dependencies {
    implementation "com.zhongbai233.sceneeditor:scene-editor-core:1.0.0-beta.1"
}
```

完整、无 Minecraft/媒体依赖的消费方位于 `examples/scene-editor-host`。它只使用 Maven 坐标，不使用根构建的
`project(...)` 依赖，因此可以检验 POM、传递 JOML 依赖和真实发布 JAR。

## NeoForge JiJ

宿主以 ModDevGradle 的 project JiJ 入口内嵌本仓模块。ModDevGradle 2.0.141 不接受 project dependency 上的
rich-version closure；添加该 closure 会污染 `Project.version`。因此构建先使用普通 `jarJar project(...)`，再只修改
生成的两个 Scene Editor descriptor，把允许范围固定为 `[1.0.0-beta.1,2.0.0)`。`verifySceneEditorJiJ` 检查：

- production 外层 JAR 不包含 `com/zhongbai233/scene_editor/**` class；
- 每个 group/artifact 只有一个 descriptor 和一个 nested JAR；
- artifact version、版本范围、模块名和 implementation version 精确匹配；
- core 的 `FMLModType` 为 `LIBRARY`；
- Minecraft adapter 的 `FMLModType` 为 `GAMELIBRARY`。

adapter 必须进入 game layer：它的方法签名引用转换后的 `net.minecraft.*` 类型。若误标为普通 `LIBRARY`，应用
classloader 会尝试再次加载 `Minecraft`，客户端将以 loader-constraint violation 崩溃。这个边界由真实
integrated-client 场景 `ncpb.scene-editor-library-smoke` 覆盖。

`phase8-fixtures/scene-editor-jij-host-a` 与 `scene-editor-jij-host-b` 是两个真正独立的 JavaFML 模组，两者都内嵌同一
core 坐标。静态门槛检查 nested JAR SHA-256 完全一致；专用服务器门槛同时加载两模组，并要求 FML 最终只提供一个
`SceneDocument.class` 身份和一个 class resource URL，不允许重复类或资源冲突。

## 版本与持久化

库采用语义版本：破坏公开 API 时提升主版本，兼容新增提升次版本。宿主世界/物品数据不属于库：

- 中控台当前为 `ControlConsoleDocument.CURRENT_SCHEMA_VERSION = 6`；
- 全息眼镜当前为 `HolographicGlassesItem.PERSISTENCE_SCHEMA_VERSION = 1`；
- core 的 `SceneDocument` 不包含序列化格式或 schema version；
- 修改 `scene_editor_version` 不得修改上述宿主常量，除非宿主同时提供显式迁移并更新冻结测试。

因此升级编辑器实现不会暗中重写中控台世界数据或全息眼镜物品 NBT。

## 可重复验证

```bash
# 发布 main/sources/Javadoc/POM/module metadata 到隔离 Maven repository
bash gradlew \
  :scene-editor-core:publishMavenJavaPublicationToPhase8VerificationRepository \
  :scene-editor-minecraft:publishMavenJavaPublicationToPhase8VerificationRepository \
  -PncpbBuildDirectory=/private/tmp/ncpb-scene-editor-maven --no-daemon

# 仅按公开 Maven 坐标运行第三方宿主
bash gradlew -p examples/scene-editor-host clean test run \
  -PsceneEditorRepository=/private/tmp/ncpb-scene-editor-maven/phase8-maven-repository --no-daemon

# production JiJ 结构及双宿主静态去重
bash gradlew verifySceneEditorJiJ verifySceneEditorJiJDedupeFixtures \
  -PncpbBuildDirectory=/private/tmp/ncpb-scene-editor-jij --no-daemon

# 双宿主运行时去重专用服务器
bash gradlew runSceneEditorJiJDedupeServerSelfTest \
  -PncpbBuildDirectory=/private/tmp/ncpb-scene-editor-jij --no-daemon

# integrated-client adapter 实际调用
bash gradlew runBenchClient -PenableModBench=true \
  -PmodBench.scenarios=ncpb.scene-editor-library-smoke \
  -PncpbBuildDirectory=/private/tmp/ncpb-scene-editor-client --no-daemon
```
