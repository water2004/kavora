/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Reference;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static java.lang.foreign.ValueLayout.*;

/** Windows 64-bit RTX Remix C API binding using the Java Foreign Function and Memory API.
 * Native handle ownership stays with the caller. Every direct buffer is read from index zero,
 * independent of its current position, and must remain alive until the call returns.
 */
public final class RemixBridge {
    private RemixBridge() {}
    private static final Linker LINKER = Linker.nativeLinker();
    private static final Map<String, java.util.Optional<MethodHandle>> tracyFunctions = new ConcurrentHashMap<>();
    private static final Map<String, MethodHandle> functions = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Field>> fields = new ConcurrentHashMap<>();
    private record Field(long offset, MemoryLayout layout) {}
    private static final Map<Long, Arena> extensions = new ConcurrentHashMap<>();
    // Owns only initialization structures, never the loaded runtime library.
    private static Arena sessionArena;
    private static volatile SymbolLookup runtime;
    private static final Arena PROCESS = Arena.ofShared();
    // Tracy's Windows destructor terminates the process when d3d9 is unloaded.
    // Runtime libraries and their loader references therefore live until process exit.
    private record RuntimeLibrary(MemorySegment module, SymbolLookup symbols) {}
    private static final Map<Path, RuntimeLibrary> runtimeLibraries = new ConcurrentHashMap<>();
    private static final Map<String, MemorySegment> stableNames = new ConcurrentHashMap<>();

    /** Per-thread call storage retains native segments so steady-state rendering creates no arenas. */
    private static final class Scratch implements SegmentAllocator, AutoCloseable {
        private MemorySegment[] slots = new MemorySegment[32];
        private int cursor;
        private boolean busy;
        @Override public MemorySegment allocate(long bytes, long alignment) {
            if (cursor == slots.length) slots = Arrays.copyOf(slots, slots.length * 2);
            MemorySegment segment = slots[cursor];
            if (segment == null || segment.byteSize() != bytes || (segment.address() & (alignment - 1)) != 0) {
                segment = Arena.ofAuto().allocate(bytes, alignment);
                slots[cursor] = segment;
            }
            cursor++;
            return segment.fill((byte) 0);
        }
        @Override public void close() { busy = false; }
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static Scratch scratch() {
        Scratch scratch = SCRATCH.get();
        if (scratch.busy) throw new IllegalStateException("Reentrant Remix foreign call");
        scratch.busy = true;
        scratch.cursor = 0;
        return scratch;
    }

