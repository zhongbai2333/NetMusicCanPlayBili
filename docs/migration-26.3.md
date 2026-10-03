# Minecraft 26.3 / Vulkan migration (draft)

Target: Minecraft 26.3, NeoForge 26.3.0.45-beta, Java 25, ModDevGradle 2.0.148.
The metadata range is limited to 26.3; this branch is not a release or a claim of 26.4 compatibility.

## Implemented first stage

- Replace raw GL_RG8 storage and PBO/fence uploads with Renderpearl R8_UNORM / RG8_UNORM textures and CommandEncoder uploads. RGBA8 UV expansion remains available when RG8 creation fails or is disabled.
- Reuse owned, tracked native CPU staging buffers. Direct decoder ByteBuffers no longer become a newly allocated heap byte[] every frame. The source cursor/limit remain unchanged.
- Keep FFmpeg decode/readback, formats, frame timing, native bundles and capability gates unchanged. This is renderer portability, not Vulkan Video or hardware zero-copy.
- Port custom shader includes and exact interface locations to 26.3's entity vertex shader. Add alpha-only and accumulation OIT variants and register all three OIT stages.
- Use withForcedSolidModelPhase instead of overriding hasBlending. Remove the obsolete mandatory GlDevice compiler mixin; it targets a removed backend-private compiler/cache API.
- Require the deterministic upload benchmark to allocate/release tracked staging and reject raw OpenGL PBO allocations. Decoder/native test requirements remain in place.

## Verification so far

- Official 26.3 client artifact inspected with javap; SHA-1 e877b6a07acd633fb3bb475002175cec036e7b87. The public RG8_UNORM format and upload signatures are real target APIs.
- VulkanCommandEncoder.writeToTexture copies the supplied CPU bytes into transient staging before returning; frame submission remains owned by Minecraft.
- Five VideoPlanePacking JUnit tests pass under Java 25: direct copy, UV expansion, buffer reuse, short input, dimension overflow and source cursor preservation.
- Full compile and remaining GUI/terrain/input API migration are in progress. No successful client launch or Vulkan/OpenGL visual benchmark is claimed.

## External integration gates

NetMusic is mandatory. As checked on 2026-10-03, Modrinth lists no NetMusic 26.3 artifact; the retained development dependency mCrjhdSq is a 26.1.2 build, not a compatible runtime assertion. SceneEditor 1.0.1-beta.2's Minecraft adapter and the pinned Iris compile API also target 26.1. Their 26.3 integration must be rebuilt/verified before this PR can leave draft. Do not remove loader gates or relabel old artifacts to force a launch.

## Required acceptance matrix

Run both OpenGL and Vulkan: RGBA/YUV420/NV12 color bars and readback; direct-buffer and RGBA8 UV fallback; opacity/OIT in world and Pad; screen resize/resource reload; playback stop/restart; terrain solid/cutout/translucent; editor/Pad input; full native media/lifecycle and terrain compatibility suites. Report backend, exact artifact SHAs, skipped/failed gates and retained-resource baseline. A CPU unit pass alone does not satisfy this matrix.
