# Real AV1 JNI smoke

`VideoJni.java` is a standalone JNI caller whose package and native method
names intentionally match production. It accepts the frozen Base64 fMP4
fixture, a raw fMP4 fragment, or an IVF sample and verifies:

- the selected AV1 backend;
- packet send and receive;
- explicit end-of-stream drain;
- exact final frame count and strictly increasing presentation timestamps;
- seek/reset flush after EOF.

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

## Six-platform release gate

`tools/verify_native_av1_smoke.py` wraps the same caller for physical-device validation. The v48 bundle has no AV1
software decoder, so the requested and actual backend must match the platform backend (`videotoolbox`, `d3d11va`,
or `vaapi`). Hosted release runners only load the libraries and verify JNI exports because they do not guarantee AV1
hardware. The separate self-hosted device workflows decode both frozen fragments, validate PTS and resource convergence,
and collect GPU identity evidence.

## Optional FFmpeg FATE IVF inputs

The caller also accepts IVF directly. The following public files were fetched
from `https://fate-suite.ffmpeg.org/av1/` on 2026-08-13. They all completed
with no loss or duplication and naturally had zero EOF-delayed frames:

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
