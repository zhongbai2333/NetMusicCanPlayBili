# Embedded FFmpeg native libraries

> **AV1 migration completed:** all six platform directories were replaced as
> one bundle from `media-min-v37`. This build contains complete H.264 software
> decoding plus platform hardware acceleration, and AV1 hardware acceleration.
> FFmpeg's built-in `av1` decoder does not provide native software decoding;
> this bundle does not include dav1d or libaom. HEVC decoder, parser, bitstream
> filter, and hardware acceleration paths remain explicitly excluded.

The native libraries in this directory are sourced from:

- Release: `media-min-v37`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v37>
- Source commit: `8027fb52e6a35f6e8889204d292a1f389d9cfb34`
- Upstream base: `1b1f6026990bd081ec6e2cef8cd88f60ddbfea66`

Release asset SHA-256 checksums:

| Platform | Asset | SHA-256 |
| --- | --- | --- |
| Linux ARM64 | `ffmpeg-media-linux-arm64.tar.gz` | `39cc79a012142326b78a4e102037537ba340ff88a233e756217a4ac46af4ac65` |
| Linux x86_64 | `ffmpeg-media-linux-x86_64.tar.gz` | `4a24cc64c484db66f3151c0e4cd5092f7fb2d7b3b3b5fe9d7167bc1d1d55a2e2` |
| macOS ARM64 | `ffmpeg-media-macos-arm64.tar.gz` | `e2ff4faca2df2673437a3264c2eb836caa6a331f47445f5fe5841660141e50a1` |
| macOS x86_64 | `ffmpeg-media-macos-x86_64.tar.gz` | `119d636326ee7d7bba86d581510a67ae19bc68cb01612c137802665b690457c8` |
| Windows ARM64 | `ffmpeg-media-windows-arm64.tar.gz` | `fb4707d69a552a0fa3acf81f8cec150736d733ff2cc587a571f29076dea4cf66` |
| Windows x86_64 | `ffmpeg-media-windows-x86_64.tar.gz` | `19909bfd7fb93a284bd122ab33ded7cde0f91ccb5549d0ef79dbeb37d6616b25` |

The archives were verified before extraction. Each platform directory is copied as a complete set; FFmpeg and JNI libraries must not be mixed between releases.

Every replacement platform directory must include the unmodified
`FFmpeg-LGPL-2.1.txt` shipped by the FFmpeg Actions artifact. This keeps the
license available both in each standalone native archive and under
`native/<platform>/` in the final mod JAR.

The corresponding FFmpeg source archive and `changes.diff` must be attached to
the same release/download location as the binary bundle. The source archive
must exactly match the source commit recorded above and include the build
workflow/configuration used for all six targets.

The macOS v37 libraries target macOS 11.0, use architecture-specific thin
Mach-O files, contain only portable system or `@loader_path` dependencies, and
are ad-hoc signed after install-name rewriting. Their embedded CodeDirectory
page hashes were independently verified before bundling.

The Linux v37 libraries enable VAAPI for both H.264 and AV1 on x86_64 and
ARM64. They dynamically depend on the host's `libva.so.2`, `libva-drm.so.2`,
and `libdrm.so.2`; these system/driver libraries are intentionally not bundled.

`libswresample` is intentionally not bundled. The media JNI wrappers consume
decoded planar-float audio directly and link only against `libavcodec`,
`libavutil`, and (for video conversion) `libswscale`. Windows builds also
include the required architecture-matched `libiconv-2.dll` and
`libwinpthread-1.dll` runtime libraries.
