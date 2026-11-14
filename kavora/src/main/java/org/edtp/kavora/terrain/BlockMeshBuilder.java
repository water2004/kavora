/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.terrain;

import org.edtp.kavora.sdk.HardcodedVertex;
import org.edtp.kavora.sdk.MeshInfoSurfaceTriangles;
import org.edtp.kavora.asset.KavoraMaterial;
import org.edtp.kavora.asset.KavoraMesh;
import org.edtp.kavora.asset.KavoraMeshSurface;
import org.edtp.kavora.texture.BlockTextureManager;
import org.edtp.kavora.terrain.capture.SectionMeshData;
import org.edtp.kavora.util.DirectBufferPool;
import org.edtp.kavora.profiling.ProfileScope;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Merges the section captures from one chunk column into category-specific,
 * byte-capped {@link KavoraMesh} pages. Captured vertices are section-local;
 * the builder offsets them into column-local space and the instance transform
 * places each page in world space.
 */
public final class BlockMeshBuilder {

    private static final int STRIDE = HardcodedVertex.STORAGE_STRIDE;
    private static final int VERTS_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;
    private static final int BYTES_PER_CANONICAL_QUAD =
            VERTS_PER_QUAD * STRIDE + INDICES_PER_QUAD * Integer.BYTES;
    private static final int INITIAL_BUCKETS = 8;
    private static final long MIB = 1024L * 1024L;
    private static final long MIN_CONFIGURED_PAGE_BYTES = 256L * 1024L;
    private static final long DEFAULT_MAX_PAGE_BYTES = 8L * MIB;
    private static final long MAX_CONFIGURED_PAGE_BYTES = 64L * MIB;
    public static final int ALL_CATEGORY_MASK = (1 << ChunkManager.CAT_COUNT) - 1;
    private static volatile int[] canonicalIndexCache = new int[0];
    private static volatile int canonicalIndexCacheFilled;
    private final long maxPageBytes;

    /** One independently installable native mesh page and its render category. */
    public record MeshPage(int category, KavoraMesh mesh) {
        public MeshPage {
            if (category < 0 || category >= ChunkManager.CAT_COUNT) {
                throw new IllegalArgumentException("Invalid terrain mesh category " + category);
            }
            Objects.requireNonNull(mesh, "mesh");
        }

        public long estimatedNativeBytes() {
            return mesh.estimatedNativeBytes();
        }
    }

    public BlockMeshBuilder() {
        this(configuredPageBytes());
    }

    BlockMeshBuilder(long maxPageBytes) {
        this.maxPageBytes = Math.max(BYTES_PER_CANONICAL_QUAD,
                Math.min(MAX_CONFIGURED_PAGE_BYTES, maxPageBytes));
    }

    /**
     * Builds Remix meshes for one 16x16 chunk column.
     * Returns byte-capped pages. Every page carries the category metadata
     * needed to create its instance independently on the render thread.
     */
    public MeshPage[] buildColumnMesh(int chunkX,
                                      int chunkZ,
                                      List<TerrainState.SectionSnapshot> sections,
                                      int categoryMask,
                                      BlockTextureManager tex) {
        return buildColumnMesh(chunkX, chunkZ, sections, categoryMask,
                key -> resolveMaterial(key, tex));
    }

