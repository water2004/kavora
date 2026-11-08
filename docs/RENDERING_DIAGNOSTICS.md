# Rendering diagnostics

Use the source and configuration from the same build. Record the mod version,
runtime provenance, resource packs, dimension, quality options, DLSS and frame
generation state. Preserve logs before another launch replaces them.

## Startup and world transitions

The runtime is the custom Vulkan 1.5.0 API build under `dxvk-remix/`.
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
`RtxRemixMod.rebuildLoadedTerrain()` owns this sequence.

Remix Horizons observes delivered chunks only. Its metadata, read, mesh, expand,
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

Use a Release runtime. `build-runtime.ps1 -Profiling` enables on-demand Tracy;
use Nsight Graphics for pass execution, shader variants, register/local-memory
pressure, synchronization and residency. Include warm-up and resource upload
state when comparing captures.

Compare equal visual settings and camera poses. Whole-frame budgets matter;
pass times can overlap or include children. Native-resolution performance,
DLSS, frame generation and quality-tier tradeoffs are separate measurements.
See [profiling tools](../rtx-remix-minecraft/tools/docs/PROFILING.md).

Intermediate image/readback probes are opt-in. Do not keep unconditional
readbacks, extra synchronization or persistent CPU texture copies in normal
rendering. Remove one-off captures after retaining the results needed for a fix.