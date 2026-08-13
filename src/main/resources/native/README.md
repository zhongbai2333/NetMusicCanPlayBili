# Embedded FFmpeg native libraries

> **Hardware-only AV1 bundle:** all six platform directories come from `media-min-v48` as one indivisible bundle.
> AV1 requires the platform hardware backend; an unavailable or failed AV1 backend falls back to H.264 hardware
> or software decoding. No AV1 software decoder and no HEVC decoder are bundled.

- Release: `media-min-v48`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v48>
- Source commit: `3b3d6f46bbd34049fcac013d743d75e953452431`
- Upstream base: `b397eba2f0d3d86daf1098d0f27daffccc74fea5`
- FFmpeg runtime version (linux-arm64): `git-2026-08-13-3b3d6f4`
- FFmpeg runtime version (linux-x86_64): `git-2026-08-13-3b3d6f4`
- FFmpeg runtime version (macos-arm64): `git-2026-08-13-3b3d6f4`
- FFmpeg runtime version (macos-x86_64): `git-2026-08-13-3b3d6f4`
- FFmpeg runtime version (windows-arm64): `8.0.git`
- FFmpeg runtime version (windows-x86_64): `8.0.git`

| Platform | Asset | SHA-256 |
| --- | --- | --- |
| Linux ARM64 | `ffmpeg-media-linux-arm64.tar.gz` | `0f5ed96d494a9d3c0e7c1b88df932a744a107d37bbb2cbf1d117aff0ff837c2b` |
| Linux x86_64 | `ffmpeg-media-linux-x86_64.tar.gz` | `a1713e8f85459262447c35b3d64667a37aa5c95f64bc23a727d6f0f09d00da82` |
| macOS ARM64 | `ffmpeg-media-macos-arm64.tar.gz` | `f7bd26b96bd7285b60f589a7ab9b4da2d54fdcada8a0bfc5362ddac56cee9c9c` |
| macOS x86_64 | `ffmpeg-media-macos-x86_64.tar.gz` | `b612d32f24c045d0cbaed5f2f82e63b7ea583e752f539f190cf8479391d146e6` |
| Windows ARM64 | `ffmpeg-media-windows-arm64.tar.gz` | `0362bf74e14e06936557497bed13e6402ef3cb518b6e4d9a1399bae8458d8a18` |
| Windows x86_64 | `ffmpeg-media-windows-x86_64.tar.gz` | `e612d28a651ab0798ab356abb05526174092cb0e4df7850c8d02a971067dba8d` |

`native/SHA256SUMS` is the authoritative exact extracted-file manifest. Every platform contains the unmodified
`FFmpeg-LGPL-2.1.txt`. The release also carries the FFmpeg corresponding-source archive,
`changes.diff`, `BUILD-PROVENANCE.txt`, and its release-level `SHA256SUMS.txt`.

The libraries are architecture-specific and must not be mixed between releases. Linux dynamically requires the
host libva/libva-drm/libdrm dependency closure recorded by the release audit. The extracted ELF symbol-version
requirements require the host glibc to provide at least `GLIBC_2.38` on Linux ARM64
and at least `GLIBC_2.35` on Linux x86_64. Windows includes the matching winpthread
runtime; iconv is disabled. macOS uses thin Mach-O files with loader-relative dependencies.
