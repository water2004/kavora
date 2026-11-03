/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ChunkBuilderMeshingTask.class)
public abstract class SodiumMeshingTaskMixin {
    @WrapMethod(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;")
    private ChunkBuildOutput rtxremix$capture(ChunkBuildContext context, CancellationToken cancelled,
            Operation<ChunkBuildOutput> original) {
        SodiumCapture.begin(((SodiumTaskAccessor) this).rtxremix$section(), context);
        ChunkBuildOutput result = null;
        try {
            result = original.call(context, cancelled);
            return result;
        } finally {
            SodiumCapture.end(result, cancelled.isCancelled());
        }
    }

    @Redirect(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState rtxremix$captureBlock(LevelSlice world, int x, int y, int z) {
        BlockState state = world.getBlockState(x, y, z);
        SodiumCapture.block(world, x, y, z, state);
        return state;
    }
    @org.spongepowered.asm.mixin.injection.Inject(
            method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/services/PlatformLevelRenderHooks;runChunkMeshAppenders(Ljava/util/List;Ljava/util/function/Function;Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/core/BlockPos;)V"))
    private void rtxremix$beginAppenders(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<ChunkBuildOutput> ci) {
        SodiumCapture.beginAppenders();
    }

}
