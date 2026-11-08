/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.sdk.FrameGenerationCapabilities;
import org.edtp.kavora.sdk.VulkanInteropInfo;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.vulkan.NativeVulkanSurface;
import org.edtp.kavora.settings.FrameGenerationMode;
import org.edtp.kavora.settings.KavoraSodiumConfig;
import java.util.LinkedHashSet;
import net.caffeinemc.mods.sodium.client.config.ConfigManager;
import net.caffeinemc.mods.sodium.client.config.structure.EnumOption;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Verifies successful native generated presents, including live multiplier and mode changes. */
final class KavoraFrameGenerationChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraFrameGenerationChecks.class);

    static void run(ClientGameTestContext context) {
        var capabilities = new FrameGenerationCapabilities();
        context.runOnClient(client -> KavoraMod.renderer.getRemixApi().getFrameGenerationCapabilities(capabilities));
        KavoraWorldGameTest.require(capabilities.initializationComplete && capabilities.supported,
                "Frame generation unavailable: " + capabilities.notSupportedReason);
        LOGGER.info("DLSS FG SDK limit: {} generated frames", capabilities.maxGeneratedFrames);
        if (Boolean.getBoolean("kavora.gametest.nativeOverlay")) verifyNativeOverlay(context);
        String[] previous = context.computeOnClient(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            return new String[] { api.getConfigVariableResolved("rtx.dlfg.enable"),
                    api.getConfigVariableResolved("rtx.dlfg.maxInterpolatedFrames") };
        });
        try {
            if (Boolean.getBoolean("kavora.gametest.fullscreenOnly")) {
                verifyFullscreen(context, Math.min(2, capabilities.maxGeneratedFrames));
                return;
            }
            var counts = new LinkedHashSet<Integer>();
            counts.add(1);
            counts.add(Math.min(2, capabilities.maxGeneratedFrames));
            counts.add(capabilities.maxGeneratedFrames);
            for (int count : counts) {
                long epoch = context.computeOnClient(client -> NativeVulkanSurface.presentationEpoch());
                context.runOnClient(client -> {
                    KavoraSodiumConfig.open(client.gui.screen());
                    option().modifyValue(FrameGenerationMode.valueOf("X" + (count + 1)));
                    ConfigManager.CONFIG.applyAllOptions();
                    client.gui.setScreen(null);
                });
                context.waitFor(client -> NativeVulkanSurface.presentationEpoch() != epoch
                        && NativeVulkanSurface.generatedFrames() > 0, 20 * 60);
                long[] before = snapshot(context);
                float yaw = context.computeOnClient(client -> client.player.getYRot());
                context.waitFor(client -> {
                    long rendered = NativeVulkanSurface.renderedFrames();
                    if (Boolean.getBoolean("kavora.gametest.frameGenerationMotion")) {
                        float angle = yaw + (rendered - before[1]) * .25f;
                        client.player.setYRot(angle);
                        client.player.yRotO = angle;
                    }
                    return rendered >= before[1] + 60;
                }, 20 * 60);
                long[] after = snapshot(context);
                KavoraWorldGameTest.require(before[0] == after[0], "Presentation changed while measuring FG");
                long rendered = after[1] - before[1], generated = after[2] - before[2];
                long sceneFrames = after[3] - before[3];
                KavoraWorldGameTest.require(Math.abs(sceneFrames - rendered) <= 3,
                        "Scene frame advanced more than once per rendered frame: scene=" + sceneFrames + ", rendered=" + rendered);
                double ratio = (double) generated / rendered;
                LOGGER.info("DLSS {}x: rendered={}, generated={}, generated/rendered={}", count + 1, rendered, generated, ratio);
                KavoraWorldGameTest.require(Math.abs(ratio - count) < .2, "Requested FG multiplier did not generate matching frames");
                KavoraWorldGameTest.captureCompletedFrame(context, "dlss-" + (count + 1) + "x-shore");
            }
            context.runOnClient(client -> KavoraSodiumConfig.open(client.gui.screen()));
            long[] menu = snapshot(context);
            context.waitFor(client -> NativeVulkanSurface.renderedFrames() >= menu[1] + 10, 200);
            long[] menuSettled = snapshot(context);
            context.waitFor(client -> NativeVulkanSurface.renderedFrames() >= menuSettled[1] + 20, 200);
            KavoraWorldGameTest.require(snapshot(context)[2] == menuSettled[2], "Settings UI continued generating frames");
            KavoraWorldGameTest.captureCompletedFrame(context, "dlss-capability-settings");
            context.runOnClient(client -> client.gui.setScreen(null));
            context.getInput().resizeWindow(1600, 900);
            context.waitFor(client -> client.getWindow().getWidth() == 1600 && client.getWindow().getHeight() == 900, 200);
            long[] resized = snapshot(context);
            context.waitFor(client -> NativeVulkanSurface.generatedFrames() > resized[2] + 30, 200);
            KavoraWorldGameTest.captureCompletedFrame(context, "dlss-resized-shore");
            context.getInput().resizeWindow(1920, 1080);
            context.waitFor(client -> client.getWindow().getWidth() == 1920 && client.getWindow().getHeight() == 1080, 200);
            context.runOnClient(client -> KavoraWorldGameTest.require(
                    SDLVideo.SDL_MinimizeWindow(client.getWindow().handle()), "Could not minimize FG window"));
            try {
                context.waitFor(client -> (SDLVideo.SDL_GetWindowFlags(client.getWindow().handle())
                        & SDLVideo.SDL_WINDOW_MINIMIZED) != 0, 200);
                context.waitTicks(2);
            } finally {
                context.runOnClient(client -> KavoraWorldGameTest.require(
                        SDLVideo.SDL_RestoreWindow(client.getWindow().handle()), "Could not restore FG window"));
            }
            context.waitFor(client -> (SDLVideo.SDL_GetWindowFlags(client.getWindow().handle())
                    & SDLVideo.SDL_WINDOW_MINIMIZED) == 0, 200);
            long[] restored = snapshot(context);
            context.waitFor(client -> NativeVulkanSurface.generatedFrames() > restored[2] + 30, 200);
            KavoraWorldGameTest.captureCompletedFrame(context, "dlss-restored-shore");
            context.runOnClient(client -> {
                KavoraSodiumConfig.open(client.gui.screen());
                option().modifyValue(FrameGenerationMode.OFF);
                ConfigManager.CONFIG.applyAllOptions();
                client.gui.setScreen(null);
            });
            context.waitFor(client -> NativeVulkanSurface.generatedFrames() == 0, 200);
            KavoraWorldGameTest.captureCompletedFrame(context, "dlss-fg-off-shore");
        } finally {
            context.runOnClient(client -> {
                var api = KavoraMod.renderer.getRemixApi();
                api.setConfigVariable("rtx.dlfg.maxInterpolatedFrames", previous[1]);
                api.setConfigVariable("rtx.dlfg.enable", previous[0]);
            });
        }
    }

    private static void verifyNativeOverlay(ClientGameTestContext context) {
        context.runOnClient(client -> KavoraWorldGameTest.require(
                !KavoraMod.getRemixInputGate().isCaptureSessionOpen(), "Native menu was open before overlay check"));
        try {
            context.runOnClient(client -> KavoraMod.openRemixMenu(
                    org.edtp.kavora.input.KavoraInputGate.Menu.DEVELOPER));
            context.waitFor(client -> !KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.showUI").equals("0")
                    && KavoraMod.renderer.getInteropCopiedSnapshotBytes() > 0, 200);
            long visible = context.computeOnClient(client -> KavoraFrameProbe.presents);
            context.waitFor(client -> KavoraFrameProbe.presents >= visible + 2, 200);
            KavoraWorldGameTest.captureNativeMenuFrame(context, "interop-native-menu");
        } finally {
            context.runOnClient(client -> {
                if (KavoraMod.getRemixInputGate().isCaptureSessionOpen()) KavoraMod.toggleRemixMenu();
            });
        }
        context.waitFor(client -> KavoraMod.renderer.getRemixApi().getConfigVariableResolved("rtx.showUI").equals("0")
                && KavoraMod.renderer.getInteropCopiedSnapshotBytes() == 0, 200);
    }

    private static void verifyFullscreen(ClientGameTestContext context, int generatedPerFrame) {
        context.runOnClient(client -> {
            KavoraSodiumConfig.open(client.gui.screen());
            option().modifyValue(FrameGenerationMode.valueOf("X" + (generatedPerFrame + 1)));
            ConfigManager.CONFIG.applyAllOptions();
            client.gui.setScreen(null);
        });
        boolean exclusive = context.computeOnClient(client -> client.getWindow().isExclusiveFullscreen());
        try {
            for (String mode : new String[] { "windowed", "borderless", "exclusive", "windowed-restored" }) {
                boolean fullscreen = mode.equals("borderless") || mode.equals("exclusive");
                context.runOnClient(client -> {
                    var window = client.getWindow();
                    window.setExclusiveFullscreen(mode.equals("exclusive"));
                    window.setFullscreen(fullscreen);
                    window.updateFullscreenIfChanged();
                });
                context.waitFor(client -> client.options.fullscreen().get() == fullscreen, 200);
                context.waitFor(client -> NativeVulkanSurface.generatedFrames() >= 30, 20 * 60);
                long[] before = snapshot(context);
                LOGGER.info("DLSS fullscreen measurement start: mode={}, epoch={}, time={}",
                        mode, before[0], java.time.Instant.now());
                float yaw = context.computeOnClient(client -> client.player.getYRot());
                context.waitFor(client -> {
                    KavoraWorldGameTest.require(NativeVulkanSurface.presentationEpoch() == before[0],
                            "Presentation repeatedly recreated in " + mode);
                    float angle = yaw + (NativeVulkanSurface.renderedFrames() - before[1]) * .125f;
                    client.player.setYRot(angle);
                    client.player.yRotO = angle;
                    return NativeVulkanSurface.renderedFrames() >= before[1] + 120;
                }, 20 * 60);
                long[] after = snapshot(context);
                long rendered = after[1] - before[1], generated = after[2] - before[2];
                double ratio = (double) generated / rendered;
                context.runOnClient(client -> LOGGER.info(
                        "DLSS fullscreen measurement end: mode={}, source={}x{}, rendered={}, generated={}, ratio={}, time={}",
                        mode, client.getWindow().getWidth(), client.getWindow().getHeight(),
                        rendered, generated, ratio, java.time.Instant.now()));
                KavoraWorldGameTest.require(Math.abs(ratio - generatedPerFrame) < .2,
                        "Frame generation did not survive " + mode);
                KavoraWorldGameTest.captureCompletedFrame(context, "dlss-" + mode + "-shore");
            }
        } finally {
            context.runOnClient(client -> {
                client.getWindow().setFullscreen(false);
                client.getWindow().updateFullscreenIfChanged();
                client.getWindow().setExclusiveFullscreen(exclusive);
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static EnumOption<FrameGenerationMode> option() {
        return (EnumOption<FrameGenerationMode>) ConfigManager.CONFIG.getOption(
                Identifier.fromNamespaceAndPath("kavora", "video.frame_generation"));
    }

    private static long[] snapshot(ClientGameTestContext context) {
        return context.computeOnClient(client -> {
            var info = new VulkanInteropInfo();
            KavoraMod.renderer.getRemixApi().dxvkGetVulkanInteropInfo(info);
            return new long[] { NativeVulkanSurface.presentationEpoch(), NativeVulkanSurface.renderedFrames(),
                    NativeVulkanSurface.generatedFrames(), info.frameStatus.frameId };
        });
    }
}