    MeshPage[] buildColumnMesh(int chunkX,
                               int chunkZ,
                               List<TerrainState.SectionSnapshot> sections,
                               int categoryMask,
                               Function<String, KavoraMaterial> materialResolver) {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(materialResolver, "materialResolver");
        validateCategoryMask(categoryMask);

        List<MeshPage> pages = new ArrayList<>();
        PageAccumulator accumulator = null;
        try (ProfileScope buildScope = ProfileScope.begin("buildColumnMesh")) {
            buildScope.captureCount(sections.size());
            int catCount = ChunkManager.CAT_COUNT;
            int columnWorldX = chunkX * 16;
            int columnWorldZ = chunkZ * 16;
            SurfaceBucket[] buckets = new SurfaceBucket[INITIAL_BUCKETS];
            int bucketCount = 0;

            for (TerrainState.SectionSnapshot snapshot : sections) {
                if (snapshot.xOff() != columnWorldX || snapshot.zOff() != columnWorldZ) {
                    throw new IllegalArgumentException(
                            "Section snapshot does not belong to chunk column ("
                                    + chunkX + "," + chunkZ + ")");
                }
                if (!(snapshot.data() instanceof SectionMeshData meshData) || meshData.isEmpty()) {
                    continue;
                }

                for (SectionMeshData.Surface surf : meshData.surfaces()) {
                    int cat = surf.category();
                    if (cat < 0 || cat >= catCount || !categorySelected(categoryMask, cat)) continue;

                    String bucketKey = surf.bucketKey();
                    int bucketIndex = findBucket(buckets, bucketCount, cat, bucketKey);
                    SurfaceBucket bucket;
                    if (bucketIndex >= 0) {
                        bucket = buckets[bucketIndex];
                    } else {
                        if (bucketCount == buckets.length) {
                            buckets = Arrays.copyOf(buckets, buckets.length * 2);
                        }
                        bucket = new SurfaceBucket(cat, bucketKey);
                        buckets[bucketCount++] = bucket;
                    }
                    bucket.add(surf,
                            snapshot.xOff() - columnWorldX,
                            snapshot.yOff(),
                            snapshot.zOff() - columnWorldZ);
                }
            }

            for (int c = 0; c < catCount; c++) {
                if (!categorySelected(categoryMask, c)) continue;
                accumulator = new PageAccumulator(c, pages, maxPageBytes);
                for (int i = 0; i < bucketCount; i++) {
                    SurfaceBucket bucket = buckets[i];
                    if (bucket.category != c || bucket.count == 0) continue;

                    KavoraMaterial mat = materialResolver.apply(bucket.key);
                    if (mat == null) continue;

                    requireCanonicalQuadIndices(bucket);
                    appendCanonicalPages(bucket, mat, accumulator);
                }
                accumulator.flush();
                accumulator = null;
            }
            buildScope.installCount(pages.size());
            return pages.toArray(new MeshPage[0]);
        } catch (RuntimeException | Error failure) {
            if (accumulator != null) accumulator.disposePending();
            for (MeshPage page : pages) {
                page.mesh().disposeData();
            }
            throw failure;
        }
    }

    // --- internals ---

    private static final class SurfaceBucket {
        final int category;
        final String key;
        SurfaceRef[] surfaces = new SurfaceRef[4];
        int count;
        int totalVerts;

        SurfaceBucket(int category, String key) {
            this.category = category;
            this.key = key;
        }

        void add(SectionMeshData.Surface surface, int xOff, int yOff, int zOff) {
            if (count == surfaces.length) {
                int next = count * 2;
                surfaces = Arrays.copyOf(surfaces, next);
            }
            surfaces[count] = new SurfaceRef(surface, xOff, yOff, zOff);
            count++;
            totalVerts += surface.vertexCount();
        }
    }

    private static final class PageAccumulator {
        private final int category;
        private final List<MeshPage> output;
        private final long maxPageBytes;
        private final List<KavoraMeshSurface> surfaces = new ArrayList<>();
        private long bytes;

        PageAccumulator(int category, List<MeshPage> output, long maxPageBytes) {
            this.category = category;
            this.output = output;
            this.maxPageBytes = maxPageBytes;
        }

        void add(KavoraMeshSurface surface, long surfaceBytes) {
            if (surfaceBytes <= 0 || surfaceBytes > maxPageBytes) {
                surface.disposeData();
                throw new IllegalArgumentException(
                        "Terrain surface page payload " + surfaceBytes
                                + " is outside the configured cap " + maxPageBytes);
            }
            boolean adopted = false;
            try {
                if (!surfaces.isEmpty() && bytes + surfaceBytes > maxPageBytes) {
                    flush();
                }
                surfaces.add(surface);
                adopted = true;
                bytes += surfaceBytes;
                if (bytes == maxPageBytes) {
                    flush();
                }
            } finally {
                if (!adopted) surface.disposeData();
            }
        }

        void flush() {
            if (surfaces.isEmpty()) return;
            KavoraMesh mesh = null;
            try {
                mesh = new KavoraMesh(surfaces.toArray(new KavoraMeshSurface[0]));
                output.add(new MeshPage(category, mesh));
            } catch (RuntimeException | Error failure) {
                if (mesh != null) {
                    mesh.disposeData();
                    surfaces.clear();
                    bytes = 0;
                } else {
                    disposePending();
                }
                throw failure;
            }
            surfaces.clear();
            bytes = 0;
        }

        void disposePending() {
            for (KavoraMeshSurface surface : surfaces) {
                surface.disposeData();
            }
            surfaces.clear();
            bytes = 0;
        }
    }

    private record SurfaceRef(SectionMeshData.Surface surface,
                              int xOff, int yOff, int zOff) {}

    private static long configuredPageBytes() {
        long configured = Long.getLong("kavora.chunk.meshPageBytes", DEFAULT_MAX_PAGE_BYTES);
        return Math.max(MIN_CONFIGURED_PAGE_BYTES,
                Math.min(MAX_CONFIGURED_PAGE_BYTES, configured));
    }

