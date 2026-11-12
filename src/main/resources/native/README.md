# Embedded FFmpeg native libraries

> **Hardware-only AV1 bundle:** all six platform directories come from `media-min-v50` as one indivisible bundle.
> AV1 requires the platform hardware backend; an unavailable or failed AV1 backend falls back to H.264 hardware
> or software decoding. No AV1 software decoder and no HEVC decoder are bundled.

- Release: `media-min-v50`
- Repository: <https://github.com/zhongbai2333/FFmpeg>
- Release URL: <https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v50>
- Source commit: `9a57f0a332a25d9f2c4a620fd5bbd5ce3bbbd972`
- Upstream base: `611bcc793fd8fce743fc6b3f784c0b9b8cf0d961`
- FFmpeg runtime version (linux-arm64): `git-2026-11-07-9a57f0a`
- FFmpeg runtime version (linux-x86_64): `git-2026-11-07-9a57f0a`
- FFmpeg runtime version (macos-arm64): `git-2026-11-07-9a57f0a`
- FFmpeg runtime version (macos-x86_64): `git-2026-11-07-9a57f0a`
- FFmpeg runtime version (windows-arm64): `8.0.git`
- FFmpeg runtime version (windows-x86_64): `8.0.git`

| Platform | Asset | SHA-256 |
| --- | --- | --- |
| Linux ARM64 | `ffmpeg-media-linux-arm64.tar.gz` | `e0014d9bd5d5908a7ed2d691848c07b7b942e19148504f874211a22ffc31f36b` |
| Linux x86_64 | `ffmpeg-media-linux-x86_64.tar.gz` | `ed249432e5e73c2564e05e9512a7a4f943a21e395bda70508d54d4e80f9d729c` |
| macOS ARM64 | `ffmpeg-media-macos-arm64.tar.gz` | `01e76e2a1ba01dd5d2399e51be6dbb17abf08357974b24a68495a17e7a64a8da` |
| macOS x86_64 | `ffmpeg-media-macos-x86_64.tar.gz` | `59a516bd38f7bd67ceec58465965125005ccf0c8681fa0bb37a8afe0a5ee62ce` |
| Windows ARM64 | `ffmpeg-media-windows-arm64.tar.gz` | `6eff633a10db34836f93d38a7c9e3e00e682142740e134e36c9f88cd50ddb4f3` |
| Windows x86_64 | `ffmpeg-media-windows-x86_64.tar.gz` | `ec5372ba3b14a3ff6108577756fb8b39c25259af42ab274f9cb663a6389fdb96` |

`native/SHA256SUMS` is the authoritative exact extracted-file manifest. Every platform contains the unmodified
`FFmpeg-LGPL-2.1.txt`. The release also carries the FFmpeg corresponding-source archive,
`changes.diff`, `BUILD-PROVENANCE.txt`, and its release-level `SHA256SUMS.txt`.

The libraries are architecture-specific and must not be mixed between releases. Linux dynamically requires the
host libva/libva-drm/libdrm dependency closure recorded by the release audit. The extracted ELF symbol-version
requirements require the host glibc to provide at least `GLIBC_2.38` on Linux ARM64
and at least `GLIBC_2.35` on Linux x86_64. Windows includes the matching winpthread
runtime; iconv is disabled. macOS uses thin Mach-O files with loader-relative dependencies.
