# AGENTS.md: rtx-remix-minecraft

Context for AI coding agents working in this repository.

## Project Overview

`rtx-remix-minecraft` is a Fabric mod that renders Minecraft 26.3 through
NVIDIA RTX Remix. It captures terrain, entities, particles, sky, and clouds from
the client and submits them to the Remix runtime as a path-traced scene. The
Java facade and Java 25 FFM bindings are tracked under `rtxremix-java/`; the native
runtime and the Remix C API come from dxvk-remix. A change often spans all
three layers.

## Source Of Truth

Prefer these sources in this order:

1. source under `src/main/java/`
2. `build.gradle`
3. the Architecture section below
4. `README.md` and the guides under `docs/`

There is deliberately no standalone architecture document. One existed and went
stale faster than the code it described. Code standards and review expectations
are in [CONTRIBUTING.md](CONTRIBUTING.md) and apply to agent-written code too. A
subdirectory may carry its own `AGENTS.md`; read the nearest one as well.

## Build, Run, Package, Test

Java 25, Windows, and the C++ toolchain are required. Stage the matching
source-built `water2004/dxvk-remix` runtime (custom C API 1.5.0) first.
Published 0.x runtime packages and upstream 1.21.1 installers are incompatible.
This is an integration branch; compilation and tests do not establish playability.
[Getting Started](docs/GETTING_STARTED.md) covers local overrides. The renderer uses Vulkan exclusively. Select it through Minecraft's options
before device creation and do not allow an OpenGL fallback. Sodium 0.9.2 is required and owns
terrain capture and the shared video settings UI. Keep Sodium and Fabric API as
independent JARs, never nested dependencies. Voxy is unsupported. Optional far terrain uses the FarTerrainExtension API;
its implementation and dependencies live in a separate project and JAR.

```powershell
.\gradlew.bat build
.\tools\rtx.bat launch
.\gradlew.bat test
.\gradlew.bat package -Parch=win64
```

