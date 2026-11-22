# Minecraft 26.2 / Vulkan migration

This is an in-progress port from `69be2d3fb5f11b0b079a34d7f77c3898b6a8133c`,
not a release or a claim of Vulkan runtime validation.

## Target

- Minecraft 26.2, NeoForge 26.2.0.88, Java 25
- BenchMod development branch: `codex/mc26.2` in `zhongbai2333/BenchMod`
- Compatibility is bounded to the 26.2 line; 26.3 is developed separately
- Official API reference: <https://docs.neoforged.net/primer/docs/26.2/>

## Required validation

Both OpenGL and **Force Vulkan** need independent reports recording the actual
backend. A requested Vulkan run that falls back to OpenGL is not a Vulkan pass.

1. Compile production, tests, and the matching BenchMod provider
2. Deterministic RGBA / YUV420 / NV12 color bars, including alpha and color order
3. Pad and MP4 offscreen GUI, world video translucency, and terrain preview
4. Resource reload, resize, repeated open/close, and allocation return to baseline
5. Existing native, packaged dependency, permission, and production isolation checks

PBO allocation is an OpenGL optimization metric, not a prerequisite for a
correct Vulkan upload. Vulkan must never call raw OpenGL APIs. CPU-backed FFmpeg
frames remain supported; no GPU-to-GPU zero-copy claim is made.

## Dependency compatibility

The existing mandatory NetMusic dependency is retained, not relabelled or
replaced with a stub. At migration start, the official Modrinth releases only
advertise Minecraft 26.1.2. A compatible real NetMusic release and validation of
the SceneEditor Minecraft adapter are gates before end-to-end support can be
claimed. Optional AreaControl/Iris/LuckPerms artifacts likewise need matching
26.2 releases before their integration matrix can pass.