    private static FunctionDescriptor descriptor(MemoryLayout... args) {
        return FunctionDescriptor.of(JAVA_INT, args);
    }
    private static MethodHandle bind(SymbolLookup lib, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(
                () -> new UnsatisfiedLinkError("Missing native export " + name)), descriptor);
    }
    private static RuntimeException failure(Throwable error) {
        if (error instanceof RuntimeException e) return e;
        if (error instanceof Error e) throw e;
        return new IllegalStateException("Foreign call failed", error);
    }
    private static MemorySegment pointer(long address) { return MemorySegment.ofAddress(address); }
    private static MemorySegment direct(ByteBuffer buffer, long bytes) {
        if (buffer == null) {
            if (bytes == 0) return MemorySegment.NULL;
            throw new IllegalArgumentException("Missing direct buffer");
        }
        if (!buffer.isDirect() || bytes < 0 || bytes > buffer.capacity())
            throw new IllegalArgumentException("Native buffer must be direct and large enough");
        return MemorySegment.ofBuffer(buffer.duplicate().clear()).asSlice(0, bytes);
    }
    private static Field metadata(String type, String field) {
        Map<String, Field> typeFields = fields.get(type);
        if (typeFields == null) typeFields = fields.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>());
        Field result = typeFields.get(field);
        if (result != null) return result;
        MemoryLayout layout = RemixLayouts.layout(type);
        long offset = 0;
        for (String part : field.split("\\.")) {
            var element = MemoryLayout.PathElement.groupElement(part);
            offset += layout.byteOffset(element);
            layout = layout.select(element);
        }
        result = new Field(offset, layout);
        typeFields.put(field, result);
        return result;
    }
    private static long offset(String type, String field) { return metadata(type, field).offset(); }
    private static MemoryLayout field(String type, String field) { return metadata(type, field).layout(); }
    private static void put(MemorySegment target, String type, String name, long value) {
        long offset = offset(type, name);
        MemoryLayout layout = field(type, name);
        if (layout instanceof AddressLayout) target.set(ADDRESS, offset, pointer(value));
        else if (layout.byteSize() == 8) target.set(JAVA_LONG, offset, value);
        else if (layout.byteSize() == 4) target.set(JAVA_INT, offset, (int) value);
        else if (layout.byteSize() == 1) target.set(JAVA_BYTE, offset, (byte) value);
        else throw new IllegalArgumentException("Not an integer field: " + type + "." + name);
    }
    private static void put(MemorySegment target, String type, String name, boolean value) {
        put(target, type, name, value ? 1L : 0L);
    }
    private static void put(MemorySegment target, String type, String name, float value) {
        target.set(JAVA_FLOAT, offset(type, name), value);
    }
    private static void vector3(MemorySegment target, String type, String name, float x, float y, float z) {
        long offset = offset(type, name);
        target.set(JAVA_FLOAT, offset, x);
        target.set(JAVA_FLOAT, offset + 4, y);
        target.set(JAVA_FLOAT, offset + 8, z);
    }
    private static void floats(MemorySegment target, String type, String name, float[] values) {
        long size = field(type, name).byteSize();
        if (values.length * 4L != size) throw new IllegalArgumentException("Wrong float array size for " + name);
        MemorySegment.copy(values, 0, target, JAVA_FLOAT, offset(type, name), values.length);
    }
    private static MemorySegment info(SegmentAllocator arena, String type, int sType, long pNext) {
        MemorySegment info = arena.allocate(RemixLayouts.layout(type));
        put(info, type, "sType", sType);
        put(info, type, "pNext", pNext);
        return info;
    }
    private static int call(String name, MemorySegment info) {
        MethodHandle handle = functions.get(name);
        if (handle == null) return ErrorCode.NOT_INITIALIZED;
        try { return (int) handle.invokeExact(info); } catch (Throwable e) { throw failure(e); }
    }
    private static int call(String name) {
        MethodHandle handle = functions.get(name);
        if (handle == null) return ErrorCode.NOT_INITIALIZED;
        try { return (int) handle.invokeExact(); } catch (Throwable e) { throw failure(e); }
    }
    private static int call(String name, long value) {
        MethodHandle handle = functions.get(name);
        if (handle == null) return ErrorCode.NOT_INITIALIZED;
        try { return (int) handle.invokeExact(value); } catch (Throwable e) { throw failure(e); }
    }
    private static int call(String name, MemorySegment first, MemorySegment second) {
        MethodHandle handle = functions.get(name);
        if (handle == null) return ErrorCode.NOT_INITIALIZED;
        try { return (int) handle.invokeExact(first, second); } catch (Throwable e) { throw failure(e); }
    }
    private static long create(String name, MemorySegment info, SegmentAllocator arena) {
        MemorySegment out = arena.allocate(ADDRESS);
        return call(name, info, out) == ErrorCode.SUCCESS ? out.get(ADDRESS, 0).address() : 0;
    }
    private static int handle(String name, long handle) { return call(name, pointer(handle)); }
    private static MemorySegment utf16(SegmentAllocator arena, String value) {
        byte[] bytes = (value + "\0").getBytes(StandardCharsets.UTF_16LE);
        MemorySegment result = arena.allocate(bytes.length, 2);
        MemorySegment.copy(bytes, 0, result, JAVA_BYTE, 0, bytes.length);
        return result;
    }

    private static Arena supportArena;
    private static MethodHandle uninstallCrashHandler;

    static synchronized void initializeSupport(Path directory) {
        if (supportArena != null) return;
        Arena arena = Arena.ofShared();
        try {
            SymbolLookup support = SymbolLookup.libraryLookup(directory.resolve("kavora-support.dll"), arena);
            MethodHandle install = bind(support, "kavora_install_crash_handler", descriptor());
            MethodHandle uninstall = bind(support, "kavora_uninstall_crash_handler", FunctionDescriptor.ofVoid());
            int result = (int) install.invokeExact();
            if (result == 0) throw new IllegalStateException("Unable to install native crash handler");
            uninstallCrashHandler = uninstall;
            supportArena = arena;
        } catch (Throwable e) { arena.close(); throw failure(e); }
    }
    static synchronized void shutdownSupport() {
        if (supportArena == null) return;
        try { uninstallCrashHandler.invokeExact(); }
        catch (Throwable e) { throw failure(e); }
        finally { supportArena.close(); supportArena = null; uninstallCrashHandler = null; }
    }

    public static synchronized int initialize(String dllPath) {
        if (sessionArena != null) return ErrorCode.ALREADY_EXISTS;
        if (ADDRESS.byteSize() != 8 || !System.getProperty("os.name").startsWith("Windows"))
            throw new UnsupportedOperationException("Remix requires Windows x64 or ARM64");
        Arena arena = Arena.ofShared();
        try {
            Path path = Path.of(dllPath).toRealPath();
            runtime = runtimeLibraries.computeIfAbsent(path, RemixBridge::loadRuntime).symbols();
            MemorySegment table = arena.allocate(RemixLayouts.layout("Interface"));
            MemorySegment init = info(arena, "InitializeLibraryInfo", StructType.INITIALIZE_LIBRARY_INFO, 0);
            put(init, "InitializeLibraryInfo", "version", RemixAPI.makeVersion(RemixAPI.VERSION_MAJOR, RemixAPI.VERSION_MINOR, RemixAPI.VERSION_PATCH));
            int result = (int) bind(runtime, "remixapi_InitializeLibrary", descriptor(ADDRESS, ADDRESS)).invokeExact(init, table);
            if (result != ErrorCode.SUCCESS) { arena.close(); runtime = null; return result; }
            bindTable(table);
            functions.put("GetNrcStatus", bind(runtime, "remixinternal_GetNrcStatus", descriptor(ADDRESS)));
            runtime.find("remixinternal_GetDlssNeuralRenderingStatus").ifPresent(address ->
                    functions.put("GetDlssNeuralRenderingStatus", LINKER.downcallHandle(address, descriptor())));
            sessionArena = arena;
            return ErrorCode.SUCCESS;
        } catch (Throwable e) {
            functions.clear(); runtime = null; arena.close(); throw failure(e);
        }
    }

    private static RuntimeLibrary loadRuntime(Path path) {
        try (Scratch scratch = scratch()) {
            MemorySegment module = (MemorySegment) Windows.LOAD_LIBRARY.invokeExact(utf16(scratch, path.toString()), MemorySegment.NULL, 0x1100);
            if (module.address() == 0) throw new UnsatisfiedLinkError("Unable to load Remix runtime and its dependencies: " + path);
            return new RuntimeLibrary(module, SymbolLookup.libraryLookup(path, PROCESS));
        } catch (Throwable e) { throw failure(e); }
    }

    private static void bindTable(MemorySegment table) {
        bindEntry(table, "Shutdown", descriptor());
        bindEntry(table, "CreateMaterial", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "DestroyMaterial", descriptor(ADDRESS));
        bindEntry(table, "CreateMesh", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "DestroyMesh", descriptor(ADDRESS));
        bindEntry(table, "SetupCamera", descriptor(ADDRESS));
        bindEntry(table, "DrawInstance", descriptor(ADDRESS));
        bindEntry(table, "CreateLight", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "DestroyLight", descriptor(ADDRESS));
        bindEntry(table, "DrawLightInstance", descriptor(ADDRESS));
        bindEntry(table, "SetConfigVariable", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "dxvk_CreateD3D9", descriptor(JAVA_INT, ADDRESS));
        bindEntry(table, "dxvk_RegisterD3D9Device", descriptor(ADDRESS));
        bindEntry(table, "dxvk_GetExternalSwapchain", descriptor(ADDRESS, ADDRESS, ADDRESS));
        bindEntry(table, "dxvk_GetVkImage", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "dxvk_CopyRenderingOutput", descriptor(ADDRESS, JAVA_INT));
        bindEntry(table, "dxvk_SetDefaultOutput", descriptor(JAVA_INT, ADDRESS));
        bindEntry(table, "pick_RequestObjectPicking", descriptor(ADDRESS, ADDRESS, ADDRESS));
        bindEntry(table, "pick_HighlightObjects", descriptor(ADDRESS, JAVA_INT, JAVA_BYTE, JAVA_BYTE, JAVA_BYTE));
        bindEntry(table, "Startup", descriptor(ADDRESS));
        bindEntry(table, "Present", descriptor(ADDRESS));
        bindEntry(table, "CreateTextureFromMemory", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "CreateTextureFromFile", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "DestroyTexture", descriptor(ADDRESS));
        bindEntry(table, "SetCameraMediumMaterial", descriptor(ADDRESS));
        bindEntry(table, "UpdateTextureFromMemory", descriptor(ADDRESS));
        bindEntry(table, "dxvk_GetInteropInfo", descriptor(ADDRESS));
        bindEntry(table, "Submit", descriptor(ADDRESS));
        bindEntry(table, "AcquireConfigLayer", descriptor(ADDRESS, JAVA_FLOAT, JAVA_FLOAT, ADDRESS));
        bindEntry(table, "SetConfigLayerBlending", descriptor(ADDRESS, JAVA_FLOAT, JAVA_FLOAT));
        bindEntry(table, "ReleaseConfigLayer", descriptor(ADDRESS));
        bindEntry(table, "SceneInstanceAdd", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "SceneInstanceUpdate", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "SceneInstanceRemove", descriptor(ADDRESS));
        bindEntry(table, "UpdateMaterial", descriptor(ADDRESS, ADDRESS));
        bindEntry(table, "GetConfigVariableResolved", descriptor(ADDRESS));
        bindEntry(table, "dxvk_GetVulkanInteropInfo", descriptor(ADDRESS));
        bindEntry(table, "CreateSharedBuffer", descriptor(ADDRESS));
        bindEntry(table, "CommitSharedBuffers", descriptor(ADDRESS));
        bindEntry(table, "SetVoxelCoverage", descriptor(ADDRESS));
        bindEntry(table, "DestroySharedBuffer", descriptor(ADDRESS));
        bindEntry(table, "dxvk_CreateVulkanDevice", descriptor(ADDRESS));
        bindEntry(table, "dxvk_PrepareVulkan", descriptor());
        bindEntry(table, "dxvk_AdoptVulkanDevice", descriptor(JAVA_LONG));
        functions.put("UpdateLight", bind(runtime, "remixapi_UpdateLight", descriptor(ADDRESS, ADDRESS)));
    }

    static int prepareVulkan() {
        return call("dxvk_PrepareVulkan");
    }

    static int adoptVulkanDevice(long device) {
        return call("dxvk_AdoptVulkanDevice", device);
    }

    static int createVulkanDevice(HostVulkanDeviceInfo value) {
        try (Arena arena = Arena.ofConfined()) {
            String type = "HostVulkanDeviceInfo";
            MemorySegment nativeInfo = info(arena, type, StructType.HOST_VULKAN_DEVICE_INFO, 0);
            put(nativeInfo, type, "instance", value.instance);
            put(nativeInfo, type, "physicalDevice", value.physicalDevice);
            put(nativeInfo, type, "instanceApiVersion", value.instanceApiVersion);
            put(nativeInfo, type, "instanceExtensionCount", value.instanceExtensionCount);
            put(nativeInfo, type, "instanceExtensions", value.instanceExtensionsAddress);
            put(nativeInfo, type, "graphicsQueueFamily", value.graphicsQueueFamily);
            put(nativeInfo, type, "deviceCreateInfo", value.deviceCreateInfoAddress);
            int result = call("dxvk_CreateVulkanDevice", nativeInfo);
            if (result == ErrorCode.SUCCESS) {
                value.device = nativeInfo.get(JAVA_LONG, offset(type, "device"));
                value.enabledExtensionCount = nativeInfo.get(JAVA_INT, offset(type, "enabledExtensionCount"));
                value.enabledExtensionsAddress = nativeInfo.get(ADDRESS, offset(type, "enabledExtensions")).address();
            }
            return result;
        }
    }
    private static void bindEntry(MemorySegment table, String name, FunctionDescriptor descriptor) {
        MemorySegment address = table.get(ADDRESS, RemixLayouts.offset("Interface", name));
        if (address.address() == 0) throw new UnsatisfiedLinkError("Remix API is missing " + name);
        functions.put(name, LINKER.downcallHandle(address, descriptor));
    }

    public static synchronized int shutdown() {
        MethodHandle shutdown = functions.get("Shutdown");
        if (shutdown == null) return ErrorCode.NOT_INITIALIZED;
        try {
            int result = (int) shutdown.invokeExact();
            shutdownSupport();
            functions.clear();
            tracyFunctions.clear();
            runtime = null;
            sessionArena.close();
            sessionArena = null;
            return result;
        } catch (Throwable e) { throw failure(e); }
    }

    public static int startup(long hwnd, boolean disableSrgb, boolean forceNoVkSwapchain,
                              boolean editorMode, boolean combineGui, boolean interopImage) {
        try (Scratch arena = scratch()) {
            String type = "StartupInfo";
            MemorySegment info = info(arena, type, StructType.STARTUP_INFO, 0);
            put(info, type, "hwnd", hwnd);
            put(info, type, "disableSrgbConversionForOutput", disableSrgb);
            put(info, type, "forceNoVkSwapchain", forceNoVkSwapchain);
            put(info, type, "editorModeEnabled", editorMode);
            put(info, type, "combineGuiInFinalColor", combineGui);
            put(info, type, "interopImage", interopImage);
            return call("Startup", info);
        }
    }
    public static int submit(int interopFlags) { return submit(interopFlags, null); }

    public static int submit(int interopFlags, WaterSimulationFrame water) {
        try (Scratch arena = scratch()) {
            long chain = 0;
            if (water != null && !water.isEmpty()) {
                long weather = 0;
                if (!Float.isNaN(water.rain())) {
                    String rainType = "SubmitInfoWaterRainEXT";
                    MemorySegment rain = info(arena, rainType, StructType.SUBMIT_INFO_WATER_RAIN_EXT, 0);
                    put(rain, rainType, "rain", water.rain());
                    weather = rain.address();
                }
                String type = "SubmitInfoWaterSimEXT";
                MemorySegment extension = info(arena, type, StructType.SUBMIT_INFO_WATER_SIM_EXT, weather);
                int count = water.count();
                if (count != 0) {
                    var layout = RemixLayouts.layout("WaterImpulse");
                    long stride = layout.byteSize();
                    MemorySegment impulses = arena.allocate(stride * WaterSimulationFrame.MAX_IMPULSES, layout.byteAlignment());
                    for (int i = 0; i < count; i++) {
                        MemorySegment impulse = impulses.asSlice(i * stride, stride);
                        vector3(impulse, "WaterImpulse", "position", water.value(i, 0), water.value(i, 1), water.value(i, 2));
                        put(impulse, "WaterImpulse", "radius", water.value(i, 3));
                        put(impulse, "WaterImpulse", "strength", water.value(i, 4));
                    }
                    put(extension, type, "impulses", impulses.address());
                }
                put(extension, type, "impulseCount", count);
                put(extension, type, "reset", water.resetsWaves() ? 1 : 0);
                chain = extension.address();
            }
            MemorySegment info = info(arena, "SubmitInfo", StructType.SUBMIT_INFO, chain);
            put(info, "SubmitInfo", "interopFlags", interopFlags);
            return call("Submit", info);
        }
    }
    public static int present(long hwndOverride, int skipVulkanSwapchain, int interopFlipY,
                              long clientDoneSemaphore_ValueToWait, int disableCpuWaitForExternalSemaphores) {
        return present(hwndOverride, skipVulkanSwapchain, interopFlipY,
                clientDoneSemaphore_ValueToWait, disableCpuWaitForExternalSemaphores, null);
    }

    public static int present(long hwndOverride, int skipVulkanSwapchain, int interopFlipY,
                              long clientDoneSemaphore_ValueToWait, int disableCpuWaitForExternalSemaphores,
                              VulkanClientFrameInfo frame) {
        try (Scratch arena = scratch()) {
            if (hwndOverride == 0) return call("Present", MemorySegment.NULL);
            MemorySegment client = MemorySegment.NULL;
            if (frame != null) {
                String type = "VulkanClientFrameInfoEXT";
                client = info(arena, type, StructType.VULKAN_CLIENT_FRAME_INFO_EXT, 0);
                put(client, type, "device", frame.device);
                put(client, type, "image", frame.image);
                put(client, type, "imageGeneration", frame.imageGeneration);
                put(client, type, "width", frame.width);
                put(client, type, "height", frame.height);
                put(client, type, "format", frame.format);
                put(client, type, "bottomUp", frame.bottomUp ? 1 : 0);
                put(client, type, "vsync", frame.vsync ? 1 : 0);
                put(client, type, "allowFrameGeneration", frame.allowFrameGeneration ? 1 : 0);
                put(client, type, "sourceReadySemaphore", frame.sourceReadySemaphore);
                put(client, type, "sourceReadyValue", frame.sourceReadyValue);
            }
            String type = "PresentInfo";
            MemorySegment info = info(arena, type, StructType.PRESENT_INFO, client.address());
            put(info, type, "hwndOverride", hwndOverride);
            put(info, type, "skipVulkanSwapchain", skipVulkanSwapchain);
            put(info, type, "interopFlipY", interopFlipY);
            put(info, type, "clientDoneSemaphore_ValueToWait", clientDoneSemaphore_ValueToWait);
            put(info, type, "disableCpuWaitForExternalSemaphores", disableCpuWaitForExternalSemaphores);
            int result = call("Present", info);
            if (result == ErrorCode.SUCCESS && frame != null) {
                frame.sourceConsumedSemaphore = client.get(JAVA_LONG, offset("VulkanClientFrameInfoEXT", "sourceConsumedSemaphore"));
                frame.sourceConsumedValue = client.get(JAVA_LONG, offset("VulkanClientFrameInfoEXT", "sourceConsumedValue"));
                frame.presentationEpoch = client.get(JAVA_LONG, offset("VulkanClientFrameInfoEXT", "presentationEpoch"));
                frame.renderedFrames = client.get(JAVA_LONG, offset("VulkanClientFrameInfoEXT", "renderedFrames"));
                frame.generatedFrames = client.get(JAVA_LONG, offset("VulkanClientFrameInfoEXT", "generatedFrames"));
            }
            return result;
        }
    }

    public static long createMaterial(long hash,
            long albedoTexture, long normalTexture,
            long tangentTexture, long emissiveTexture,
            float emissiveIntensity,
            float emissiveR, float emissiveG, float emissiveB,
            int spriteSheetRow, int spriteSheetCol, int spriteSheetFps,
            int filterMode, int wrapModeU, int wrapModeV,
            long pNext) {
        try (Scratch arena = scratch()) {
            MemorySegment info = arena.allocate(RemixLayouts.layout("MaterialInfo"));
            put(info, "MaterialInfo", "sType", StructType.MATERIAL_INFO);
            put(info, "MaterialInfo", "pNext", pNext);
            put(info, "MaterialInfo", "hash", hash);
            put(info, "MaterialInfo", "albedoTexture", albedoTexture);
            put(info, "MaterialInfo", "normalTexture", normalTexture);
            put(info, "MaterialInfo", "tangentTexture", tangentTexture);
            put(info, "MaterialInfo", "emissiveTexture", emissiveTexture);
            put(info, "MaterialInfo", "emissiveIntensity", emissiveIntensity);
            vector3(info, "MaterialInfo", "emissiveColorConstant", emissiveR, emissiveG, emissiveB);
            put(info, "MaterialInfo", "spriteSheetRow", spriteSheetRow);
            put(info, "MaterialInfo", "spriteSheetCol", spriteSheetCol);
            put(info, "MaterialInfo", "spriteSheetFps", spriteSheetFps);
            put(info, "MaterialInfo", "filterMode", filterMode);
            put(info, "MaterialInfo", "wrapModeU", wrapModeU);
            put(info, "MaterialInfo", "wrapModeV", wrapModeV);
            return create("CreateMaterial", info, arena);
        }
    }

    public static int updateMaterial(long handle, long hash,
            long albedoTexture, long normalTexture,
            long tangentTexture, long emissiveTexture,
            float emissiveIntensity,
            float emissiveR, float emissiveG, float emissiveB,
            int spriteSheetRow, int spriteSheetCol, int spriteSheetFps,
            int filterMode, int wrapModeU, int wrapModeV,
            long pNext) {
        try (Scratch arena = scratch()) {
            MemorySegment info = arena.allocate(RemixLayouts.layout("MaterialInfo"));
            put(info, "MaterialInfo", "sType", StructType.MATERIAL_INFO);
            put(info, "MaterialInfo", "pNext", pNext);
            put(info, "MaterialInfo", "hash", hash);
            put(info, "MaterialInfo", "albedoTexture", albedoTexture);
            put(info, "MaterialInfo", "normalTexture", normalTexture);
            put(info, "MaterialInfo", "tangentTexture", tangentTexture);
            put(info, "MaterialInfo", "emissiveTexture", emissiveTexture);
            put(info, "MaterialInfo", "emissiveIntensity", emissiveIntensity);
            vector3(info, "MaterialInfo", "emissiveColorConstant", emissiveR, emissiveG, emissiveB);
            put(info, "MaterialInfo", "spriteSheetRow", spriteSheetRow);
            put(info, "MaterialInfo", "spriteSheetCol", spriteSheetCol);
            put(info, "MaterialInfo", "spriteSheetFps", spriteSheetFps);
            put(info, "MaterialInfo", "filterMode", filterMode);
            put(info, "MaterialInfo", "wrapModeU", wrapModeU);
            put(info, "MaterialInfo", "wrapModeV", wrapModeV);
            return call("UpdateMaterial", pointer(handle), info);
        }
    }

    public static long createLight(long hash,
                                          float radianceR, float radianceG,
                                          float radianceB, long pNext) {
        try (Scratch arena = scratch()) {
            return create("CreateLight", lightInfo(arena, hash, radianceR, radianceG, radianceB, pNext), arena);
        }
    }

    /** Native code copies the light and its extension chain before the call returns. */
    public static int updateLight(long handle, long hash, float radianceR, float radianceG,
                                  float radianceB, long pNext) {
        try (Scratch arena = scratch()) {
            return call("UpdateLight", pointer(handle), lightInfo(arena, hash, radianceR, radianceG, radianceB, pNext));
        }
    }

    private static MemorySegment lightInfo(Scratch arena, long hash, float r, float g, float b, long pNext) {
        MemorySegment info = info(arena, "LightInfo", StructType.LIGHT_INFO, pNext);
        put(info, "LightInfo", "hash", hash);
        vector3(info, "LightInfo", "radiance", r, g, b);
        return info;
    }

    public static long createTextureFromMemory(long hash, ByteBuffer pixelData,
            int width, int height, int format, int purpose) {
        return createTextureFromMemory(hash, pixelData, width, height, format, purpose, 0);
    }

    /** Direct packed mip bytes remain live through the call; the runtime copies before returning. */
    public static long createTextureFromMemory(long hash, ByteBuffer pixelData,
            int width, int height, int format, int purpose, int mipLevels) {
        return createTextureFromMemory(hash, pixelData, width, height, format, purpose, mipLevels, false);
    }

    /** Direct pixels must remain live through this call, including immutable texture uploads. */
    public static long createTextureFromMemory(long hash, ByteBuffer pixelData,
            int width, int height, int format, int purpose, int mipLevels, boolean immutable) {
        if (mipLevels < 0 || mipLevels > 32) throw new IllegalArgumentException("Invalid authored mip count");
        long bytes = textureBytes(width, height, format);
        if (mipLevels > 0) {
            for (int mip = 1, w = width, h = height; mip < mipLevels; mip++) {
                if (w == 1 && h == 1) throw new IllegalArgumentException("Mip count exceeds texture dimensions");
                w = Math.max(1, w / 2); h = Math.max(1, h / 2);
                bytes = Math.addExact(bytes, textureBytes(w, h, format));
            }
        }
        try (Scratch arena = scratch()) {
            MemorySegment info = arena.allocate(RemixLayouts.layout("TextureFromMemoryInfo"));
            put(info, "TextureFromMemoryInfo", "sType", StructType.TEXTURE_FROM_MEMORY_INFO);
            put(info, "TextureFromMemoryInfo", "hash", hash);
            put(info, "TextureFromMemoryInfo", "pixelData", direct(pixelData, bytes).address());
            put(info, "TextureFromMemoryInfo", "width", width);
            put(info, "TextureFromMemoryInfo", "height", height);
            put(info, "TextureFromMemoryInfo", "format", format);
            put(info, "TextureFromMemoryInfo", "purpose", purpose);
            long pNext = immutable
                    ? info(arena, "TextureImmutableEXT", StructType.TEXTURE_IMMUTABLE_EXT, 0).address() : 0;
            if (mipLevels > 0) {
                MemorySegment extension = info(arena, "TextureMipChainEXT", StructType.TEXTURE_MIP_CHAIN_EXT, pNext);
                put(extension, "TextureMipChainEXT", "mipLevels", mipLevels);
                put(extension, "TextureMipChainEXT", "dataSize", bytes);
                pNext = extension.address();
            }
            put(info, "TextureFromMemoryInfo", "pNext", pNext);
            return create("CreateTextureFromMemory", info, arena);
        } finally {
            Reference.reachabilityFence(pixelData);
        }
    }

    public static int destroyMaterial(long handle) { return handle("DestroyMaterial", handle); }
    public static int destroyTexture(long handle) { return handle("DestroyTexture", handle); }
    public static int destroyMesh(long handle) { return handle("DestroyMesh", handle); }
    public static int destroyLight(long handle) { return handle("DestroyLight", handle); }
    public static int drawLightInstance(long handle) { return handle("DrawLightInstance", handle); }
    public static int sceneInstanceRemove(long handle) { return handle("SceneInstanceRemove", handle); }
    public static long createTextureFromFile(String filePath) {
        try (Scratch arena = scratch()) { return create("CreateTextureFromFile", utf16(arena, filePath), arena); }
    }
    public static int setupCamera(int type, float[] view16, float[] projection16, long pNext) {
        try (Scratch arena = scratch()) {
            MemorySegment info = info(arena, "CameraInfo", StructType.CAMERA_INFO, pNext);
            put(info, "CameraInfo", "type", type);
            floats(info, "CameraInfo", "view", view16);
            floats(info, "CameraInfo", "projection", projection16);
            return call("SetupCamera", info);
        }
    }
    public static int setCameraMediumMaterial(long materialHandle) {
        try (Scratch arena = scratch()) {
            MemorySegment info = info(arena, "CameraMediumInfo", StructType.CAMERA_MEDIUM_INFO, 0);
            put(info, "CameraMediumInfo", "medium", materialHandle);
            return call("SetCameraMediumMaterial", info);
        }
    }
    private static MemorySegment instance(SegmentAllocator arena, long meshHandle, float[] transform12,
                                          int categoryFlags, int doubleSided, long pNext) {
        MemorySegment info = info(arena, "InstanceInfo", StructType.INSTANCE_INFO, pNext);
        put(info, "InstanceInfo", "mesh", meshHandle);
        put(info, "InstanceInfo", "categoryFlags", categoryFlags);
        put(info, "InstanceInfo", "doubleSided", doubleSided);
        floats(info, "InstanceInfo", "transform", transform12);
        return info;
    }
    public static int drawInstance(long meshHandle, float[] transform12, int categoryFlags, int doubleSided, long pNext) {
        try (Scratch arena = scratch()) {
            return call("DrawInstance", instance(arena, meshHandle, transform12, categoryFlags, doubleSided, pNext));
        }
    }
    public static long sceneInstanceAdd(long meshHandle, float[] transform12, int categoryFlags, int doubleSided, long pNext) {
        try (Scratch arena = scratch()) {
            return create("SceneInstanceAdd", instance(arena, meshHandle, transform12, categoryFlags, doubleSided, pNext), arena);
        }
    }
    public static int sceneInstanceUpdate(long sceneInstanceHandle, long meshHandle, float[] transform12,
                                           int categoryFlags, int doubleSided, long pNext) {
        try (Scratch arena = scratch()) {
            return call("SceneInstanceUpdate", pointer(sceneInstanceHandle), instance(arena, meshHandle, transform12, categoryFlags, doubleSided, pNext));
        }
    }
    public static int setConfigVariable(String key, String value) {
        try (Scratch arena = scratch()) {
            return call("SetConfigVariable", arena.allocateFrom(key), arena.allocateFrom(value));
        }
    }
    public static long getNeuralRadianceCacheTrainingRecords() {
        try (Scratch arena = scratch()) {
            MemorySegment records = arena.allocate(JAVA_INT);
            int status = call("GetNrcStatus", records);
            if (status < 0) throw new IllegalStateException("No Remix device registered");
            return status == 0 ? -1 : Integer.toUnsignedLong(records.get(JAVA_INT, 0));
        }
    }

    public static int getDlssNeuralRenderingStatus() {
        MethodHandle query = functions.get("GetDlssNeuralRenderingStatus");
        if (query == null) return 0;
        try { return (int) query.invokeExact(); }
        catch (Throwable e) { throw failure(e); }
    }

    public static int getConfigVariableResolved(String key, byte[] outValue) {
        if (key == null) return -ErrorCode.INVALID_ARGUMENTS;
        try (Scratch arena = scratch()) {
            String type = "ConfigVariableResolvedInfo";
            MemorySegment info = info(arena, type, StructType.CONFIG_VARIABLE_RESOLVED_INFO, 0);
            MemorySegment value = outValue == null ? MemorySegment.NULL : arena.allocate(outValue.length);
            put(info, type, "key", stableName(key).address());
            put(info, type, "value", value.address());
            put(info, type, "valueSize", outValue == null ? 0 : outValue.length);
            int result = call("GetConfigVariableResolved", info);
            if (result == ErrorCode.SUCCESS && outValue != null)
                MemorySegment.copy(value, JAVA_BYTE, 0, outValue, 0, outValue.length);
            return result == ErrorCode.SUCCESS || result == ErrorCode.BUFFER_TOO_SMALL
                    ? info.get(JAVA_INT, offset(type, "valueSize")) : -result;
        }
    }
    public static long acquireConfigLayer(String path, float priority, float blendThreshold) {
        MethodHandle function = functions.get("AcquireConfigLayer");
        if (function == null) return 0;
        try (Scratch arena = scratch()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int result = (int) function.invokeExact(arena.allocateFrom(path), priority, blendThreshold, out);
            return result == ErrorCode.SUCCESS ? out.get(ADDRESS, 0).address() : 0;
        } catch (Throwable e) { throw failure(e); }
    }
    public static int setConfigLayerBlending(long handle, float strength, float blendThreshold) {
        MethodHandle function = functions.get("SetConfigLayerBlending");
        if (function == null) return ErrorCode.NOT_INITIALIZED;
        try { return (int) function.invokeExact(pointer(handle), strength, blendThreshold); }
        catch (Throwable e) { throw failure(e); }
    }
    public static int releaseConfigLayer(long handle) { return handle("ReleaseConfigLayer", handle); }
    public static boolean isConfigLayersSupported() { return functions.containsKey("AcquireConfigLayer"); }

    public static int createSharedBuffer(long size, SharedBufferInfo output) {
        if (size <= 0 || size > (512L << 20)) throw new IllegalArgumentException("Invalid shared GPU buffer size");
        try (Scratch arena = scratch()) {
            String type = "SharedBufferInfo";
            MemorySegment info = info(arena, type, StructType.SHARED_BUFFER_INFO, 0);
            put(info, type, "size", size);
            int result = call("CreateSharedBuffer", info);
            if (result != ErrorCode.SUCCESS) return result;
            output.size = info.get(JAVA_LONG, offset(type, "size"));
            output.buffer = info.get(ADDRESS, offset(type, "buffer")).address();
            output.memoryHandle = info.get(JAVA_LONG, offset(type, "memoryHandle"));
            output.allocationSize = info.get(JAVA_LONG, offset(type, "allocationSize"));
            output.bindOffset = info.get(JAVA_LONG, offset(type, "bindOffset"));
            output.producerSemaphoreHandle = info.get(JAVA_LONG, offset(type, "producerSemaphoreHandle"));
            output.readyValue = info.get(JAVA_LONG, offset(type, "readyValue"));
            return result;
        }
    }

    /** Marshals handle metadata only; no GPU payload is mapped or copied through Java. */
    public static int commitSharedBuffers(long[] buffers, long readyValue) {
        if (buffers.length == 0 || buffers.length > 4096 || readyValue <= 0) throw new IllegalArgumentException("Invalid shared GPU batch");
        try (Scratch arena = scratch()) {
            MemorySegment handles = arena.allocate((long) buffers.length * ADDRESS.byteSize(), ADDRESS.byteAlignment());
            for (int i = 0; i < buffers.length; i++) handles.set(ADDRESS, (long) i * ADDRESS.byteSize(), pointer(buffers[i]));
            String type = "SharedBufferCommitInfo";
            MemorySegment info = info(arena, type, StructType.SHARED_BUFFER_COMMIT_INFO, 0);
            put(info, type, "buffers", handles.address()); put(info, type, "bufferCount", buffers.length);
            put(info, type, "readyValue", readyValue);
            return call("CommitSharedBuffers", info);
        }
    }

    /** The direct level buffer remains live until return; the runtime copies it during the call. */
    public static int setVoxelCoverage(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
            int cellSize, ByteBuffer levels) {
        return setVoxelCoverageTree(originX, originY, originZ, sizeX, sizeY, sizeZ, cellSize, levels, 1, null, 0);
    }

    /** Both direct buffers are borrowed until return. Tree nodes follow the public 64-byte C layout. */
    public static int setVoxelCoverageTree(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ,
            int cellSize, ByteBuffer levels, int flags, ByteBuffer treeNodes, int treeNodeCount) {
        if ((flags & ~1) != 0 || treeNodeCount < 0 || treeNodeCount > (1 << 20)) {
            throw new IllegalArgumentException("Invalid voxel coverage tree");
        }
        boolean clear = sizeX == 0 && sizeY == 0 && sizeZ == 0;
        long count = Math.multiplyExact(Math.multiplyExact((long) sizeX, sizeY), sizeZ);
        if (!clear && (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || count > (32L << 20)
                || cellSize <= 0 || (cellSize & (cellSize - 1)) != 0)) {
            throw new IllegalArgumentException("Invalid voxel coverage grid");
        }
        try (Scratch arena = scratch()) {
            String type = "VoxelCoverageInfo";
            MemorySegment data = clear ? MemorySegment.NULL : direct(levels, count);
            MemorySegment info = info(arena, type, StructType.VOXEL_COVERAGE_INFO, 0);
            put(info, type, "originX", originX); put(info, type, "originY", originY); put(info, type, "originZ", originZ);
            put(info, type, "sizeX", sizeX); put(info, type, "sizeY", sizeY); put(info, type, "sizeZ", sizeZ);
            put(info, type, "cellSize", cellSize); put(info, type, "dataSize", count); put(info, type, "levels", data.address());
            MemorySegment nodes = treeNodeCount == 0 ? MemorySegment.NULL : direct(treeNodes, (long) treeNodeCount * 64);
            put(info, type, "flags", flags); put(info, type, "treeNodes", nodes.address());
            put(info, type, "treeNodeCount", treeNodeCount);
            try { return call("SetVoxelCoverage", info); }
            finally { Reference.reachabilityFence(levels); Reference.reachabilityFence(treeNodes); }
        }
    }

    public static int destroySharedBuffer(long handle) { return handle("DestroySharedBuffer", handle); }

    /** Runtime retains committed allocations after this call; no native segment escapes the call. */
    public static long createMeshFromSharedBuffers(MeshSharedSurface[] descriptions) {
        if (descriptions.length == 0 || descriptions.length > 65536) throw new IllegalArgumentException("Invalid shared mesh surface count");
        try (Scratch arena = scratch()) {
            String type = "MeshSharedSurface";
            long stride = RemixLayouts.size(type);
            MemorySegment surfaces = arena.allocate(stride * descriptions.length, 8);
            for (int i = 0; i < descriptions.length; i++) {
                var source = descriptions[i];
                var target = surfaces.asSlice(i * stride, stride);
                put(target, type, "vertexBuffer", source.vertexBuffer()); put(target, type, "vertexOffset", source.vertexOffset());
                put(target, type, "vertexCount", source.vertexCount()); put(target, type, "indexBuffer", source.indexBuffer());
                put(target, type, "indexOffset", source.indexOffset()); put(target, type, "indexCount", source.indexCount());
                put(target, type, "material", source.material()); put(target, type, "flags", source.flags());
            }
            String extensionType = "MeshInfoSharedBuffersEXT";
            MemorySegment extension = info(arena, extensionType, StructType.MESH_INFO_SHARED_BUFFERS_EXT, 0);
            put(extension, extensionType, "surfaces", surfaces.address());
            put(extension, extensionType, "surfaceCount", descriptions.length);
            MemorySegment info = info(arena, "MeshInfo", StructType.MESH_INFO, extension.address());
            return create("CreateMesh", info, arena);
        }
    }

    public static long createQuadMesh(long hash, MeshQuadSurface[] descriptions) {
        if (descriptions.length == 0 || descriptions.length > 65536)
            throw new IllegalArgumentException("Invalid quad surface count");
        try (Scratch arena = scratch()) {
            String type = "MeshQuadSurface";
            long stride = RemixLayouts.size(type);
            MemorySegment surfaces = arena.allocate(stride * descriptions.length, 8);
            for (int i = 0; i < descriptions.length; i++) {
                var source = descriptions[i];
                var target = surfaces.asSlice(i * stride, stride);
                put(target, type, "material", source.material());
                put(target, type, "vertices", direct(source.vertices(), (long) source.quadCount() * 64).address());
                put(target, type, "materials", direct(source.materials(), (long) source.materialCount() * 40).address());
                put(target, type, "quadCount", source.quadCount());
                put(target, type, "materialCount", source.materialCount());
            }
            String extensionType = "MeshInfoQuadsEXT";
            MemorySegment extension = info(arena, extensionType, StructType.MESH_INFO_QUADS_EXT, 0);
            put(extension, extensionType, "surfaces", surfaces.address());
            put(extension, extensionType, "surfaceCount", descriptions.length);
            MemorySegment mesh = info(arena, "MeshInfo", StructType.MESH_INFO, extension.address());
            put(mesh, "MeshInfo", "hash", hash);
            try { return create("CreateMesh", mesh, arena); }
            finally { Reference.reachabilityFence(descriptions); }
        }
    }

    public static long createMesh(long hash, int surfaceCount, ByteBuffer[] vertexBuffers, int[] vertexCounts,
            ByteBuffer[] indexBuffers, int[] indexCounts, long[] materialHandles, int[] flags,
            int[] bonesPerVertex, ByteBuffer[] blendWeightBuffers, int[] blendWeightCounts,
            ByteBuffer[] blendIndexBuffers, int[] blendIndexCounts) {
        try (Scratch arena = scratch()) {
            if (surfaceCount < 0) throw new IllegalArgumentException("Negative surface count");
            String type = "MeshInfoSurfaceTriangles";
            long stride = RemixLayouts.size(type);
            MemorySegment surfaces = arena.allocate(Math.multiplyExact(stride, surfaceCount), 8);
            for (int i = 0; i < surfaceCount; i++) {
                MemorySegment surface = surfaces.asSlice(i * stride, stride);
                if ((flags[i] & MeshInfoSurfaceTriangles.PREVIOUS_POSITIONS) != 0
                        && ((flags[i] & MeshInfoSurfaceTriangles.COMPACT_VERTICES) != 0
                            || (bonesPerVertex != null && bonesPerVertex[i] > 0))) {
                    throw new IllegalArgumentException("Explicit mesh motion requires full-stride, unskinned vertices");
                }
                int vertexStride = (flags[i] & MeshInfoSurfaceTriangles.COMPACT_VERTICES) != 0
                        ? HardcodedVertex.STORAGE_STRIDE : HardcodedVertex.STRIDE;
                put(surface, type, "vertices_values", direct(vertexBuffers[i], Math.multiplyExact((long) vertexCounts[i], vertexStride)).address());
                put(surface, type, "vertices_count", vertexCounts[i]);
                put(surface, type, "indices_values", direct(indexBuffers[i], Math.multiplyExact((long) indexCounts[i], 4L)).address());
                put(surface, type, "indices_count", indexCounts[i]);
                put(surface, type, "material", materialHandles[i]);
                put(surface, type, "flags", flags[i]);
                if (bonesPerVertex != null && bonesPerVertex[i] > 0 && blendWeightBuffers != null && blendIndexBuffers != null
                        && blendWeightBuffers[i] != null && blendIndexBuffers[i] != null) {
                    put(surface, type, "skinning_hasvalue", true);
                    put(surface, type, "skinning_value.bonesPerVertex", bonesPerVertex[i]);
                    put(surface, type, "skinning_value.blendWeights_values", direct(blendWeightBuffers[i], Math.multiplyExact((long) blendWeightCounts[i], 4L)).address());
                    put(surface, type, "skinning_value.blendWeights_count", blendWeightCounts[i]);
                    put(surface, type, "skinning_value.blendIndices_values", direct(blendIndexBuffers[i], Math.multiplyExact((long) blendIndexCounts[i], 4L)).address());
                    put(surface, type, "skinning_value.blendIndices_count", blendIndexCounts[i]);
                }
            }
            MemorySegment info = info(arena, "MeshInfo", StructType.MESH_INFO, 0);
            put(info, "MeshInfo", "hash", hash);
            put(info, "MeshInfo", "surfaces_values", surfaces.address());
            put(info, "MeshInfo", "surfaces_count", surfaceCount);
            return create("CreateMesh", info, arena);
        } finally {
            Reference.reachabilityFence(vertexBuffers);
            Reference.reachabilityFence(indexBuffers);
            Reference.reachabilityFence(blendWeightBuffers);
            Reference.reachabilityFence(blendIndexBuffers);
        }
    }
    public static int updateTextureFromMemory(long[] handles, int[] entryPatchCounts, int[] dstXs, int[] dstYs,
            int[] widths, int[] heights, ByteBuffer[] pixelBuffers, int[] rowPitches, int[] formats) {
        return updateTextureFromMemory(handles, entryPatchCounts, dstXs, dstYs, widths, heights, pixelBuffers, rowPitches, formats, null);
    }

    private static final class TextureUploadLayout {
        static final long ENTRY_STRIDE = RemixLayouts.size("TextureBatchEntry");
        static final long HANDLE = offset("TextureBatchEntry", "handle");
        static final long PATCH_COUNT = offset("TextureBatchEntry", "patchCount");
        static final long PATCHES = offset("TextureBatchEntry", "patches");
        static final long PATCH_STRIDE = RemixLayouts.size("TexturePatch");
        static final long X = offset("TexturePatch", "dstX");
        static final long Y = offset("TexturePatch", "dstY");
        static final long WIDTH = offset("TexturePatch", "width");
        static final long HEIGHT = offset("TexturePatch", "height");
        static final long PIXELS = offset("TexturePatch", "pixelData");
        static final long PITCH = offset("TexturePatch", "rowPitch");
        static final long FORMAT = offset("TexturePatch", "format");
        static final long MIP = offset("TexturePatch", "mipLevel");
    }

    public static int updateTextureFromMemory(long[] handles, int[] entryPatchCounts, int[] dstXs, int[] dstYs,
            int[] widths, int[] heights, ByteBuffer[] pixelBuffers, int[] rowPitches, int[] formats, int[] mipLevels) {
        if (handles == null || entryPatchCounts == null || entryPatchCounts.length != handles.length)
            return ErrorCode.INVALID_ARGUMENTS;
        try (Scratch arena = scratch()) {
            long patchCount = 0;
            for (int count : entryPatchCounts) {
                if (count < 0) return ErrorCode.INVALID_ARGUMENTS;
                patchCount += count;
            }
            if (dstXs == null || dstYs == null || widths == null || heights == null || pixelBuffers == null
                    || rowPitches == null || formats == null || dstXs.length != patchCount || dstYs.length != patchCount
                    || widths.length != patchCount || heights.length != patchCount || pixelBuffers.length != patchCount
                    || rowPitches.length != patchCount || formats.length != patchCount)
                return ErrorCode.INVALID_ARGUMENTS;
            if (mipLevels != null && mipLevels.length != patchCount) return ErrorCode.INVALID_ARGUMENTS;
            long entryStride = TextureUploadLayout.ENTRY_STRIDE, patchStride = TextureUploadLayout.PATCH_STRIDE;
            MemorySegment entries = arena.allocate(Math.multiplyExact(handles.length, entryStride), 8);
            MemorySegment patches = arena.allocate(Math.multiplyExact(patchCount, patchStride), 8);
            int patchIndex = 0;
            for (int i = 0; i < handles.length; i++) {
                if (handles[i] == 0 || entryPatchCounts[i] == 0) return ErrorCode.INVALID_ARGUMENTS;
                long entry = i * entryStride;
                entries.set(ADDRESS, entry + TextureUploadLayout.HANDLE, pointer(handles[i]));
                entries.set(JAVA_INT, entry + TextureUploadLayout.PATCH_COUNT, entryPatchCounts[i]);
                entries.set(ADDRESS, entry + TextureUploadLayout.PATCHES, pointer(patches.address() + patchIndex * patchStride));
                for (int j = 0; j < entryPatchCounts[i]; j++, patchIndex++) {
                    int k = patchIndex;
                    int bpp = bytesPerPixel(formats[k]);
                    if (mipLevels != null && (mipLevels[k] < 0 || mipLevels[k] > 31)) return ErrorCode.INVALID_ARGUMENTS;
                    if (dstXs[k] < 0 || dstYs[k] < 0 || widths[k] <= 0 || heights[k] <= 0 || bpp == 0)
                        return ErrorCode.INVALID_ARGUMENTS;
                    long rowSize = (long) widths[k] * bpp;
                    long pitch = rowPitches[k] == 0 ? rowSize : Integer.toUnsignedLong(rowPitches[k]);
                    if (pitch < rowSize) return ErrorCode.INVALID_ARGUMENTS;
                    long bytes = Math.addExact(Math.multiplyExact(pitch, heights[k] - 1L), rowSize);
                    if (pixelBuffers[k] == null || !pixelBuffers[k].isDirect() || bytes > pixelBuffers[k].capacity())
                        return ErrorCode.INVALID_ARGUMENTS;
                    long patch = k * patchStride;
                    // The buffer's capacity was validated above. Recover its index-zero
                    // address without allocating a duplicate/slice for every sprite.
                    long pixels = MemorySegment.ofBuffer(pixelBuffers[k]).address() - pixelBuffers[k].position();
                    patches.set(JAVA_INT, patch + TextureUploadLayout.X, dstXs[k]);
                    patches.set(JAVA_INT, patch + TextureUploadLayout.Y, dstYs[k]);
                    patches.set(JAVA_INT, patch + TextureUploadLayout.WIDTH, widths[k]);
                    patches.set(JAVA_INT, patch + TextureUploadLayout.HEIGHT, heights[k]);
                    patches.set(ADDRESS, patch + TextureUploadLayout.PIXELS, pointer(pixels));
                    patches.set(JAVA_INT, patch + TextureUploadLayout.PITCH, rowPitches[k]);
                    patches.set(JAVA_INT, patch + TextureUploadLayout.FORMAT, formats[k]);
                    patches.set(JAVA_INT, patch + TextureUploadLayout.MIP, mipLevels == null ? 0 : mipLevels[k]);
                }
            }
            MemorySegment info = info(arena, "TexturePatchInfo", StructType.TEXTURE_PATCH_INFO, 0);
            put(info, "TexturePatchInfo", "entryCount", handles.length);
            put(info, "TexturePatchInfo", "entries", entries.address());
            return call("UpdateTextureFromMemory", info);
        } finally {
            Reference.reachabilityFence(pixelBuffers);
        }
    }
    private static long textureBytes(int width, int height, int format) {
        int bpp = bytesPerPixel(format);
        if (width <= 0 || height <= 0 || bpp == 0) throw new IllegalArgumentException("Invalid texture dimensions or format");
        return Math.multiplyExact(Math.multiplyExact((long) width, height), bpp);
    }
    private static int bytesPerPixel(int format) {
        return switch (format) {
            case TextureFormat.RGBA8_UNORM, TextureFormat.RGBA8_SRGB, TextureFormat.BGRA8_UNORM, TextureFormat.BGRA8_SRGB, TextureFormat.R32_SFLOAT -> 4;
            case TextureFormat.R8_UNORM -> 1;
            case TextureFormat.RG8_UNORM -> 2;
            case TextureFormat.RGBA16_SFLOAT -> 8;
            case TextureFormat.RGBA32_SFLOAT -> 16;
            default -> 0;
        };
    }
    /** Copies SDK capabilities into a holder without retaining native strings or scratch segments. */
    public static int getFrameGenerationCapabilities(FrameGenerationCapabilities output) {
        try (Scratch arena = scratch()) {
            String type = "FrameGenerationCapabilitiesEXT";
            MemorySegment capabilities = info(arena, type, StructType.FRAME_GENERATION_CAPABILITIES_EXT, 0);
            MemorySegment info = info(arena, "VulkanInteropInfo", StructType.VULKAN_INTEROP_INFO, capabilities.address());
            int result = call("dxvk_GetVulkanInteropInfo", info);
            if (result != ErrorCode.SUCCESS) return result;
            output.initializationComplete = capabilities.get(JAVA_INT, offset(type, "initializationComplete")) != 0;
            output.supported = capabilities.get(JAVA_INT, offset(type, "supported")) != 0;
            output.maxGeneratedFrames = capabilities.get(JAVA_INT, offset(type, "maxGeneratedFrames"));
            output.notSupportedReason = capabilities.asSlice(offset(type, "notSupportedReason"), 256).getString(0);
            return result;
        }
    }

    /** Fills a reusable holder with native Vulkan image and timeline semaphore metadata. */
    public static int dxvkGetVulkanInteropInfo(VulkanInteropInfo output) {
        try (Scratch arena = scratch()) {
            MemorySegment status = info(arena, "InteropFrameStatusEXT", StructType.INTEROP_FRAME_STATUS_EXT, 0);
            MemorySegment info = info(arena, "VulkanInteropInfo", StructType.VULKAN_INTEROP_INFO, status.address());
            int result = call("dxvk_GetVulkanInteropInfo", info);
            if (result != ErrorCode.SUCCESS) return result;
            output.frameStatus.frameId = status.get(JAVA_LONG, offset("InteropFrameStatusEXT", "frameId"));
            output.frameStatus.flags = status.get(JAVA_INT, offset("InteropFrameStatusEXT", "flags"));
            output.frameStatus.shaderCompileCount = status.get(JAVA_INT, offset("InteropFrameStatusEXT", "shaderCompileCount"));
            MemorySegment.copy(info, JAVA_BYTE, offset("VulkanInteropInfo", "deviceUUID"), output.deviceUUID, 0, 16);
            MemorySegment.copy(info, JAVA_BYTE, offset("VulkanInteropInfo", "driverUUID"), output.driverUUID, 0, 16);
            MemorySegment.copy(info, JAVA_BYTE, offset("VulkanInteropInfo", "deviceLUID"), output.deviceLUID, 0, 8);
            output.deviceNodeMask = info.get(JAVA_INT, offset("VulkanInteropInfo", "deviceNodeMask"));
            output.deviceLUIDValid = info.get(JAVA_INT, offset("VulkanInteropInfo", "deviceLUIDValid"));
            output.queueFamilyIndex = info.get(JAVA_INT, offset("VulkanInteropInfo", "queueFamilyIndex"));
            output.device = info.get(JAVA_LONG, offset("VulkanInteropInfo", "device"));
            output.semaphoreType = info.get(JAVA_INT, offset("VulkanInteropInfo", "semaphoreType"));
            output.dxvkDoneSemaphore_Handle = info.get(JAVA_LONG, offset("VulkanInteropInfo", "dxvkDoneSemaphore_Handle"));
            output.dxvkDoneSemaphore_ValueToWait = info.get(JAVA_LONG, offset("VulkanInteropInfo", "dxvkDoneSemaphore_ValueToWait"));
            output.clientDoneSemaphore_Handle = info.get(JAVA_LONG, offset("VulkanInteropInfo", "clientDoneSemaphore_Handle"));
            readVulkanImage(info.asSlice(offset("VulkanInteropInfo", "color"), RemixLayouts.size("VulkanInteropImage")), output.color);
            readVulkanImage(info.asSlice(offset("VulkanInteropInfo", "depth"), RemixLayouts.size("VulkanInteropImage")), output.depth);
            readVulkanImage(info.asSlice(offset("VulkanInteropInfo", "uiOverlay"), RemixLayouts.size("VulkanInteropImage")), output.uiOverlay);
            return result;
        }
    }
    private static void readVulkanImage(MemorySegment image, VulkanInteropInfo.Image output) {
        output.image = image.get(JAVA_LONG, offset("VulkanInteropImage", "image"));
        output.memoryHandle = image.get(JAVA_LONG, offset("VulkanInteropImage", "memoryHandle"));
        output.allocationSize = image.get(JAVA_LONG, offset("VulkanInteropImage", "allocationSize"));
        output.bindOffset = image.get(JAVA_LONG, offset("VulkanInteropImage", "bindOffset"));
        output.generation = image.get(JAVA_LONG, offset("VulkanInteropImage", "generation"));
        output.memoryTypeIndex = image.get(JAVA_INT, offset("VulkanInteropImage", "memoryTypeIndex"));
        output.allocationFlags = image.get(JAVA_INT, offset("VulkanInteropImage", "allocationFlags"));
        output.allocationDeviceMask = image.get(JAVA_INT, offset("VulkanInteropImage", "allocationDeviceMask"));
        output.flags = image.get(JAVA_INT, offset("VulkanInteropImage", "flags"));
        output.imageType = image.get(JAVA_INT, offset("VulkanInteropImage", "imageType"));
        output.format = image.get(JAVA_INT, offset("VulkanInteropImage", "format"));
        output.width = image.get(JAVA_INT, offset("VulkanInteropImage", "width"));
        output.height = image.get(JAVA_INT, offset("VulkanInteropImage", "height"));
        output.depth = image.get(JAVA_INT, offset("VulkanInteropImage", "depth"));
        output.mipLevels = image.get(JAVA_INT, offset("VulkanInteropImage", "mipLevels"));
        output.arrayLayers = image.get(JAVA_INT, offset("VulkanInteropImage", "arrayLayers"));
        output.samples = image.get(JAVA_INT, offset("VulkanInteropImage", "samples"));
        output.tiling = image.get(JAVA_INT, offset("VulkanInteropImage", "tiling"));
        output.usage = image.get(JAVA_INT, offset("VulkanInteropImage", "usage"));
        output.sharingMode = image.get(JAVA_INT, offset("VulkanInteropImage", "sharingMode"));
        output.initialLayout = image.get(JAVA_INT, offset("VulkanInteropImage", "initialLayout"));
        output.externalLayout = image.get(JAVA_INT, offset("VulkanInteropImage", "externalLayout"));
        output.ownerQueueFamily = image.get(JAVA_INT, offset("VulkanInteropImage", "ownerQueueFamily"));
        output.externalMemoryHandleType = image.get(JAVA_INT, offset("VulkanInteropImage", "externalMemoryHandleType"));
        output.dedicatedAllocation = image.get(JAVA_INT, offset("VulkanInteropImage", "dedicatedAllocation"));
        output.viewFormatCount = image.get(JAVA_INT, offset("VulkanInteropImage", "viewFormatCount"));
        MemorySegment.copy(image, JAVA_INT, offset("VulkanInteropImage", "viewFormats"), output.viewFormats, 0, 16);
        if (output.viewFormatCount < 0 || output.viewFormatCount > output.viewFormats.length)
            throw new IllegalStateException("Runtime returned too many Vulkan image view formats");
    }
    public static int dxvkGetInteropInfo(InteropInfo outInfo) {
        try (Scratch arena = scratch()) {
            MemorySegment info = arena.allocate(RemixLayouts.layout("dxvk_InteropInfo"));
            int result = call("dxvk_GetInteropInfo", info);
            if (result != ErrorCode.SUCCESS) return result;
            outInfo.dxvkDoneSemaphore_Handle = info.get(JAVA_LONG, offset("dxvk_InteropInfo", "dxvkDoneSemaphore_Handle"));
            outInfo.dxvkDoneSemaphore_ValueToWait = info.get(JAVA_LONG, offset("dxvk_InteropInfo", "dxvkDoneSemaphore_ValueToWait"));
            outInfo.clientDoneSemaphore_Handle = info.get(JAVA_LONG, offset("dxvk_InteropInfo", "clientDoneSemaphore_Handle"));
            outInfo.imageMemory_Handle = info.get(JAVA_LONG, offset("dxvk_InteropInfo", "imageMemory_Handle"));
            outInfo.allocationSize = info.get(JAVA_LONG, offset("dxvk_InteropInfo", "allocationSize"));
            outInfo.width = info.get(JAVA_INT, offset("dxvk_InteropInfo", "width"));
            outInfo.height = info.get(JAVA_INT, offset("dxvk_InteropInfo", "height"));
            outInfo.interopImageFormat = info.get(JAVA_INT, offset("dxvk_InteropInfo", "interopImageFormat"));
            outInfo.depthImageMemory_Handle = info.get(JAVA_LONG, offset("dxvk_InteropInfo", "depthImageMemory_Handle"));
            outInfo.depthAllocationSize = info.get(JAVA_LONG, offset("dxvk_InteropInfo", "depthAllocationSize"));
            outInfo.depthWidth = info.get(JAVA_INT, offset("dxvk_InteropInfo", "depthWidth"));
            outInfo.depthHeight = info.get(JAVA_INT, offset("dxvk_InteropInfo", "depthHeight"));
            outInfo.interopDepthImageFormat = info.get(JAVA_INT, offset("dxvk_InteropInfo", "interopDepthImageFormat"));
            return result;
        }
    }
    public static long dxvkCreateD3D9(boolean editorMode) {
        MethodHandle function = functions.get("dxvk_CreateD3D9");
        if (function == null) return 0;
        try (Scratch arena = scratch()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int result = (int) function.invokeExact(editorMode ? 1 : 0, out);
            return result == ErrorCode.SUCCESS ? out.get(ADDRESS, 0).address() : 0;
        } catch (Throwable e) { throw failure(e); }
    }
    public static int dxvkRegisterD3D9Device(long device) { return handle("dxvk_RegisterD3D9Device", device); }
    public static long[] dxvkGetExternalSwapchain() {
        MethodHandle function = functions.get("dxvk_GetExternalSwapchain");
        if (function == null) return null;
        try (Scratch arena = scratch()) {
            MemorySegment image = arena.allocate(JAVA_LONG), done = arena.allocate(JAVA_LONG), resume = arena.allocate(JAVA_LONG);
            int result = (int) function.invokeExact(image, done, resume);
            return result == ErrorCode.SUCCESS ? new long[] { image.get(JAVA_LONG, 0), done.get(JAVA_LONG, 0), resume.get(JAVA_LONG, 0) } : null;
        } catch (Throwable e) { throw failure(e); }
    }
    public static long dxvkGetVkImage(long surface) {
        try (Scratch arena = scratch()) {
            MemorySegment out = arena.allocate(JAVA_LONG);
            return call("dxvk_GetVkImage", pointer(surface), out) == ErrorCode.SUCCESS ? out.get(JAVA_LONG, 0) : 0;
        }
    }
    public static int dxvkCopyRenderingOutput(long surface, int type) {
        MethodHandle function = functions.get("dxvk_CopyRenderingOutput");
        if (function == null) return ErrorCode.NOT_INITIALIZED;
        try { return (int) function.invokeExact(pointer(surface), type); } catch (Throwable e) { throw failure(e); }
    }
    public static int dxvkSetDefaultOutput(int type, float r, float g, float b, float a) {
        MethodHandle function = functions.get("dxvk_SetDefaultOutput");
        if (function == null) return ErrorCode.NOT_INITIALIZED;
        try (Scratch arena = scratch()) {
            MemorySegment color = arena.allocateFrom(JAVA_FLOAT, r, g, b, a);
            return (int) function.invokeExact(type, color);
        } catch (Throwable e) { throw failure(e); }
    }
    public static long allocOpaqueExt(long roughnessTex, long metallicTex, float anisotropy,
            float albedoR, float albedoG, float albedoB,
            float opacity, float roughness, float metallic,
            boolean hasThinFilm, float thinFilmThickness,
            boolean alphaIsThinFilm,
            long heightTex, float displaceIn,
            boolean useDrawCallAlpha,
            boolean hasBlendType, int blendType, boolean invertedBlend,
            int alphaTestType, int alphaRefValue, float displaceOut,
            long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("MaterialInfoOpaqueEXT"));
            put(ext, "MaterialInfoOpaqueEXT", "sType", StructType.MATERIAL_INFO_OPAQUE_EXT);
            put(ext, "MaterialInfoOpaqueEXT", "pNext", chainNext);
            put(ext, "MaterialInfoOpaqueEXT", "roughnessTexture", roughnessTex);
            put(ext, "MaterialInfoOpaqueEXT", "metallicTexture", metallicTex);
            put(ext, "MaterialInfoOpaqueEXT", "anisotropy", anisotropy);
            vector3(ext, "MaterialInfoOpaqueEXT", "albedoConstant", albedoR, albedoG, albedoB);
            put(ext, "MaterialInfoOpaqueEXT", "opacityConstant", opacity);
            put(ext, "MaterialInfoOpaqueEXT", "roughnessConstant", roughness);
            put(ext, "MaterialInfoOpaqueEXT", "metallicConstant", metallic);
            put(ext, "MaterialInfoOpaqueEXT", "thinFilmThickness_hasvalue", hasThinFilm ? 1 : 0);
            put(ext, "MaterialInfoOpaqueEXT", "thinFilmThickness_value", thinFilmThickness);
            put(ext, "MaterialInfoOpaqueEXT", "alphaIsThinFilmThickness", alphaIsThinFilm ? 1 : 0);
            put(ext, "MaterialInfoOpaqueEXT", "heightTexture", heightTex);
            put(ext, "MaterialInfoOpaqueEXT", "displaceIn", displaceIn);
            put(ext, "MaterialInfoOpaqueEXT", "useDrawCallAlphaState", useDrawCallAlpha ? 1 : 0);
            put(ext, "MaterialInfoOpaqueEXT", "blendType_hasvalue", hasBlendType ? 1 : 0);
            put(ext, "MaterialInfoOpaqueEXT", "blendType_value", blendType);
            put(ext, "MaterialInfoOpaqueEXT", "invertedBlend", invertedBlend ? 1 : 0);
            put(ext, "MaterialInfoOpaqueEXT", "alphaTestType", alphaTestType);
            put(ext, "MaterialInfoOpaqueEXT", "alphaReferenceValue", alphaRefValue);
            put(ext, "MaterialInfoOpaqueEXT", "displaceOut", displaceOut);
            put(ext, "MaterialInfoOpaqueEXT", "enableDlssControlMask", 1);
            put(ext, "MaterialInfoOpaqueEXT", "dlssControlMaskIntensity", 1.0f);
            put(ext, "MaterialInfoOpaqueEXT", "dlssControlMaskToneStrength", 0.3f);
            put(ext, "MaterialInfoOpaqueEXT", "dlssControlMaskStructuralStrength", 0.7f);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocOpaqueSubsurfaceExt(long transmittanceTex, long thicknessTex,
            long singleScatterAlbedoTex,
            float transR, float transG, float transB,
            float measurementDist,
            float ssaR, float ssaG, float ssaB,
            float volumetricAniso, boolean diffusionProfile,
            float radiusR, float radiusG, float radiusB,
            float radiusScale, float maxSampleRadius,
            long radiusTex,
            long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("MaterialInfoOpaqueSubsurfaceEXT"));
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "sType", StructType.MATERIAL_INFO_OPAQUE_SUBSURFACE_EXT);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "pNext", chainNext);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceTransmittanceTexture", transmittanceTex);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceThicknessTexture", thicknessTex);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceSingleScatteringAlbedoTexture", singleScatterAlbedoTex);
            vector3(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceTransmittanceColor", transR, transG, transB);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceMeasurementDistance", measurementDist);
            vector3(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceSingleScatteringAlbedo", ssaR, ssaG, ssaB);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceVolumetricAnisotropy", volumetricAniso);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceDiffusionProfile", diffusionProfile ? 1 : 0);
            vector3(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceRadius", radiusR, radiusG, radiusB);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceRadiusScale", radiusScale);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceMaxSampleRadius", maxSampleRadius);
            put(ext, "MaterialInfoOpaqueSubsurfaceEXT", "subsurfaceRadiusTexture", radiusTex);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocTranslucentExt(long transmittanceTex, float refractiveIndex,
            float transR, float transG, float transB,
            float measurementDist,
            boolean hasThinWall, float thinWallThickness,
            boolean useDiffuseLayer,
            long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("MaterialInfoTranslucentEXT"));
            put(ext, "MaterialInfoTranslucentEXT", "sType", StructType.MATERIAL_INFO_TRANSLUCENT_EXT);
            put(ext, "MaterialInfoTranslucentEXT", "pNext", chainNext);
            put(ext, "MaterialInfoTranslucentEXT", "transmittanceTexture", transmittanceTex);
            put(ext, "MaterialInfoTranslucentEXT", "refractiveIndex", refractiveIndex);
            vector3(ext, "MaterialInfoTranslucentEXT", "transmittanceColor", transR, transG, transB);
            put(ext, "MaterialInfoTranslucentEXT", "transmittanceMeasurementDistance", measurementDist);
            put(ext, "MaterialInfoTranslucentEXT", "thinWallThickness_hasvalue", hasThinWall ? 1 : 0);
            put(ext, "MaterialInfoTranslucentEXT", "thinWallThickness_value", thinWallThickness);
            put(ext, "MaterialInfoTranslucentEXT", "useDiffuseLayer", useDiffuseLayer ? 1 : 0);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    static long allocWaterSurfaceExt() {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment extension = info(arena, "MaterialInfoWaterSurfaceEXT", StructType.MATERIAL_INFO_WATER_SURFACE_EXT, 0);
            extensions.put(extension.address(), arena);
            return extension.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    static long allocEndPortalExt(int layers, long next) {
        Arena arena = Arena.ofShared();
        try {
            String type = "MaterialInfoEndPortalEXT";
            MemorySegment ext = info(arena, type, StructType.MATERIAL_INFO_END_PORTAL_EXT, next);
            put(ext, type, "starLayers", layers);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    static long allocGlintExt(long texture, int mode, long next) {
        Arena arena = Arena.ofShared();
        try {
            String type = "MaterialInfoGlintEXT";
            MemorySegment ext = info(arena, type, StructType.MATERIAL_INFO_GLINT_EXT, next);
            put(ext, type, "texture", texture);
            put(ext, type, "mode", mode);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    /** Caller owns the extension until freeExt; the next pointer remains caller-owned. */
    public static long allocVertexColorMaskExt(long texture, long chainNext) {
        if (texture == 0) throw new IllegalArgumentException("Missing vertex color mask texture");
        Arena arena = Arena.ofShared();
        try {
            String type = "MaterialInfoVertexColorMaskEXT";
            MemorySegment ext = info(arena, type, StructType.MATERIAL_INFO_VERTEX_COLOR_MASK_EXT, chainNext);
            put(ext, type, "texture", texture);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) { arena.close(); throw failure(error); }
    }

    /** Caller owns this extension until freeExt; the next pointer remains caller-owned. */
    public static long allocVoxelCoverageExt(int level, long chainNext) {
        return allocVoxelCoverageTreeExt(level, 0, chainNext);
    }

    /** Caller owns the extension until freeExt; nodeIndex zero disables sparse-tree clipping. */
    public static long allocVoxelCoverageTreeExt(int level, int nodeIndex, long chainNext) {
        if (level < 0 || level > 255 || nodeIndex < 0 || nodeIndex >= (1 << 24))
            throw new IllegalArgumentException("Invalid voxel coverage instance");
        Arena arena = Arena.ofShared();
        try {
            String type = "InstanceInfoVoxelCoverageEXT";
            MemorySegment ext = info(arena, type, StructType.INSTANCE_INFO_VOXEL_COVERAGE_EXT, chainNext);
            put(ext, type, "level", level); put(ext, type, "nodeIndex", nodeIndex);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) { arena.close(); throw failure(error); }
    }

    public static long allocBlendExt(boolean alphaTestEnabled, int alphaTestRef, int alphaTestCompareOp,
            boolean alphaBlendEnabled,
            int srcColorBlend, int dstColorBlend, int colorBlendOp,
            int texColorArg1, int texColorArg2, int texColorOp,
            int texAlphaArg1, int texAlphaArg2, int texAlphaOp,
            int tFactor, boolean isTexFactorBlend,
            int srcAlphaBlend, int dstAlphaBlend, int alphaBlendOp,
            int writeMask, boolean isVertexColorBakedLighting,
            long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("InstanceInfoBlendEXT"));
            put(ext, "InstanceInfoBlendEXT", "sType", StructType.INSTANCE_INFO_BLEND_EXT);
            put(ext, "InstanceInfoBlendEXT", "pNext", chainNext);
            put(ext, "InstanceInfoBlendEXT", "alphaTestEnabled", alphaTestEnabled ? 1 : 0);
            put(ext, "InstanceInfoBlendEXT", "alphaTestReferenceValue", alphaTestRef);
            put(ext, "InstanceInfoBlendEXT", "alphaTestCompareOp", alphaTestCompareOp);
            put(ext, "InstanceInfoBlendEXT", "alphaBlendEnabled", alphaBlendEnabled ? 1 : 0);
            put(ext, "InstanceInfoBlendEXT", "srcColorBlendFactor", srcColorBlend);
            put(ext, "InstanceInfoBlendEXT", "dstColorBlendFactor", dstColorBlend);
            put(ext, "InstanceInfoBlendEXT", "colorBlendOp", colorBlendOp);
            put(ext, "InstanceInfoBlendEXT", "textureColorArg1Source", texColorArg1);
            put(ext, "InstanceInfoBlendEXT", "textureColorArg2Source", texColorArg2);
            put(ext, "InstanceInfoBlendEXT", "textureColorOperation", texColorOp);
            put(ext, "InstanceInfoBlendEXT", "textureAlphaArg1Source", texAlphaArg1);
            put(ext, "InstanceInfoBlendEXT", "textureAlphaArg2Source", texAlphaArg2);
            put(ext, "InstanceInfoBlendEXT", "textureAlphaOperation", texAlphaOp);
            put(ext, "InstanceInfoBlendEXT", "tFactor", tFactor);
            put(ext, "InstanceInfoBlendEXT", "isTextureFactorBlend", isTexFactorBlend ? 1 : 0);
            put(ext, "InstanceInfoBlendEXT", "srcAlphaBlendFactor", srcAlphaBlend);
            put(ext, "InstanceInfoBlendEXT", "dstAlphaBlendFactor", dstAlphaBlend);
            put(ext, "InstanceInfoBlendEXT", "alphaBlendOp", alphaBlendOp);
            put(ext, "InstanceInfoBlendEXT", "writeMask", writeMask);
            put(ext, "InstanceInfoBlendEXT", "isVertexColorBakedLighting", isVertexColorBakedLighting ? 1 : 0);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocBoneTransformsExt(ByteBuffer transforms,
                                                     int count,
                                                     long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("InstanceInfoBoneTransformsEXT"));
            put(ext, "InstanceInfoBoneTransformsEXT", "sType", StructType.INSTANCE_INFO_BONE_TRANSFORMS_EXT);
            put(ext, "InstanceInfoBoneTransformsEXT", "pNext", chainNext);
            put(ext, "InstanceInfoBoneTransformsEXT", "boneTransforms_count", count);
            long bytes = Math.multiplyExact((long) count, 48L);
            MemorySegment copy = arena.allocate(bytes, 4);
            MemorySegment.copy(direct(transforms, bytes), 0, copy, 0, bytes);
            Reference.reachabilityFence(transforms);
            put(ext, "InstanceInfoBoneTransformsEXT", "boneTransforms_values", copy.address());
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocGpuInstancingExt(ByteBuffer transforms,
                                                    int count,
                                                    long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("InstanceInfoGpuInstancingEXT"));
            put(ext, "InstanceInfoGpuInstancingEXT", "sType", StructType.INSTANCE_INFO_GPU_INSTANCING_EXT);
            put(ext, "InstanceInfoGpuInstancingEXT", "pNext", chainNext);
            put(ext, "InstanceInfoGpuInstancingEXT", "instanceTransforms_count", count);
            long bytes = Math.multiplyExact((long) count, 48L);
            MemorySegment copy = arena.allocate(bytes, 4);
            MemorySegment.copy(direct(transforms, bytes), 0, copy, 0, bytes);
            Reference.reachabilityFence(transforms);
            put(ext, "InstanceInfoGpuInstancingEXT", "instanceTransforms_values", copy.address());
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocSphereExt(float px, float py, float pz, float radius,
            boolean hasShaping,
            float shapeDirX, float shapeDirY, float shapeDirZ,
            float coneAngle, float coneSoftness, float focusExponent,
            float volumetricRadianceScale,
            long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("LightInfoSphereEXT"));
            put(ext, "LightInfoSphereEXT", "sType", StructType.LIGHT_INFO_SPHERE_EXT);
            put(ext, "LightInfoSphereEXT", "pNext", chainNext);
            vector3(ext, "LightInfoSphereEXT", "position", px, py, pz);
            put(ext, "LightInfoSphereEXT", "radius", radius);
            put(ext, "LightInfoSphereEXT", "shaping_hasvalue", hasShaping ? 1 : 0);
            vector3(ext, "LightInfoSphereEXT", "shaping_value.direction", shapeDirX, shapeDirY, shapeDirZ);
            put(ext, "LightInfoSphereEXT", "shaping_value.coneAngleDegrees", coneAngle);
            put(ext, "LightInfoSphereEXT", "shaping_value.coneSoftness", coneSoftness);
            put(ext, "LightInfoSphereEXT", "shaping_value.focusExponent", focusExponent);
            put(ext, "LightInfoSphereEXT", "volumetricRadianceScale", volumetricRadianceScale);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocDistantExt(float dirX, float dirY, float dirZ,
            float angularDiameter, float volumetricRadianceScale,
            long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("LightInfoDistantEXT"));
            put(ext, "LightInfoDistantEXT", "sType", StructType.LIGHT_INFO_DISTANT_EXT);
            put(ext, "LightInfoDistantEXT", "pNext", chainNext);
            vector3(ext, "LightInfoDistantEXT", "direction", dirX, dirY, dirZ);
            put(ext, "LightInfoDistantEXT", "angularDiameterDegrees", angularDiameter);
            put(ext, "LightInfoDistantEXT", "volumetricRadianceScale", volumetricRadianceScale);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocRectExt(float px, float py, float pz,
            float xAxisX, float xAxisY, float xAxisZ, float xSize,
            float yAxisX, float yAxisY, float yAxisZ, float ySize,
            float dirX, float dirY, float dirZ,
            boolean hasShaping,
            float shapeDirX, float shapeDirY, float shapeDirZ,
            float coneAngle, float coneSoftness, float focusExponent,
            float volumetricRadianceScale,
            long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("LightInfoRectEXT"));
            put(ext, "LightInfoRectEXT", "sType", StructType.LIGHT_INFO_RECT_EXT);
            put(ext, "LightInfoRectEXT", "pNext", chainNext);
            vector3(ext, "LightInfoRectEXT", "position", px, py, pz);
            vector3(ext, "LightInfoRectEXT", "xAxis", xAxisX, xAxisY, xAxisZ);
            put(ext, "LightInfoRectEXT", "xSize", xSize);
            vector3(ext, "LightInfoRectEXT", "yAxis", yAxisX, yAxisY, yAxisZ);
            put(ext, "LightInfoRectEXT", "ySize", ySize);
            vector3(ext, "LightInfoRectEXT", "direction", dirX, dirY, dirZ);
            put(ext, "LightInfoRectEXT", "shaping_hasvalue", hasShaping ? 1 : 0);
            vector3(ext, "LightInfoRectEXT", "shaping_value.direction", shapeDirX, shapeDirY, shapeDirZ);
            put(ext, "LightInfoRectEXT", "shaping_value.coneAngleDegrees", coneAngle);
            put(ext, "LightInfoRectEXT", "shaping_value.coneSoftness", coneSoftness);
            put(ext, "LightInfoRectEXT", "shaping_value.focusExponent", focusExponent);
            put(ext, "LightInfoRectEXT", "volumetricRadianceScale", volumetricRadianceScale);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocDiskExt(float px, float py, float pz,
            float xAxisX, float xAxisY, float xAxisZ, float xRadius,
            float yAxisX, float yAxisY, float yAxisZ, float yRadius,
            float dirX, float dirY, float dirZ,
            boolean hasShaping,
            float shapeDirX, float shapeDirY, float shapeDirZ,
            float coneAngle, float coneSoftness, float focusExponent,
            float volumetricRadianceScale,
            long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("LightInfoDiskEXT"));
            put(ext, "LightInfoDiskEXT", "sType", StructType.LIGHT_INFO_DISK_EXT);
            put(ext, "LightInfoDiskEXT", "pNext", chainNext);
            vector3(ext, "LightInfoDiskEXT", "position", px, py, pz);
            vector3(ext, "LightInfoDiskEXT", "xAxis", xAxisX, xAxisY, xAxisZ);
            put(ext, "LightInfoDiskEXT", "xRadius", xRadius);
            vector3(ext, "LightInfoDiskEXT", "yAxis", yAxisX, yAxisY, yAxisZ);
            put(ext, "LightInfoDiskEXT", "yRadius", yRadius);
            vector3(ext, "LightInfoDiskEXT", "direction", dirX, dirY, dirZ);
            put(ext, "LightInfoDiskEXT", "shaping_hasvalue", hasShaping ? 1 : 0);
            vector3(ext, "LightInfoDiskEXT", "shaping_value.direction", shapeDirX, shapeDirY, shapeDirZ);
            put(ext, "LightInfoDiskEXT", "shaping_value.coneAngleDegrees", coneAngle);
            put(ext, "LightInfoDiskEXT", "shaping_value.coneSoftness", coneSoftness);
            put(ext, "LightInfoDiskEXT", "shaping_value.focusExponent", focusExponent);
            put(ext, "LightInfoDiskEXT", "volumetricRadianceScale", volumetricRadianceScale);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocCylinderExt(float px, float py, float pz,
            float radius,
            float axisX, float axisY, float axisZ,
            float axisLength,
            float volumetricRadianceScale,
            long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("LightInfoCylinderEXT"));
            put(ext, "LightInfoCylinderEXT", "sType", StructType.LIGHT_INFO_CYLINDER_EXT);
            put(ext, "LightInfoCylinderEXT", "pNext", chainNext);
            vector3(ext, "LightInfoCylinderEXT", "position", px, py, pz);
            put(ext, "LightInfoCylinderEXT", "radius", radius);
            vector3(ext, "LightInfoCylinderEXT", "axis", axisX, axisY, axisZ);
            put(ext, "LightInfoCylinderEXT", "axisLength", axisLength);
            put(ext, "LightInfoCylinderEXT", "volumetricRadianceScale", volumetricRadianceScale);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocDomeExt(float[] transform12,
                                           long colorTexture,
                                           long transmittanceTexture,
                                           float opacity,
                                           int blendType,
                                           int projection,
                                           boolean backgroundOnly,
                                           long chainNext) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = arena.allocate(RemixLayouts.layout("LightInfoDomeEXT"));
            put(ext, "LightInfoDomeEXT", "sType", StructType.LIGHT_INFO_DOME_EXT);
            if (projection != 0 || backgroundOnly) {
                MemorySegment mapping = info(arena, "LightInfoDomeProjectionEXT",
                        StructType.LIGHT_INFO_DOME_PROJECTION_EXT, chainNext);
                put(mapping, "LightInfoDomeProjectionEXT", "projection", projection);
                put(mapping, "LightInfoDomeProjectionEXT", "backgroundOnly", backgroundOnly ? 1 : 0);
                chainNext = mapping.address();
            }
            if (transmittanceTexture != 0) {
                MemorySegment transmission = info(arena, "LightInfoDomeTransmittanceEXT",
                        StructType.LIGHT_INFO_DOME_TRANSMITTANCE_EXT, chainNext);
                put(transmission, "LightInfoDomeTransmittanceEXT", "transmittanceTexture", transmittanceTexture);
                chainNext = transmission.address();
            }
            put(ext, "LightInfoDomeEXT", "pNext", chainNext);
            put(ext, "LightInfoDomeEXT", "colorTexture", colorTexture);
            put(ext, "LightInfoDomeEXT", "opacity", opacity);
            put(ext, "LightInfoDomeEXT", "blendType", blendType);
            floats(ext, "LightInfoDomeEXT", "transform", transform12);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    public static long allocCameraParamExt(float posX, float posY, float posZ,
            float fwdX, float fwdY, float fwdZ,
            float upX, float upY, float upZ,
            float rightX, float rightY, float rightZ,
            float fovY, float aspect, float nearPlane, float farPlane,
            long chainNext) {
        return allocParameterizedCamera(posX, posY, posZ, fwdX, fwdY, fwdZ, upX, upY, upZ,
                rightX, rightY, rightZ, fovY, aspect, nearPlane, farPlane, chainNext, null);
    }

    static long allocParameterizedCamera(float posX, float posY, float posZ,
            float fwdX, float fwdY, float fwdZ,
            float upX, float upY, float upZ,
            float rightX, float rightY, float rightZ,
            float fovY, float aspect, float nearPlane, float farPlane,
            long chainNext, CameraInfo.SceneOrigin origin) {
        Arena arena = Arena.ofShared();
        try {
            if (origin != null) chainNext = writeCameraSceneOrigin(arena, origin, chainNext).address();
            MemorySegment ext = arena.allocate(RemixLayouts.layout("CameraInfoParameterizedEXT"));
            put(ext, "CameraInfoParameterizedEXT", "sType", StructType.CAMERA_INFO_PARAMETERIZED_EXT);
            put(ext, "CameraInfoParameterizedEXT", "pNext", chainNext);
            vector3(ext, "CameraInfoParameterizedEXT", "position", posX, posY, posZ);
            vector3(ext, "CameraInfoParameterizedEXT", "forward", fwdX, fwdY, fwdZ);
            vector3(ext, "CameraInfoParameterizedEXT", "up", upX, upY, upZ);
            vector3(ext, "CameraInfoParameterizedEXT", "right", rightX, rightY, rightZ);
            put(ext, "CameraInfoParameterizedEXT", "fovYInDegrees", fovY);
            put(ext, "CameraInfoParameterizedEXT", "aspect", aspect);
            put(ext, "CameraInfoParameterizedEXT", "nearPlane", nearPlane);
            put(ext, "CameraInfoParameterizedEXT", "farPlane", farPlane);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }
    private static MemorySegment writeCameraSceneOrigin(Arena arena, CameraInfo.SceneOrigin origin, long next) {
        String type = "CameraInfoSceneOriginEXT";
        MemorySegment ext = arena.allocate(RemixLayouts.layout(type));
        put(ext, type, "sType", StructType.CAMERA_INFO_SCENE_ORIGIN_EXT);
        put(ext, type, "pNext", next);
        put(ext, type, "x", origin.x());
        put(ext, type, "z", origin.z());
        return ext;
    }

    static long allocCameraSceneOrigin(CameraInfo.SceneOrigin origin) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment ext = writeCameraSceneOrigin(arena, origin, 0);
            extensions.put(ext.address(), arena);
            return ext.address();
        } catch (Throwable error) {
            arena.close();
            throw failure(error);
        }
    }

    /** Releases one extension and its owned transform copy; chain nodes retain independent ownership. */
    public static void freeExt(long extPtr) {
        if (extPtr == 0) return;
        Arena arena = extensions.remove(extPtr);
        if (arena == null) throw new IllegalArgumentException("Unknown or released extension: " + extPtr);
        arena.close();
    }

    static void freeExtChain(long extPtr) {
        while (extPtr != 0) {
            long next = pointer(extPtr).reinterpret(16).get(ADDRESS, 8).address();
            freeExt(extPtr);
            extPtr = next;
        }
    }

    /**
     * Requests asynchronous object picking. The native callback and user-data segments must stay
     * alive until the callback completes; use a shared arena because the callback can run on any thread.
     */
    public static int pickRequestObjectPicking(MemorySegment pixelRegion, MemorySegment callback, MemorySegment userData) {
        MethodHandle function = functions.get("pick_RequestObjectPicking");
        if (function == null) return ErrorCode.NOT_INITIALIZED;
        if (!pixelRegion.isNative() || pixelRegion.byteSize() < RemixLayouts.size("Rect2D") || !callback.isNative() || !userData.isNative())
            return ErrorCode.INVALID_ARGUMENTS;
        try { return (int) function.invokeExact(pixelRegion, callback, userData); }
        catch (Throwable e) { throw failure(e); }
    }
    public static int pickHighlightObjects(int[] values, byte red, byte green, byte blue) {
        MethodHandle function = functions.get("pick_HighlightObjects");
        if (function == null) return ErrorCode.NOT_INITIALIZED;
        try (Scratch arena = scratch()) {
            MemorySegment data = arena.allocateFrom(JAVA_INT, values);
            return (int) function.invokeExact(data, values.length, red, green, blue);
        } catch (Throwable e) { throw failure(e); }
    }

    private static final class Windows {
        static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32.dll", PROCESS);
        static final MethodHandle LOAD_LIBRARY = bind(KERNEL, "LoadLibraryExW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
        static final SymbolLookup NT = SymbolLookup.libraryLookup("ntdll.dll", PROCESS);
        static final MethodHandle GET_PROCESS = bind(KERNEL, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
        static final MethodHandle QUERY_PROCESS = bind(NT, "NtQueryInformationProcess", descriptor(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        static final MethodHandle COMMAND_W = bind(KERNEL, "GetCommandLineW", FunctionDescriptor.of(ADDRESS));
        static final MethodHandle COMMAND_A = bind(KERNEL, "GetCommandLineA", FunctionDescriptor.of(ADDRESS));
        static final MethodHandle TO_ANSI = bind(KERNEL, "WideCharToMultiByte", descriptor(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static MemorySegment commandLine() {
            try (Scratch arena = scratch()) {
                MemorySegment basic = arena.allocate(48, 8);
                MemorySegment process = (MemorySegment) GET_PROCESS.invokeExact();
                int result = (int) QUERY_PROCESS.invokeExact(process, 0, basic, 48, MemorySegment.NULL);
                if (result != 0) throw new IllegalStateException("NtQueryInformationProcess failed: " + result);
                MemorySegment peb = basic.get(ADDRESS, 8).reinterpret(0x28);
                MemorySegment parameters = peb.get(ADDRESS, 0x20).reinterpret(0x80);
                return parameters.asSlice(0x70, 16);
            } catch (Throwable e) { throw failure(e); }
        }
    }
    public static String processCommandLine() {
        MemorySegment command = Windows.commandLine();
        int bytes = Short.toUnsignedInt(command.get(JAVA_SHORT, 0));
        return new String(command.get(ADDRESS, 8).reinterpret(bytes).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
    }
    public static int overwriteProcessCommandLine(String replacement) {
        MemorySegment command = Windows.commandLine();
        int bytes = Short.toUnsignedInt(command.get(JAVA_SHORT, 0));
        if (replacement == null || replacement.length() * 2 != bytes) return ErrorCode.INVALID_ARGUMENTS;
        try (Scratch arena = scratch()) {
            MemorySegment replacementWide = utf16(arena, replacement);
            MemorySegment ansi = ((MemorySegment) Windows.COMMAND_A.invokeExact()).reinterpret(Integer.MAX_VALUE);
            long originalAnsiLength = 0;
            while (ansi.get(JAVA_BYTE, originalAnsiLength) != 0) originalAnsiLength++;
            int ansiLength = (int) Windows.TO_ANSI.invokeExact(0, 0, replacementWide, replacement.length(), MemorySegment.NULL, 0, MemorySegment.NULL, MemorySegment.NULL);
            if (ansiLength <= 0 || ansiLength > originalAnsiLength) return ErrorCode.INVALID_ARGUMENTS;
            MemorySegment pebBuffer = command.get(ADDRESS, 8).reinterpret(bytes);
            MemorySegment wide = ((MemorySegment) Windows.COMMAND_W.invokeExact()).reinterpret(bytes + 2L);
            boolean separateCopy = wide.address() != pebBuffer.address() && wide.asSlice(0, bytes).mismatch(pebBuffer) == -1 && wide.get(JAVA_SHORT, bytes) == 0;
            MemorySegment.copy(replacementWide, 0, pebBuffer, 0, bytes);
            if (separateCopy) MemorySegment.copy(replacementWide, 0, wide, 0, bytes);
            int written = (int) Windows.TO_ANSI.invokeExact(0, 0, replacementWide, replacement.length(), ansi, ansiLength, MemorySegment.NULL, MemorySegment.NULL);
            if (written != ansiLength) throw new IllegalStateException("WideCharToMultiByte changed size");
            ansi.asSlice(ansiLength, originalAnsiLength - ansiLength + 1).fill((byte) 0);
            return ErrorCode.SUCCESS;
        } catch (Throwable e) { throw failure(e); }
    }
    private static MethodHandle com(long object, int slot, MemoryLayout... arguments) {
        if (object == 0) throw new IllegalArgumentException("Null COM object");
        MemorySegment vtable = pointer(object).reinterpret(8).get(ADDRESS, 0).reinterpret((slot + 1L) * 8);
        return LINKER.downcallHandle(vtable.get(ADDRESS, slot * 8L), descriptor(arguments));
    }
    private static MemorySegment presentationParameters(SegmentAllocator arena, int width, int height, long hwnd, boolean reset) {
        MemorySegment pp = arena.allocate(64, 8);
        pp.set(JAVA_INT, 0, width); pp.set(JAVA_INT, 4, height);
        pp.set(JAVA_INT, 8, reset ? 22 : 0); pp.set(JAVA_INT, 12, reset ? 1 : 0);
        pp.set(JAVA_INT, 24, 1); pp.set(ADDRESS, 32, pointer(hwnd)); pp.set(JAVA_INT, 40, 1);
        return pp;
    }
    public static long d3d9CreateDevice(long pD3D9Ex, long hwnd, int width, int height) {
        try (Scratch arena = scratch()) {
            MemorySegment out = arena.allocate(ADDRESS);
            MemorySegment pp = presentationParameters(arena, width, height, hwnd, false);
            int result = (int) com(pD3D9Ex, 20, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS)
                    .invokeExact(pointer(pD3D9Ex), 0, 1, MemorySegment.NULL, 0x824, pp, MemorySegment.NULL, out);
            return result >= 0 ? out.get(ADDRESS, 0).address() : 0;
        } catch (Throwable e) { throw failure(e); }
    }
    public static long d3d9CreateRenderTarget(long pDevice, int width, int height, int format) {
        try (Scratch arena = scratch()) {
            MemorySegment out = arena.allocate(ADDRESS);
            int result = (int) com(pDevice, 28, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)
                    .invokeExact(pointer(pDevice), width, height, format, 0, 0, 0, out, MemorySegment.NULL);
            return result >= 0 ? out.get(ADDRESS, 0).address() : 0;
        } catch (Throwable e) { throw failure(e); }
    }
    public static int d3d9Reset(long pDevice, int width, int height) {
        try (Scratch arena = scratch()) {
            MemorySegment pp = presentationParameters(arena, width, height, 0, true);
            return (int) com(pDevice, 16, ADDRESS, ADDRESS).invokeExact(pointer(pDevice), pp);
        } catch (Throwable e) { throw failure(e); }
    }
    public static int d3d9Present(long pDevice) {
        try { return (int) com(pDevice, 17, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
                .invokeExact(pointer(pDevice), MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL); }
        catch (Throwable e) { throw failure(e); }
    }
    public static int d3d9Release(long comPtr) {
        if (comPtr == 0) return 0;
        try { return (int) com(comPtr, 2, ADDRESS).invokeExact(pointer(comPtr)); }
        catch (Throwable e) { throw failure(e); }
    }
    private static final MemoryLayout TRACY_CONTEXT = MemoryLayout.structLayout(JAVA_INT.withName("id"), JAVA_INT.withName("active"));
    private static final FunctionDescriptor TRACY_DESCRIPTOR_0 = FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final FunctionDescriptor TRACY_DESCRIPTOR_1 = FunctionDescriptor.of(TRACY_CONTEXT, JAVA_LONG, JAVA_INT);
    private static final FunctionDescriptor TRACY_DESCRIPTOR_2 = FunctionDescriptor.ofVoid(TRACY_CONTEXT);
    private static final FunctionDescriptor TRACY_DESCRIPTOR_3 = FunctionDescriptor.ofVoid(ADDRESS);
    private static final FunctionDescriptor TRACY_DESCRIPTOR_4 = FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE);
    private static final FunctionDescriptor TRACY_DESCRIPTOR_5 = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT);
    private static MethodHandle tracy(String name, FunctionDescriptor descriptor) {
        if (runtime == null) return null;
        java.util.Optional<MethodHandle> cached = tracyFunctions.get(name);
        if (cached != null) return cached.orElse(null);
        cached = runtime.find(name).map(address -> LINKER.downcallHandle(address, descriptor));
        tracyFunctions.put(name, cached);
        return cached.orElse(null);
    }
    public static synchronized long tracyZoneBegin(String name) {
        MethodHandle allocate = tracy("___tracy_alloc_srcloc_name", TRACY_DESCRIPTOR_0);
        MethodHandle begin = tracy("___tracy_emit_zone_begin_alloc", TRACY_DESCRIPTOR_1);
        if (allocate == null || begin == null) return 0;
        try (Scratch arena = scratch()) {
            MemorySegment text = stableName(name), source = stableName("Java");
            long size = text.byteSize() - 1;
            long location = (long) allocate.invokeExact(0, source, 4L, text, size, text, size);
            MemorySegment result = (MemorySegment) begin.invokeExact((SegmentAllocator) arena, location, 1);
            return Integer.toUnsignedLong(result.get(JAVA_INT, 0)) | ((long) result.get(JAVA_INT, 4) << 32);
        } catch (Throwable e) { throw failure(e); }
    }
    public static synchronized void tracyZoneEnd(long ctx) {
        MethodHandle end = tracy("___tracy_emit_zone_end", TRACY_DESCRIPTOR_2);
        if (ctx == 0 || end == null) return;
        try (Scratch arena = scratch()) { end.invokeExact(arena.allocateFrom(JAVA_LONG, ctx)); }
        catch (Throwable e) { throw failure(e); }
    }
    private static MemorySegment stableName(String name) {
        MemorySegment value = stableNames.get(name);
        return value != null ? value : stableNames.computeIfAbsent(name, PROCESS::allocateFrom);
    }
    public static synchronized void tracySetThreadName(String name) {
        MethodHandle function = tracy("___tracy_set_thread_name", TRACY_DESCRIPTOR_3);
        if (function == null) return;
        try { function.invokeExact(stableName(name)); } catch (Throwable e) { throw failure(e); }
    }
    public static synchronized void tracyPlot(String name, double value) {
        MethodHandle function = tracy("___tracy_emit_plot", TRACY_DESCRIPTOR_4);
        if (function == null) return;
        try { function.invokeExact(stableName(name), value); } catch (Throwable e) { throw failure(e); }
    }
    public static synchronized void tracyMessage(String message) {
        MethodHandle function = tracy("___tracy_emit_message", TRACY_DESCRIPTOR_5);
        if (function == null) return;
        try (Scratch arena = scratch()) {
            MemorySegment text = arena.allocateFrom(message);
            function.invokeExact(text, text.byteSize() - 1, 0);
        } catch (Throwable e) { throw failure(e); }
    }
    public static synchronized void tracyFrameMark(String name) {
        MethodHandle function = tracy("___tracy_emit_frame_mark", TRACY_DESCRIPTOR_3);
        if (function == null) return;
        try {
            MemorySegment frameName = name == null ? MemorySegment.NULL : stableName(name);
            function.invokeExact(frameName);
        }
        catch (Throwable e) { throw failure(e); }
    }
}