`tools\rtx.bat launch` builds the mod, installs it into a checkout-specific
Prism Launcher instance, and starts Minecraft through Prism. Installed Prism
reuses its normal account profile; portable Prism keeps its profile under
`run-prism/`. Use `tools\rtx.bat prism signin` with a Microsoft account that
owns Minecraft: Java Edition. See
[Getting Started](docs/GETTING_STARTED.md#sign-in-to-prism).

`classes` depends on `setupRuntime`, which compiles `rtxremix-support.dll`, validates the
source-built runtime provenance, and stages both, so even `test` needs the C++ toolchain.
`build` leaves a lightweight JAR in `build/libs/`; `package` embeds
the runtime and payload in `build/distributions/`. It verifies inclusion of
every `packaging/licenses/` file and excludes Python interpreter and binding
payloads. Staged and packaged contents must agree with `NativeExtractor`.
See [Getting Started](docs/GETTING_STARTED.md) and
[`build.gradle`](build.gradle) for the staging inventory.

Do not run Gradle while a client from the same checkout is up: staging can fail
on locked DLLs or replace its live configuration. Stop the client or use another
worktree. Build guards should prevent a silently wrong artifact, as the
mixed-runtime-source guard does, rather than police checkout cleanliness.

Keep the unconditional tuning arguments in `loom.runs.client.vmArgs` in
`build.gradle` and `RECOMMENDED_JVM_ARGS` in `packaging/installer.ps1`
identical, and when `RECOMMENDED_JVM_ARGS` changes, append its outgoing value to
`PREVIOUS_RECOMMENDED_JVM_ARGS`. Opt-in benchmark and profiling arguments are
excluded. See
[JVM Arguments](docs/TUNING.md#jvm-arguments).

## Runtime And Install Model

The mod needs the native runtime, not only Java code, and `NativeExtractor`
unpacks the bundled payload on first launch, version or architecture change,
when the bundled runtime provenance or support-library fingerprint changes,
or when a required file under `remix-sdk/` is missing. The marker includes
SHA-256 hashes of the bundled provenance and support library, so a runtime
rebuilt under the same mod version is installed too. Deleting
`.rtx-remix-version` also forces full re-extraction. Full extraction replaces
the mod-owned `remix-sdk/`, `rtx.conf`, and the shipped runtime textures in
`textures/`, leaving anything else in that folder alone, so a JAR installed
without the release installer is complete. With a matching marker and runtime
present, launch restores a missing `rtx.conf`, a missing runtime texture, and
any missing shipped files under `rtx-remix/` without replacing existing files. The `rtx-remix/` payload
is author-owned: existing files, including edited confs, are never overwritten,
even during full extraction. Deleting a shipped conf restores its default on
the next launch. A present conf that fails to acquire still prevents renderer
initialization; the error identifies the file.

Baseline LabPBR textures live in `src/main/resources/assets/` and load through
Fabric's `fabric` resource pack below user packs. Development setup, `rtx install`,
and the release installer use `packaging/migrate_resource_packs.ps1` to remove
their exactly identified legacy baseline copies and migrate existing pack order.

## Architecture

**Orchestration.** `RtxRemixMod` is the Fabric entrypoint: lifecycle hooks, key
bindings, and the `/rtx` command tree. `RemixRenderer` is the render thread's
bridge into Remix and owns the frame. Sky textures are generated and published
on the render thread, branched on the dimension's sky type: the End
keeps a constant dark dome and a skyless dimension a black one with no sky
ambient. Thread-safe stores wrap Remix-side meshes, instances, textures,
materials, and lights over raw `long` handles from the FFM binding. Minecraft's
selected backend owns its graphics resources. Vulkan device, physical device,
queue and image synchronization belong to the render-thread integration.
Minecraft blits the acquired runtime color directly into its composition target.
Sampled color and depth snapshots are created and copied only when precipitation
needs them, and the native menu snapshot only when its overlay is drawn. Each
snapshot is copied at most once per acquired runtime frame.
`NativeRuntime` prepares Remix before instance creation, combines Minecraft's
device requests with the runtime's requirements, and transfers ownership of
the resulting logical device to Minecraft after its dispatch table is ready.
Both renderers use this single device, with distinct queues in the same graphics
family and borrowed images and timeline semaphores. Shared color and depth
images alias the producer's outputs; the client copies them into its own textures.
Remix resources retire before Minecraft destroys the device, including during startup failure.
`NativeVulkanSurface` replaces only Minecraft's surface backend and delegates
the sole window swapchain, DLSS frame generation, and pacing to the runtime.
The early RTX Submit ends the scene once; late Present consumes Minecraft's
composed color. Each virtual backbuffer owns depth and motion snapshots, with
reuse protected by its GPU acquire semaphore. A zero-image client Present
releases borrowed source views before Minecraft destroys the image. Native
presentation outlives renderer shutdown and closes with the host device.
`JoinBurstHold` keeps vanilla's terrain-download screen up for a bounded wait
for stable frames after a world join, hiding first-sight mesh, pipeline, and
BLAS costs without holding the screen indefinitely.

**Overlay input.** `RemixInputGate` owns the Minecraft-side capture session for
the Remix overlays; `KeyboardMixin` and `MouseMixin` route GLFW input through
it. Capture follows whether a native menu is shown, which `rtx.showUI` records:
`GameRendererMixin` has the gate read it through `GetConfigVariableResolved` at
the start of every frame. The mod opens and closes menus by writing it, and the
menus' own close and switch controls, the runtime's menu hotkey, and its First
Use Guide change it too. Alt+X without Control toggles the developer menu over
gameplay or a screen. Ordinary video settings, including advanced runtime options, stay in Sodium
and do not open a native menu or capture input. The native developer menu
opens through `RtxRemixMod.openRemixMenu`. Opening, by the mod or the runtime, finalizes held
key bindings, vanilla's F3 combination state, and active mouse interactions
because their releases will not reach Minecraft. Capture excludes Minecraft
input and cursor-lock requests, and screens see an off-screen cursor to clear
hover state. Remix resolves a write a frame or more later, so after the mod's
own write the gate ignores reads until one agrees with it. After
`OWN_WRITE_OVERDUE_FRAMES` frames without one, it logs that a config layer above
Derived Settings likely sets `rtx.showUI` and follows reads again. The native
controls take effect only because `SetConfigVariable` writes `NoSave` options
such as `rtx.showUI` to the Derived layer, where the menus write them; a User
Settings value would mask every native close. The packaged `rtx.conf` parks the
runtime binding on a chord Windows cannot deliver.
Native menu visibility also enables its render pass in the interop swapchain;
`rtx.gui.enableExternalPresenter` is not required to open it.

**Camera paths.** A time-aware shape-preserving cubic Hermite path over
nonuniform waypoint times with shortest-route unwrapped yaw, a strict version 1
JSON codec, and world-scoped storage. Playback applies each pose at the
pre-`GameRenderer` hook. The default clock follows wall time; the fixed-60 clock
advances one deterministic sample per rendered frame, which benchmarks reuse
with their own frame index. `docs/CAMERA_PATHS.md` is the canonical guide.

**Gameplay-driven conf layers.** Option layers come from a mod root shipping
archetypes, acquired at init, and a world root inside a save supplying zone and
biome profiles, acquired on join. A world layer sits just above its mod
counterpart, so a map overrides per option rather than replacing an archetype,
and it needs the integrated server, so it is single-player only. Profiles
resolve above `rtx.conf` and below user settings and quality presets.

**Clouds and fog.** Volumetric clouds use a world-space density field advected
by synchronized world time. The runtime generates a periodic 3D noise palette
once per device. Camera, reflection, refraction, and visibility rays sample the
same density field. A single ray-segment integrator combines cloud and fog
extinction and scattering before compositing. Fog height and noise affect both
lighting and attenuation. Cloud distance is independent of terrain distance.
Choosing Classic submits vanilla texture partitions with vanilla's period and
phase as ray-traced geometry instead. The visual settings stage edits across
Iris-style folders and commit once on Apply; Sodium owns the performance pages.

**Terrain.** Sodium workers build and capture section meshes, a low-concurrency executor
merges them into chunk-column assets split into byte-capped pages per category,
and the render thread publishes them under a frame budget and retires replaced
pages through a bounded queue. Concurrent vanilla section builds are capped.
Visibility rebuilds use a low-priority executor and produce a coarse frustum
index that survives chunk slots being re-originned by replaying a render-thread
move log. While capture is on, integrated-server chunk delivery and chunk
unloads are paced by measured render-thread cost under separate budgets, with
chunk caps on delivery and a ring-edge bypass for unloads
(`LocalChunkBatchPacing`, `PacedUnloadQueue`, `ChunkDataSenderMixin`); a
chunk-load neighbour re-flag is dropped if the
neighbour's last build already saw the loading column (`NeighbourReflagFilter`);
the client light engine publishes its live section map as its snapshot
(`ClientLightSnapshotPolicy`, `LightStorageMixin`) while the integrated server's
engines, which have real readers on other threads, publish an immutable layered
view instead of vanilla's whole-map clone (`ServerLightSnapshot`); and the
client's sky-light surface walk is seeded at the packet's `WORLD_SURFACE`
height (`SeededSurfaceScan`, `SkyLightSurfaceScanPolicy`). `VanillaSectionBuilds`
keeps real copies until every build that latched capture off has finished.
Each policy has a `rtxremix.*` kill switch listed in `docs/TUNING.md`.

**Allocation.** The collector's concurrent work is what stretches frames at
high render distance, so the hot vanilla paths that run per section or per
frame are ported allocation-free and kept that way: the chunk visibility walk
and its far-section probe (`ChunkRenderingDataPreparerMixin`,
`terrain/visibility/`), block model variant and part selection
(`WeightedBakedModelMixin`, `MultipartBakedModelMixin`), biome colour blending,
and the integrated server's per-tick chunk list. Retained section meshes live
in C-allocated direct buffers outside the JDK's direct-memory budget
(`SectionMeshData`, `DirectBufferPool`) and render-region snapshots in pooled
heap arrays (`RenderRegionStoragePool`), both under explicit reference counts:
the creator's reference travels worker, `BlockDataQueue`, `TerrainState`
entry; a column build retains each snapshot before submission and releases in
its executor task's `finally`; the last release returns the buffers and makes
the surfaces unreadable while the cached hashes stay readable for diffing.

**Textures and atlases.** Atlas capture produces per-sprite materials plus
atlas-level PBR, emissive, and luminance textures. Animated sprite uploads are
batched and forwarded, which needs the upload hook, the patch types in the
wrapper, and runtime support for updating a texture from memory. The runtime
groups CPU mip generation and upload snapshots by texture on a bounded worker
pool, preserves entry order within each texture, and joins all accepted work
before returning from FFM. GPU uploads stay on the command thread. Glyph pages
are mirrored on upload, because vanilla assembles them on the GPU and never
keeps them on the CPU.

**Particles.** Slot preparation and CPU mesh snapshots run on a dedicated worker
and are double buffered: the render thread publishes the previous frame's
payload at the top of the frame and joins the worker after submit, so particles
trail the scene by exactly one frame. Vanilla particle colours are lightmap
tints, not albedos, and the runtime lights a blended particle from the radiance
cache with no self-shadowing, so `ParticleTint` draws soot near-black to keep
smoke dark over a lit floor.

**Entities.** Entities and block entities that vanilla already draws are
captured in place, so a model is evaluated once rather than once for raster rendering and
again for Remix; entities outside vanilla's frustum are swept up closest-first
under a budget, because a path tracer needs them for shadows and reflections,
and an entity that owns a Remix light this frame is swept at any distance so
its light never arrives without its housing. Rigid occurrences retain instances
keyed by owner, category, layer, and part; strips and beams stream transient
meshes per frame. `RemixBlockDamagePass` submits static terrain damage decals.
`CopperBeaconBlockEntityRenderer` submits the articulated beacon model; the
mount stays in terrain. `CopperBeaconPose` is shared with the per-frame disk
light. Server-authoritative block-entity state owns the axis parameters and
anchor tick; clients reconstruct motion from those and synchronized world time.

**Lights.** Block lights are created, updated, and destroyed on the render
thread under their own budget, from resource-pack rules keyed by block state. A
hand-held item whose placed block shows a lit particle gets the same element in
hand, emitted once the item's pose is on the matrix stack, so first person, the
hidden shadow model, and other players share one hook. `HeldLightState` is the
one decision on whether a held item's lit element is burning: the local
player's Toggle Held Lights key puts out everything in their own hands, and by
default an open flame goes out for any holder whose eyes are under water.

Terrain block lights are per-frame draws, not retained instances: every frame
`ChunkManager.draw` submits each surviving `TerrainLight` (the `RemixLight`
plus its position, power, and last drawn frame), so not drawing a light is a
complete cull. Culling applies the column Chebyshev box and the section
connectivity flood in `TerrainState`, then a power-aware reach test with
hysteresis. A light cull distance of 0 means Auto: `ChunkManager` resolves it,
and any explicit value, against Minecraft's render distance and pushes the
radius into `TerrainState` only on change, so `TerrainState` is always finite.
The flood rebuilds into a second set in per-frame slices and swaps it in when
complete; a section added mid-build marks the set dirty rather than expanding a
set about to be replaced. `LightManager` lights are never culled. Some small
emitters use `BuiltinEmission` instead of built-in analytic-light rules to avoid
diluting the sampler's candidate pool.

## Invariants

- Every Remix handle is created, updated, and destroyed on the render thread;
  workers hand over CPU data through bounded, generation-tagged queues
  (`BlockDataQueue`, `ChunkManager`).
- A retained instance is submitted where it is created or mutated, never once
  per frame (`ChunkManager`, `RemixEntitySubmitter`).
- A prepared particle payload holds world-space vertices and a live bucket, so
  every path that suspends or tears down rendering discards it rather than
  publishing it, including a world swap that keeps the connection alive
  (`RemixParticleRenderer`).
- A coplanar decal stack, such as a banner's pattern layers, is one mesh in
  paint order, because Remix orders coplanar decals by primitive index rather
  than by depth; text turns draw order into depth the same way
  (`RemixEntitySubmitter`).
- A local first-person light is submitted with a zero volumetric radiance scale,
  because the volumetric grid reuses the previous frame's radiance and a
  camera-locked emitter would smear into distant geometry (`LightManager`).
- Ordinary classes must not live in the mixin package (`MixinPackageBoundaryTest`).
- A mixin that cancels vanilla work replays the GL, fog, and framebuffer state
  its target would have set (`WorldRendererMixin`).
- Never cancel `WorldRenderer.render()` itself or return early past
  `setupTerrain`. Remix is hosted inside vanilla's traversal and captures its
  sub-passes in place. Cancel only replaced draws and preserve their net state
  changes; see [Cancelling Vanilla Render Work](CONTRIBUTING.md#cancelling-vanilla-render-work).
- A terrain recapture must clear Remix terrain, reload vanilla terrain, and
  explicitly queue the previous built set. Snapshot `builtSections` before
  `WorldRenderer.reload()` forgets it, then call `recaptureTerrainForRemix`.
  `RtxRemixMod.rebuildLoadedTerrain()` owns this sequence. Commit settings once
  per transaction, never once per slider step.
- Schedule world-transition work through `client.execute`, not a flag drained
  by a world-render hook: those hooks stop when `client.world` is null, so the
  clear would land in the next world's initialization. Re-check the session
  after an executor hop.
- `JOIN` and `DISCONNECT` are not paired across server reconfiguration. A join
  handler must release the previous session's state before acquiring new state.
- Keep terrain budget loops allocation-free and do not time individual native
  calls with additional `System.nanoTime` reads. Reuse `ProfileScope`, the
  loop's deadline read, and clock-free `TerrainNativeCallStats` tallies.
  Avoid per-block/per-quad capture allocation and per-frame render allocation.
- Terrain capture has one path; the pre-`SectionMeshData` paths are gone
  (`SodiumMeshingTaskMixin`, `SodiumCapture`). There is no optional vanilla
  capture fallback; both graphics backends require Sodium.
- Every retained `SectionMeshData` or render-region reference is released
  exactly once: `SectionMeshData` rejects an over-release, `DirectBufferPool`
  ignores a buffer that is already idle, and `RenderRegionStoragePool` relies
  on its callers' counts (`SectionMeshData`, `DirectBufferPool`,
  `RenderRegionStoragePool`).
- The visibility walk port produces vanilla's visible set exactly;
  `-Drtxremix.vanillaVisibilityWalk` and `-Drtxremix.logVisibleSet` are how to
  prove it on a live world (`ChunkRenderingDataPreparerMixin`, `FarChunkProbeTest`).
- A queued chunk unload is cancelled by the column's re-send, and a queued
  column that reaches the client ring's edge is sent at once regardless of
  budget (`PacedUnloadQueue`).
- Nothing a `ServerLightSnapshot` has published is ever mutated; the engine
  copies a nibble array before its first write after a publish
  (`LightStorageMixin`).
- The camera schema stays strict version 1 and its discriminator stays
  `"catmull-rom"` (`CameraPathCodec`); still shots are a separate file with
  their own strict codec, never extra waypoint fields (`StillShotCodec`).
- Authored camera paths stay separate from launcher-owned fixture staging,
  and shot sets live in the directory the trigger names, never inside a world
  fixture (`CameraController`).
- A value meant to stay dynamically blended is written as a conf layer, never
  through a user-layer config write (`ConfLayerDriver`).
- `remix-sdk/` and `rtx.conf` stay mod-owned and the `rtx-remix/` payload
  author-owned, and the native compile tasks, `setupRuntime`, and `package` stay
  aligned with the packaged contents they verify (`NativeExtractor`,
  `build.gradle`).
- The launcher's account options (`--username`, `--accessToken`, `--uuid`,
  `--xuid`, `--clientId`) are blanked in the process command line before the
  runtime loads, because its crash dumps copy that line (`RemixRenderer`,
  `CommandLineRedaction`).
- Vanilla's shared worker pool and section-builder concurrency stay capped
  (`WorkerPoolCap`, `SectionBuildConcurrency`).
- Nothing but the render thread reads client light (`World.getLightLevel`,
  `LightingProvider.get`) while capture is on: the published snapshot is then
  the engine's live map (`ClientLightSnapshotPolicy`, `LightStorageMixin`).
- There is one overlay toggle path. `RemixInputGate` alone owns the capture
  session. Every open request goes through `RtxRemixMod.openRemixMenu`, and
  every menu the runtime shows or hides reaches the gate through
  `RtxRemixMod.followRemixMenuVisibility`, which `GameRendererMixin` calls at
  the start of each frame and which allocates nothing on the Java heap. The
  packaged runtime menu binding stays away from Alt+X (`KeyboardMixin`,
  `MouseMixin`, `RtxSodiumConfig`, `packaging/rtx.conf`).
- The placed Copper Beacon is not a `BlockLightDefaults` rule: its block entity
  owns a dynamic articulated light, and a static rule would light it twice
  (`LightManager`, `CopperBeaconBlockEntityRenderer`).
- Whether a held item's lit element burns is decided once, in `HeldLightState`;
  the flame, the smoke, the hand light, and the held beam all consult it
  (`HeldItemEffectsRenderer`, `LightManager`).
- A terrain light's cull is the decision not to draw it this frame, never a
  destroy; the radius `TerrainState` holds is finite and pushed only on
  change, because a changed radius dirties the whole flood (`ChunkManager`,
  `TerrainState`).
- Keep `rtx.showUICursor = false` in `packaging/rtx.conf`. The mod does not
  write this key; the runtime software cursor can leave the OS cursor hidden.
- Change `BlockLightDefaults` and `BlockLightIntensityControls` together.
  Their rule-id sets are validated during resource reload, not just unit tests.
- Run entity capture on the render thread. Vanilla shares mutable renderer and
  model instances; gathering entities in parallel races on their part angles.
- Developer tooling retains its signed-in Prism workflow and does not pass
  Prism's `--offline`. Direct Gradle `runClient` is supported for source-port
  integration validation; it uses the separate `run/` directory.

## Focused Engineering Guides

- [Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md): lifecycle, recapture,
  material updates, entity precision, and runtime surface-capacity symptoms.
- [Tuning](docs/TUNING.md): queue waiting, retirement gates, worker limits, and
  JVM argument ownership. Named source classes own the defaults.
- [Benchmarking](tools/docs/BENCHMARKING.md) and
  [Profiling](tools/docs/PROFILING.md): counter interpretation and measurement.
- [Getting Started](docs/GETTING_STARTED.md): runtime staging, initialization
  failures, debug views, and worktree setup. Debug-view indices belong to the
  matching dxvk-remix source, not this repository.

## Integrated FFM Binding

`rtxremix-java/` is a Minecraft-agnostic Java facade and Java 25 Foreign
Function and Memory binding over the custom RTX Remix C API. It must never
import `net.minecraft` or `net.fabricmc`; `RemixJavaBoundaryTest` enforces
this boundary. Keep game-state capture and Fabric integration in the mod.

The binding's source of truth, in order, is:

1. `rtxremix-java/src/com/nvidia/rtxremix/`: `RemixAPI.java` is the facade,
   `RemixBridge.java` performs foreign calls, and `RemixLayouts.java` defines
   native structure layouts. The other types are data holders and handles.
2. The source-built `water2004/dxvk-remix` runtime's custom 1.5.0 API
   and the matching copied `rtxremix-java/native/include/remix_c.h`.
3. `rtxremix-java/native/crash_support.cpp` and `build_native.bat`: the small
   Windows crash/process support library, separate from the Remix binding.
4. SDK source validation in `tools/src/rtx_tools/sdk.py` and `build.gradle`.

The root build compiles Java directly with Java 25 and builds
`rtxremix-support.dll` into `build/native/{win64|arm64}/` using MSVC. It does
not compile a JNI bridge or require `jni.h`. The JVM needs
`--enable-native-access=ALL-UNNAMED`. Keep that argument in both Gradle and
installer defaults, along with their history contract.

Runtime outputs live in `remix-runtime/{win64|arm64}/`. A staged source build
must include `d3d9.dll`, dependent runtime DLLs, USD payload, and
`.runtime-provenance.json`. Provenance identifies the fork, custom API 1.5.0,
architecture, 40-character source commit, dirty flag, and SHA-256 of the DLL.
Validate provenance and PE architecture before replacing any staged snapshot.

- `tools\rtx.bat sdk local --arch <arch> --path <output>` stages a completed
  source build and records `.local-sdk-source`; `sdk restage` refreshes it.
- Without a path, local staging uses the recorded source, then `sdk.localPath`
  in ignored `tools/config.local.json`, then `../dxvk-remix/_output`.
- `sdk status` reports source and snapshot freshness without changing them.
- `sdk packman` rejects the unsupported 0.x binary fallback without modifying
  staging. Gradle's `fetchRuntimeSdk*` task names remain, but validate source
  builds instead of fetching published SDKs.
- Support-library builds use `gradlew.bat compileNativeWin64 compileNativeArm64`
  or `rtxremix-java/native/build_native.bat x64` / `arm64`.

Keep runtime directories and native outputs ignored. Runtime textures remain
under the read-only Packman junction `remix-runtime/textures/`, outside `build/`.
Never write support-library outputs into a Packman-managed directory. Staging
and packaging combine the validated runtime with the separately built support
library, excluding obsolete `remixbridge.dll` payloads.

The Remix C API is reached through FFM, never JNI or JNA. Java-side handles
remain raw `long` values. Document direct-buffer requirements and native
segment lifetime on new binding entry points. A direct buffer passed to a
foreign call must remain alive until that call has returned.

## Runtime And Java API Changes

Renderer behavior and the C API belong in the runtime fork. Marshalling and
layouts belong in the FFM binding. Game capture belongs in the mod. Inspect
all three layers for API, structure, texture-update, or runtime-asset changes.

Keep the runtime's custom API, copied header, `RemixLayouts.java`, foreign
function descriptors, `RemixBridge.java`, `RemixAPI.java`, data holders, and
affected callers aligned. `StructType.java` values must exactly match the
runtime header and its specialization definitions. Do not infer compatibility
from an upstream SDK version or edit only one copy of a structure.

Rebuild the runtime and generate its provenance from the completed output,
restage it, then rebuild the support library and mod. The Java version passed
to `remixapi_InitializeLibrary` and every foreign layout must describe that
same runtime. A version label alone cannot establish ABI compatibility.

Verify `compileJava`, the full root `build` and `test`, and a development
client using Gradle `runClient` or the signed-in Prism workflow. JVM tests do
not establish native initialization, Vulkan device sharing, presentation,
capture correctness, or performance. Record outstanding integration failures
honestly; do not describe this port as a validated release before those checks
have passed with the exact source-built runtime.

## Tests

`TerrainStateTest` is the entry point for the JVM-side chunk-pipeline tests.
The suite does not cross the FFM boundary or render, so a rendering change is
verified by launching with `tools\rtx.bat launch` and looking.

Python tooling uses `tools\rtx.bat check --tests` for its test suite.
Its shared defaults pin the demo world and art pack without selecting a
graphics preset; machine-local choices belong in ignored
`tools/config.local.json`. See `tools/AGENTS.md` for the configuration and
benchmark contracts.

Keep implementation plans and task checklists outside the repository. Source
and maintained guides are the durable record, not planning scaffolding.
