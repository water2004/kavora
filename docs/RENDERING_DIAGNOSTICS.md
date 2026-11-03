# Rendering Diagnostics

Read the [architecture and invariants](../AGENTS.md#architecture) first. This
guide explains failure signatures and the ownership boundaries behind them;
[Benchmarking](../tools/docs/BENCHMARKING.md) owns measured counters and
[Profiling](../tools/docs/PROFILING.md) owns capture analysis.

## OpenGL world-entry stalls

A stuck preparation screen can be a native wait rather than Java work. Capture
the render thread and native submission threads while the process is still alive.
An OpenGL draw or buffer mapping call waiting inside the driver, together with a
Vulkan queue submission waiting for a driver lock, points to the interop handoff.
Compiler threads can wait for that same lock; their presence alone does not show
that shader compilation caused the stall. Clearing runtime caches changes timing
and does not establish a fix.

The default Remix `Submit` path must wait for its queued Vulkan submissions to
reach the driver before returning the frame to an external consumer. The native
`DxvkDevice.waitForSubmission()` drains the CPU submission queue. GPU completion
remains synchronized by the shared timeline semaphore. Do not replace that host
ordering with per-frame `glFinish`, device-idle waits or sleeps. The explicit
`DISABLE_CPU_WAIT_FOR_VK_SUBMIT` flag opts out of the host wait; the Minecraft
renderer leaves it unset.

## Reference Material Comparisons

Dynamic lights use `remixapi_UpdateLight` through FFM. Recreating a light with
the same hash does not preserve its native handle or ReSTIR history. The runtime
also retains a live light's previous GPU index across updates, and invalidates
that association when the light skipped the previous submission. A volume-lighting
comparison must keep temporal resampling enabled and compare its scattered
radiance with the all-light reference, rather than only checking transmission.
For a single-frame comparison, freeze the frame-dependent random seed so both
paths use the same sample positions. Restore normal sampling before measuring
temporal stability and camera motion.

Volumetric lighting stores incident radiance in a camera-aligned 3D grid.
Light visibility is ray traced at jittered positions within each cell. Temporal
reprojection retains the unblurred lighting estimate; ray integration evaluates
that field with the local medium's extinction, scattering and phase function.
The displayed lighting uses a normalized, nonnegative 3D reconstruction kernel
after temporal accumulation. That result never feeds back into history and the
filter does not change surface textures, density or transmittance.
Sky visibility uses one ray per cell and low-discrepancy temporal samples.
Neighbouring cells rotate those samples through eight angular strata. The 3D
reconstruction gives each phase equal weight, combining their directional
coverage without increasing ray count. Independent sky directions per cell turn
integration error into spatial blotches; one common direction for the whole
grid instead makes that error fluctuate coherently over time.
Sampling more points along the camera ray cannot recover shadow detail missing
from the lighting grid. Compare depth resolution and XY resolution separately,
at equal history ages, before changing reconstruction or a density preset.

Evaluate light-path transmittance starting at the receiving volume sample.
A distant light uses a virtual position, and clipping a ray from that position
can omit all of the local height fog. The ReSTIR target distribution and final
lighting evaluation must use the same physical segment. Camera reprojection
also distinguishes the coarse reservoir grid from the irradiance grid, including
their different guard-band cameras.

Use the same world, art-pack contents, camera, time, weather, render distance,
resolution and resolved native options. Matching `rtx.conf` text does not prove
that the runtime implements its keys or that stronger layers resolve identically.
The client GameTests query the shipped Minecraft-specific material policies
directly. Debug-view indices must come from the matching runtime source.

Compare channels by sprite identifier rather than atlas coordinates. Minecraft
26.3 changes atlas placement and may replace RGB values in fully transparent
pixels. A whole-image checksum difference therefore needs separate checks of
visible RGB, alpha, normal, height and material channels before attributing it
to colour conversion.

Minecraft's padded sprite slots are not generally aligned to the coarser mip
grid. Repeating UVs within sprite bounds and clamping only to base-level texel
centres still allows coarse atlas mips to sample neighbouring sprites, including
unused blue normal-map sprites. The native sampler limits mip footprints to
complete texels inside the supplied bounds. Height-map traversal follows the
same restriction. Keep mip filtering active when diagnosing this issue; a large
negative mip bias can isolate cross-sprite contamination but is not a fix.

Displacement distances also need the sprite's UV scale. Tangent generation
normalizes away the common scale of an atlas tile; using the material's
quarter-tile depth directly in whole-atlas UVs greatly exaggerates relief.
Primary traversal and height-map shadow sampling must both account for the
supplied tile bounds.

NRC loads its CUDA dependencies dynamically. When no dependency-directory
override is configured, the native runtime resolves them beside `NRC_Vulkan.dll`,
which may be separate from the Java executable. Checking advertised NRC support
does not verify initialization: switch to Custom graphics settings, select NRC,
and require its context initialization and rendered frames before claiming that
cache path works.

The runtime logs `Embedded RTX shader bytecode SHA-256` at initialization.
Compare this with the build's declared `_built_shaders.h` dependency when
checking shader changes. Generating new SPIR-V files alone does not establish
that their bytecode was linked into the DLL. GPU storage image declarations
must also match the allocated image formats; the UV and packed atlas-bound
buffer is `R32G32B32A32_UINT` in every producing and consuming pass.

### Native intermediate images

Ray Reconstruction keeps the resolved primary surface's diffuse guide separate
from PSR reflections. Both lobes of a reflected surface contribute to the
camera's specular guide; adding a cloud's diffuse albedo to the underwater
diffuse guide makes reconstruction smooth unrelated seabed detail. Dense and
compacted capture use the same path classification in
`rr_guide_albedo.slangh`. This is a renderer contract, not a user tuning option.

Depth, motion, normal, roughness, and albedo must describe the same resolved
primary surface. The GBuffer publishes the geometric guides with that surface,
and a primary miss clears them together. Deferred decals blend normals in world
space before one virtual-space transformation. Native captures include
`rrDiffuseGuide`, `rrSpecularGuide`, `rrNormalGuide`, `rrDepthGuide`,
`rrMotionGuide`, and `rrRoughnessGuide`, along with the resolved primary depth
and motion for a pixel comparison. RR uses hardware depth; normal and motion
guides account for the virtual PSR surface.

Set `DXVK_DUMP_SCREENSHOT_FRAME` to a native frame number,
`DXVK_SCREENSHOT_PATH` to an absolute output directory, and
`RTXREMIX_EXTRA_RTXCONF=rtx.captureDebugImage=True` before launching the client.
The runtime writes DDS images for material inputs, demodulated lighting before
and after denoising, the composite before upscaling, and the final tone-mapped
output. Debug capture also includes the shared material word, shared radiance,
and primary world position with its packed triangle frame. Check the log's
capture timestamp against the camera teleport and terrain-settle messages: the
native frame counter also advances outside the chosen still shot.

Keep the original DDS files and the resolved settings for each run. HDR channels
must be compared in their stored linear range with one common preview transform;
independently normalized PNGs hide energy differences. Integer resources contain
packed values, not display colours. Decode normals and subsurface data using the
matching shader's packing helpers. Inspect alpha separately: lighting alpha can
hold a hit distance. With Ray Reconstruction active, the ordinary denoiser can
be bypassed, so identical pre/post-denoiser buffers do not imply a failed capture.

Verify both the output size and the internal rendering size from the captured
files. Windows can constrain a large window at creation, and automatic DLSS can
select a different scale at each output resolution. A larger final PNG can also
contain a stretched smaller runtime image. Compare matching dimensions and a
fixed upscaling mode, and keep unscaled detail crops alongside the full images.

Read diagnostic options back after the scene has settled. Changing a graphics
preset can reset an option written in the same frame, so a successful API write
or a launch argument does not establish that a control capture used that value.

Compare inputs first, then lighting, composition, reconstruction, and display.
Use controlled option changes to isolate cache, displacement, or filtering
behavior. A single noisy frame is useful for locating a divergent stage but
does not establish equal converged lighting. Also verify resource registration:
declaring and binding a shader input is insufficient unless the managed shader's
parameter list includes the corresponding descriptor.

Thin opaque materials need separate checks of transport, denoising, and cache
classification. Diffuse transmission belongs to the diffuse denoiser signal,
even though the transport sampler distinguishes it from diffuse reflection.
Changing the transport flag to repair denoising also changes sampling decisions.
The runtime carries a separate diffuse-signal flag through integration,
demodulation, NRC resolve, and reconstruction hit-distance preparation.

Primary thin-surface SHaRC entries distinguish the sampled ray's hemisphere
relative to the triangle normal. Query, update, debug views, and adjacent-level
reprojection must agree on that distinction while retaining the physical voxel
size. Combining the hemispheres can flatten visible material detail even when
albedo and normal captures are correct. Surface-selection probability is removed
from the radiance and throughput written to the cache and applied again when
querying for an image sample; it must not become a property of the cached surface.

Thin-cell filtering acts on cached radiance, before surface demodulation and
reconstruction. The runtime's `rtx.spatiallyHashedRadianceCache.cellFilterMode`
selects disabled, spatial, or spatial-and-temporal filtering. It uses compatible
surface normals and rejects cells shared with ordinary opaque material or
incoherent normals. Compare mode 0 and mode 2 with otherwise identical primary
query jitter, camera, resolution, and accumulation time. Smoothing the final
image or the albedo instead would hide material detail and is not equivalent.
Measure the `SHaRC Cell Filter` GPU zone separately from CPU submission time;
include its cache buffers when assessing memory cost.
The resolve pass compacts eligible thin cells into a GPU work list and builds
an indirect dispatch. The list uses `(entries + 4) * 4` bytes; its count and
three dispatch dimensions are reset before each resolve. No CPU readback is
needed. In temporal mode, cells with no new samples reuse valid history on
15 of 16 frames; their slot and frame index stagger the refresh. Changed
normals, replaced keys and missing history still require a fresh filter.

The integration surface PDF must survive both direct and indirect integration.
It cannot alias RTXDI illuminance, which direct integration overwrites before
the indirect pass consumes the PDF. Exercise this lifetime with DLSS Ray
Reconstruction disabled as well as enabled: compacted GBuffer storage can
otherwise hide the overlap.


Tracy must not intercept first-chance exceptions in a JVM host. HotSpot uses
recoverable access violations during normal execution; Tracy's crash handler
can suspend the process before HotSpot receives them. The source runtime skips
that handler when `jvm.dll` is loaded, while retaining CPU and GPU profiling.

## World Transitions

Terrain capture starts disabled and is enabled only after native initialization
succeeds. An unavailable or failed runtime must leave vanilla section builds
active, and the RTX toggle must not enable capture without an initialized
renderer. Otherwise section workers discard raster geometry while no Remix
frame can replace it, leaving only entities, particles and sky visible.

Fabric's `ClientPlayConnectionEvents.JOIN` and `DISCONNECT` are not paired.
`DISCONNECT` fires when the connection closes. A server reconfiguration
(`EnterReconfigurationS2CPacket`) switches the play handler to a configuration
handler without closing that connection, clears `client.world`, and fires
`JOIN` again when play resumes. Modded servers and proxies switching backends
can do this; the integrated server does not reconfigure.

The `RtxRemixMod` disconnect cleanup therefore does not run on that path:
renderer world clear, benchmark and camera session reset, and world conf-layer
release cannot be assumed to have happened. The null-world frame path hides
retained terrain and discards the prepared particle payload. Join handlers
must release the previous session before acquiring the next one, as the zone
registry clear and `ConfLayerDriver.onWorldJoin` do.

Schedule transition work through the client executor and re-check the session
when it runs. A flag drained from `WorldRenderEvents` or the submit hook will
not drain while the world is null. It can instead clear native resources in
the first frame of the next world, during their reinitialization.

## Terrain Recapture

`RtxRemixMod.rebuildLoadedTerrain()` owns the full recapture sequence:

1. Clear the Remix terrain through `ChunkManager.clear()`.
2. Snapshot `builtSections(client)` before `client.levelExtractor.allChanged()`
   invalidates the old section slots, then request the vanilla reload.
3. Call `recaptureTerrainForRemix(client, built)` to restore that set. Vanilla
   replaces its `ViewArea` during extraction, so its queue runs after
   `LevelRenderer.invalidateCompiledGeometry` returns. Sodium's reload is
   synchronous and uses its own capture queue immediately.

The RTX toggle and material/texture reload paths preserve the same contract.
A clear alone leaves an empty path-traced world until vanilla happens to
rebuild. A reload alone schedules only what its current occlusion graph
reaches and can omit terrain the prior scene held. The explicit queue restores
the previously built set, including accepted work still pending from an earlier
recapture in the same world. It does not capture every loaded section, which
would add terrain the scene never held and increase memory use. `/rtx reload`
uses this sequence too.

Submitting vanilla recaptures before that slot replacement cancels the work.
Check preservation while looking away from previously captured geometry, not
only while facing terrain vanilla would rebuild anyway. Pending recaptures are
world-scoped and released on join/disconnect; they cannot land in a later world.

The light-readiness query uses `SectionPos.getZeroNode(cx, cz)`, not
`ChunkPos.pack(cx, cz)`. Both return a `long` but encode coordinates differently;
passing the latter can reject every resident column except the origin. The
vanilla and Sodium recapture paths share this requirement.

Vanilla replaces a compile result with `CompiledSectionMesh.EMPTY` when it has
no raster layers or block entities. Captured results instead retain the real
`CompiledSectionMesh`, including its face-connectivity graph. Otherwise opaque
sections become all-open in the visibility walk, changing vanilla's reached set
and increasing capture and native scene costs. The decision follows the result's
latched capture marker rather than a later read of the RTX toggle.

### Missing terrain with an idle Remix queue

An idle Remix queue does not imply vanilla section compilation finished.
Still-shot settling also checks `SectionRenderDispatcher.isQueueEmpty()`.
With `-Drtxremix.logVisibleSet=true`, each captured shot logs visible and
uncompiled sections plus the vanilla compile queue. A large queue with idle
workers can indicate a captured exception, rather than expensive meshing.

Vanilla's dispatcher treats `NullPointerException` as buffer-pool contention
and requeues the task. `SectionBuilderMixin` reports capture failures before
that boundary and wraps a capturing null dereference so it cannot silently
stall the queue. Its worker state and region references are released on both
success and failure. In 26.3, block and fluid tint sources are nullable; an
absent source means white, preserving the texture's colour, just as 1.21.1's
colour lookup did. The client GameTest pack covers a tinted face without a
registered source and the gallery includes untinted lava.

A settings transaction triggers one recapture, not one per slider step. When
it is expensive, investigate page sizing, native work and runtime capacity,
rather than silently omitting one of the steps. Queue waiting and the two
retirement gates are documented in [Tuning](TUNING.md#terrain-capture).

## Block Lights And Resource Reload

`BlockLightRuleManager` loads built-in rules from `BlockLightDefaults` and
resource-pack rules under `rtx_remix/block_lights/`. One `BlockLightKey`
(position, rule id, light index) owns one `RemixLight`; a rule can emit multiple
lights per block, and lights are not clustered. `TerrainState` owns the
section light set and connectivity flood; `ChunkManager.draw` applies the
culls and counts their outcomes. The [resource-pack guide](RESOURCE_PACKS.md)
describes authoring rules.

Keep `BlockLightDefaults` and `BlockLightIntensityControls` in lockstep.
`validateBuiltInRules` requires the built-in rule ids and slider control ids to
match when the catalog is built during resource reload. A mismatch throws from
the reload. Minecraft can then clear the selected packs and return to the
title screen, leaving a quick-play launch waiting for a world it will never
join. Read `logs/latest.log`, not just the launcher's final timeout.
`BlockLightIntensityControlsTest` checks distinct control mappings but cannot
build the registry-bootstrapped catalog.

## Entity Geometry And Motion

Capture uses vanilla's `EntityRenderDispatcher` only on the render thread.
The dispatcher owns one renderer and mutable model per entity type, so a
parallel gather would race on model part angles.

Minecraft's model parts are rigid: their pivot belongs in the instance
transform, not a skinning stream. Skinning would add CPU mesh work in the
runtime without deforming any vertices. GPU-instanced `RemixAPI.drawInstanced`
is not interchangeable with retained parts either: the runtime can derive
previous and current transforms from the same per-instance array beneath an
identity parent, yielding zero motion and denoiser trails. Verify the matching
runtime's transform-history behavior before changing the submission model.
`Ent.Draws` near `Ent.Bones` is expected for one instance per rigid part;
`Ent.MeshCreates`, not that ratio, exposes pooled-mesh misses.

Flat item cards use rectangles of opaque texels instead of two full faces.
The runtime resumes a ray after a transparent texel from an origin displaced
by a few floating-point units along the normal. A surface within that offset,
such as a held flame crossing the card, can be skipped and show a hairline of
background. The offset grows with world-position magnitude, and the view-model
rig is scaled by `RemixFirstPersonPass.VIEW_MODEL_SCALE`. Compare near the
origin and thousands of blocks away before attributing a distance-dependent
artifact to capture. Sprites exceeding
`RemixCapturingConsumer.MAX_CARD_FACE_RECTS` retain full faces and their alpha
relaunch behavior.

## Material Updates And Surface Capacity

`RemixMaterialStore.update` uses the runtime's `UpdateMaterial` to mutate a
live material without changing its handle. Terrain pages and pooled meshes
that baked the handle then use the new parameters without rebuilding. Sun,
moon and star opacity, cloud tint, and the active beacon lens use this path.
Destroying and recreating the material instead orphans the baked handles and
requires recapture.

The runtime tracks the last translated API material revision on each surface.
Updating one material invalidates only surfaces that reference it. Material
creation or weather-driven updates must not invalidate unrelated retained
terrain. Texture-content patches keep their existing material binding and
become visible through the queued texture upload.

Success from the API alone is not proof of a visible update. The runtime can
reject a type or sprite-sheet-layout change with a warning, and updates rely
on the GPU-resident scene path. Verify the effect in game before removing a
recapture path. These semantics belong to the source-built runtime recorded in
`.runtime-provenance.json`; this repository's JVM unit tests do not execute
that implementation.

Mod-built material parameters are not automatically exposed as `rtx.*` options.
`SetConfigVariable` accepts registered options and valid keys in registered
dynamic namespaces, not arbitrary material fields. `GetConfigVariableResolved`
reads the value after native layer resolution; a successful write may still be
masked by a stronger layer. A tunable mod-built material parameter needs a
mod-owned setting unless the runtime explicitly exposes it.

Per-frame `drawInstance` submissions, including sky, clouds, the block outline
and particles, can return success while the runtime drops them for lack of a
free surface slot. `Cloud.Draws` counts calls made, not surfaces accepted, and
`latest.log` need not contain an error. Sky and cloud draws arrive late, so
intermittently missing clouds under load are a reason to inspect the runtime
log, not proof that the cloud generator failed.

Look in `rtx-remix/logs/remix-dxvk.log` under the game directory for
`GPU-resident scene exceeded its fixed surface capacity`. The runtime emits
that warning only once per process. Retained terrain/entity adds instead fail
through `sceneInstanceAdd`. Bursts can exhaust capacity while frees are still
pending and replacements arrive, even if steady-state use is low. The native
runtime owns the capacity and warning behavior; inspect that build rather than
hardcoding its pool size here.

## Separating Capture From Shading

Use a [Remix debug view](GETTING_STARTED.md#viewing-a-remix-debug-view) to isolate
texture coordinates, normals, emission, or mesh identity. A defect already
visible in those inputs points toward capture or its native interpretation;
a clean input with a defect only in the final image points toward shading or
denoising. Include player coordinates in the report and compare the same pose,
settings and resource pack. The [contribution guide](../CONTRIBUTING.md#visual-comparisons)
explains repeatable captures.

For a same-frame buffer dump, enable `rtx.captureDebugImage` and write
`rtx.captureNextFrame = True`. The latter is a one-shot request and resets when
the next valid rendered frame is captured. Files with the same `_frame<number>_`
belong together. The DDS files preserve HDR radiance and packed attenuation;
packed colour formats without interoperable DDS layouts are converted to
floating-point RGBA. These are not display-space screenshots. Set
`DXVK_SCREENSHOT_PATH` before launch to select the output directory. The dump
includes primary and secondary signals before composition and outputs after
composition, upscaling, neural rendering, and tonemapping.

The opt-in `RemixCloudMirrorChecks` GameTest places a static cloud slab over a
flat water surface, then moves the camera to the equivalent reflected position
through a small aperture in that surface. It compares accumulated raw indirect
radiance before denoising, exposure, water Fresnel composition, and tonemapping.
Both random and dithered first-bounce lobe selection must agree with the mirror
camera within 15% in each channel. The tolerance covers Monte Carlo noise,
8-bit debug-image quantization, and the aperture's small lighting difference;
it is not a visual-quality threshold for the final image.

The same test replaces the cloud and water box with a single emissive thin pane.
The increase in shared radiance when enabling its emission must agree within 5%
with PSR enabled or disabled. This detects repeated accumulation of the pane's
emission when reflection and transmission both miss and sample the sky.
`-Drtxremix.gametest.sharedRadianceOnly=true` runs just this part of the probe.

```powershell
.\gradlew.bat runClientGameTest -Parch=win64 -PgameTestRunDir=build/run/cloudMirrorGameTest -PgameTestExpectedBackend=VULKAN -Drtxremix.gametest.streamingOnly=true -Drtxremix.gametest.cloudMirrorOnly=true -Drtxremix.gametest.width=1280 -Drtxremix.gametest.height=720
```

This check requires the matching source-built runtime's raw-radiance debug
views. Keep surface-selection random numbers independent from subsequent light
and BSDF samples: selecting a reflected surface with the same random value as
its first-bounce lobe conditions the lighting estimator and can darken it even
when both individual samplers have uniform marginals.
