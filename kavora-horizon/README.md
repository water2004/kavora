# Kavora Horizon

Kavora Horizon is an independent Fabric addon for the [Kavora renderer](https://github.com/water2004/rtx-remix-minecraft). It captures observed terrain beyond Minecraft's near renderer and submits it to the same path-traced Vulkan scene. It is optional and ships as a separate JAR.

## Build and install

Use Windows x64, Java 25, and Minecraft 26.3. From the monorepo root, build the addon and its declared renderer dependency:

```powershell
.\gradlew.bat :kavora-horizon:fabric:build
```

Install `kavora-horizon/fabric/build/libs/Kavora-horizon.jar` as a separate JAR alongside the matching Kavora renderer, Fabric API, and Sodium 0.9.2. The renderer must provide the `kavora-quad-mesh-v1` capability. The Fabric mod ID is `kavora-horizon`. Do not install Distant Horizons or Voxy alongside this addon; the mod metadata declares them incompatible. The renderer, Fabric API, and Sodium are not bundled in the addon.

## Terrain and cache

The addon observes chunks delivered to the client. It does not request unexplored chunks, force chunk loads, or generate distant terrain. Observed data is stored as fixed 32×32×32 voxel sections at multiple detail levels in a world- and dimension-scoped RocksDB cache. Coarser sections are derived from observed terrain, and missing data remains distinct from observed empty terrain.

This cache is incompatible with the old Distant Horizons database. Kavora Horizon creates its own `voxels-v1` cache and does not migrate or delete existing DH caches. Single-player caches live under the world's `kavora-horizon` directory; multiplayer caches live under the game directory's `kavora-horizon/servers` directory.

The addon builds CPU meshes from voxel sections and publishes geometry to the renderer's shared Vulkan scene. It uses the renderer's atlas materials and near-terrain coverage rather than a separate raster LOD renderer. Geometry uses four float3 positions and one material-table index per quad, shared affine UV/color attributes, and a common index pattern. The runtime owns one immutable GPU copy after upload. Sodium's video settings include the addon's distance, detail, and geometry-memory controls.

Voxel records use a local material palette and packed indices. RocksDB compresses newly written records with Zstandard level 1; existing LZ4 records remain readable without migration or a forced cache rebuild.

Mesh workers build occupancy and full-opaque row masks to skip internal solid faces in groups of 32 cells. Greedy rectangle generation traverses only live face bits and consumes rectangles through those masks, keeping material, fluid height, and paint order intact. Cutouts, partial models, liquids, and waterlogged companion geometry retain their directional visibility rules. Storage has one owner and meshing uses up to three workers. Completed geometry expands through at most eight staging batches; the render thread publishes it under a shared 1.5 ms upload/retirement budget and a 4 MiB upload ceiling per frame. Faster loading does not increase that staging-memory limit or reduce selected detail.

With `-Dkavora.profile.javaPhases=true`, JFR records `Lod.Ingest`, `Lod.Metadata`, `Lod.MeshRead`, `Lod.Mesh`, and `Lod.Expand`, alongside the render thread's `Lod.Upload` and `Lod.Retire` phases. These distinguish worker execution from a render-thread publication bottleneck. The scopes are disabled in normal play.

## Licenses

The project is distributed under [LGPL 3.0](LICENSE.LESSER.txt); the original [GPL notice](LICENSE.txt) is retained. The material descriptors retain their [Apache 2.0 license](common/src/main/resources/LICENSE-APACHE-2.0.txt). The bundled RocksDB 10.2.1 is used under its [Apache 2.0 license](licenses/rocksdb-LICENSE-Apache.txt); its [LevelDB notice](licenses/rocksdb-LICENSE.leveldb.txt) is also retained. The earlier Distant Horizons-derived code is no longer part of the build. Voxy source was used for architectural study and is not included in this addon.
