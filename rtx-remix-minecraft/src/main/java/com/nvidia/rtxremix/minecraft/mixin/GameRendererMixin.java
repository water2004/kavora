/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the extracted first-person model and composites before screen effects and GUI. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    private ProfileScope rtxremix$guiScope;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void rtxremix$prepareWorldResources(CallbackInfo ci) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            try (ProfileScope ignored = ProfileScope.begin("WorldRender.START")) {
                renderer.beginWorldRenderFrame();
            }
        }
    }

    @Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V"))
    private void rtxremix$captureFirstPersonModel(FirstPersonHandsAndItemsRenderer hands, float tickDelta,
            PoseStack matrices, SubmitNodeCollector submits, PlayerRenderState playerState,
            FirstPersonHandsAndItemsRenderState handsState) {
        RemixRenderer renderer = RtxRemixMod.renderer;
        if (!RtxRemixMod.shouldCaptureFirstPersonModel()) {
            hands.submitHandsWithItems(tickDelta, matrices, submits, playerState, handsState);
            return;
        }
        renderer.captureFirstPersonModel(hands, tickDelta, matrices, Minecraft.getInstance().player,
                playerState, RenderSystem.getModelViewStack());
    }

    @Inject(method = "render3dHud", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(FLnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Z)V"))
    private void rtxremix$submitBeforeScreenOverlays(CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void rtxremix$submitAtWorldTail(CallbackInfo ci) {
        RtxRemixMod.submitRemixFrameIfReady();
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void rtxremix$followRemixMenuVisibility(DeltaTracker tracker, boolean tick, CallbackInfo ci) {
        RtxRemixMod.followRemixMenuVisibility();
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void rtxremix$beginGuiScope(CallbackInfo ci) {
        if (ProfileScope.isJavaScopeEnabled()) {
            rtxremix$guiScope = ProfileScope.begin("GameRenderer.render.gui")
                    .plotOnClose("Java.GameRenderer.render.gui.ms");
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void rtxremix$endGuiScope(CallbackInfo ci) {
        if (rtxremix$guiScope != null) {
            rtxremix$guiScope.close();
            rtxremix$guiScope = null;
        }
    }
}
