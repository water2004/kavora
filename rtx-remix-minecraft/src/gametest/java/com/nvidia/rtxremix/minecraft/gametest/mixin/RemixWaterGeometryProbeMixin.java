/* SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.nvidia.rtxremix.MeshInfo;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.gametest.RemixWaterGeometryProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RemixAPI.class, remap = false)
public abstract class RemixWaterGeometryProbeMixin {
    @Inject(method = "createMesh", at = @At("RETURN"))
    private void created(MeshInfo info, CallbackInfoReturnable<Long> cir) {
        RemixWaterGeometryProbe.created(info, cir.getReturnValue());
    }

    @WrapMethod(method = "sceneInstanceAdd")
    private long added(long mesh, float[] transform, int flags, int doubleSided, long pNext, Operation<Long> original) {
        long handle = original.call(mesh, transform, RemixWaterGeometryProbe.flagsFor(mesh, flags), doubleSided, pNext);
        RemixWaterGeometryProbe.submitted(handle, mesh, transform, flags, doubleSided, pNext);
        return handle;
    }

    @WrapMethod(method = "sceneInstanceUpdate")
    private void updated(long handle, long mesh, float[] transform, int flags, int doubleSided, long pNext, Operation<Void> original) {
        original.call(handle, mesh, transform,
                RemixWaterGeometryProbe.submitted(handle, mesh, transform, flags, doubleSided, pNext), doubleSided, pNext);
    }

    @Inject(method = "sceneInstanceRemove", at = @At("HEAD"))
    private void removed(long handle, CallbackInfo ci) { RemixWaterGeometryProbe.removed(handle); }

    @Inject(method = "destroyMesh", at = @At("HEAD"))
    private void destroyed(long handle, CallbackInfo ci) { RemixWaterGeometryProbe.destroyed(handle); }
}
