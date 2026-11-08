/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.gametest.RemixFrameProbe;
import net.minecraft.CrashReport;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Records worker failures before vanilla's potentially expensive system report collection. */
@Mixin(SectionCompiler.class)
public abstract class RemixSectionCaptureProbeMixin {
    @WrapMethod(method = "compile")
    private SectionCompiler.Results rtxremix$recordEarlyFailure(net.minecraft.core.SectionPos position,
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            com.mojang.blaze3d.vertex.VertexSorting sorting,
            net.minecraft.client.renderer.SectionBufferBuilderPack buffers,
            Operation<SectionCompiler.Results> original) {
        try {
            return original.call(position, region, sorting, buffers);
        } catch (RuntimeException | Error failure) {
            RemixFrameProbe.failures++;
            throw failure;
        }
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/CrashReport;forThrowable(Ljava/lang/Throwable;Ljava/lang/String;)Lnet/minecraft/CrashReport;"))
    private CrashReport rtxremix$recordFailure(Throwable failure, String title, Operation<CrashReport> original) {
        RemixFrameProbe.failures++;
        LoggerFactory.getLogger("RemixSectionCaptureProbe").error(title, failure);
        return original.call(failure, title);
    }
}
