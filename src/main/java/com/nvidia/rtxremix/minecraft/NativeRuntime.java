/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.HostVulkanDeviceInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.StartupInfo;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLProperties;
import net.fabricmc.loader.api.FabricLoader;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.util.List;

/** Render-thread ownership of the runtime across Vulkan device creation and renderer startup. */
public final class NativeRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeRuntime.class);
    private static final List<String> ACCOUNT_OPTIONS =
            List.of("--username", "--accessToken", "--uuid", "--xuid", "--clientId");
    private static RemixAPI api;
    private static long instance;
    private static int instanceApiVersion;
    private static String[] instanceExtensions;
    private static long sharedDevice;
    private static boolean adopted;
    private static boolean started;
    private static long windowHandle;
    private static long sdlWindow;

    private NativeRuntime() {}

    public static void prepareVulkan() {
        if (api != null) throw new IllegalStateException("The Remix Vulkan runtime is already prepared");
        var dll = RemixRenderer.runtimeDll(FabricLoader.getInstance().getGameDir());
        if (!Files.isRegularFile(dll)) throw new IllegalStateException("Missing source-built Remix runtime: " + dll);
        String path = dll.toAbsolutePath().toString();
        int blanked = RemixAPI.redactCommandLineOptions(path, ACCOUNT_OPTIONS);
        LOGGER.info("Blanked {} account values before preparing Vulkan", blanked);
        RemixAPI loaded = RemixAPI.load(path);
        try {
            String extra = System.getenv("RTXREMIX_EXTRA_RTXCONF");
            if (extra != null && !extra.isBlank()) {
                for (String entry : extra.split(";")) {
                    int separator = entry.indexOf('=');
                    if (separator <= 0) throw new IllegalArgumentException("Invalid runtime setting: " + entry);
                    loaded.setConfigVariable(entry.substring(0, separator).trim(), entry.substring(separator + 1).trim());
                }
            }
            loaded.prepareVulkan();
            api = loaded;
        } catch (RuntimeException | Error failure) {
            loaded.shutdown();
            throw failure;
        }
    }

    /** Starts presentation on the shared device before Minecraft constructs its game renderer. */
    public static RemixAPI startup(long window) {
        if (started) {
            if (sdlWindow != window) throw new IllegalStateException("Remix already owns another window's presentation");
            return api();
        }
        if (!adopted) throw new IllegalStateException("Vulkan device adoption must precede presentation startup");
        long hwnd = SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(window),
                SDLVideo.SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
        if (hwnd == 0) throw new IllegalStateException("Missing Win32 window for Vulkan presentation");
        String windowPos = System.getenv("RTXREMIX_WINDOW_POS");
        if (windowPos != null && !windowPos.isBlank()) {
            try {
                String[] parts = windowPos.split(",");
                if (parts.length != 2) throw new IllegalArgumentException("Expected x,y");
                SDLVideo.SDL_SetWindowPosition(window, Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            } catch (IllegalArgumentException invalid) {
                LOGGER.warn("Ignoring invalid RTXREMIX_WINDOW_POS: {}", windowPos);
            }
        }
        api().startup(StartupInfo.builder().hwnd(hwnd).interopImage(true).build());
        windowHandle = hwnd;
        sdlWindow = window;
        started = true;
        LOGGER.info("Started native Vulkan presentation for the shared device");
        return api();
    }

    public static boolean isStarted() { return started; }
    public static long windowHandle() { return windowHandle; }

    public static void recordInstance(long handle, VkInstanceCreateInfo createInfo) {
        if (instance != 0) throw new IllegalStateException("More than one host Vulkan instance");
        instance = handle;
        instanceApiVersion = createInfo.pApplicationInfo().apiVersion();
        PointerBuffer names = createInfo.ppEnabledExtensionNames();
        instanceExtensions = new String[createInfo.enabledExtensionCount()];
        for (int i = 0; i < instanceExtensions.length; i++) instanceExtensions[i] = names.getStringUTF8(i);
    }

    public static int createVulkanDevice(VkPhysicalDevice physical, VkDeviceCreateInfo createInfo,
                                         VkAllocationCallbacks allocator, PointerBuffer output, int graphicsFamily) {
        if (allocator != null) throw new IllegalArgumentException("Shared Vulkan devices require the default allocator");
        if (physical.getInstance().address() != instance || sharedDevice != 0) {
            throw new IllegalStateException("Invalid shared Vulkan device creation sequence");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer names = stack.mallocPointer(instanceExtensions.length);
            for (String name : instanceExtensions) names.put(MemoryUtil.memAddress(stack.UTF8(name)));
            names.flip();
            HostVulkanDeviceInfo info = new HostVulkanDeviceInfo();
            info.instance = instance;
            info.physicalDevice = physical.address();
            info.instanceApiVersion = instanceApiVersion;
            info.instanceExtensionCount = names.remaining();
            info.instanceExtensionsAddress = names.address();
            info.graphicsQueueFamily = graphicsFamily;
            info.deviceCreateInfoAddress = createInfo.address();
            api().createVulkanDevice(info);
            sharedDevice = info.device;
            createInfo.ppEnabledExtensionNames(PointerBuffer.create(info.enabledExtensionsAddress, info.enabledExtensionCount));
            output.put(0, sharedDevice);
            LOGGER.info("Created the shared Vulkan device 0x{} with {} enabled extensions", Long.toHexString(sharedDevice), info.enabledExtensionCount);
            return org.lwjgl.vulkan.VK10.VK_SUCCESS;
        }
    }

    public static void adoptVulkanDevice(VkDevice device) {
        if (device.address() != sharedDevice || adopted) throw new IllegalStateException("Invalid shared Vulkan device adoption");
        api().adoptVulkanDevice(sharedDevice);
        adopted = true;
    }

    public static RemixAPI api() {
        if (api == null) throw new IllegalStateException("The shared Vulkan runtime has not been prepared");
        return api;
    }

    static void shutdown(RemixAPI closing) {
        try {
            closing.shutdown();
        } finally {
            if (api == closing) {
                api = null;
                started = false;
                windowHandle = sdlWindow = 0;
            }
        }
    }

    public static void beforeDeviceClose(long device) {
        if (device != sharedDevice || api == null) return;
        try {
            RemixRenderer renderer = RtxRemixMod.renderer;
            if (renderer != null && renderer.getRemixApi() != null) RtxRemixMod.shutdownForClientStop();
        } finally {
            if (api != null) shutdown(api);
        }
    }

    public static void beforeDeviceDestroy(long device) {
        beforeDeviceClose(device);
        if (device == sharedDevice) {
            sharedDevice = 0;
            adopted = false;
        }
    }

    public static void beforeInstanceDestroy(long handle) {
        if (handle != instance) return;
        if (adopted && sharedDevice != 0) throw new IllegalStateException("The host Vulkan device must be destroyed before its instance");
        // An unadopted device is still runtime-owned when host dispatch initialization fails.
        if (api != null) shutdown(api);
        instance = sharedDevice = 0;
        instanceExtensions = null;
    }
}
