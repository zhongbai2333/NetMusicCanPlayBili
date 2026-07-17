# Embedded FFmpeg native libraries

The native libraries in this directory are sourced from:

- Release: `media-min-v35`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v35>
- Source commit: `7b60dafa60f02b8cb5da18d80618b0ac21332245`

Release asset SHA-256 checksums:

| Platform | Asset | SHA-256 |
| --- | --- | --- |
| Linux ARM64 | `ffmpeg-media-linux-arm64.tar.gz` | `adabb30c2592f291008ce47185b4e31c9c81ddfe7e68ab24975ceb3423ffcb32` |
| Linux x86_64 | `ffmpeg-media-linux-x86_64.tar.gz` | `826b687adb06656938b29d9a9cedf233886ada6ad77504213e9ce0129fb8ac7f` |
| macOS ARM64 | `ffmpeg-media-macos-arm64.tar.gz` | `17e52f8158deff298a646546d67df2083f27d1d9ef9d6081e8f23d4ead78b5da` |
| macOS x86_64 | `ffmpeg-media-macos-x86_64.tar.gz` | `15a858c3079b0c3bd1c100c9fe83ad7ec1782815dd1bb407d17f8c82a1ad92bf` |
| Windows ARM64 | `ffmpeg-media-windows-arm64.tar.gz` | `f4fd1b23ce8ac9743b9ec7d017da208488dda72b5e4d889a7836500dd535996c` |
| Windows x86_64 | `ffmpeg-media-windows-x86_64.tar.gz` | `a5e3e39b419da47b191476b8887558a7ff364565ce9b502322c68c31ba891503` |

The archives were verified before extraction. Each platform directory is copied as a complete set; FFmpeg and JNI libraries must not be mixed between releases.

The macOS v35 libraries target macOS 11.0, use architecture-specific thin
Mach-O files, contain only portable system or `@loader_path` dependencies, and
are ad-hoc signed after install-name rewriting. Their embedded CodeDirectory
page hashes were independently verified before bundling.

`libswresample` is intentionally not bundled. The media JNI wrappers consume
decoded planar-float audio directly and link only against `libavcodec`,
`libavutil`, and (for video conversion) `libswscale`. Windows builds also
include the required architecture-matched `libiconv-2.dll` and
`libwinpthread-1.dll` runtime libraries.
