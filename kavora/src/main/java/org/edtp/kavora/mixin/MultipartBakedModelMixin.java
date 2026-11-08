/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.List;

/** Keeps multipart collection free of per-block iterator allocation. */
@Mixin(MultiPartModel.class)
public abstract class MultipartBakedModelMixin {
    @Shadow private List<BlockStateModel> models;

    @Inject(method = "collectParts", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/RandomSource;nextLong()J"), cancellable = true)
    private void kavora$collectParts(RandomSource random, List<BlockStateModelPart> parts, CallbackInfo ci) {
        long seed = random.nextLong();
        for (int i = 0, size = models.size(); i < size; i++) {
            random.setSeed(seed);
            models.get(i).collectParts(random, parts);
        }
        ci.cancel();
    }
}
