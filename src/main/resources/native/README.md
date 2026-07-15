# Embedded FFmpeg native libraries

The native libraries in this directory are sourced from:

- Release: `media-min-v26`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v26>
- Source commit: `cb0e3f2d225a533bbf679a494516ae80e397a6f5`

Release asset SHA-256 checksums:

| Platform | Asset | SHA-256 |
| --- | --- | --- |
| Linux ARM64 | `ffmpeg-media-linux-arm64.tar.gz` | `22dff4ae2661d2972a03ec0e12106527f6eff1d30893a920ff0c2e0929c02503` |
| Linux x86_64 | `ffmpeg-media-linux-x86_64.tar.gz` | `dedfdf006a49a929085dbaa187efe4986006fed0c673202ca99fe449af74457d` |
| macOS ARM64 | `ffmpeg-media-macos-arm64.tar.gz` | `fef5c4d44a653a976ad29430533fdc062c51e14562f74ac61af0309a2bf55b3a` |
| macOS x86_64 | `ffmpeg-media-macos-x86_64.tar.gz` | `0673c6b9e6978de5e87cd3e6c788a8bb335830936ab82cb6557b0c3d1cf71128` |
| Windows ARM64 | `ffmpeg-media-windows-arm64.tar.gz` | `1c406142dc8a2327bfb9374116498b820fabe9637274ee456961eb74c06734a1` |
| Windows x86_64 | `ffmpeg-media-windows-x86_64.tar.gz` | `b9990d8d651061e273708b2070dab5703684deb978b6e61a063b64c6d58c54cb` |

The archives were verified before extraction. Each platform directory is copied as a complete set; FFmpeg and JNI libraries must not be mixed between releases.