    private static boolean categorySelected(int categoryMask, int category) {
        return (categoryMask & (1 << category)) != 0;
    }

    private static void validateCategoryMask(int categoryMask) {
        if ((categoryMask & ~ALL_CATEGORY_MASK) != 0) {
            throw new IllegalArgumentException(
                    "Invalid terrain category mask 0x" + Integer.toHexString(categoryMask));
        }
    }

    private void appendCanonicalPages(SurfaceBucket bucket,
                                      KavoraMaterial material,
                                      PageAccumulator accumulator) {
        int stride = bucket.surfaces[0].surface().vertexStride();
        int bytesPerQuad = VERTS_PER_QUAD * stride + INDICES_PER_QUAD * Integer.BYTES;
        int totalQuads = bucket.totalVerts / VERTS_PER_QUAD;
        int maxQuadsPerSurface = (int) Math.max(1L,
                Math.min(Integer.MAX_VALUE, maxPageBytes / bytesPerQuad));
        for (int firstQuad = 0; firstQuad < totalQuads; ) {
            int quadCount = Math.min(maxQuadsPerSurface, totalQuads - firstQuad);
            KavoraMeshSurface surface = mergeCanonicalSurfaceRange(
                    bucket, material, firstQuad, quadCount, stride);
            accumulator.add(surface, (long) quadCount * bytesPerQuad);
            firstQuad += quadCount;
        }
    }

    private static KavoraMeshSurface mergeCanonicalSurfaceRange(
            SurfaceBucket bucket,
            KavoraMaterial material,
            int firstQuad,
            int quadCount,
            int stride) {
        int totalVerts = Math.multiplyExact(quadCount, VERTS_PER_QUAD);
        int totalIdx = Math.multiplyExact(quadCount, INDICES_PER_QUAD);
        ByteBuffer vm = null;
        ByteBuffer im = null;
        boolean transferred = false;

        try {
            vm = DirectBufferPool.acquire(Math.multiplyExact(totalVerts, stride));
            im = DirectBufferPool.acquire(Math.multiplyExact(totalIdx, Integer.BYTES));

            int wantedStart = firstQuad;
            int wantedEnd = firstQuad + quadCount;
            int sourceStart = 0;
            int outputVerts = 0;
            for (int s = 0; s < bucket.count && sourceStart < wantedEnd; s++) {
                SurfaceRef ref = bucket.surfaces[s];
                SectionMeshData.Surface source = ref.surface();
                int sourceQuads = source.vertexCount() / VERTS_PER_QUAD;
                int sourceEnd = sourceStart + sourceQuads;
                int overlapStart = Math.max(wantedStart, sourceStart);
                int overlapEnd = Math.min(wantedEnd, sourceEnd);
                if (overlapStart < overlapEnd) {
                    int localFirstQuad = overlapStart - sourceStart;
                    int copyQuads = overlapEnd - overlapStart;
                    int copyVerts = copyQuads * VERTS_PER_QUAD;
                    ByteBuffer src = source.vertexBuffer().duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    copySectionVertices(vm, outputVerts, src,
                            localFirstQuad * VERTS_PER_QUAD, copyVerts,
                            ref.xOff(), ref.yOff(), ref.zOff(), stride);
                    outputVerts += copyVerts;
                }
                sourceStart = sourceEnd;
            }

            if (outputVerts != totalVerts) {
                throw new IllegalArgumentException(
                        "Canonical terrain page range exceeds the bucket payload");
            }
            putCanonicalQuadIndices(im, totalVerts);
            int flags = MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS | MeshInfoSurfaceTriangles.COMPACT_VERTICES;
            int layer = BlockTextureManager.layerType(bucket.key);
            if (layer == BlockTextureManager.L_GLASS_MEDIUM_BOUNDARY) {
                flags |= MeshInfoSurfaceTriangles.MATCHED_IOR_BOUNDARY;
            } else if (layer == BlockTextureManager.L_GLASS || layer == BlockTextureManager.L_STAINED_GLASS_BLOCK) {
                flags |= MeshInfoSurfaceTriangles.WATER_CONTACT;
            }
            KavoraMeshSurface surface = new KavoraMeshSurface(material)
                    .setVertexData(vm, totalVerts)
                    .setIndexData(im, totalIdx)
                    .setFlags(flags);
            transferred = true;
            return surface;
        } finally {
            if (!transferred) {
                DirectBufferPool.release(vm);
                DirectBufferPool.release(im);
            }
        }
    }

