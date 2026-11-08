# Tuning

Developer knobs: the JVM system properties the mod reads, the Gradle
pass-throughs `build.gradle` understands, the JVM arguments a play install and
a development launch get, and the files the mod owns in the game directory.

None of this is needed to play. Everything a player should change is in the
in-game settings screen, described in [Settings](SETTINGS.md).

## How To Set A Property

Properties below are read at startup or first use; some are read again later.
Set them before launching the game, and restart after changing the launch
arguments.

In a launcher, add `-Dkey=value` to the profile's or instance's JVM arguments.
For a client the developer tools launch, add it to `launch.jvmArgs` in ignored
`tools/config.local.json`, as [JVM Arguments](#jvm-arguments) describes.

The named owner class defines each property's current default, accepted range,
and invalid-value behaviour. Sizes are in bytes and durations in nanoseconds
unless the key name says otherwise.

## Vulkan Frame Submission

The released renderer uses ordinary Vulkan BLAS and TLAS. CLAS/PTLAS experiments
are not part of this source tree.

`RemixRenderer` keeps the runtime Submit call's default host-submission ordering:
the runtime's Vulkan signal is submitted to the driver before Minecraft consumes
the shared images. This waits for command submission, not GPU completion.
The shared-device path leaves generated frames in the presenter's asynchronous
pacing queue. Source detachment, swapchain recreation, and device teardown still
drain presentation work before releasing its resources.
The early scene submission advances the runtime's scene frame once. The late
host-image present does not advance it again; frame-generation continuity and
other temporal consumers require consecutive scene-frame IDs.

`RenderSystemMixin` records the image release, calls Minecraft's original frontend
encoder submission once. `NativeVulkanSurface` then supplies Minecraft's final
color to the runtime's sole Vulkan presenter, completing the shared-image
handoff without ending the RTX scene a second time. If the frontend has no
acquired surface, the ownership handoff still completes. The frontend submit
also closes Minecraft's GPU profiling frame.
`VulkanInterop.releaseImages` only records barriers and the timeline signal;
its shutdown caller submits them before waiting for the device and freeing images.
An extra encoder submission advances Minecraft's resource ring and can force the
CPU to wait for the current GPU frame, reducing overlap.

Frame generation snapshots depth, motion, and a compact camera alongside each
virtual backbuffer. Reuse waits on that slot's GPU acquire semaphore, after the
previous generated frames have read its inputs. This is independent of the
timeline that releases Minecraft's source color. Scene capture can stop while
presentation remains alive; the native runtime retires with Minecraft's device.

Benchmark counters `DLSS.RenderedPresents` and `DLSS.GeneratedPresents` report
asynchronous native presentation counts. Use both alongside rendered frame
times; multiplying the rendered FPS by the selected mode does not measure
successful generation. Counter comparisons restart across presenter changes.
`Vulkan.DeviceLocalUsedMiB` and `Vulkan.DeviceLocalBudgetMiB` are the driver's
current-process estimates for device-local heaps, sampled every 64 presents.
They are separate from `Texture.SharedSavedMiB`, which counts allocations
avoided by sharing identical immutable atlas channels.

### Path-tracer work ordering

On devices with Shader Execution Reordering, the G-buffer PSR pass separates
hit-object traversal from material shading and groups hits by material before
reading its textures. The primary visibility pass keeps its RayQuery path.
Ray counts, displacement, material evaluation, resolution and reconstruction
settings are unchanged. The runtime option
`rtx.enableShaderExecutionReorderingInPathtracerGbuffer=false` selects the
original ordering for a matched timing comparison. Unsupported devices keep
their original path. Compare rendered frame times with frame generation off;
the effect depends on the scene and GPU.

## Volumetric Lighting Quality

Sodium's advanced page controls volume pixel spacing, depth layers and history
length. Smaller spacing or more depth layers resolves finer light boundaries at
higher GPU and memory cost. More history reduces noise but reacts more slowly
to changing lighting. Apply commits these controls together. Fog density,
height and scattering direction remain in the visual settings.

The reconstruction output adds 12 bytes per lighting cell, independently of
the two temporal history buffers. At 1920×1080 with spacing 8 and 64 depth layers,
this is about 23.7 MiB per volume before allocator padding. Reconstruction does
not feed back into history or blur surface textures.

## Terrain Capture

Section capture, column pages and CPU FFM uploads use the same 44-byte vertex
attributes. `COMPACT_VERTICES` omits only the 20 trailing padding bytes from
the legacy 64-byte input format. The runtime copies a compact page contiguously
before the foreign call returns. Legacy CPU input and shared Vulkan buffers
retain their existing layouts. No position, normal, UV, colour or atlas-bound
precision changes. Native source geometry remains host-readable; the runtime's
geometry cache allocates its GPU copy when needed.

### Optional Far Terrain

Remix Horizons is a separate voxel addon. Its RocksDB cache stores observed
terrain; its CPU workers build meshes for a screen-space-selected octree.
The addon converts completed CPU quads to
Remix meshes and uses the main renderer's atlas, PBR, water, and lighting.
There is no OpenGL/Voxy adapter, second raster scene, or far-scene composite.
See [Far Terrain](FAR_TERRAIN.md) for the interface and integration limits.

`ChunkManager` prepares hidden near instances under a frame budget and publishes
complete column groups through the same path with or without the addon. Failed
activation rolls back the group's visible prefix. The addon receives only
committed near-section coverage; it cannot delay near publication. Coverage is
uploaded once after the frame's near changes, and coarse ray candidates covered
by near geometry are rejected before choosing the closest hit.

Far distance comes from the installed extension. Automatic distance fading
extends beyond the near ring below authored biome and zone profiles. The shipped
`view_distance_voxy.conf` filename remains for existing author-owned configuration
files; it no longer denotes a Voxy integration. The layer's 2048-chunk endpoint
blends above the 32-chunk baseline and is released when far capture stops.
The native camera extends its far plane with the configured radius.

`Chunk.LodVisibilityMutations` reports native instance visibility changes.
Uploading meshes is paced; an atomic parent/child switch can still contain many
visibility changes. These bounds do not establish a frame-rate or total GPU
memory limit. Native acceleration structures and renderer textures have their own
costs, which must be measured on the target GPU.

Animated LabPBR channels are checked for exact periodicity at resource load.
Roughness, metallic, and scattering channels identical at every frame origin
retain their initialized pixels without repeated uploads or mip generation.
Emission still follows animated albedo even with a static mask. Animation capture
copies unblended RGBA frames by row into owned buffers; no live image pointer
survives capture.

### Capture Queues

Vanilla's chunk workers capture section geometry and hand it to the render
thread through a bounded queue. These bound how much work is in flight and how
long a worker may wait.

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.chunk.sectionBuilders` | Concurrent vanilla section builds. Vanilla allocates one buffer per logical CPU, which floods the frame that a chunk-boundary crossing lands in | `SectionBuildConcurrency` |
| `rtxremix.blockDataQueue.maxPendingItems` | Section captures queued for the render thread | `BlockDataQueue` |
| `rtxremix.blockDataQueue.maxPendingBytes` | Bytes of queued captures | `BlockDataQueue` |
| `rtxremix.blockDataQueue.maxDrainItems` | Captures drained per render-thread pass | `BlockDataQueue` |
| `rtxremix.blockDataQueue.maxDrainBytes` | Bytes drained per pass | `BlockDataQueue` |
| `rtxremix.blockDataQueue.publishWaitBudgetNs` | Initial worker wait budget before attempting FIFO eviction. In-flight reservations can require additional waits; it is not a total-wait limit | `BlockDataQueue` |
| `rtxremix.chunk.localBatchPacing` | Pace the integrated server's chunk delivery while capture is on, spreading chunk-load bursts across frames. Set to false to restore vanilla's single batch per tick | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBatchBudgetMs` | Measured render-thread chunk-apply time, in milliseconds, the paced batch may fill per tick | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBacklogBudgetMs` | Ceiling, in milliseconds, the per-tick budget rises to while a backlog is waiting, so a join burst streams in rather than landing in one frame | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBatchBacklogDivisor` | Share of the backlog, one chunk per this many waiting, added to the per-tick budget | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBatchMaxChunks` | Cap on chunks in one paced batch, whichever of the time budget and this cap is smaller; keeps a row spread even where the apply is cheap, because the rebuilds a row triggers cost the frame more than its deserialization | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localBacklogMaxChunks` | Ceiling that cap rises to while a backlog is waiting | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localUnloadPacing` | Pace the integrated server's chunk unload packets alongside its chunk data, so a crossing's trailing row does not land in the same drain as the new row. Set to false to send unloads the tick a column leaves the filter; off whenever `rtxremix.chunk.localBatchPacing` is off | `LocalChunkBatchPacing` |
| `rtxremix.chunk.localUnloadBudgetMs` | Measured render-thread unload time, in milliseconds, the paced unloads may fill per tick | `LocalChunkBatchPacing` |
| `rtxremix.chunk.neighbourReflagFilter` | Drop vanilla's chunk-load and light-data neighbour rebuild flags when the neighbour's last build already saw the loading column. Set to false to restore those flags. Block updates and the loading column itself stay vanilla | `NeighbourReflagFilter` |
| `rtxremix.light.vanillaSnapshot` | Set to true to make the client light engine clone its section map every pass as vanilla does. With false, while capture is on, the live map is published as the snapshot, which rests on nothing but the render thread reading client light | `ClientLightSnapshotPolicy` |
| `rtxremix.light.vanillaSurfaceScan` | Set to true to start each column's sky-light surface walk at the top of the chunk as vanilla does. With false the walk is seeded at the packet's `WORLD_SURFACE` height, which skips the air-over-air pairs that were most of the render thread's chunk-apply cost; the remedy for a sender whose heightmaps do not match its blocks | `SkyLightSurfaceScanPolicy` |
| `rtxremix.vanillaVisibilityWalk` | Set to true to leave the chunk visibility walk to vanilla with RTX on instead of the mod's allocation-free port, which must produce the same visible set | `ChunkRenderingDataPreparerMixin` |
| `rtxremix.logVisibleSet` | Log the size and a position hash of every full visibility walk's result whenever it changes, to compare the port against vanilla over one camera | `ChunkRenderingDataPreparerMixin` |
| `rtxremix.joinHold` | Keep vanilla's terrain-download screen up until frame times settle after a world join, with a short bounded hold, so the first-sight mesh, pipeline, and BLAS costs land behind the screen. Set to false to disable the hold | `JoinBurstHold` |

`BlockDataQueue.acquireCapacity` is authoritative for waiting: a worker
initially waits up to the configured budget (500 ms by default, with a 50 ms
floor), then attempts FIFO eviction until its capture fits. Capacity held by
reservations between reserve and enqueue cannot be evicted, so the worker
rearms another 50 ms wait and retries while the capture remains current.
The initial budget is not a hard upper bound on total waiting. The render
thread's synchronous builds never wait because it is the sole consumer.

The defaults bound worker captures to 512 items and 128 MiB. An evicted capture
that still belongs to a resident section is re-flagged through
`BuiltChunk.scheduleRebuild`, up to 128 per frame, and contributes to
`Chunk.CaptureEvicted`. The queue and `ChunkManager` own these limits; consult
their current constants when interpreting a capture.

## Terrain Assembly And Publication

A low-concurrency executor merges section buffers into chunk-column pages; the
render thread publishes them and retires the pages they replace, both under
per-frame budgets.

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.chunk.meshBuildThreads` | Threads in the column merge executor | `ChunkManager` |
| `rtxremix.chunk.maxPendingBuilds` | Merges outstanding at once | `ChunkManager` |
| `rtxremix.chunk.maxPendingMeshBytes` | Bytes of merged but not yet installed geometry | `ChunkManager` |
| `rtxremix.chunk.installBudgetNs` | Render-thread time per frame spent installing columns | `ChunkManager` |
| `rtxremix.chunk.maxInstalledColumnsPerFrame` | Columns published per frame | `ChunkManager` |
| `rtxremix.chunk.columnStreamSettleFrames` | Frames a column waits for its neighbours before publishing | `ChunkManager` |
| `rtxremix.chunk.columnStreamMaxWaitFrames` | Hard cap, in frames, on that wait | `ChunkManager` |
| `rtxremix.chunk.maxSectionRemovalsPerFrame` | Deferred section removals per frame | `ChunkManager` |
| `rtxremix.chunk.maxSectionRemovalValidationsPerFrame` | Removal checks per frame | `ChunkManager` |
| `rtxremix.chunk.maintenanceBudgetNs` | Render-thread time per frame for terrain maintenance | `ChunkManager` |
| `rtxremix.chunk.maxColumnUnloadsPerFrame` | Column unloads processed per frame | `ChunkManager` |
| `rtxremix.chunk.meshPageBytes` | Byte cap on one terrain mesh page | `BlockMeshBuilder` |
| `rtxremix.chunk.meshRetireBudgetNs` | Render-thread time per frame spent retiring replaced pages | `TerrainAssetRetirementQueue` |
| `rtxremix.chunk.meshRetireSoftCount` | Queued retirements before backpressure starts | `TerrainAssetRetirementQueue` |
| `rtxremix.chunk.meshRetireHardCount` | Queued retirements that force the queue to drain | `TerrainAssetRetirementQueue` |
| `rtxremix.chunk.meshRetireSoftBytes` | Byte equivalent of the soft count | `TerrainAssetRetirementQueue` |
| `rtxremix.chunk.meshRetireHardBytes` | Byte equivalent of the hard count | `TerrainAssetRetirementQueue` |

Retirement has two different gates in `TerrainAssetRetirementQueue`.
`retirementBackpressured()` is a hysteretic count-or-bytes limit for teardown
(column unloads, section removals, and zombie audits).
`retirementBytesBackpressured()` pauses installs only at the hard byte bound,
currently 512 MiB. Keeping the gates separate prevents retirement of terrain
behind the camera from starving publication ahead of it.

Do not add a clock read around each native call inside these wall-clock budgeted
loops. That overhead consumes the same budget as useful page work and can
reduce publication throughput without showing up as a sampling hotspot.
`ProfileScope` is the per-call timing instrument; `TerrainNativeCallStats`
keeps the frame tallies clock-free.

## Block Lights

Light creation and destruction are native calls like any other, so they run on
the render thread under their own budget. Whether a surviving light is drawn is
decided per frame: a connectivity flood from the camera's section marks which
sections are reachable, and a reach test drops lights too dim to matter at
their distance.

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.chunk.maxLightOpsPerFrame` | Light create, update, and destroy calls per frame | `ChunkManager` |
| `rtxremix.chunk.lightBudgetNs` | Render-thread time per frame for those calls | `ChunkManager` |
| `rtxremix.chunk.maxPendingLightOps` | Queued light work before backpressure | `ChunkManager` |
| `rtxremix.light.floodSliceNs` | Render-thread time per frame a connectivity rebuild may take. The rebuild fills a second set across frames and swaps it in when complete, so a larger slice finishes sooner and a smaller one costs less per frame | `TerrainState` |

## Entities, Textures, And Particles

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.entity.meshCache.maxEntries` | Pooled entity meshes kept for reuse | `RemixEntitySubmitter` |
| `rtxremix.dynamicTextureCache.maxEntries` | Entries in the dynamic texture cache that serves skins and glyph pages | `DynamicTextureCache` |
| `rtxremix.directBufferPool.maxBytes` | Bytes of idle direct buffers kept pooled. 0 disables pooling | `DirectBufferPool` |
| `rtxremix.waterRapids.spawnBudgetPerTick` | Foam and mist particles spawned per tick near fast water. 0 turns the effect off | `WaterRapidParticleSpawner` |
| `rtxremix.particles.fastAirCollision` | Take a fast path through particle collision when every swept cell is air. Set to false to disable the fast path | `ParticleStateMixin` |
| `rtxremix.particles.fastAirCollisionMaxBlocks` | Largest sweep, in cells, that the fast path handles | `ParticleStateMixin` |

## Profiling And Diagnostics

| Key | Effect | Owner |
|---|---|---|
| `rtxremix.benchmark.jfrPath` | Names the JFR dump the benchmark flushes before it writes its result. A non-empty value also turns Java phase events on | `ProfileScope`, `BenchmarkController` |
| `rtxremix.profile.javaPhases` | Enable Java phase events in JFR. A non-empty `jfrPath` forces them on | `ProfileScope` |
| `rtxremix.profile.tracyJavaZones` | Mirror the same phases as Tracy zones; follows `javaPhases` unless explicitly set. Set to true for a Tracy-only session with no JFR recording | `ProfileScope` |
| `rtxremix.profile.tracyJavaMaxDepth` | Nesting depth limit for those Tracy zones. -1 means unlimited | `ProfileScope` |
| `rtxremix.profile.clientTasks` | Enable per-client-task JFR events; only read when Java phase events are on | `ProfileScope` |
| `rtxremix.profile.clientTaskSlowThresholdNs` | A client task slower than this gets its own JFR event | `ProfileScope` |
| `rtxremix.debug.entityTypeSummary` | Enable a periodic per-entity-type capture summary in the log. It costs a map probe per entity per frame and a sort and format pass on the render thread, so it is a diagnostic, not a setting | `RemixEntityPass` |

Tracy zones only appear when the staged runtime was built with Tracy support.
JFR works with any runtime.

## The Vanilla Worker Pool

The mod sets vanilla's own `max.bg.threads` property at pre-launch, before
Minecraft creates its shared `Worker-Main` pool.

Section builds, chunk loads, lighting updates, and resource reloads share that
pool. With Remix capture inline in section builds, a burst of workers contends
with the render thread for CPU time, so the mod bounds the pool.

`WorkerPoolCap` chooses the smaller of 8 and logical CPUs minus one, with a
minimum of one. It must run at pre-launch because `net.minecraft.util.Util`
creates the pool in a static initializer. The section-builder cap alone does
not limit the other work sharing that pool.

The cap is only applied when the property is unset, so `-Dmax.bg.threads=N`
opts out and keeps your value. `WorkerPoolCap` logs which of the two happened.

## Gradle Pass-Throughs

`gradlew` accepts these `-P` properties. Architecture selection applies to any
task; the rest apply to `runClient`, Fabric's standard Gradle task, and not to
clients the developer tools launch.

| Property | Effect |
|---|---|
| `-Parch=win64` or `-Parch=arm64` | Build and package one architecture instead of both |
| `-PrtxPreset=<low|medium|high|ultra>` | Sets `rtxremix.preset`, which fills editable visual settings for that session without changing DLSS or frame generation |
| `-PmcQuickPlayServer=<host:port>` | Join a server on launch |
| `-PbenchmarkJfrPath=<file.jfr>` | Starts a JFR recording, sets `rtxremix.benchmark.jfrPath`, and turns Java phases on |
| `-PbenchmarkTracyJavaMaxDepth=<n>` | Sets `rtxremix.profile.tracyJavaMaxDepth`; only applies when a JFR path is given |
| `-PbenchmarkJfrThrowables=true` | Adds exception and error throw events to that recording |
| `-PbenchmarkQuickPlayWorld=<name>` | Loads a single-player world on launch |

`mod_version` lives in `gradle.properties`; the build appends a timestamp, the
short commit hash, and a dirty marker to it.

## JVM Arguments

Use Java 25 and `--enable-native-access=ALL-UNNAMED`. Development Gradle arguments
are in `rtx-remix-minecraft/build.gradle`; the optional Prism workflow reads
`rtx-remix-minecraft/packaging/launcher-defaults.json`. Keep those two defaults
aligned. Benchmarks add profiling flags separately. Players choose their heap
size in their own launcher; resource packs and view distance affect its needs.

Prism takes heap sizes through its memory settings, not duplicate `-Xmx` flags
in `JvmArgs`. The development launcher separates these when writing its instance.
There is no release installer and no automatic rewrite of a player's JVM options.

## Files In The Game Directory

Paths are relative to the game directory, which is the checkout-specific Prism
instance for a client the developer tools launch and the instance or profile
directory for an installed client. Portable Prism keeps the former at
`run-prism/instances/rtx-remix-dev/.minecraft`.

| Path | Owner | Access | Deleting it |
|---|---|---|---|
| `remix-sdk/` | mod | Validated source runtime, provenance, and separately built `rtxremix-support.dll`; replaced on full extraction | Missing runtime, support library, or provenance triggers full extraction even with a matching marker |
| `rtx.conf` | mod | Replaced on full extraction; with a matching marker and runtime present, copied only if missing | Restored on the next launch |
| `.rtx-remix-version` | mod | Marker recording the extracted version, architecture, and SHA-256 fingerprints of bundled runtime provenance and the support library | Forces full re-extraction of the runtime and `rtx.conf`, preserving existing files under `rtx-remix/` |
| `remix-sdk.old-*/` | mod | Left behind when the previous runtime was locked at upgrade time | Safe, and cleaned up opportunistically |
| `rtx-remix/mods/minecraft-logic/` | map or profile author | Missing shipped files are added on launch even with a matching marker; existing files, including edited confs, are never overwritten | A deleted shipped conf returns to its default on the next launch |
| `textures/` | mod | The shipped runtime DDS textures, read by name and replaced on full extraction; other files there are left alone | Restored on the next launch even with a matching marker |
| `config/rtxremix.json` | settings screen | Written when a settings screen closes, and deleted when nothing differs from the defaults | Resets every setting |
| `camera-paths/<world-key>/` | you | Your recordings | Loses them, so keep this one |
| `benchmark/` | benchmark harness | Trigger file, status heartbeat, results, and screenshots | Safe |
| `saves/<world>/rtx-remix/confs/` | map author | Read when you join that world | Part of the map, not of the install |
| `saves/<world>/data/rtx_remix_zones.dat` | mod, per save | Zone definitions for that world | Loses the zones defined in that world |
| `logs/latest.log` | game | Where the mod reports what it did and why | Safe |

## Resource Pack Inputs

Directory atlas sources exclude paired `_n`, `_s`, and `_sss` material maps from
the color atlas. Their original pixels remain available to the PBR loader; this
does not reduce texture resolution. Unpaired texture names are left alone.
The renderer uses shared atlas textures instead of allocating another set of
GPU textures for every block sprite. Immutable images with identical pixels,
dimensions, formats and mip filters share one native allocation, including
across atlas channels. Color and data channels can share when their mip
generation is identical; generated sRGB, normal and height filters stay distinct.
Uniform immutable scalar data maps use a single texel. Mutable channels and
authored mip chains keep their original extents.
Memory textures keep no persistent native CPU pixel copy. Uploads snapshot only
the supplied base level, authored levels, or dirty patches before the foreign
call returns. Generated mips run on the shared Vulkan device in affected 8 by 8
tiles, with one writer per tile and the same color, normal and height filters.
Authored mip levels remain verbatim and are updated only when supplied.
Sprite atlases use their existing `SpriteContents` instead of a second upload
mirror, and font uploads write directly into their glyph mirror.
Minecraft's animation and dynamic entity sources retain the CPU data needed
to supply incremental patches. Texture memory also
includes driver and GPU allocations, so Java heap usage alone does not measure
the process's texture footprint.

SHaRC allocates its surface-filter resources only while that filter is selected.
Its two filtered-lighting histories retain RGB at the original half-float
precision; hash-slot ownership is held once in the guidance buffer. At the
default four million entries, the two histories occupy 64 MiB together.

API meshes without skinning use build-only BLAS storage. Instance transforms,
material changes, and smooth-normal generation do not require mutable vertex
positions. Skinned meshes and view models retain BLAS update support; once a
BLAS needs deformation, it keeps that support for its remaining lifetime.
Immutable BLAS are compacted after the GPU reports their final sizes. A bounded
query queue and copy budget spread this work across frames without a CPU wait.
New geometry is visible before compaction completes. Relocation updates cached
TLAS addresses without invalidating surface or material data; command-list
references retain old storage for in-flight and previous-frame tracing.

Allocation chunks default to 32 MiB in system memory and 128 MiB in device-local
memory. Their sizes change allocator slack, not resource resolution or capacity.
`dxvk.otherMemoryChunkSizeMB` and `dxvk.deviceLocalMemoryChunkSizeMB` override
these startup settings.

For resource attribution, start with both `RTX_MEMORY_SNAPSHOTS=1` and
`RTX_PROFILER_MEMORY_ENABLE=1`. The runtime logs named allocation groups and heap
budgets on ray-traced frames 1, 300 and 600. Groups below 1 MiB are included in
the totals but omitted from the list. Logical image and buffer sizes differ
from allocator block sizes and driver residency. The acceleration-structure
category also contains geometry buffers, so its total is not the BVH size.
`API mutable texture` lines give the logical mip payload and persistent native
CPU pixel bytes for each creation. `BLAS compaction` lines report cumulative copy
sizes, which differ from live allocator usage when geometry has been retired.

To check generated mip pixels, set `RTX_API_TEXTURE_CAPTURE_PATH` to a local
directory and `RTX_API_TEXTURE_CAPTURE_HASH` to the texture's decimal or `0x`
hash. Matching uploads write a five-uint32 header (width, height, canonical
format, purpose, mip count), followed by tightly packed raw levels. This opt-in
diagnostic waits for its readback and must be disabled during performance runs.

Textures, material maps, and block light rules come from resource packs, not
from properties. Their formats are in [Resource Packs](RESOURCE_PACKS.md).
