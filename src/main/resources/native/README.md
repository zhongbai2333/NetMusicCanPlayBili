# Embedded FFmpeg native libraries

> **AV1 migration completed:** all six platform directories were replaced as
> one bundle from `media-min-v38`. This build contains complete H.264 software
> decoding plus platform hardware acceleration, and AV1 hardware acceleration.
> FFmpeg's built-in `av1` decoder does not provide native software decoding;
> this bundle does not include dav1d or libaom. HEVC decoder, parser, bitstream
> filter, and hardware acceleration paths remain explicitly excluded.

The native libraries in this directory are sourced from:

- Release: `media-min-v38`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v38>
- Source commit: `3dbd6699c0eb649cc7eb53f2b57324db01779480`
- Upstream base: `1f276a42dbd693ef58222e2c1499d45691b49089`

Release asset SHA-256 checksums:

| Platform | Asset | SHA-256 |
| --- | --- | --- |
| Linux ARM64 | `ffmpeg-media-linux-arm64.tar.gz` | `15694f2e6e531162ba39fe8805357bd056c9ed1dcb18db0c771b6586dbabba6a` |
| Linux x86_64 | `ffmpeg-media-linux-x86_64.tar.gz` | `524965accaa0f15d3ff3fbc100144351ab0002fca553807497a9c7e2579ad74d` |
| macOS ARM64 | `ffmpeg-media-macos-arm64.tar.gz` | `ae2a65aa5a86e98a849dac39ddf4f2bbd7000f67cf34177740bb4bdf8fb0d8a1` |
| macOS x86_64 | `ffmpeg-media-macos-x86_64.tar.gz` | `2d07ff02d1d5ac0afddc50256182d1daa9c49a0b500fc65f9845aedd11dd45fa` |
| Windows ARM64 | `ffmpeg-media-windows-arm64.tar.gz` | `297ab142d6ccfc02269f750986362334097ae6d06b8e6daead2beb2e269629b6` |
| Windows x86_64 | `ffmpeg-media-windows-x86_64.tar.gz` | `69d71e563d7a9ecd6f623a287f8ac5016b0d46155dc91e6e2073c5a383324283` |

The archives were verified before extraction. Each platform directory is copied as a complete set; FFmpeg and JNI libraries must not be mixed between releases.

Every replacement platform directory must include the unmodified
`FFmpeg-LGPL-2.1.txt` shipped by the FFmpeg Actions artifact. This keeps the
license available both in each standalone native archive and under
`native/<platform>/` in the final mod JAR.

The corresponding FFmpeg source archive and `changes.diff` must be attached to
the same release/download location as the binary bundle. The source archive
must exactly match the source commit recorded above and include the build
workflow/configuration used for all six targets.

The macOS v38 libraries target macOS 11.0, use architecture-specific thin
Mach-O files, contain only portable system or `@loader_path` dependencies, and
are ad-hoc signed after install-name rewriting. Their embedded CodeDirectory
page hashes were independently verified before bundling.

The Linux v38 libraries enable VAAPI for both H.264 and AV1 on x86_64 and
ARM64. They dynamically depend on the host's `libva.so.2`, `libva-drm.so.2`,
and `libdrm.so.2`; these system/driver libraries are intentionally not bundled.

`libswresample` is intentionally not bundled. The media JNI wrappers consume
decoded planar-float audio directly and link only against `libavcodec`,
`libavutil`, and (for video conversion) `libswscale`. Windows builds also
include the required architecture-matched `libiconv-2.dll` and
`libwinpthread-1.dll` runtime libraries.
