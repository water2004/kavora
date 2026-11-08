/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import static java.lang.foreign.ValueLayout.*;

/** Exercises the real FFM boundary against a DLL compiled with the pinned Remix header. */
public final class RemixBridgeContractTest {
    private static final long HANDLE = 0x1234;

    private static void equal(long expected, long actual) {
        if (expected != actual) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Throwable {
        String originalCommandLine = RemixBridge.processCommandLine();
        if (originalCommandLine.isEmpty()) throw new AssertionError("Missing PEB command line");
        equal(ErrorCode.INVALID_ARGUMENTS, RemixBridge.overwriteProcessCommandLine("wrong length"));
        String redactedCommandLine = "*" + originalCommandLine.substring(1);
        try {
            equal(0, RemixBridge.overwriteProcessCommandLine(redactedCommandLine));
            if (!redactedCommandLine.equals(RemixBridge.processCommandLine())) throw new AssertionError("PEB command line was not overwritten");
        } finally {
            equal(0, RemixBridge.overwriteProcessCommandLine(originalCommandLine));
        }
        equal(0, RemixBridge.initialize(args[0]));
        equal(0, RemixBridge.prepareVulkan());
        HostVulkanDeviceInfo host = new HostVulkanDeviceInfo();
        host.instance=0x1234; host.physicalDevice=0x5678; host.instanceApiVersion=0x404000;
        host.instanceExtensionCount=2; host.instanceExtensionsAddress=0x9999;
        host.graphicsQueueFamily=7; host.deviceCreateInfoAddress=0x8888;
        equal(0, RemixBridge.createVulkanDevice(host));
        equal(0x7777, host.device); equal(1, host.enabledExtensionCount);
        if (host.enabledExtensionsAddress == 0) throw new AssertionError("Missing enabled Vulkan extensions");
        equal(0, RemixBridge.adoptVulkanDevice(host.device));
        long zone = RemixBridge.tracyZoneBegin("zone");
        equal((1L << 32) | 77L, zone);
        RemixBridge.tracyZoneEnd(zone);
        RemixBridge.tracySetThreadName("thread");
        RemixBridge.tracyPlot("plot", 2.5);
        RemixBridge.tracyMessage("message");
        RemixBridge.tracyFrameMark("frame");
        try (Arena tracy = Arena.ofConfined()) {
            SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(args[0]).toAbsolutePath(), tracy);
            MethodHandle status = Linker.nativeLinker().downcallHandle(lookup.find("mock_tracy_validate").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
            equal(1, (int) status.invokeExact());
        }
        try (Arena picking = Arena.ofConfined()) {
            equal(0, RemixBridge.pickRequestObjectPicking(picking.allocate(RemixLayouts.layout("Rect2D")), MemorySegment.NULL, MemorySegment.NULL));
            equal(0, RemixBridge.pickHighlightObjects(new int[] {1, 2}, (byte) 255, (byte) 3, (byte) 4));
        }
        equal(0, RemixBridge.startup(HANDLE, true, false, false, false, true));
        equal(0, RemixBridge.submit(5));
        SharedBufferInfo shared = new SharedBufferInfo();
        equal(0, RemixBridge.createSharedBuffer(4096, shared));
        equal(0x7777, shared.buffer); equal(0x8888, shared.memoryHandle);
        equal(65536, shared.allocationSize); equal(256, shared.bindOffset);
        equal(0x9999, shared.producerSemaphoreHandle); equal(19, shared.readyValue);
        equal(0, RemixBridge.commitSharedBuffers(new long[]{0x7777, 0x7778}, 19));
        equal(HANDLE, RemixBridge.createMeshFromSharedBuffers(new MeshSharedSurface[]{
                new MeshSharedSurface(0x7777, 64, 4, 0x7777, 320, 6, HANDLE, 1)}));
        equal(0, RemixBridge.destroySharedBuffer(0x7777));
        equal(0, RemixBridge.present(HANDLE, 1, 0, 0x123456789abcdef0L, 1));
        VulkanClientFrameInfo clientFrame = new VulkanClientFrameInfo();
        clientFrame.device = 0xabcdef;
        clientFrame.image = 0x99887766L;
        clientFrame.imageGeneration = 0x100000001L;
        clientFrame.width = 1920;
        clientFrame.height = 1080;
        clientFrame.format = 37;
        clientFrame.bottomUp = true;
        clientFrame.sourceReadySemaphore = 0x12345;
        clientFrame.sourceReadyValue = 0x200000002L;
        equal(0, RemixBridge.present(HANDLE, 0, 0, 0x123456789abcdef0L, 0, clientFrame));
        equal(0xf000, clientFrame.sourceConsumedSemaphore);
        equal(0x300000003L, clientFrame.sourceConsumedValue);
        equal(0x400000004L, clientFrame.presentationEpoch);
        equal(0x500000005L, clientFrame.renderedFrames);
        equal(0x600000006L, clientFrame.generatedFrames);
        equal(HANDLE, RemixBridge.createMaterial(123, 0x1111, 0, 0, 0, 7.5f,
                1, 2, 3, 2, 3, 4, 1, 1, 1, 0));
        equal(0, RemixBridge.updateMaterial(HANDLE, 123, 0x1111, 0, 0, 0, 7.5f,
                1, 2, 3, 2, 3, 4, 1, 1, 1, 0));
        ByteBuffer pixels = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 16; i++) pixels.put(i, (byte) (17 + i));
        pixels.position(7);
        equal(HANDLE, RemixBridge.createTextureFromMemory(123, pixels, 2, 2, 0, 0));
        ByteBuffer authored = ByteBuffer.allocateDirect(20);
        for (int i = 0; i < 16; i++) authored.put(i, (byte) (17 + i));
        for (int i = 0; i < 4; i++) authored.put(16 + i, (byte) (71 + i));
        equal(HANDLE, RemixBridge.createTextureFromMemory(123, authored, 2, 2, 0, 0, 2));
        equal(HANDLE, RemixBridge.createTextureFromMemory(124, pixels, 2, 2, 0, 0, 0, true));
        equal(HANDLE, RemixBridge.createTextureFromMemory(124, authored, 2, 2, 0, 0, 2, true));
        equal(HANDLE, RemixBridge.createTextureFromFile("C:/材质/光.png"));
        equal(0, RemixBridge.updateTextureFromMemory(new long[]{0x1111, 0x2222},
                new int[]{1, 1}, new int[]{0, 0}, new int[]{0, 0}, new int[]{2, 2},
                new int[]{2, 2}, new ByteBuffer[]{pixels, pixels}, new int[]{8, 8}, new int[]{0, 0}));
        equal(0, RemixBridge.updateTextureFromMemory(new long[]{0x1111, 0x2222},
                new int[]{1, 1}, new int[]{0, 0}, new int[]{0, 0}, new int[]{2, 2},
                new int[]{2, 2}, new ByteBuffer[]{pixels, pixels}, new int[]{8, 8}, new int[]{0, 0}, new int[]{0, 3}));
        texturePatchBatch(args[0]);
        boolean rejected = false;
        try {
            RemixBridge.createTextureFromMemory(123, ByteBuffer.allocate(16), 2, 2, 0, 0);
        } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Heap pixel buffers reached the native boundary");
        rejected = false;
        try {
            RemixBridge.createTextureFromMemory(123, ByteBuffer.allocateDirect(15), 2, 2, 0, 0);
        } catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("Undersized pixel buffers reached the native boundary");
        ByteBuffer vertices = ByteBuffer.allocateDirect(3 * 64).order(ByteOrder.nativeOrder());
        vertices.putFloat(0, 1.5f);
        equal(HANDLE, RemixBridge.createMesh(123, 1, new ByteBuffer[]{vertices}, new int[]{3},
                new ByteBuffer[]{ByteBuffer.allocateDirect(12)}, new int[]{3}, new long[]{HANDLE},
                new int[]{0}, new int[]{0}, new ByteBuffer[]{null}, new int[]{0},
                new ByteBuffer[]{null}, new int[]{0}));
        vertices.putFloat(HardcodedVertex.PREVIOUS_POSITION_OFFSET, -3.25f);
        equal(HANDLE, RemixBridge.createMesh(123, 1, new ByteBuffer[]{vertices}, new int[]{3},
                new ByteBuffer[]{ByteBuffer.allocateDirect(12)}, new int[]{3}, new long[]{HANDLE},
                new int[]{MeshInfoSurfaceTriangles.PREVIOUS_POSITIONS}, new int[]{0}, new ByteBuffer[]{null}, new int[]{0},
                new ByteBuffer[]{null}, new int[]{0}));
        rejected = false;
        try {
            RemixBridge.createMesh(123, 1, new ByteBuffer[]{vertices}, new int[]{3},
                    new ByteBuffer[]{ByteBuffer.allocateDirect(12)}, new int[]{3}, new long[]{HANDLE},
                    new int[]{MeshInfoSurfaceTriangles.PREVIOUS_POSITIONS | MeshInfoSurfaceTriangles.COMPACT_VERTICES},
                    new int[]{0}, new ByteBuffer[]{null}, new int[]{0}, new ByteBuffer[]{null}, new int[]{0});
        } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Compact vertices must not silently discard explicit motion");
        float[] matrix = new float[16];
        for (int i = 0; i < matrix.length; i++) matrix[i] = i + 1;
        equal(0, RemixBridge.setupCamera(0, matrix, matrix, 0));
        equal(0, RemixBridge.setCameraMediumMaterial(HANDLE));
        ByteBuffer coverage = ByteBuffer.allocateDirect(4);
        coverage.put(0, (byte) 1); coverage.put(3, (byte) 6);
        equal(0, RemixBridge.setVoxelCoverage(-7, -4, 3, 2, 1, 2, 16, coverage));
        equal(0, RemixBridge.setVoxelCoverage(0, 0, 0, 0, 0, 0, 0, null));
        float[] transform = new float[12];
        transform[0] = 1;
        equal(0, RemixBridge.drawInstance(HANDLE, transform, 3, 1, 0));
        equal(HANDLE, RemixBridge.sceneInstanceAdd(HANDLE, transform, 3, 1, 0));
        equal(0, RemixBridge.sceneInstanceUpdate(HANDLE, HANDLE, transform, 3, 1, 0));
        equal(0, RemixBridge.sceneInstanceRemove(HANDLE));
        equal(HANDLE, RemixBridge.createLight(123, 1, 2, 3, 0));
        long distant = RemixBridge.allocDistantExt(0, -1, 0, .5f, 1, 0);
        try {
            equal(0, RemixBridge.updateLight(HANDLE, 123, 4, 5, 6, distant));
            equal(ErrorCode.INVALID_ARGUMENTS, RemixBridge.updateLight(0, 123, 4, 5, 6, distant));
        } finally {
            RemixBridge.freeExt(distant);
        }
        equal(0, RemixBridge.drawLightInstance(HANDLE));
        equal(0, RemixBridge.destroyLight(HANDLE));
        equal(0, RemixBridge.setConfigVariable("rtx.test", "True"));
        equal(5, RemixBridge.getConfigVariableResolved("rtx.test", null));
        equal(5, RemixBridge.getConfigVariableResolved("rtx.test", new byte[2]));
        byte[] resolved = new byte[5];
        equal(5, RemixBridge.getConfigVariableResolved("rtx.test", resolved));
        if (!new String(resolved, 0, 4, StandardCharsets.UTF_8).equals("True"))
            throw new AssertionError("Resolved config copy failed");
        equal(HANDLE, RemixBridge.acquireConfigLayer("C:/配置.conf", 7.5f, 0.25f));
        equal(0, RemixBridge.setConfigLayerBlending(HANDLE, 0.75f, 0.25f));
        equal(0, RemixBridge.releaseConfigLayer(HANDLE));
        if (!RemixBridge.isConfigLayersSupported()) throw new AssertionError("Config layer slots missing");
        equal(HANDLE, RemixBridge.dxvkCreateD3D9(true));
        equal(0, RemixBridge.dxvkRegisterD3D9Device(HANDLE));
        long[] swapchain = RemixBridge.dxvkGetExternalSwapchain();
        equal(0x1111, swapchain[0]); equal(0x2222, swapchain[1]); equal(0x3333, swapchain[2]);
        equal(0x5678, RemixBridge.dxvkGetVkImage(HANDLE));
        equal(0, RemixBridge.dxvkCopyRenderingOutput(HANDLE, 0));
        equal(0, RemixBridge.dxvkSetDefaultOutput(0, 1, 2, 3, 4));
        VulkanInteropInfo vk = new VulkanInteropInfo();
        equal(0, RemixBridge.dxvkGetVulkanInteropInfo(vk));
        FrameGenerationCapabilities capabilities = new FrameGenerationCapabilities();
        equal(0, RemixBridge.getFrameGenerationCapabilities(capabilities));
        check(capabilities.initializationComplete && capabilities.supported, "supported FG capabilities");
        equal(6, capabilities.maxGeneratedFrames);
        check(capabilities.notSupportedReason.isEmpty(), "supported FG has no failure reason");
        equal(0, RemixBridge.getFrameGenerationCapabilities(capabilities));
        check(capabilities.initializationComplete && !capabilities.supported, "unsupported FG capabilities");
        equal(0, capabilities.maxGeneratedFrames);
        check(capabilities.notSupportedReason.equals("GPU scheduling unavailable"), "FG failure reason");
        equal(0, RemixBridge.getFrameGenerationCapabilities(capabilities));
        check(!capabilities.initializationComplete && !capabilities.supported, "FG still initializing");
        check(capabilities.notSupportedReason.equals("DLSS is initializing."), "FG initializing reason");
        equal(0xabcdef, vk.device); equal(0x9876, vk.color.image);
        equal(1234, vk.frameStatus.frameId);
        equal(3, vk.frameStatus.flags);
        equal(0, vk.frameStatus.shaderCompileCount);
        equal(15, vk.deviceUUID[15]); equal(31, vk.driverUUID[15]); equal(39, vk.deviceLUID[7]);
        equal(3, vk.deviceNodeMask); equal(1, vk.deviceLUIDValid); equal(7, vk.queueFamilyIndex);
        equal(1, vk.semaphoreType); equal(0x1111, vk.dxvkDoneSemaphore_Handle);
        equal(0x123456789abcdef0L, vk.dxvkDoneSemaphore_ValueToWait); equal(0x2222, vk.clientDoneSemaphore_Handle);
        equal(0x3333, vk.color.memoryHandle); equal(0x100000000L, vk.color.allocationSize);
        equal(2, vk.color.allocationFlags); equal(3, vk.color.allocationDeviceMask);
        equal(4096, vk.color.bindOffset); equal(17, vk.color.generation); equal(1920, vk.color.width);
        equal(1080, vk.color.height); equal(2, vk.color.viewFormatCount); equal(43, vk.color.viewFormats[1]);
        equal(0x4444, vk.depth.memoryHandle); equal(18, vk.depth.generation);
        equal(0x5555, vk.uiOverlay.memoryHandle); equal(19, vk.uiOverlay.generation);
        equal(1920, vk.uiOverlay.width); equal(43, vk.uiOverlay.viewFormats[1]);
        InteropInfo interop = new InteropInfo();
        equal(0, RemixBridge.dxvkGetInteropInfo(interop));
        equal(640, interop.width); equal(480, interop.height);
        equal(HANDLE, interop.dxvkDoneSemaphore_Handle); equal(0x5678, interop.depthImageMemory_Handle);
        extensions(args[0]);
        equal(0, RemixBridge.destroyMesh(HANDLE));
        equal(0, RemixBridge.destroyTexture(HANDLE));
        equal(0, RemixBridge.destroyMaterial(HANDLE));
        equal(0, RemixBridge.shutdown());
        System.out.println("Verified Remix FFM DLL calls, retained handles, texture patches, config layers, Unicode, direct buffers, and all extensions");
    }

