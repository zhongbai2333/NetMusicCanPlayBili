# media-min-v39 staged native source patch

`media-min-v39-dav1d-eof-drain.patch` applies to FFmpeg fork commit
`3dbd6699c0eb649cc7eb53f2b57324db01779480` (`media-min-v38`). It changes
four upstream-owned files as one release unit:

- `video_jni.c`: selects FFmpeg's native `av1` decoder for hardware requests,
  selects `libdav1d` only for `none`/`off` software requests, and exports the
  explicit `sendEndOfStream` JNI entry point;
- `build_media.sh`: requires a pinned static dav1d 1.5.4 installation and
  audits that no dynamic dav1d dependency enters the runtime bundle;
- `.github/workflows/build.yml`: builds dav1d commit
  `54706fc6bc0cdecab7e9593974a4039cc038fca7` as PIC static code on all six
  target runners, verifies both AV1 decoders and the EOF JNI export, packages
  the BSD-2-Clause license, publishes dav1d corresponding source, and records
  the exact FFmpeg runtime identity plus both Linux GLIBC symbol floors in
  release provenance. Windows builds install Meson, Ninja, and UPX inside the
  selected MSYS2 environment instead of crossing shell PATH boundaries or
  depending on Chocolatey. Windows exports dav1d's pkg-config directory as a
  native mixed-style path, performs a real static `dav1d_version()` link check
  before FFmpeg configuration, and uploads `ffbuild/config.log` when
  configuration fails. Linux records its GLIBC symbol floor before UPX and
  passes the validated value to the release job as build metadata instead of
  trying to reconstruct ELF version sections after compression;
- `.github/workflows/probe-native-size.yml`: keeps the native size probe on
  current Node 24-based official checkout infrastructure.

The GitHub source tarball of that dav1d commit has SHA-256
`9edb11a2108b375cc58370354e705feebc93430bb780130363815c1e1ac0c250`.
The workflow checkout uses the commit rather than a mutable branch or tag.

Verification performed on 2026-08-13:

```text
patch --batch --dry-run -p1 -i media-min-v39-dav1d-eof-drain.patch
  video_jni.c                         applied
  build_media.sh                      applied
  .github/workflows/build.yml         applied
  .github/workflows/probe-native-size.yml applied

bash -n build_media.sh                passed
Ruby Psych YAML parse                 passed
actionlint 1.7.12                     0 errors
C syntax after FFmpeg configure       passed
reverse apply against current FFmpeg tree passed
```

A local macOS ARM64 build additionally proved the actual native path using the
frozen public Bilibili fixture in `src/test/resources/bili/real-av1`:

```text
requested=none
actual=cpu(libdav1d)
packets=125
framesBeforeEofDrain=125
framesDrainedAtEof=0
framesAfterEofDrain=125
firstPts=0
lastPts=4960000000
```

The locally built libraries were thin ARM64 Mach-O files targeting macOS 11.0,
had only system/framework or `@loader_path` dependencies, contained no dav1d
dylib dependency, and passed ad-hoc code-signature verification. A later
production integrated-client run on the same architecture reported actual
`videotoolbox`, completed a 0 to 35 second SIDX Range Seek, and converged all
resource diagnostics; the older standalone rejection is retained only as a
negative observation for that particular direct-smoke configuration.

A separate macOS x86_64 release build used a pinned Temurin 21 x86_64 JDK and
the same dav1d commit. Rosetta loaded all five dylibs, reported the exact
`git-2026-08-13-669aa53` runtime and 5 EAC3 / 18 Video JNI exports, and decoded
the frozen fragment as `cpu(libdav1d)` with 125 packets, 125 frames and strictly
increasing PTS. Rosetta rejected `videotoolbox` AV1 at the first packet, so that
result is a negative x86_64 host/device observation rather than hardware success.

This particular low-delay fragment has no decoder-delayed tail frame
(`framesDrainedAtEof=0`). It proves that the new EOF JNI symbol and reset path
execute without losing or duplicating any of its 125 frames, but it does not by
itself close the separate delayed-output test requirement. The test-only
overlay and standalone caller in `tools/native_av1_smoke/` deterministically
force libdav1d frame delay without changing this release patch. On the same
macOS ARM64 build, the frozen fragment then produced 118 frames before EOF and
7 during EOF drain, for exactly 125 frames with strictly increasing PTS.

This patch and the two macOS architecture build evidence do not replace the release gates.
Do not enable the Java bundled-software capability flag until a tagged v39
release has produced all six archives, the whole embedded bundle and
`native/SHA256SUMS` have been replaced together, every runtime smoke is green,
and the six-device validation matrix has been recorded.

The consuming importer now also rejects any `media-min-v39` release whose
`BUILD-PROVENANCE.txt` does not exactly identify source commit
`669aa5300a4b6ca91dd60632856c6dca1b63de70`, upstream base
`1f276a42dbd693ef58222e2c1499d45691b49089`, and runtime
`git-2026-08-13-669aa53`. Correctly formatted but different identities do not
pass. After import, the consuming repository's six-platform runtime workflow
loads every bundle and decodes the frozen 125-packet sample through JNI with
actual backend `cpu(libdav1d)` before a mod release can be published.
