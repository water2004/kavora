# Rendering diagnostics

Use the source and configuration from the same build. Record the mod version,
runtime provenance, resource packs, dimension, quality options, DLSS and frame
generation state. Preserve logs before another launch replaces them.

## Startup and world transitions

The runtime is the custom Vulkan 1.5.0 API build under `kavora-runtime/`.
An old DLL or missing dependent payload can fail before world capture starts.
Check the extraction/provenance log and the earliest native error.

A frozen process is more useful with a Java thread dump and native crash data
than repeated restarts. Use the JDK's `jcmd <pid> Thread.print` and preserve
`hs_err_pid*.log` if present. Do not collect launcher credentials. Development
launch redacts account arguments before loading the runtime.

World transitions must release the preceding generation even when JOIN and
DISCONNECT are not paired. Queue clears through the client executor and
recheck the session after the hop. Render hooks stop while the world is null;
a pending render-hook flag can accidentally clear the next world.

## Missing terrain

Inspect near capture, column assembly, native publication and coverage as
separate steps. A visible-set count alone does not show that a mesh reached
the runtime. A retained instance must be submitted after mutation.

A recapture snapshots built sections before vanilla reload, clears native
terrain, reloads vanilla terrain and explicitly queues the preceding built set.
`KavoraMod.rebuildLoadedTerrain()` owns this sequence.

Kavora Horizon observes delivered chunks only. Its metadata, read, mesh, expand,
upload and retire stages have separate counters. Check its bounded queues and
committed near-coverage mask before changing distance or adding wait times.
See [far terrain](FAR_TERRAIN.md).

## Materials and particles

Compare authored albedo, LabPBR channels, native upload/mip interpretation,
material flags and final lighting. A low-resolution or color-space mistake
can resemble a lighting difference. Animated upload order is part of the ABI.

Particles retain world-space positions and previous-vertex history. Motion
vectors and frame generation must use the same scene-frame IDs. A submitted
generated frame is not proof that it reached the display.

For water, distinguish the reflected and transmitted paths and their temporal
guides. Medium attenuation belongs to each segment; applying it twice can
produce dark overlays or visible quad boundaries.

## GPU analysis

Choose the pass that owns the effect before tracing the host call chain.
`RtxContext::recordSceneLighting` records sky, volumetrics, tracing, lighting
and composition. Its outputs are HDR composite color and reconstruction
guides. `reconstructSceneImage` writes display-resolution linear HDR into
`m_finalOutput`. `recordDisplayEffects` consumes that image in place, with
motion blur before tonemapping and lens effects after it. Debug output, frame
generation inputs and export to the game target follow these stages. Pass
implementations own their bindings and image access modes.

For an effect change, start at its Slang calculation owner and the native pass
that supplies its resources. Java's sky and cloud modules receive the camera and
world explicitly. They publish scene inputs through the existing Remix API.
Keep the input encoding, output extent, image access and history lifetime at
that boundary. If the change needs new per-frame shader parameters, follow
the shared [shader buffer contract](../kavora-runtime/src/dxvk/shaders/rtx/README.md#ray-tracing-constant-buffer).
See the [effect entry points](../kavora-runtime/src/dxvk/shaders/rtx/README.md#visual-effect-boundaries).

For the cloud-sky map, the dispatch and bindings live in `dispatchCloudSky`.
The density and lighting used to fill the map live on the shader side.
[Native cloud pass](../kavora-runtime/src/dxvk/rtx_render/rtx_global_volumetrics.cpp) ·
[Cloud-sky shader](../kavora-runtime/src/dxvk/shaders/rtx/pass/clouds/generate_cloud_sky.comp.slang)

For final color grading, start at the shader that consumes the tonemapped
image and writes the exported color.
[Final color shader](../kavora-runtime/src/dxvk/shaders/rtx/pass/srgb_dither/srgb_dither.comp.slang)

For a frozen frame, follow `KavoraFramePresentation` from Submit through image
acquire and release to Present. Submit publishes the producer timeline signal
from `D3D9SwapchainExternal::Present`, including when scene rendering is skipped.
Present completes that handoff after the host encoder submits its release.
When a caller renders through `GameRenderer.render()` directly, the handoff
finishes before it returns, with no window image. Window source-image lifetime
belongs to `NativeVulkanSurface`. Particle worker
lifetime and stale-world payloads belong to `ParticleFramePipeline`.
The renderer marks phase boundaries. `KavoraFrameMetrics` owns the metric IDs,
presentation deltas and reporting for those phases.

Use a Release runtime. `build-runtime.ps1 -Profiling` enables on-demand Tracy;
use Nsight Graphics for pass execution, shader variants, register/local-memory
pressure, synchronization and residency. Include warm-up and resource upload
state when comparing captures.

Compare equal visual settings and camera poses. Whole-frame budgets matter;
pass times can overlap or include children. Native-resolution performance,
DLSS, frame generation and quality-tier tradeoffs are separate measurements.
See [profiling tools](../kavora/tools/docs/PROFILING.md).

Intermediate image/readback probes are opt-in. Do not keep unconditional
readbacks, extra synchronization or persistent CPU texture copies in normal
rendering. Remove one-off captures after retaining the results needed for a fix.
