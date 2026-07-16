# Embedded FFmpeg native libraries

The native libraries in this directory are sourced from:

- Release: `media-min-v34`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v34>
- Source commit: `9ee517059dc158de7928a640756d9ad9769089eb`

Release asset SHA-256 checksums:

| Platform | Asset | SHA-256 |
| --- | --- | --- |
| Linux ARM64 | `ffmpeg-media-linux-arm64.tar.gz` | `4cd833c6bd0b3d98827aa7454ac66006ec02a96e13e728b13369d00d3ae38ac4` |
| Linux x86_64 | `ffmpeg-media-linux-x86_64.tar.gz` | `abddfb0bf517b911cb04296ec0f24ff508bdf7332641cee524070496e8202f50` |
| macOS ARM64 | `ffmpeg-media-macos-arm64.tar.gz` | `28db3b8eff925d06d17ff8e6735b0beb6e063e7244377626aef3ca5627992682` |
| macOS x86_64 | `ffmpeg-media-macos-x86_64.tar.gz` | `123806577d3460e54e01cd2edc1d083f124146f08a55e53404c4ba322f0b5cd6` |
| Windows ARM64 | `ffmpeg-media-windows-arm64.tar.gz` | `ec9377976d0aeed3a288011551280c4c9eb442f3b0f074f2bea095aecb473dc1` |
| Windows x86_64 | `ffmpeg-media-windows-x86_64.tar.gz` | `c3d99aecee29257bb217f611d9118cc6176063cb0e3e37064dd2ba695e08e6ab` |

The archives were verified before extraction. Each platform directory is copied as a complete set; FFmpeg and JNI libraries must not be mixed between releases.

`libswresample` is intentionally not bundled. The media JNI wrappers consume
decoded planar-float audio directly and link only against `libavcodec`,
`libavutil`, and (for video conversion) `libswscale`. Windows builds also
include the required architecture-matched `libiconv-2.dll` and
`libwinpthread-1.dll` runtime libraries.
