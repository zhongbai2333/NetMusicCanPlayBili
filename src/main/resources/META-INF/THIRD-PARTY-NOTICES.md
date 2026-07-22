# Third-party notices

## FFmpeg

This software uses libraries from the FFmpeg project under the GNU Lesser
General Public License version 2.1 or later.

- Project: https://ffmpeg.org/
- License: `native/<platform>/FFmpeg-LGPL-2.1.txt`
- Exact source and build provenance: `native/README.md`
- FFmpeg legal and compliance guidance: https://ffmpeg.org/legal.html

FFmpeg is not owned by the NetMusicCanPlayBili project. The embedded libraries
are dynamically loaded shared libraries and are built without `--enable-gpl`
and without `--enable-nonfree`.

## AV1 patent license

The embedded FFmpeg build implements AV1 decoding. The Alliance for Open Media
Patent License 1.0 is reproduced at
`META-INF/licenses/AOMedia-Patent-License-1.0.txt`.

The patent license contains reciprocity and defensive-termination conditions.
Its inclusion is not a representation that AV1 is free from every third-party
patent claim in every jurisdiction.