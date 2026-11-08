/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.edtp.kavora.compat.sodium.SodiumRasterSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Omits only raster publication; Sodium still owns the full CPU output and section metadata. */
@Mixin(RenderRegionManager.class)
public abstract class SodiumRegionUploadMixin {
    @WrapOperation(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
            + "Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;"
                    + "getMesh(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)"
                    + "Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionMeshParts;"))
    private BuiltSectionMeshParts kavora$omitRasterMesh(ChunkBuildOutput output, TerrainRenderPass pass,
            Operation<BuiltSectionMeshParts> original, @Local(argsOnly = true) RenderRegion region) {
        if (!((SodiumRasterSection) output.section).kavora$isRasterUploadOmitted()) {
            return original.call(output, pass);
        }
        if (pass.isTranslucent()) {
            var storage = region.getStorage(pass);
            if (storage != null) {
                // The uploader already retired vertices and invalidated this pass's cached batch.
                storage.removeIndexData(output.section.getSectionIndex());
            }
        }
        return null;
    }

    @WrapOperation(method = "uploadResults(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegion;"
            + "Ljava/util/Collection;Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkSortOutput;"
                    + "containsNewIndexData()Z"))
    private boolean kavora$omitRasterIndices(ChunkSortOutput output, Operation<Boolean> original) {
        return !((SodiumRasterSection) output.section).kavora$isRasterUploadOmitted()
                && original.call(output);
    }
}