    private static void texturePatchBatch(String library) throws Throwable {
        long[] handles = {0x3333, 0x4444};
        int[] counts = {3, 2}, xs = {1, 5, 17, 3, 0}, ys = {2, 7, 11, 4, 0};
        int[] widths = {3, 5, 2, 2, 1}, heights = {2, 3, 2, 2, 2};
        int[] pitches = {16, 8, 0, 24, 0}, mips = {0, 1, 4, 7, 31};
        int[] formats = {TextureFormat.RGBA8_UNORM, TextureFormat.R8_UNORM,
                TextureFormat.RG8_UNORM, TextureFormat.RGBA16_SFLOAT, TextureFormat.RGBA32_SFLOAT};
        int[] bytesPerPixel = {4, 1, 2, 8, 16};
        ByteBuffer[] buffers = new ByteBuffer[5];
        for (int k = 0; k < buffers.length; k++) {
            int rowSize = widths[k] * bytesPerPixel[k];
            int pitch = pitches[k] == 0 ? rowSize : pitches[k];
            int size = pitch * (heights[k] - 1) + rowSize;
            ByteBuffer allocation = ByteBuffer.allocateDirect(13 + size);
            ByteBuffer data = allocation.position(13).slice();
            for (int y = 0; y < heights[k]; y++) {
                for (int x = 0; x < rowSize; x++) data.put(y * pitch + x, (byte) (17 + k * 13 + y * 3 + x));
            }
            // Native reads from the slice's index zero even with no remaining bytes.
            buffers[k] = data.position(1).limit(1).asReadOnlyBuffer();
        }
        equal(0, RemixBridge.updateTextureFromMemory(handles, counts, xs, ys, widths, heights, buffers, pitches, formats, mips));
        for (ByteBuffer buffer : buffers) {
            equal(1, buffer.position());
            equal(1, buffer.limit());
        }
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(library).toAbsolutePath(), arena);
            MethodHandle calls = Linker.nativeLinker().downcallHandle(
                    lookup.find("mock_texture_patch_call_count").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
            int before = (int) calls.invokeExact();
            ByteBuffer valid = buffers[0];
            buffers[0] = ByteBuffer.allocate(28);
            equal(ErrorCode.INVALID_ARGUMENTS, RemixBridge.updateTextureFromMemory(handles, counts, xs, ys, widths, heights, buffers, pitches, formats, mips));
            buffers[0] = ByteBuffer.allocateDirect(27);
            equal(ErrorCode.INVALID_ARGUMENTS, RemixBridge.updateTextureFromMemory(handles, counts, xs, ys, widths, heights, buffers, pitches, formats, mips));
            buffers[0] = valid;
            mips[0] = 32;
            equal(ErrorCode.INVALID_ARGUMENTS, RemixBridge.updateTextureFromMemory(handles, counts, xs, ys, widths, heights, buffers, pitches, formats, mips));
            mips[0] = 0;
            pitches[0] = 11;
            equal(ErrorCode.INVALID_ARGUMENTS, RemixBridge.updateTextureFromMemory(handles, counts, xs, ys, widths, heights, buffers, pitches, formats, mips));
            equal(before, (int) calls.invokeExact());
        }
    }

    private static void extensions(String library) throws Throwable {
        Map<String, Integer> types = Map.ofEntries(
                Map.entry("allocOpaqueExt", 5), Map.entry("allocOpaqueSubsurfaceExt", 18),
                Map.entry("allocTranslucentExt", 4), Map.entry("allocBlendExt", 15),
                Map.entry("allocBoneTransformsExt", 14), Map.entry("allocGpuInstancingExt", 26),
                Map.entry("allocSphereExt", 11), Map.entry("allocDistantExt", 7),
                Map.entry("allocRectExt", 10), Map.entry("allocDiskExt", 9),
                Map.entry("allocCylinderExt", 8), Map.entry("allocDomeExt", 20),
                Map.entry("allocCameraParamExt", 17), Map.entry("allocVoxelCoverageExt", 38), Map.entry("allocVoxelCoverageTreeExt", 38), Map.entry("allocVertexColorMaskExt", 40));
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup lookup = SymbolLookup.libraryLookup(Path.of(library).toAbsolutePath(), arena);
            MethodHandle validate = Linker.nativeLinker().downcallHandle(
                    lookup.find("mock_validate_extension").orElseThrow(), FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
            int checked = 0;
            for (Method method : RemixBridge.class.getDeclaredMethods()) {
                Integer type = types.get(method.getName());
                if (type == null) continue;
                Class<?>[] parameters = method.getParameterTypes();
                Object[] values = new Object[parameters.length];
                for (int i = 0; i < parameters.length; i++) {
                    Class<?> parameter = parameters[i];
                    if (parameter == float.class) values[i] = 1.5f;
                    else if (parameter == long.class) values[i] = HANDLE;
                    else if (parameter == int.class) values[i] = 1;
                    else if (parameter == boolean.class) values[i] = true;
                    else if (parameter == ByteBuffer.class) {
                        ByteBuffer transforms = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
                        transforms.putFloat(0, 1.5f);
                        values[i] = transforms;
                    }
                    else if (parameter == float[].class) values[i] = new float[12];
                    else throw new AssertionError("Uncovered extension parameter " + parameter);
                }
                long pointer = (long) method.invoke(null, values);
                if (pointer == 0) throw new AssertionError(method.getName() + " returned null");
                for (Object value : values) {
                    if (value instanceof ByteBuffer transforms) transforms.putFloat(0, 9.5f);
                }
                int result = (int) validate.invokeExact(type.intValue(), MemorySegment.ofAddress(pointer));
                if (result != 0) throw new AssertionError("Native extension validation failed for " + method.getName());
                RemixBridge.freeExt(pointer);
                checked++;
            }
            equal(types.size(), checked);
        }
    }
}
