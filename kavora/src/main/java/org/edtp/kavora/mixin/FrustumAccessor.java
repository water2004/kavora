/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Frustum.class)
public interface FrustumAccessor {
    @Accessor("intersection")
    FrustumIntersection kavora$getFrustumIntersection();

    @Accessor("camX")
    double kavora$getX();

    @Accessor("camY")
    double kavora$getY();

    @Accessor("camZ")
    double kavora$getZ();
}
