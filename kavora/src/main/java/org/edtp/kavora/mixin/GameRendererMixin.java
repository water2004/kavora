/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import org.edtp.kavora.KavoraRenderer;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.profiling.ProfileScope;
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
    private ProfileScope kavora$guiScope;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void kavora$prepareWorldResources(CallbackInfo ci) {
        KavoraRenderer renderer = KavoraMod.renderer;
        if (renderer != null && renderer.isInitialized()) {
            try (ProfileScope ignored = ProfileScope.begin("WorldRender.START")) {
                renderer.beginWorldRenderFrame();
            }
        }
    }

    @Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V"))
    private void kavora$captureFirstPersonModel(FirstPersonHandsAndItemsRenderer hands, float tickDelta,
            PoseStack matrices, SubmitNodeCollector submits, PlayerRenderState playerState,
            FirstPersonHandsAndItemsRenderState handsState) {
        KavoraRenderer renderer = KavoraMod.renderer;
        if (!KavoraMod.shouldCaptureFirstPersonModel()) {
            hands.submitHandsWithItems(tickDelta, matrices, submits, playerState, handsState);
            return;
        }
        renderer.captureFirstPersonModel(hands, tickDelta, matrices, Minecraft.getInstance().player,
                playerState, RenderSystem.getModelViewStack());
    }

    @Inject(method = "render3dHud", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ScreenEffectRenderer;submit(FLnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Z)V"))
    private void kavora$submitBeforeScreenOverlays(CallbackInfo ci) {
        KavoraMod.submitRemixFrameIfReady();
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void kavora$submitAtWorldTail(CallbackInfo ci) {
        KavoraMod.submitRemixFrameIfReady();
    }

    @Inject(method = "extract", at = @At("HEAD"))
    private void kavora$followRemixMenuVisibility(DeltaTracker tracker, boolean tick, CallbackInfo ci) {
        KavoraMod.followRemixMenuVisibility();
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void kavora$beginGuiScope(CallbackInfo ci) {
        if (ProfileScope.isJavaScopeEnabled()) {
            kavora$guiScope = ProfileScope.begin("GameRenderer.render.gui")
                    .plotOnClose("Java.GameRenderer.render.gui.ms");
        }
    }

    // A caller can cancel the GUI phase before the original return instruction.
    @WrapMethod(method = "render")
    private void kavora$finishRender(Operation<Void> original) {
        try {
            original.call();
        } finally {
            if (kavora$guiScope != null) {
                kavora$guiScope.close();
                kavora$guiScope = null;
            }
        }
        KavoraRenderer renderer = KavoraMod.renderer;
        if (renderer != null && renderer.isInitialized()) renderer.finishDirectRender();
    }
}
