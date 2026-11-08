/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.edtp.kavora.sdk.MeshInfo;
import org.edtp.kavora.sdk.RemixAPI;
import org.edtp.kavora.gametest.KavoraWaterGeometryProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RemixAPI.class, remap = false)
public abstract class KavoraWaterGeometryProbeMixin {
    @Inject(method = "createMesh", at = @At("RETURN"))
    private void created(MeshInfo info, CallbackInfoReturnable<Long> cir) {
        KavoraWaterGeometryProbe.created(info, cir.getReturnValue());
    }

    @WrapMethod(method = "sceneInstanceAdd")
    private long added(long mesh, float[] transform, int flags, int doubleSided, long pNext, Operation<Long> original) {
        long handle = original.call(mesh, transform, KavoraWaterGeometryProbe.flagsFor(mesh, flags), doubleSided, pNext);
        KavoraWaterGeometryProbe.submitted(handle, mesh, transform, flags, doubleSided, pNext);
        return handle;
    }

    @WrapMethod(method = "sceneInstanceUpdate")
    private void updated(long handle, long mesh, float[] transform, int flags, int doubleSided, long pNext, Operation<Void> original) {
        original.call(handle, mesh, transform,
                KavoraWaterGeometryProbe.submitted(handle, mesh, transform, flags, doubleSided, pNext), doubleSided, pNext);
    }

    @Inject(method = "sceneInstanceRemove", at = @At("HEAD"))
    private void removed(long handle, CallbackInfo ci) { KavoraWaterGeometryProbe.removed(handle); }

    @Inject(method = "destroyMesh", at = @At("HEAD"))
    private void destroyed(long handle, CallbackInfo ci) { KavoraWaterGeometryProbe.destroyed(handle); }
}
