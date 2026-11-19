/* SPDX-License-Identifier: Apache-2.0 */
package org.edtp.kavora.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.edtp.kavora.render.HudColorBoundary;
import org.edtp.kavora.vulkan.NativeVulkanSurface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractCameraOverlays", at = @At("RETURN"))
    private void kavora$markHudStart(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
        if (!NativeVulkanSurface.needsHudlessColor()) return;
        graphics.nextStratum();
        ((GuiGraphicsExtractorAccessor) graphics).kavora$guiRenderState().addGuiElement(HudColorBoundary.INSTANCE);
        graphics.nextStratum();
    }
}
