# Embedded FFmpeg native libraries

The native libraries in this directory are sourced from:

- Release: `media-min-v27`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v27>
- Source commit: `1bc8edee1ae54f2793faee4b789ff7de044221b7`

Release asset SHA-256 checksums:

| Platform | Asset | SHA-256 |
| --- | --- | --- |
| Linux ARM64 | `ffmpeg-media-linux-arm64.tar.gz` | `3d72b2952bba4bf9eb52737c76d8abf5f3843bbb3ce63fbf4ec3dfdece30c63e` |
| Linux x86_64 | `ffmpeg-media-linux-x86_64.tar.gz` | `b0be2e9d4dbf5fdf8e32c8381cc2e97e9c4da888cb764e8953703a299fe3024f` |
| macOS ARM64 | `ffmpeg-media-macos-arm64.tar.gz` | `af93f0fc801c3dacb98add89c807f51b310dae8d1cb55be001efc40705c27033` |
| macOS x86_64 | `ffmpeg-media-macos-x86_64.tar.gz` | `3a6e1f6f7cc623a49fd2e84bfd63a86347edc5ea760275d1cb9fd5c0d0b9efda` |
| Windows ARM64 | `ffmpeg-media-windows-arm64.tar.gz` | `d141461c5d7456084c83404a65409a08bf974e7d268b0d700fc3c14be3d0233b` |
| Windows x86_64 | `ffmpeg-media-windows-x86_64.tar.gz` | `d0885c6d2aac0f179360397ad414d5e8182f4e2ff0b7deceb20b5e8b72767cb7` |

The archives were verified before extraction. Each platform directory is copied as a complete set; FFmpeg and JNI libraries must not be mixed between releases.