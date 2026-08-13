# media-min-v48 native source state

The current embedded bundle is built from FFmpeg fork commit
`3b3d6f46bbd34049fcac013d743d75e953452431` and released as
[`media-min-v48`](https://github.com/zhongbai2333/FFmpeg/releases/tag/media-min-v48).
Its upstream base is `b397eba2f0d3d86daf1098d0f27daffccc74fea5`.

The v48 build intentionally keeps AV1 hardware-only:

- `video_jni.c` opens FFmpeg's native AV1 decoder only with a platform hardware
  backend and rejects `none`/`off`, allowing the Java candidate loop to move to
  H.264 hardware or software decoding;
- H.264 software decoding remains bundled;
- dav1d, libaom, HEVC and iconv are disabled and absent from the archives;
- Windows bundles only the matching MinGW winpthread runtime;
- all six archives include the FFmpeg LGPL text, exact runtime provenance and
  Linux GLIBC symbol floors.

The release workflow completed successfully for Linux, macOS and Windows on
ARM64 and x86_64. The consumer validates release-level hashes, FFmpeg
corresponding source, the exact extracted file set, binary architecture,
runtime version, JNI exports and JAR packaging. Hosted runners do not claim AV1
decode success because GPU/driver availability is not guaranteed; the separate
self-hosted device workflows retain the real AV1 decode/seek/resource evidence
gate.

`media-min-v39-dav1d-eof-drain.patch` is retained as historical migration
evidence for the earlier dav1d experiment. It is not applied to v48 and its
software-AV1 behavior is not part of the current mod release.
