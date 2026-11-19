/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.edtp.kavora.KavoraMod;
import org.edtp.kavora.render.HudColorBoundary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Supplier;

@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
    @Shadow @Final private List<?> draws;
    @Shadow private StagedVertexBuffer.Draw previousDraw;
    @Unique private int kavora$hudDrawStart;

    @Inject(method = "prepare", at = @At("HEAD"))
    private void kavora$resetHudBoundary(CallbackInfo ci) {
        kavora$hudDrawStart = 0;
    }

    @Inject(method = "addElementToMesh", at = @At("HEAD"), cancellable = true)
    private void kavora$collectHudBoundary(GuiElementRenderState element, CallbackInfo ci) {
        if (element == HudColorBoundary.INSTANCE) {
            kavora$hudDrawStart = draws.size();
            previousDraw = null;
            ci.cancel();
        }
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private void kavora$captureWhenNoCameraOverlays(CallbackInfo ci) {
        if (kavora$hudDrawStart == 0) {
            kavora$captureHudless(Minecraft.getInstance().gameRenderer.mainRenderTarget());
        }
    }

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;executeDrawRange(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;II)V"))
    private void kavora$drawAroundHudBoundary(GuiRenderer renderer, Supplier<String> label, RenderTarget target,
            GpuBufferSlice transform, int from, int to, Operation<Void> drawRange) {
        int boundary = kavora$hudDrawStart;
        if (boundary <= from || boundary > to) {
            drawRange.call(renderer, label, target, transform, from, to);
            return;
        }
        drawRange.call(renderer, label, target, transform, from, boundary);
        kavora$captureHudless(target);
        if (boundary < to) drawRange.call(renderer, label, target, transform, boundary, to);
    }

    @Unique
    private static void kavora$captureHudless(RenderTarget target) {
        var renderer = KavoraMod.renderer;
        if (renderer != null && renderer.isInitialized()) renderer.captureHudless(target.getColorTexture());
    }
}