    /**
     * Asserts the pipeline invariant that {@code DirectQuadWriter} emits: whole quads
     * whose indices follow the fixed per-quad pattern. Paging rewrites the indices from
     * that pattern rather than copying them, so a surface that breaks the invariant has
     * no representation here and must fail rather than be silently reinterpreted.
     */
    private static void requireCanonicalQuadIndices(SurfaceBucket bucket) {
        for (int s = 0; s < bucket.count; s++) {
            SectionMeshData.Surface surface = bucket.surfaces[s].surface();
            if (!surface.canonicalQuadIndices()
                    || surface.vertexCount() % VERTS_PER_QUAD != 0
                    || surface.indexCount() != canonicalIndexCount(surface.vertexCount())) {
                throw new IllegalArgumentException(
                        "Terrain surface in bucket " + bucket.key + " with "
                                + surface.vertexCount() + " vertices and "
                                + surface.indexCount()
                                + " indices does not carry canonical quad indices");
            }
        }
    }

    private static int findBucket(SurfaceBucket[] buckets, int bucketCount, int category, String key) {
        for (int i = 0; i < bucketCount; i++) {
            SurfaceBucket bucket = buckets[i];
            if (bucket.category == category && Objects.equals(bucket.key, key)) {
                return i;
            }
        }
        return -1;
    }

    private KavoraMaterial resolveMaterial(String bucketKey, BlockTextureManager tex) {
        boolean isSss = bucketKey.endsWith("_sss");
        String baseName = isSss ? bucketKey.substring(0, bucketKey.length() - 4) : bucketKey;

        if ("water".equals(baseName)) return tex.getWaterSurfaceMaterial();

        int layerType = BlockTextureManager.layerType(baseName);
        return isSss ? tex.getAtlasMaterialSSS(layerType)
                     : tex.getAtlasMaterial(layerType);
    }


    private static void copySectionVertices(ByteBuffer destination, int destinationBase,
            ByteBuffer source, int sourceBase, int vertexCount, int xOff, int yOff, int zOff, int stride) {
        destination.put(destinationBase * stride, source, sourceBase * stride, vertexCount * stride);
        for (int v = 0; v < vertexCount; v++) {
            int src = (sourceBase + v) * stride;
            int dst = (destinationBase + v) * stride;
            destination.putFloat(dst, source.getFloat(src) + xOff);
            destination.putFloat(dst + 4, source.getFloat(src + 4) + yOff);
            destination.putFloat(dst + 8, source.getFloat(src + 8) + zOff);
        }
        destination.position((destinationBase + vertexCount) * stride);
    }

    private static int canonicalIndexCount(int vertexCount) {
        return (vertexCount / VERTS_PER_QUAD) * INDICES_PER_QUAD;
    }

    /**
     * Writes the fixed per-quad index pattern for a whole page. Every page starts its own
     * vertex numbering at zero, so the pattern is position-independent and comes from the
     * shared cache rather than being generated per page.
     */
    static void putCanonicalQuadIndices(ByteBuffer dst, int vertexCount) {
        int indexCount = canonicalIndexCount(vertexCount);
        ByteBuffer indexBytes = dst.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        indexBytes.position(0);
        IntBuffer ints = indexBytes.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        ints.put(canonicalIndices(indexCount), 0, indexCount);
    }

    private static int[] canonicalIndices(int indexCount) {
        int[] cached = canonicalIndexCache;
        if (cached.length >= indexCount && canonicalIndexCacheFilled >= indexCount) {
            return cached;
        }

        synchronized (BlockMeshBuilder.class) {
            cached = canonicalIndexCache;
            int filled = canonicalIndexCacheFilled;
            if (cached.length < indexCount) {
                int newLength = Math.max(indexCount, Math.max(INDICES_PER_QUAD, cached.length * 2));
                cached = Arrays.copyOf(cached, newLength);
            }
            fillCanonicalIndices(cached, filled, indexCount);
            canonicalIndexCache = cached;
            canonicalIndexCacheFilled = indexCount;
            return cached;
        }
    }

    private static void fillCanonicalIndices(int[] dst, int fromIndex, int toIndex) {
        int startQuad = fromIndex / INDICES_PER_QUAD;
        int endQuad = toIndex / INDICES_PER_QUAD;
        for (int q = startQuad; q < endQuad; q++) {
            int base = q * VERTS_PER_QUAD;
            int i = q * INDICES_PER_QUAD;
            dst[i] = base;
            dst[i + 1] = base + 2;
            dst[i + 2] = base + 1;
            dst[i + 3] = base;
            dst[i + 4] = base + 3;
            dst[i + 5] = base + 2;
        }
    }
}
