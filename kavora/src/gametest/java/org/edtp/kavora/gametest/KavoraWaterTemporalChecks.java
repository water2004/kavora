/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest;

import org.edtp.kavora.KavoraMod;
import java.util.Arrays;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Repeats one rendered-frame camera sweep to separate water reconstruction from source radiance. */
final class KavoraWaterTemporalChecks {
    private static final Logger LOGGER = LoggerFactory.getLogger(KavoraWaterTemporalChecks.class);
    private static final String[] KEYS = {
            "rtx.enableRayReconstruction", "rtx.rayreconstruction.useSpecularHitDistance",
            "rtx.rayreconstruction.invalidateHistoryForAnimatedWater", "rtx.dlfg.enable",
            "rtx.debugView.debugViewIdx", "rtx.captureDebugImage"
    };

    private KavoraWaterTemporalChecks() { }

    static void run(ClientGameTestContext context) {
        String[] previous = context.computeOnClient(client -> Arrays.stream(KEYS)
                .map(KavoraMod.renderer.getRemixApi()::getConfigVariableResolved).toArray(String[]::new));
        try {
            String[] names = {"path-guides", "without-hit-distance"};
            for (int arm = 0; arm < names.length; arm++) {
                String name = names[arm];
                options(context, new String[]{"True", Boolean.toString(arm == 0), "False", "False", "0", "True"});
                LOGGER.info("Water temporal arm {}: {}", name, context.computeOnClient(client ->
                        Arrays.toString(Arrays.stream(KEYS).map(KavoraMod.renderer.getRemixApi()
                                ::getConfigVariableResolved).toArray(String[]::new))));
                context.runOnClient(client -> pose(-4));
                waitFrames(context, 120);
                capture(context, name + "-static");
                long first = context.computeOnClient(client -> KavoraFrameProbe.presents);
                context.runOnClient(client -> KavoraFrameProbe.frameAction = frame ->
                        pose(-4 + Math.min(120, Math.max(0, frame - first)) / 15f));
                for (int frame : new int[]{40, 60, 80, 120}) {
                    context.waitFor(client -> KavoraFrameProbe.presents >= first + frame, 20 * 60);
                    capture(context, name + "-move-" + frame);
                    if (frame == 80) nativeCapture(context);
                }
                context.runOnClient(client -> KavoraFrameProbe.frameAction = null);
                long stopped = context.computeOnClient(client -> KavoraFrameProbe.presents);
                for (int frame : new int[]{4, 16, 64}) {
                    context.waitFor(client -> KavoraFrameProbe.presents >= stopped + frame, 20 * 60);
                    capture(context, name + "-stopped-" + frame);
                }
                nativeCapture(context);
            }
        } finally {
            context.runOnClient(client -> KavoraFrameProbe.frameAction = null);
            options(context, previous);
        }
    }

    private static void pose(float yaw) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        player.setYRot(yaw);
        player.yRotO = yaw;
        player.setXRot(18);
        player.xRotO = 18;
    }

    private static void options(ClientGameTestContext context, String[] values) {
        context.runOnClient(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            for (int i = 0; i < KEYS.length; i++) api.setConfigVariable(KEYS[i], values[i]);
        });
        context.waitFor(client -> {
            var api = KavoraMod.renderer.getRemixApi();
            for (int i = 0; i < KEYS.length; i++) {
                if (!values[i].equalsIgnoreCase(api.getConfigVariableResolved(KEYS[i]))) return false;
            }
            return true;
        }, 200);
    }

    private static void waitFrames(ClientGameTestContext context, int count) {
        long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.waitFor(client -> KavoraFrameProbe.presents >= frame + count, 20 * 60);
    }

    private static void capture(ClientGameTestContext context, String name) {
        var path = KavoraWorldGameTest.captureCompletedFrame(context, "water-temporal-" + name);
        LOGGER.info("Water temporal capture {} at frame {}: {}", name,
                context.computeOnClient(client -> KavoraFrameProbe.presents), path);
    }

    private static void nativeCapture(ClientGameTestContext context) {
        long frame = context.computeOnClient(client -> KavoraFrameProbe.presents);
        context.runOnClient(client -> KavoraMod.renderer.getRemixApi().setConfigVariable("rtx.captureNextFrame", "True"));
        context.waitFor(client -> KavoraFrameProbe.presents >= frame + 2
                && "False".equalsIgnoreCase(KavoraMod.renderer.getRemixApi()
                .getConfigVariableResolved("rtx.captureNextFrame")), 200);
    }
}
