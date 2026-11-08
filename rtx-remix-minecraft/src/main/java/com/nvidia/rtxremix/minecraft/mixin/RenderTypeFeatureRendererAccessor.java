/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the CPU-only geometry builder independently of GPU frame preparation. */
@Mixin(RenderTypeFeatureRenderer.class)
public interface RenderTypeFeatureRendererAccessor {
    @Invoker("buildGroup")
    void rtxremix$buildGroup(FeatureFrameContext context, List<SubmitNode> submits);
}
