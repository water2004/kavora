/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Main RTX Remix API class providing access to Remix functionality.
 * Thin facade over {@link RemixBridge} foreign-function calls.
 *
 * <p>Usage example:</p>
 * <pre>
 * RemixAPI remix = RemixAPI.load("d3d9.dll");
 * remix.startup(hwnd);
 *
 * // Create mesh, lights, set camera, draw instances...
 * remix.present();
 *
 * remix.shutdown();
 * </pre>
 */
public class RemixAPI {
    public static final int VERSION_MAJOR = 1;
    public static final int VERSION_MINOR = 5;
    public static final int VERSION_PATCH = 0;

    /**
     * Fits every value except long hash lists, so most reads through
     * {@link #getConfigVariableResolved(String)} take one call.
     */
    private static final int CONFIG_VALUE_INITIAL_CAPACITY = 256;

    private boolean initialized = false;
    private static final float[] IDENTITY_TRANSFORM = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f
    };

    // Profiling remains disabled until the runtime has been successfully loaded.
    private static volatile boolean nativeLoaded = false;

    public static final int COPY_OUTPUT_FINAL_COLOR     = 0;
    public static final int COPY_OUTPUT_DEPTH           = 1;
    public static final int COPY_OUTPUT_NORMALS         = 2;
    public static final int COPY_OUTPUT_OBJECT_PICKING  = 3;
    public static final int COPY_OUTPUT_GUI             = 4;
    public static final int COPY_OUTPUT_WORLD_POSITION_RAW = 5;
    public static final int COPY_OUTPUT_TEXCOORDS_RAW = 6;
    public static final int COPY_OUTPUT_NORMAL_F32 = 7;
    public static final int COPY_OUTPUT_COVERAGE = 8;

    private RemixAPI() {}

    public static long makeVersion(int major, int minor, int patch) {
        return ((long) major << 48) | ((long) minor << 16) | (long) patch;
    }

    /**
     * Loads the Remix DLL and initializes the C API through FFM.
     *
     * @param dllPath Path to the d3d9.dll file from RTX Remix
     * @return A RemixAPI instance ready for use
     * @throws RemixException if the DLL cannot be loaded or initialized
     */
    public static RemixAPI load(String dllPath) {
        java.io.File dllFile = runtimeFile(dllPath);
        RemixBridge.initializeSupport(dllFile.toPath().toAbsolutePath().getParent());
        try {
            int result = RemixBridge.initialize(dllFile.getAbsolutePath());
            if (result != ErrorCode.SUCCESS) {
                throw new RemixException("Failed to initialize Remix library", result);
            }
        } catch (RuntimeException | Error error) {
            RemixBridge.shutdownSupport();
            throw error;
        }

        RemixAPI api = new RemixAPI();
        api.initialized = true;
        nativeLoaded = true;
        return api;
    }

    /**
     * Blanks the values of {@code options} in this process's command line,
     * which the runtime's crash dumps capture. Each value that follows one of
     * the options, as the next argument or after {@code =}, becomes asterisks.
     * The line keeps its length and splits into the same arguments, and values
     * the process has already parsed are unaffected.
     *
     * <p>Call before {@link #load(String)}, so the runtime never captures the
     * command line with the values in it. Only the command-line copies are
     * blanked: copies the process has already made, such as parsed arguments,
     * stay in memory, and a dump can still contain them.
     *
     * @param dllPath Path to the d3d9.dll file from RTX Remix; validates the runtime path without loading it
     * @param options Option names, such as {@code --password}, matched against
     *                whole arguments
     * @return The number of values blanked
     * @throws RemixException if the DLL cannot be found or the command line
     *                        cannot be rewritten
     */
    public static int redactCommandLineOptions(String dllPath, Collection<String> options) {
        runtimeFile(dllPath);
        CommandLineRedaction.Result redaction =
                CommandLineRedaction.redact(RemixBridge.processCommandLine(), options);
        if (redaction.blankedValues() > 0) {
            int result = RemixBridge.overwriteProcessCommandLine(redaction.commandLine());
            if (result != ErrorCode.SUCCESS) {
                throw new RemixException("Failed to rewrite the process command line", result);
            }
        }
        return redaction.blankedValues();
    }

    private static java.io.File runtimeFile(String dllPath) {
        java.io.File dllFile = new java.io.File(dllPath);
        if (!dllFile.exists()) {
            throw new RemixException("Remix DLL not found: " + dllPath,
                    ErrorCode.LOAD_LIBRARY_FAILURE);
        }

        return dllFile;
    }

    // ─── lifecycle ───────────────────────────────────────────────────────

    /** Initializes configuration and capture tooling before the host creates a Vulkan instance. */
    public void prepareVulkan() {
        checkInitialized();
        int result = RemixBridge.prepareVulkan();
        if (result != ErrorCode.SUCCESS) throw new RemixException("Vulkan preparation failed", result);
    }

    /**
     * Creates the host-owned device with the union of both renderers' requirements.
     * The complete pointer graphs at {@code instanceExtensionsAddress} and
     * {@code deviceCreateInfoAddress} must remain valid until this call returns.
     * The host must shut this API down before destroying the resulting VkDevice.
     */
    public void createVulkanDevice(HostVulkanDeviceInfo info) {
        checkInitialized();
        int result = RemixBridge.createVulkanDevice(info);
        if (result != ErrorCode.SUCCESS) throw new RemixException("Shared Vulkan device creation failed", result);
    }

    /** Transfers device destruction to the host after its Vulkan dispatch table was built. */
    public void adoptVulkanDevice(long device) {
        checkInitialized();
        int result = RemixBridge.adoptVulkanDevice(device);
        if (result != ErrorCode.SUCCESS) throw new RemixException("Shared Vulkan ownership transfer failed", result);
    }

    public void startup(StartupInfo info) {
        checkInitialized();
        int result = RemixBridge.startup(
            info.hwnd,
                info.disableSrgbConversionForOutput,
                info.forceNoVkSwapchain,
                info.editorModeEnabled,
            info.combineGuiInFinalColor,
            info.interopImage
        );
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("Startup failed", result);
        }
    }

    public void shutdown() {
        if (!initialized) return;
        markNativeRuntimeUnavailable();
        try {
            RemixBridge.shutdown();
        } finally {
            initialized = false;
        }
    }

    public void submit(SubmitInfo info) {
        checkInitialized();
        int result = RemixBridge.submit(info != null ? info.interopFlags : 0,
                info != null ? info.waterSimulation : null);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("Submit failed", result);
    }
    }

    public void present(PresentInfo info) {
        checkInitialized();
        int result = RemixBridge.present(
            info != null ? info.hwndOverride : 0,
            info != null ? info.skipVulkanSwapchain : 0,
            info != null ? info.interopFlipY : 0,
            info != null ? info.clientDoneSemaphore_ValueToWait : 0L,
            info != null ? info.disableCpuWaitForExternalSemaphores : 0,
            info != null ? info.vulkanFrame : null);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("Present failed", result);
        }
    }

    // ─── materials ───────────────────────────────────────────────────────

    public long createMaterial(MaterialInfo info) {
        checkInitialized();
        long pNext = allocMaterialPNext(info);
        try {
            long handle = RemixBridge.createMaterial(
                    info.hash,
                    info.albedoTexture, info.normalTexture,
                    info.tangentTexture, info.emissiveTexture,
                    info.emissiveIntensity,
                    info.emissiveColorConstant.x,
                    info.emissiveColorConstant.y,
                    info.emissiveColorConstant.z,
                    info.spriteSheetRow, info.spriteSheetCol, info.spriteSheetFps,
                    info.filterMode, info.wrapModeU, info.wrapModeV,
                    pNext);
            if (handle == 0) {
                throw new RemixException("CreateMaterial failed", ErrorCode.GENERAL_FAILURE);
            }
            return handle;
        } finally {
            if (pNext != 0) RemixBridge.freeExtChain(pNext);
        }
    }

    public void updateMaterial(long handle, MaterialInfo info) {
        checkInitialized();
        long pNext = allocMaterialPNext(info);
        try {
            int result = RemixBridge.updateMaterial(
                    handle, info.hash,
                    info.albedoTexture, info.normalTexture,
                    info.tangentTexture, info.emissiveTexture,
                    info.emissiveIntensity,
                    info.emissiveColorConstant.x,
                    info.emissiveColorConstant.y,
                    info.emissiveColorConstant.z,
                    info.spriteSheetRow, info.spriteSheetCol, info.spriteSheetFps,
                    info.filterMode, info.wrapModeU, info.wrapModeV,
                    pNext);
            if (result != ErrorCode.SUCCESS) {
                throw new RemixException("UpdateMaterial failed", result);
            }
        } finally {
            if (pNext != 0) RemixBridge.freeExtChain(pNext);
        }
    }

    private long allocMaterialPNext(MaterialInfo info) {
        if ((info.vertexColorMaskTexture != 0 || info.glintTexture != 0) && info.opaqueExt == null) {
            throw new IllegalArgumentException("Vertex color masks and glint require an opaque material");
        }
        if ((info.glintTexture != 0) != (info.glintMode != null)) {
            throw new IllegalArgumentException("Glint needs a texture and UV mode");
        }
        long next = allocMaterialTypePNext(info);
        try {
            if (info.vertexColorMaskTexture != 0) {
                next = RemixBridge.allocVertexColorMaskExt(info.vertexColorMaskTexture, next);
            }
            if (info.glintMode != null) {
                next = RemixBridge.allocGlintExt(info.glintTexture, info.glintMode.abiValue, next);
            }
            return next;
        } catch (RuntimeException | Error failure) {
            RemixBridge.freeExtChain(next);
            throw failure;
        }
    }

    private long allocMaterialTypePNext(MaterialInfo info) {
        if (info.opaqueExt != null) {
            MaterialInfoOpaqueEXT o = info.opaqueExt;
            long subsurfacePtr = 0;
            if (o.subsurfaceExt != null) {
                MaterialInfoOpaqueSubsurfaceEXT s = o.subsurfaceExt;
                subsurfacePtr = RemixBridge.allocOpaqueSubsurfaceExt(
                        s.subsurfaceTransmittanceTexture,
                        s.subsurfaceThicknessTexture,
                        s.subsurfaceSingleScatteringAlbedoTexture,
                        s.subsurfaceTransmittanceColor.x,
                        s.subsurfaceTransmittanceColor.y,
                        s.subsurfaceTransmittanceColor.z,
                        s.subsurfaceMeasurementDistance,
                        s.subsurfaceSingleScatteringAlbedo.x,
                        s.subsurfaceSingleScatteringAlbedo.y,
                        s.subsurfaceSingleScatteringAlbedo.z,
                        s.subsurfaceVolumetricAnisotropy,
                        s.subsurfaceDiffusionProfile != 0,
                        s.subsurfaceRadius.x, s.subsurfaceRadius.y, s.subsurfaceRadius.z,
                        s.subsurfaceRadiusScale, s.subsurfaceMaxSampleRadius,
                        s.subsurfaceRadiusTexture, 0);
            }
            if (o.endPortalLayers != 0) {
                try {
                    subsurfacePtr = RemixBridge.allocEndPortalExt(o.endPortalLayers, subsurfacePtr);
                } catch (RuntimeException | Error failure) {
                    RemixBridge.freeExtChain(subsurfacePtr);
                    throw failure;
                }
            }
            try {
                return RemixBridge.allocOpaqueExt(
                        o.roughnessTexture, o.metallicTexture, o.anisotropy,
                        o.albedoConstant.x, o.albedoConstant.y, o.albedoConstant.z,
                        o.opacityConstant, o.roughnessConstant, o.metallicConstant,
                        o.thinFilmThickness_hasvalue != 0, o.thinFilmThickness_value,
                        o.alphaIsThinFilmThickness != 0,
                        o.heightTexture, o.displaceIn,
                        o.useDrawCallAlphaState != 0,
                        o.blendType_hasvalue != 0, o.blendType_value, o.invertedBlend != 0,
                        o.alphaTestType, o.alphaReferenceValue, o.displaceOut,
                        subsurfacePtr);
            } catch (RuntimeException | Error failure) {
                RemixBridge.freeExtChain(subsurfacePtr);
                throw failure;
            }
        } else if (info.translucentExt != null) {
            MaterialInfoTranslucentEXT t = info.translucentExt;
            long water = t.simulatedWater ? RemixBridge.allocWaterSurfaceExt() : 0;
            try {
                return RemixBridge.allocTranslucentExt(
                        t.transmittanceTexture, t.refractiveIndex,
                        t.transmittanceColor.x, t.transmittanceColor.y,
                        t.transmittanceColor.z,
                        t.transmittanceMeasurementDistance,
                        t.thinWallThickness_hasvalue != 0, t.thinWallThickness_value,
                        t.useDiffuseLayer != 0, water);
            } catch (RuntimeException | Error failure) {
                RemixBridge.freeExt(water);
                throw failure;
            }
        }
        return 0;
    }

    public void destroyMaterial(long handle) {
        checkInitialized();
        int result = RemixBridge.destroyMaterial(handle);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("DestroyMaterial failed", result);
        }
    }

    // ─── textures ────────────────────────────────────────────────────────

    public long createTextureFromMemory(TextureFromMemoryInfo info) {
        checkInitialized();
        long handle = RemixBridge.createTextureFromMemory(
                info.hash, info.pixelData,
                info.width, info.height, info.format, info.purpose, info.mipLevels, info.immutable);
        if (handle == 0) {
            throw new RemixException("CreateTextureFromMemory failed",
                    ErrorCode.GENERAL_FAILURE);
        }
        return handle;
    }

    public long createTextureFromFile(String filePath) {
        checkInitialized();
        long handle = RemixBridge.createTextureFromFile(filePath);
        if (handle == 0) {
            throw new RemixException("CreateTextureFromFile failed for: " + filePath,
                    ErrorCode.GENERAL_FAILURE);
        }
        return handle;
    }

    public void destroyTexture(long handle) {
        checkInitialized();
        int result = RemixBridge.destroyTexture(handle);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("DestroyTexture failed", result);
        }
    }

    public void updateTextureFromMemory(TexturePatchInfo info) {
        checkInitialized();
        if (info == null || info.entries == null || info.entries.length == 0) {
            return;
        }

        // First pass: count total patches across entries so we can allocate
        // the flat per-patch arrays exactly once.
        int entryCount = info.entries.length;
        int totalPatches = 0;
        for (int e = 0; e < entryCount; e++) {
            TextureBatchEntry entry = info.entries[e];
            if (entry == null || entry.patches == null || entry.patches.length == 0) {
                throw new RemixException("UpdateTextureFromMemory entry is missing patches",
                        ErrorCode.INVALID_ARGUMENTS);
            }
            totalPatches += entry.patches.length;
        }
        if (totalPatches == 0) {
            return;
        }

        long[] handles = new long[entryCount];
        int[] entryPatchCounts = new int[entryCount];
        int[] dstXs = new int[totalPatches];
        int[] dstYs = new int[totalPatches];
        int[] widths = new int[totalPatches];
        int[] heights = new int[totalPatches];
        ByteBuffer[] pixelBuffers = new ByteBuffer[totalPatches];
        int[] rowPitches = new int[totalPatches];
        int[] formats = new int[totalPatches];
        int[] mipLevels = null;

        int flat = 0;
        for (int e = 0; e < entryCount; e++) {
            TextureBatchEntry entry = info.entries[e];
            handles[e] = entry.handle;
            entryPatchCounts[e] = entry.patches.length;
            for (int p = 0; p < entry.patches.length; p++) {
                TexturePatch patch = entry.patches[p];
                if (patch == null || patch.pixelData == null) {
                    throw new RemixException("UpdateTextureFromMemory patch is missing pixel data",
                            ErrorCode.INVALID_ARGUMENTS);
                }
                dstXs[flat] = patch.dstX;
                dstYs[flat] = patch.dstY;
                widths[flat] = patch.width;
                heights[flat] = patch.height;
                pixelBuffers[flat] = patch.pixelData;
                rowPitches[flat] = patch.rowPitch;
                formats[flat] = patch.format;
                if (patch.mipLevel != 0) {
                    if (mipLevels == null) mipLevels = new int[totalPatches];
                    mipLevels[flat] = patch.mipLevel;
                }
                flat++;
            }
        }

        int result = RemixBridge.updateTextureFromMemory(
                handles, entryPatchCounts,
                dstXs, dstYs,
                widths, heights,
                pixelBuffers,
                rowPitches, formats, mipLevels);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("UpdateTextureFromMemory failed", result);
        }
    }

    // ─── meshes ──────────────────────────────────────────────────────────

    /** Allocates device-local memory for an external GPU producer; caller destroys its buffer handle. */
    public SharedBufferInfo createSharedBuffer(long bytes) {
        checkInitialized();
        var output = new SharedBufferInfo();
        int result = RemixBridge.createSharedBuffer(bytes, output);
        if (result != ErrorCode.SUCCESS) throw new RemixException("CreateSharedBuffer failed", result);
        return output;
    }

    /** Copies a direct R8 grid during this call. Origins are cell coordinates; lower nonzero levels own a cell. */
    public void setVoxelCoverage(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
            int cellSize, ByteBuffer levels) {
        checkInitialized();
        int result = RemixBridge.setVoxelCoverage(originX, originY, originZ, sizeX, sizeY, sizeZ, cellSize, levels);
        if (result != ErrorCode.SUCCESS) throw new RemixException("SetVoxelCoverage failed", result);
    }

    /** Copies the near grid and sparse 64-byte tree records atomically; flags bit 0 clips outside the grid. */
    public void setVoxelCoverageTree(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
            int cellSize, ByteBuffer levels, int flags, ByteBuffer treeNodes, int treeNodeCount) {
        checkInitialized();
        int result = RemixBridge.setVoxelCoverageTree(originX, originY, originZ, sizeX, sizeY, sizeZ,
                cellSize, levels, flags, treeNodes, treeNodeCount);
        if (result != ErrorCode.SUCCESS) throw new RemixException("SetVoxelCoverageTree failed", result);
    }

    /** Signal the producer timeline first. After commit, GPU payloads must never be modified. */
    public void commitSharedBuffers(long[] buffers, long readyValue) {
        checkInitialized();
        int result = RemixBridge.commitSharedBuffers(buffers, readyValue);
        if (result != ErrorCode.SUCCESS) throw new RemixException("CommitSharedBuffers failed", result);
    }

    public void destroySharedBuffer(long handle) {
        checkInitialized();
        int result = RemixBridge.destroySharedBuffer(handle);
        if (result != ErrorCode.SUCCESS) throw new RemixException("DestroySharedBuffer failed", result);
    }

    /** Meshes retain the committed GPU slices independently of the public buffer handles. */
    public long createMeshFromSharedBuffers(MeshSharedSurface... surfaces) {
        checkInitialized();
        long handle = RemixBridge.createMeshFromSharedBuffers(surfaces);
        if (handle == 0) throw new RemixException("CreateMeshFromSharedBuffers failed", ErrorCode.GENERAL_FAILURE);
        return handle;
    }

    /** Copies the direct quad buffers during this call; native code owns the resulting geometry. */
    public long createQuadMesh(long hash, MeshQuadSurface... surfaces) {
        checkInitialized();
        long handle = RemixBridge.createQuadMesh(hash, surfaces);
        if (handle == 0) throw new RemixException("CreateQuadMesh failed", ErrorCode.GENERAL_FAILURE);
        return handle;
    }

    public long createMesh(MeshInfo info) {
        checkInitialized();
        int n = info.surfaces != null ? info.surfaces.length : 0;
        if (n == 0) {
            throw new RemixException("CreateMesh: no surfaces", ErrorCode.INVALID_ARGUMENTS);
        }
        ByteBuffer[] vbs = new ByteBuffer[n];
        int[] vcs = new int[n];
        ByteBuffer[] ibs = new ByteBuffer[n];
        int[] ics = new int[n];
        long[] mats = new long[n];
        int[] flags = new int[n];
        int[] bpv = new int[n];
        ByteBuffer[] bwbs = new ByteBuffer[n];
        int[] bwcs = new int[n];
        ByteBuffer[] bibs = new ByteBuffer[n];
        int[] bics = new int[n];

        for (int i = 0; i < n; i++) {
            MeshInfoSurfaceTriangles s = info.surfaces[i];
            vbs[i] = s.vertexBuffer;
            vcs[i] = s.vertexCount;
            ibs[i] = s.indexBuffer;
            ics[i] = s.indexCount;
            mats[i] = s.material;
            flags[i] = s.flags;
            bpv[i] = s.skinning_hasvalue ? s.skinning_bonesPerVertex : 0;
            bwbs[i] = s.skinning_blendWeights;
            bwcs[i] = s.skinning_blendWeightsCount;
            bibs[i] = s.skinning_blendIndices;
            bics[i] = s.skinning_blendIndicesCount;
        }

        long handle = RemixBridge.createMesh(info.hash, n,
                vbs, vcs, ibs, ics, mats, flags,
                bpv, bwbs, bwcs, bibs, bics);
        if (handle == 0) {
            throw new RemixException("CreateMesh failed", ErrorCode.GENERAL_FAILURE);
        }
        return handle;
    }

    public void destroyMesh(long handle) {
        checkInitialized();
        int result = RemixBridge.destroyMesh(handle);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("DestroyMesh failed", result);
        }
    }

    // ─── camera ──────────────────────────────────────────────────────────

    public void setupCamera(CameraInfo info) {
        checkInitialized();
        long pNext = 0;
        if (info.parameterizedExt != null) {
            CameraInfoParameterizedEXT p = info.parameterizedExt;
            pNext = RemixBridge.allocParameterizedCamera(
                    p.position.x, p.position.y, p.position.z,
                    p.forward.x, p.forward.y, p.forward.z,
                    p.up.x, p.up.y, p.up.z,
                    p.right.x, p.right.y, p.right.z,
                    p.fovYInDegrees, p.aspect, p.nearPlane, p.farPlane, 0, info.sceneOrigin);
        } else if (info.sceneOrigin != null) {
            pNext = RemixBridge.allocCameraSceneOrigin(info.sceneOrigin);
        }
        try {
            int result = RemixBridge.setupCamera(info.type, info.view, info.projection, pNext);
            if (result != ErrorCode.SUCCESS) {
                throw new RemixException("SetupCamera failed", result);
            }
        } finally {
            if (pNext != 0) RemixBridge.freeExt(pNext);
        }
    }

    public void setCameraMediumMaterial(long materialHandle) {
        checkInitialized();
        int result = RemixBridge.setCameraMediumMaterial(materialHandle);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("SetCameraMediumMaterial failed", result);
        }
    }

    // ─── instances ───────────────────────────────────────────────────────

    /**
     * Draws a mesh instance using pre-assembled parameters.
     *
     * @param meshHandle handle from createMesh
     * @param transform  3x4 matrix as float[12]
     * @param categoryFlags instance category bits
     * @param doubleSided 1 = double sided
     * @param pNext pre-allocated extension chain (blend/bone), or 0
     */
    public void drawInstance(long meshHandle, float[] transform,
                             int categoryFlags, int doubleSided, long pNext) {
        checkInitialized();
        int result = RemixBridge.drawInstance(meshHandle, transform,
                categoryFlags, doubleSided, pNext);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("DrawInstance failed", result);
        }
    }

    /** Adds an instance that remains in the scene until explicitly removed. */
    public long sceneInstanceAdd(long meshHandle, float[] transform,
                                 int categoryFlags, int doubleSided, long pNext) {
        checkInitialized();
        long handle = RemixBridge.sceneInstanceAdd(meshHandle, transform,
                categoryFlags, doubleSided, pNext);
        if (handle == 0) {
            throw new RemixException("SceneInstanceAdd failed", ErrorCode.GENERAL_FAILURE);
        }
        return handle;
    }

    /** Replaces the complete description of a retained instance. */
    public void sceneInstanceUpdate(long sceneInstanceHandle,
                                    long meshHandle, float[] transform,
                                    int categoryFlags, int doubleSided, long pNext) {
        checkInitialized();
        int result = RemixBridge.sceneInstanceUpdate(sceneInstanceHandle,
                meshHandle, transform, categoryFlags, doubleSided, pNext);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("SceneInstanceUpdate failed", result);
        }
    }

    /** Removes a retained instance from the scene. */
    public void sceneInstanceRemove(long sceneInstanceHandle) {
        checkInitialized();
        int result = RemixBridge.sceneInstanceRemove(sceneInstanceHandle);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("SceneInstanceRemove failed", result);
        }
    }

    /**
     * Draws a mesh instance from an {@link InstanceInfo}, allocating and
     * freeing the pNext extension chain automatically.
     */
    public void drawInstance(InstanceInfo info) {
        long bonePNext = 0;
        long gpuPNext = 0;
        long blendPNext = 0;
        long pNext = 0;
        try {
            if (info.boneTransformsExt != null
                    && info.boneTransformsExt.transformData != null) {
                bonePNext = RemixBridge.allocBoneTransformsExt(
                        info.boneTransformsExt.transformData,
                        info.boneTransformsExt.count, pNext);
                pNext = bonePNext;
            }
            if (info.gpuInstancingExt != null
                    && info.gpuInstancingExt.transformData != null
                    && info.gpuInstancingExt.count > 0) {
                gpuPNext = RemixBridge.allocGpuInstancingExt(
                        info.gpuInstancingExt.transformData,
                        info.gpuInstancingExt.count, pNext);
                pNext = gpuPNext;
            }
            if (info.blendExt != null) {
                blendPNext = info.blendExt.allocNative(pNext);
                pNext = blendPNext;
            }
            drawInstance(info.mesh, info.transform.matrix,
                    info.categoryFlags, info.doubleSided, pNext);
        } finally {
            if (blendPNext != 0) info.blendExt.freeNative();
            if (gpuPNext != 0) RemixBridge.freeExt(gpuPNext);
            if (bonePNext != 0) RemixBridge.freeExt(bonePNext);
        }
    }

    /**
     * Draws many instances of the same mesh using GPU instancing transforms.
     *
     * @param pNext pre-allocated extension chain to append after GPU instancing,
     *              such as a cached blend extension. Ownership stays with caller.
     */
    public void drawInstanced(long meshHandle, ByteBuffer transforms,
                              int instanceCount, int categoryFlags,
                              int doubleSided, long pNext) {
        if (instanceCount <= 0) return;
        checkInitialized();

        long gpuPNext = RemixBridge.allocGpuInstancingExt(transforms, instanceCount, pNext);
        try {
            drawInstance(meshHandle, IDENTITY_TRANSFORM, categoryFlags, doubleSided, gpuPNext);
        } finally {
            RemixBridge.freeExt(gpuPNext);
        }
    }

    // ─── lights ──────────────────────────────────────────────────────────

    public long createLight(LightInfo info) {
        checkInitialized();
        long pNext = allocLightPNext(info);
        try {
            long handle = RemixBridge.createLight(info.hash,
                    info.radiance.x, info.radiance.y, info.radiance.z, pNext);
            if (handle == 0) {
                throw new RemixException("CreateLight failed", ErrorCode.GENERAL_FAILURE);
            }
            return handle;
        } finally {
            if (pNext != 0) RemixBridge.freeExt(pNext);
        }
    }

    /** Updates an existing light in place; its handle and temporal identity remain valid. */
    public void updateLight(long handle, LightInfo info) {
        checkInitialized();
        long pNext = allocLightPNext(info);
        try {
            int result = RemixBridge.updateLight(handle, info.hash,
                    info.radiance.x, info.radiance.y, info.radiance.z, pNext);
            if (result != ErrorCode.SUCCESS) throw new RemixException("UpdateLight failed", result);
        } finally {
            if (pNext != 0) RemixBridge.freeExt(pNext);
        }
    }

    private long allocLightPNext(LightInfo info) {
        if (info.sphereExt != null) {
            LightInfoSphereEXT s = info.sphereExt;
            return RemixBridge.allocSphereExt(
                    s.position.x, s.position.y, s.position.z, s.radius,
                    s.shaping_hasvalue,
                    s.shaping_direction.x, s.shaping_direction.y, s.shaping_direction.z,
                    s.shaping_coneAngleDegrees, s.shaping_coneSoftness, s.shaping_focusExponent,
                    s.volumetricRadianceScale, 0);
        } else if (info.distantExt != null) {
            LightInfoDistantEXT d = info.distantExt;
            return RemixBridge.allocDistantExt(
                    d.direction.x, d.direction.y, d.direction.z,
                    d.angularDiameterDegrees, d.volumetricRadianceScale, 0);
        } else if (info.rectExt != null) {
            LightInfoRectEXT r = info.rectExt;
            return RemixBridge.allocRectExt(
                    r.position.x, r.position.y, r.position.z,
                    r.xAxis.x, r.xAxis.y, r.xAxis.z, r.xSize,
                    r.yAxis.x, r.yAxis.y, r.yAxis.z, r.ySize,
                    r.direction.x, r.direction.y, r.direction.z,
                    r.shaping_hasvalue,
                    r.shaping_direction.x, r.shaping_direction.y, r.shaping_direction.z,
                    r.shaping_coneAngleDegrees, r.shaping_coneSoftness,
                    r.shaping_focusExponent,
                    r.volumetricRadianceScale, 0);
        } else if (info.diskExt != null) {
            LightInfoDiskEXT d = info.diskExt;
            return RemixBridge.allocDiskExt(
                    d.position.x, d.position.y, d.position.z,
                    d.xAxis.x, d.xAxis.y, d.xAxis.z, d.xRadius,
                    d.yAxis.x, d.yAxis.y, d.yAxis.z, d.yRadius,
                    d.direction.x, d.direction.y, d.direction.z,
                    d.shaping_hasvalue,
                    d.shaping_direction.x, d.shaping_direction.y, d.shaping_direction.z,
                    d.shaping_coneAngleDegrees, d.shaping_coneSoftness,
                    d.shaping_focusExponent,
                    d.volumetricRadianceScale, 0);
        } else if (info.cylinderExt != null) {
            LightInfoCylinderEXT c = info.cylinderExt;
            return RemixBridge.allocCylinderExt(
                    c.position.x, c.position.y, c.position.z,
                    c.radius,
                    c.axis.x, c.axis.y, c.axis.z,
                    c.axisLength,
                    c.volumetricRadianceScale, 0);
        } else if (info.domeExt != null) {
            LightInfoDomeEXT d = info.domeExt;
            return RemixBridge.allocDomeExt(
                    d.transform, d.colorTexture, d.transmittanceTexture, d.opacity, d.blendType, d.projection, d.backgroundOnly, 0);
        }
        return 0;
    }

    public void destroyLight(long handle) {
        checkInitialized();
        int result = RemixBridge.destroyLight(handle);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("DestroyLight failed", result);
        }
    }

    public void drawLightInstance(long handle) {
        checkInitialized();
        int result = RemixBridge.drawLightInstance(handle);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("DrawLightInstance failed", result);
        }
    }

    // ─── config ──────────────────────────────────────────────────────────

    public void setConfigVariable(String key, String value) {
        checkInitialized();
        int result = RemixBridge.setConfigVariable(key, value);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("SetConfigVariable failed", result);
        }
    }

    /**
     * Reads an option's resolved value: the value the renderer uses after every config layer is
     * combined, as text in the form {@code .conf} files and {@link #setConfigVariable} use.
     * Booleans read as {@code True} or {@code False}, enums as their integer value, vectors as
     * comma-separated components, and floats with 6 significant digits, so writing back a float or
     * vector with more precision rounds it.
     *
     * <p>Most changes, including those made by {@link #setConfigVariable} and config layers, resolve
     * at the runtime's option update, which runs on the render thread at the end of each frame. A
     * few of the runtime's own changes, such as starting a scene capture from its menu, resolve
     * immediately. The render thread can trail the application by a few frames, so a read returns
     * the value the render thread uses now, not one tied to the application's current frame.
     *
     * <p>A read right after {@link #setConfigVariable} may still return the previous value. It can
     * also return the new value, because an option update can run between the two calls, so a read
     * does not show where a frame ends. To change a value based on its current one, track your own
     * writes instead of reading the value back. Reading several keys does not give a consistent
     * snapshot, because an option update can happen between calls.
     *
     * @throws RemixException for a key that no option or namespace owns, for a malformed key in a
     *                        dynamic option namespace, or before the runtime has loaded its
     *                        configuration during {@link #startup}
     */
    public String getConfigVariableResolved(String key) {
        byte[] value = new byte[CONFIG_VALUE_INITIAL_CAPACITY];
        int size = getConfigVariableResolved(key, value);
        while (size > value.length) {
            value = new byte[size];
            size = getConfigVariableResolved(key, value);
        }
        return new String(value, 0, size - 1, StandardCharsets.UTF_8);
    }

    /**
     * Reads an option's resolved value as {@link #getConfigVariableResolved(String)} does, into a
     * buffer the caller owns, so a caller that reads every frame can reuse one buffer and allocate
     * nothing.
     *
     * @param buffer receives the value as UTF-8 followed by a NUL byte
     * @return the value's size in bytes, including the NUL byte. A size larger than
     *         {@code buffer.length} means the value did not fit, and the buffer does not hold it.
     * @throws RemixException as {@link #getConfigVariableResolved(String)} does
     */
    public int getConfigVariableResolved(String key, byte[] buffer) {
        checkInitialized();
        int size = RemixBridge.getConfigVariableResolved(key, buffer);
        if (size < 0) {
            throw new RemixException("GetConfigVariableResolved failed for " + key, -size);
        }
        return size;
    }

    /**
     * Acquires a conf-file option layer and registers it for per-frame blend
     * resolution in the runtime. When no matching layer is cached, the conf
     * file is parsed synchronously and fails only if it is missing or
     * unreadable. A file with no options, such as a comments-only stub,
     * acquires successfully as a no-op layer. Absolute paths are recommended;
     * relative paths resolve against the process working directory. A newly
     * created layer starts disabled at blend strength 0 (no effect); enable
     * and raise it with
     * {@link #setConfigLayerBlending(long, float, float)}. Repeated acquires
     * of the same priority+path return the same handle without re-parsing and
     * must be balanced by an equal number of
     * {@link #releaseConfigLayer(long)} calls.
     *
     * @param path           path to the .conf file; absolute is recommended
     * @param priority       layer ordering (higher wins per option; ties use
     *                       lexicographically earlier paths); must be finite
     * @param blendThreshold finite value clamped to [0,1]; controls when
     *                       non-float options switch
     * @return opaque config-layer handle
     * @throws RemixException if the layer cannot be acquired
     */
    public long acquireConfigLayer(String path, float priority, float blendThreshold) {
        checkInitialized();
        long handle = RemixBridge.acquireConfigLayer(path, priority, blendThreshold);
        if (handle == 0) {
            throw new RemixException("AcquireConfigLayer failed", ErrorCode.GENERAL_FAILURE);
        }
        return handle;
    }

    /**
     * Sets the layer's blend strength and threshold. Both must be finite and
     * are clamped to [0,1].
     * Float/vector options LERP by strength through the layer stack; other
     * option types switch when strength reaches {@code blendThreshold}. The
     * values persist until changed, so call this only when either value
     * changes; layers are resolved per frame in the runtime.
     */
    public void setConfigLayerBlending(long handle, float strength, float blendThreshold) {
        checkInitialized();
        int result = RemixBridge.setConfigLayerBlending(handle, strength, blendThreshold);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("SetConfigLayerBlending failed", result);
        }
    }

    /**
     * Releases one reference on the layer. At the final release the layer is
     * removed, affected options re-resolve during the next option-resolution
     * pass, and the handle must not be used again.
     */
    public void releaseConfigLayer(long handle) {
        checkInitialized();
        int result = RemixBridge.releaseConfigLayer(handle);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("ReleaseConfigLayer failed", result);
        }
    }

    /** @return true when the loaded runtime exposes the config-layer API (API >= 0.9.0) */
    public boolean isConfigLayersSupported() {
        checkInitialized();
        return RemixBridge.isConfigLayersSupported();
    }

    // ─── DXVK interop ────────────────────────────────────────────────────

    public long dxvkCreateD3D9(boolean editorModeEnabled) {
        checkInitialized();
        long ptr = RemixBridge.dxvkCreateD3D9(editorModeEnabled);
        if (ptr == 0) {
            throw new RemixException("dxvk_CreateD3D9 failed", ErrorCode.GENERAL_FAILURE);
        }
        return ptr;
    }

    public void dxvkRegisterD3D9Device(long d3d9Device) {
        checkInitialized();
        int result = RemixBridge.dxvkRegisterD3D9Device(d3d9Device);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("dxvk_RegisterD3D9Device failed", result);
        }
    }

    public long[] dxvkGetExternalSwapchain() {
        checkInitialized();
        return RemixBridge.dxvkGetExternalSwapchain();
    }

    public long dxvkGetVkImage(long surface) {
        checkInitialized();
        return RemixBridge.dxvkGetVkImage(surface);
    }

    public void dxvkCopyRenderingOutput(long destination, int type) {
        checkInitialized();
        int result = RemixBridge.dxvkCopyRenderingOutput(destination, type);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("dxvk_CopyRenderingOutput failed", result);
        }
    }

    public void dxvkSetDefaultOutput(int type, float r, float g, float b, float a) {
        checkInitialized();
        int result = RemixBridge.dxvkSetDefaultOutput(type, r, g, b, a);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("dxvk_SetDefaultOutput failed", result);
        }
    }

    public void dxvkSetDefaultOutputNormals() {
        dxvkSetDefaultOutput(COPY_OUTPUT_NORMALS, 0, 0, 0, 1);
    }

    public void dxvkSetDefaultOutputFinalColor() {
        dxvkSetDefaultOutput(COPY_OUTPUT_FINAL_COLOR, 0, 0, 0, 1);
    }

    public void dxvkSetDefaultOutputGUI() {
        dxvkSetDefaultOutput(COPY_OUTPUT_GUI, 0, 0, 0, 1);
    }

    // ─── internal ────────────────────────────────────────────────────────

    private void checkInitialized() {
        if (!initialized) {
            throw new RemixException("RemixAPI not initialized", ErrorCode.NOT_INITIALIZED);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ─── Tracy profiling ─────────────────────────────────────────────────

    static void markNativeRuntimeUnavailable() {
        nativeLoaded = false;
    }

    public static long tracyBegin(String name) {
        if (!nativeLoaded) {
            return 0L;
        }
        return RemixBridge.tracyZoneBegin(name);
    }

    public static void tracyEnd(long ctx) {
        if (!nativeLoaded) {
            return;
        }
        RemixBridge.tracyZoneEnd(ctx);
    }

    public static void tracyThreadName(String name) {
        if (!nativeLoaded) {
            return;
        }
        RemixBridge.tracySetThreadName(name);
    }

    public static void tracyPlot(String name, double value) {
        if (!nativeLoaded) {
            return;
        }
        RemixBridge.tracyPlot(name, value);
    }

    public static void tracyMessage(String message) {
        if (!nativeLoaded) {
            return;
        }
        RemixBridge.tracyMessage(message);
    }

    public static void tracyFrameMark(String name) {
        if (!nativeLoaded) {
            return;
        }
        RemixBridge.tracyFrameMark(name);
    }

    /** Reads direct Vulkan interop metadata into a reusable holder. */
    public void dxvkGetVulkanInteropInfo(VulkanInteropInfo output) {
        checkInitialized();
        int result = RemixBridge.dxvkGetVulkanInteropInfo(output);
        if (result != ErrorCode.SUCCESS) throw new RemixException("dxvk_GetVulkanInteropInfo failed", result);
    }

    /**
     * Returns the active NRC context's training record count, or -1 when NRC is inactive.
     * The runtime's atomic readout trails GPU work by four frames. Zero means that the
     * context is active but no completed training batch has been read back yet.
     * This reads the source runtime's {@code remixinternal_GetNrcStatus} export.
     */
    public long getNeuralRadianceCacheTrainingRecords() {
        checkInitialized();
        return RemixBridge.getNeuralRadianceCacheTrainingRecords();
    }

    /** Returns -1 while NGX initializes, 0 if unavailable, or 1 if supported. */
    public int getDlssNeuralRenderingStatus() {
        checkInitialized();
        return RemixBridge.getDlssNeuralRenderingStatus();
    }

    /** Queries NGX after Vulkan interop initialization; does not enable or allocate frame generation. */
    public void getFrameGenerationCapabilities(FrameGenerationCapabilities output) {
        checkInitialized();
        int result = RemixBridge.getFrameGenerationCapabilities(output);
        if (result != ErrorCode.SUCCESS) throw new RemixException("Frame generation capability query failed", result);
    }

    public InteropInfo dxvkGetInteropInfo() {
        checkInitialized();

        InteropInfo interopInfo = new InteropInfo();
        int result = RemixBridge.dxvkGetInteropInfo(interopInfo);
        if (result != ErrorCode.SUCCESS) {
            throw new RemixException("dxvkGetInteropInfo failed", result);
        }

        return interopInfo;
    }
}
