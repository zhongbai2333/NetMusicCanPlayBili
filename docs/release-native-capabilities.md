## 媒体解码能力与第三方许可

当前发布包内置 `media-min-v48` FFmpeg/JNI 动态库，覆盖 Linux、macOS 和 Windows 的 x86_64/ARM64：

- H.264 提供软件解码，并可在平台支持时使用硬件加速；
- AV1 仅支持平台硬件解码，不包含 dav1d 或 libaom 软件解码器；
- AV1 硬件后端不可用、画质超出设备能力或首帧失败时，会尝试较低画质 AV1，再回退到用户画质上限内的 H.264 硬件或软件解码；
- HEVC/H.265 decoder、parser、bitstream filter 与硬件加速路径均未打包，服务端返回的 HEVC 流不会进入候选计划；
- 实际 AV1 可用性取决于操作系统、GPU、驱动、视频 profile、位深和分辨率，不能保证所有设备或视频都能使用 AV1。

Linux bundle 动态依赖宿主机提供 `libva.so.2`、`libva-drm.so.2`、`libdrm.so.2`；缺少这些库时 bundle 可能在
加载阶段失败，即使只希望使用 H.264 软件回退。AV1/H.264 的 VAAPI 硬解还需要可用驱动。这些系统组件不会随
模组 JAR 分发。Linux ARM64 要求宿主 glibc 至少提供 `GLIBC_2.38` symbol version（通常为 glibc 2.38+），
Linux x86_64 至少提供 `GLIBC_2.35`（通常为 glibc 2.35+）；更老的发行版可能无法加载。普通用户不需要单独
安装系统 FFmpeg。

第三方与对应源码材料：

- FFmpeg 使用 LGPL v2.1 或更高版本。每个平台的完整文本位于 JAR 内
  `native/<platform>/FFmpeg-LGPL-2.1.txt`；
- AOMedia Patent License 1.0 位于 JAR 内 `META-INF/licenses/AOMedia-Patent-License-1.0.txt`；
- 汇总声明位于 JAR 内 `META-INF/THIRD-PARTY-NOTICES.md`；
- 精确源码提交、修改补丁、构建来源和六个平台归档校验和记录在 JAR 内 `native/README.md`；六个平台已提取
  文件的精确文件集与逐文件 SHA-256 记录在 `native/SHA256SUMS`。Gradle 与 tag release workflow 会同时验证
  source tree 和生产 JAR 的文件集/哈希，缺失、多余、混架构或内容漂移都会阻断发布。对应
  [media-min-v48 源码与构建发布](https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v48)。

AOMedia Patent License 包含互惠和防御性终止条件；随包提供该许可证不代表 AV1 在所有司法辖区绝对不存在
第三方专利主张。

## 发布策略与已知限制

`media-min-v48` 的六个平台归档已通过 GitHub hosted runner 的构建、动态加载、runtime identity、JNI 导出、
文件集、哈希和架构检查；主项目生产 JAR 仍是一份覆盖六个平台的离线通用包。hosted runner 结果只能证明
bundle 可加载且身份正确，不能替代所有物理 GPU/驱动上的 AV1 解码认证。当前发布采用“固定组合验证通过后先发布，
其余兼容性按问题报告补齐”的策略。

需要注意：

- B站正常会提供 H.264 兼容候选，AV1 硬解失败时可进入 H.264 硬解/软解；若特定响应没有可用 H.264，播放会明确失败，客户端不会下载或启用 AV1 软件 decoder；
- Linux 的 glibc 与 libva/libdrm 依赖属于加载前置条件，缺失时 H.264 软件回退也可能无法启动；
- 同一次发布必须原子更新六个平台 native、`native/README.md`、`native/SHA256SUMS`、许可证和对应源码材料，禁止只替换某个平台或单个动态库；
- 用户报告视频问题时，应附操作系统/架构、GPU/驱动、Linux 发行版与 glibc/libva 信息、BV/画质、requested/actual codec/backend、fallback reason 和资源关闭诊断；
- 出现旧 decoder 关闭超时或异常时应保持 fail-closed；不能为了自动恢复而允许新旧 native context 并存。

字段级配置拒绝原因、更宽的 GPU/驱动/模组矩阵和 GitHub Actions 运行时告警清理属于发布后改进，不是当前
v48 的功能阻断项。
