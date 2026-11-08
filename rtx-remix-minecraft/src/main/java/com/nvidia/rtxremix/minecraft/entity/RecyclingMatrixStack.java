/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;


import org.joml.Quaternionf;




/**
 * Matrix stack dedicated to Remix entity capture.
 *
 * <p>Vanilla allocates an entry, a position matrix, and a normal matrix for
 * every {@link #pushPose()}. Entity models repeat that traversal every frame, so
 * popped entries are retained here and copied back into service on the next
 * push. The pool belongs to this stack and therefore never changes vanilla
 * rendering outside the capture pass.
 */
public final class RecyclingMatrixStack extends PoseStack {

    private final Quaternionf modelPartRotation = new Quaternionf();
    private int captureDepth;

    @Override
    public void pushPose() {
        // PoseStack retains its entries in 26.3, including their two matrices.
        super.pushPose();
        captureDepth++;
    }

    @Override
    public void popPose() {
        super.popPose();
        captureDepth--;
    }

    /** Restores the identity root after a normal render or renderer exception. */
    void reset() {
        while (captureDepth > 0) popPose();
        setIdentity();
    }
    /** Applies vanilla's {@code ModelPart.rotate} transform without allocating a quaternion. */
    public void applyModelPartTransform(float pivotX, float pivotY, float pivotZ,
                                        float pitch, float yaw, float roll,
                                        float xScale, float yScale, float zScale) {
        translate(pivotX / 16.0F, pivotY / 16.0F, pivotZ / 16.0F);
        if (pitch != 0.0F || yaw != 0.0F || roll != 0.0F) {
            rotate(modelPartRotation.rotationZYX(roll, yaw, pitch));
        }
        if (xScale != 1.0F || yScale != 1.0F || zScale != 1.0F) {
            scale(xScale, yScale, zScale);
        }
    }
}
