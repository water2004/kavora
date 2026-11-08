/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Preserves MatrixStack.Entry's normal-normalization state while recycling it. */
@Mixin(PoseStack.Pose.class)
public interface MatrixStackEntryAccessor {

    @Accessor("trustedNormals")
    boolean kavora$getCanSkipNormalization();

    @Accessor("trustedNormals")
    void kavora$setCanSkipNormalization(boolean canSkipNormalization);
}
