# Real AV1 JNI smoke

`VideoJni.java` is a standalone JNI caller whose package and native method
names intentionally match production. It accepts the frozen Base64 fMP4
fixture, a raw fMP4 fragment, or an IVF sample and verifies:

- the selected AV1 backend;
- packet send and receive;
- explicit end-of-stream drain;
- exact final frame count and strictly increasing presentation timestamps;
- seek/reset flush after EOF.

The commands below run from the repository root. They compile a standalone caller and then execute native decoding on the local device; they are separate from the headless Java test suites. Use a JDK with `--enable-native-access` support (the device workflows use Java 21).

Compile and run it against a built native directory:

```text
javac -d /tmp/ncpb-native-smoke-classes \
  tools/native_av1_smoke/com/zhongbai233/net_music_can_play_bili/media/codec/VideoJni.java
java --enable-native-access=ALL-UNNAMED \
  -cp /tmp/ncpb-native-smoke-classes \
  com.zhongbai233.net_music_can_play_bili.media.codec.VideoJni \
  /path/to/native-directory \
  src/test/resources/bili/real-av1/init-index-first-fragment.m4s.b64 \
  videotoolbox
```

## Physical-device validation

[`verify_native_av1_smoke.py`](../verify_native_av1_smoke.py) wraps the same caller. The embedded bundle identified by [`native/README.md`](../../src/main/resources/native/README.md) has no AV1 software decoder. Requested and actual backends must match the platform backend: `videotoolbox` on macOS, `d3d11va` on Windows, or `vaapi` on Linux. The runner must match the selected OS/architecture and provide a GPU/driver with AV1 support.

For example, on a compatible macOS ARM64 machine:

```sh
python3 tools/verify_native_runtime.py --platform macos-arm64
python3 tools/verify_native_av1_smoke.py --platform macos-arm64 --hwaccel auto \
  --report build/reports/native-av1-device/macos-arm64.json
```

The first command loads the native libraries, checks their runtime identity and JNI exports; it does not decode AV1. The second command decodes both frozen fragments, checks EOF drain, 35-second seek PTS and native resource return to baseline, then records GPU/driver identity and the actual backend. It requires local native execution, not just metadata or fixture inspection.

The [single-device workflow](../../.github/workflows/native-av1-device-validation.yml) and [six-device workflow](../../.github/workflows/native-av1-device-matrix.yml) require dedicated self-hosted GPU runners. The latter also checks that all six platform reports are present and consistent. Hosted release checks only establish loadability/runtime identity/exports; the existence of these workflows does not mean that a current six-device hardware run has passed. See [release capabilities](../../docs/release-native-capabilities.md) for the published boundary.

## Historical exploratory IVF inputs (2026-08-13)

The caller also accepts IVF directly. The following public files were fetched
from `https://fate-suite.ffmpeg.org/av1/` on 2026-08-13. A single exploratory run at that time reported
no loss or duplication and zero EOF-delayed frames for these files; its version, machine and backend are not recorded here. This is historical sample evidence, not a result for the current checkout or every hardware backend:

| File | SHA-256 | Frames |
| --- | --- | ---: |
| `decode_model.ivf` | `956947f45c96ea1f944fc2aac646d9088a307e436b2afc0477b9ae627babc7cd` | 24 |
| `film_grain.ivf` | `40470898ac832513f2e0cce3d1d4e1c864b8a05ba79b5466167f2a65b03cd149` | 10 |
| `frames_refs_short_signaling.ivf` | `d9136e7e427a1ec1423823f1d8cee37ed0aa354d16bb6607feb5413436810918` | 50 |
| `non_uniform_tiling.ivf` | `c2bf1ba280ea19373a3a001b86b5ea4dbd0eacfb92011917b74866cb49ec90a7` | 24 |
| `seq_hdr_op_param_info.ivf` | `f2412e136f620c521633c9ca5037de86c68a2cad6b8c01932ca0e18a0df969f8` | 64 |
| `switch_frame.ivf` | `f5749d34c9076f5d123910c276b5d371ab0e3bcc70ece4cd5c229a561f05413e` | 32 |

These external samples are not release inputs and are not stored in the
repository. Verify the hash before using one as smoke evidence.
