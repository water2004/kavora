/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.JoinBurstHold;
import org.edtp.kavora.KavoraRenderer;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the terrain-download screen up until Remix's world-join burst has settled.
 *
 * <p>Vanilla closes this screen from its tick as soon as the player's own section has
 * built, which is while Remix pays its first-sight costs behind the screen. The close is
 * cancelled while {@link JoinBurstHold#keepScreen(long)} says so; vanilla retries every
 * tick, so the screen closes on the first tick after the hold ends.
 */
@Mixin(LevelLoadingScreen.class)
public abstract class DownloadingTerrainScreenMixin {

    private boolean kavora$holdBegun;
    @Shadow private LevelLoadTracker loadTracker;

    @Inject(method = "update", at = @At("HEAD"))
    private void kavora$beginNewLoad(LevelLoadTracker tracker, LevelLoadingScreen.Reason reason, CallbackInfo ci) {
        if (loadTracker != tracker) kavora$holdBegun = false;
    }

    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void kavora$holdUntilSettled(CallbackInfo ci) {
        KavoraRenderer renderer = org.edtp.kavora.KavoraMod.renderer;
        if (org.edtp.kavora.KavoraMod.isRtxEnabled()
                && renderer != null && renderer.isInitialized() && !renderer.isSceneReady()) {
            net.minecraft.client.Minecraft.getInstance().gui.setScreen(
                    new org.edtp.kavora.KavoraPreparationScreen((LevelLoadingScreen) (Object) this));
            ci.cancel();
            return;
        }
        JoinBurstHold hold = KavoraRenderer.activeJoinBurstHold();
        if (hold == null) {
            return;
        }
        long now = System.nanoTime();
        if (!kavora$holdBegun) {
            kavora$holdBegun = true;
            hold.begin(now);
        }
        if (hold.keepScreen(now)) {
            ci.cancel();
        }
    }
}
