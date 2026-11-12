# Far Terrain

The base renderer exposes the Fabric entrypoint `kavora:far_terrain` and the
capability `kavora-far-terrain-v1`. It contains no far-terrain storage or mesher.
One optional addon supplies `FarTerrainExtension.Factory`; multiple factories
are rejected. Sodium and Fabric API remain independent dependencies.

Kavora Horizon lives in `kavora-horizon/` in this repository and uses the mod ID
`kavora-horizon`. Its active pipeline is an independent sparse 3D voxel engine.
The previous DH storage, column mesher, selection, platform layers and world
generation code have been removed. Neither DH nor Voxy is supported alongside
this addon. Voxy was studied as an architectural reference; its code is not
included.

## Observation and storage

Only chunks delivered by Minecraft are observed. Chunk sections are copied on
the client thread. A bounded batch of at most four detached columns converts
on the meshing worker pool, while the storage worker serves mesh and metadata
reads. That worker commits the completed batch in order into immutable 32-cubed
voxel sections. Levels 0 through 4 preserve three-dimensional occupancy and
representative material identities. Known empty data and unobserved data are
distinct. Water heights remain in world units across mip levels.
Newly observed block states queue model discovery before meshing. Resource
models are still baked on the render thread under the palette's drain limit.

RocksDB stores a local palette and packed indices for each section. A write
batch commits changed sections, their mip ancestors and newly referenced
material identities together. The decoded CPU cache is bounded to 1024 sections.
Each write batch copies a changed node at most once, then publishes an immutable
snapshot. Mesh workers can retain the preceding snapshot without locks. Empty
records share a read-only zero array and do not run face generation.
Neighbourhood and child metadata reads use ordered database batches. Known
absent sections are cached separately from an unqueried entry; a subsequent
observation replaces the absent entry with the new immutable section.
Single-player caches live below the save's `kavora-horizon/` directory;
multiplayer caches live below the game directory's `kavora-horizon/servers/`.
The `voxels-v1` format does not import old DH databases and leaves them untouched.
No unexplored terrain is generated or requested.

## Mesh selection and publication

Workers cull internal faces and greedily merge directional face masks into
8-byte CPU quad records. Model descriptors preserve partial geometry; textures
repeat in world-block units rather than stretching across a coarse cell. Mip
selection uses projected node area with a split/merge hysteresis. Queue size
and memory pressure do not change the selected screen-space detail.

A resident parent remains visible until every known child in its replacement
is ready. A merge retains children until its parent is ready. Each transaction
names its replaced cover, so admission needs only those nodes' memory totals.
Sixteen transactions can overlap IO, meshing, material resolution and upload;
only the active mesh workers hold decoded neighbourhood snapshots. Vertex
expansion keeps at most eight four-page batches pending.

Upload and retirement share a render-thread time budget, with separate byte
limits. Retirement runs first so old geometry cannot strand an otherwise idle
upload budget. Surface categories are packed together into byte-capped pages.
Unchanged quad arrays reuse their native resources, and only changed boundary
materials invalidate a neighbouring mesh. Off-screen detail may remain resident
while memory is available and is merged when capacity is needed.

All native resources belong to the render thread. Workers never read live
client chunks or mutable model instances. Cancelling publication does not free
a CPU buffer until its last reader has finished. World close drains accepted
observations, and the next session waits for the previous database to close.

## Shared Vulkan scene

`ChunkManager` owns near geometry publication. `recordNear` reports committed
16-block sections; it is not an approval callback. `flushCoverage` publishes a
3D mask after near updates and before scene submission. The runtime clips
far hits covered by near geometry, including secondary rays.

The same published mask has a CPU summed-volume index. A far page wholly
covered by near sections leaves the ray-traced scene; its mesh remains
resident so removing near coverage restores it in the same scene submission.
Bounds include the runtime's coverage-position offset and float rounding.
Partly covered pages still use per-hit clipping. This decision applies to all
rays and does not discard geometry merely because the camera cannot see it.
`-Dkavora.horizons.disableCoveredPageCulling=true` disables the page-level
optimization for image and timing comparisons. Diagnostics report submitted
and covered page counts separately.

Both distances use `BlockTextureManager` materials: albedo, PBR, emissive,
subsurface scattering and animated water. Biome tints are albedo, with no baked
lightmap. Coplanar overlays use the near renderer's decal blend rule. Liquid
interfaces inside solid blocks or continuous water volumes are culled.
There is no separate raster framebuffer or OpenGL readback.

CPU pages and their FFM uploads contain 44-byte vertices, marked
`COMPACT_VERTICES`. They contain all vertex attributes without the legacy
20-byte trailing padding. The runtime copies each vertex array contiguously
and keeps every attribute bit. The geometry budget accounts for this storage
plus indices. Legacy CPU input and shared-buffer vertex layouts remain
64 bytes. This requires the matching source-built runtime, not a version label
applied to an older DLL.

## Build and settings

Build the base renderer with its matching source-built runtime first, then:

```powershell
# From the monorepo root, using Java 25:
.\gradlew.bat :kavora-horizon:fabric:build
```

Install `kavora-horizon/fabric/build/libs/Kavora-horizon-1.1.0+mc26.3.jar`
alongside the matching renderer, Sodium 0.9.2 and Fabric API. The addon contains
neither the main mod nor the Remix native runtime. Its sources JAR includes both
the core and Fabric sources. The base renderer still needs the native path tracer
and DLSS libraries.

Sodium's Kavora Horizon page exposes enabled state, distance in chunks,
projected node size in pixels, and a geometry payload budget. Smaller pixel
values select finer geometry. The memory budget excludes runtime textures and
acceleration structures. Lighting and DLSS remain in the base renderer's settings.

## Verification

CPU checks cover persistence, 3D reduction, water heights, boundary invalidation,
atomic parent/child replacement, face generation, texture coordinates, native
buffer ownership and reservation accounting. Artifact checks reject DH classes,
SQLite, and embedded Sodium/Fabric/main-mod JARs.

The [client GameTest](../kavora/src/gametest/README.md) checks continuous movement,
retained distant coverage, whole-view refinement and world exit/rejoin. It also
captures diagnostic images and optional JFR recordings. A successful assertion
is not an image-quality or performance baseline: inspect the screenshots and
compare measurements for the same scene, resolution and camera route.
