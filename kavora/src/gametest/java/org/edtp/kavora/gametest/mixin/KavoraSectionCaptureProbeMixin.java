/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.edtp.kavora.gametest.KavoraFrameProbe;
import net.minecraft.CrashReport;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Records worker failures before vanilla's potentially expensive system report collection. */
@Mixin(SectionCompiler.class)
public abstract class KavoraSectionCaptureProbeMixin {
    @WrapMethod(method = "compile")
    private SectionCompiler.Results kavora$recordEarlyFailure(net.minecraft.core.SectionPos position,
            net.minecraft.client.renderer.chunk.RenderSectionRegion region,
            com.mojang.blaze3d.vertex.VertexSorting sorting,
            net.minecraft.client.renderer.SectionBufferBuilderPack buffers,
            Operation<SectionCompiler.Results> original) {
        try {
            return original.call(position, region, sorting, buffers);
        } catch (RuntimeException | Error failure) {
            KavoraFrameProbe.failures++;
            throw failure;
        }
    }

    @WrapOperation(method = "compile", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/CrashReport;forThrowable(Ljava/lang/Throwable;Ljava/lang/String;)Lnet/minecraft/CrashReport;"))
    private CrashReport kavora$recordFailure(Throwable failure, String title, Operation<CrashReport> original) {
        KavoraFrameProbe.failures++;
        LoggerFactory.getLogger("RemixSectionCaptureProbe").error(title, failure);
        return original.call(failure, title);
    }
}
