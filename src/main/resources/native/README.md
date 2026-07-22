# Embedded FFmpeg native libraries

> **AV1 migration completed:** all six platform directories were replaced as
> one bundle from `media-min-v36`. This build contains H.264 + AV1 decoding and
> explicitly excludes the HEVC decoder, parser, bitstream filter, and hardware
> acceleration paths in its GitHub Actions configuration checks.

The native libraries in this directory are sourced from:

- Release: `media-min-v36`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v36>
- Source commit: `0ee789bda4acad75fff8598eb8408a145244b6c7`
- Upstream base: `1b1f6026990bd081ec6e2cef8cd88f60ddbfea66`

Release asset SHA-256 checksums:

| Platform | Asset | SHA-256 |
| --- | --- | --- |
| Linux ARM64 | `ffmpeg-media-linux-arm64.tar.gz` | `e6126579eb211ea4eabcde88487581f05b959516dd735ac7945fcd0324e2adfc` |
| Linux x86_64 | `ffmpeg-media-linux-x86_64.tar.gz` | `a28a718779124e9348726c32298f9b1af449852cbdd5694474b75ef9f5c8a3d7` |
| macOS ARM64 | `ffmpeg-media-macos-arm64.tar.gz` | `2222c73ae957070a7d5991b69f1741109997a9baff3b1cd1dcbcb8a3d6d7fc87` |
| macOS x86_64 | `ffmpeg-media-macos-x86_64.tar.gz` | `0d661c09bf36bddec02f2bac91a5796087f5ae0342c31ee94c20f4979bd2eb26` |
| Windows ARM64 | `ffmpeg-media-windows-arm64.tar.gz` | `8cbac49138372b8f6ddcd9dcebb68afa2cf9ef244f141822097590d3544b3956` |
| Windows x86_64 | `ffmpeg-media-windows-x86_64.tar.gz` | `73236032ed7c65934d9103707f1c0e9291f1fabe94a727d55767cba27e5bacda` |

The archives were verified before extraction. Each platform directory is copied as a complete set; FFmpeg and JNI libraries must not be mixed between releases.

Every replacement platform directory must include the unmodified
`FFmpeg-LGPL-2.1.txt` shipped by the FFmpeg Actions artifact. This keeps the
license available both in each standalone native archive and under
`native/<platform>/` in the final mod JAR.

The corresponding FFmpeg source archive and `changes.diff` must be attached to
the same release/download location as the binary bundle. The source archive
must exactly match the source commit recorded above and include the build
workflow/configuration used for all six targets.

The macOS v36 libraries target macOS 11.0, use architecture-specific thin
Mach-O files, contain only portable system or `@loader_path` dependencies, and
are ad-hoc signed after install-name rewriting. Their embedded CodeDirectory
page hashes were independently verified before bundling.

`libswresample` is intentionally not bundled. The media JNI wrappers consume
decoded planar-float audio directly and link only against `libavcodec`,
`libavutil`, and (for video conversion) `libswscale`. Windows builds also
include the required architecture-matched `libiconv-2.dll` and
`libwinpthread-1.dll` runtime libraries.
