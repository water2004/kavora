/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest.mixin;

import org.edtp.kavora.sdk.MeshInfoSurfaceTriangles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Test-only reproduction of camera-only motion for comparison with explicit vertex history. */
@Mixin(targets = "org.edtp.kavora.particle.KavoraParticleRenderer$PreparedMeshData", remap = false)
public abstract class KavoraParticleMotionProbeMixin {
    @ModifyArg(method = "takeMesh", at = @At(value = "INVOKE",
            target = "Lorg/edtp/kavora/asset/KavoraMeshSurface;setFlags(I)Lorg/edtp/kavora/asset/KavoraMeshSurface;"), index = 0)
    private int kavora$motionFlags(int flags) {
        return Boolean.getBoolean("kavora.gametest.particleLegacyMotion")
                ? flags & ~MeshInfoSurfaceTriangles.PREVIOUS_POSITIONS : flags;
    }
}
