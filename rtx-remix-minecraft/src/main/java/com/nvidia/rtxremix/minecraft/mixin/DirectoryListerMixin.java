/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.nvidia.rtxremix.minecraft.texture.PbrSpriteSources;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(DirectoryLister.class)
public abstract class DirectoryListerMixin {
    @WrapOperation(method = "run", at = @At(value = "INVOKE",
            target = "Ljava/util/Map;forEach(Ljava/util/function/BiConsumer;)V"))
    private void rtxremix$collectAlbedo(Map<Identifier, Resource> files,
            BiConsumer<Identifier, Resource> consumer, Operation<Void> original,
            ResourceManager resources, SpriteSource.Output output) {
        BiConsumer<Identifier, Resource> filtered = (id, resource) -> {
            if (!PbrSpriteSources.isAuxiliary(id, base -> resources.getResource(base).isPresent())) {
                consumer.accept(id, resource);
            }
        };
        original.call(files, filtered);
    }
}
