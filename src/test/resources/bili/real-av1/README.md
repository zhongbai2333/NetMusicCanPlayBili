# Real Bilibili AV1 fMP4 fixture

This directory contains two Base64-encoded excerpts from the public DASH video
identified in `fixture.json`. They are exact CDN bytes, not hand-built MP4
boxes:

- bytes `0-117149`: `ftyp`/`moov`, the complete `sidx`, and its first media
  reference;
- bytes `900893-962851`: the complete `sidx` reference that begins at 35s.

The excerpts exist solely for deterministic parser, seek, codec-config, sample
boundary, and timestamp interoperability tests. No signed CDN URL, account
cookie, audio track, or complete video is stored. `fixture.json` records source
identity, exact ranges, decoded sizes, and SHA-256 values. To independently
re-fetch and verify the bytes using a fresh public playurl response:

```text
python3 tools/fetch_bilibili_av1_fixture.py \
  --output-directory /tmp/ncpb-real-av1-fixture
```

Any hash or SegmentBase change is a review event; the fetcher must not silently
replace the frozen test data.
