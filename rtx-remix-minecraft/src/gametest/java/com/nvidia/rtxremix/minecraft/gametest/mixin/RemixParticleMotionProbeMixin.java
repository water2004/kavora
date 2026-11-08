/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest.mixin;

import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Test-only reproduction of camera-only motion for comparison with explicit vertex history. */
@Mixin(targets = "com.nvidia.rtxremix.minecraft.particle.RemixParticleRenderer$PreparedMeshData", remap = false)
public abstract class RemixParticleMotionProbeMixin {
    @ModifyArg(method = "takeMesh", at = @At(value = "INVOKE",
            target = "Lcom/nvidia/rtxremix/minecraft/asset/RemixMeshSurface;setFlags(I)Lcom/nvidia/rtxremix/minecraft/asset/RemixMeshSurface;"), index = 0)
    private int rtxremix$motionFlags(int flags) {
        return Boolean.getBoolean("rtxremix.gametest.particleLegacyMotion")
                ? flags & ~MeshInfoSurfaceTriangles.PREVIOUS_POSITIONS : flags;
    }
}
