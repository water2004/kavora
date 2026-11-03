/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.compat.sodium.mixin;

import com.nvidia.rtxremix.minecraft.compat.sodium.SodiumCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.builder.ChunkMeshBufferBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.nio.ByteBuffer;

@Mixin(ChunkMeshBufferBuilder.class)
public abstract class SodiumMeshBufferMixin {
    @Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;I)V", at = @At("HEAD"))
    private void rtxremix$captureQuad(ChunkVertexEncoder.Vertex[] vertices, int bits, CallbackInfo ci) {
        SodiumCapture.quad(vertices, bits);
    }
    @Inject(method = "push([Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/material/Material;)V", at = @At("HEAD"))
    private void rtxremix$captureMaterial(ChunkVertexEncoder.Vertex[] vertices, Material material, CallbackInfo ci) {
        SodiumCapture.material(material);
    }
    @Inject(method = "writeExternal", at = @At("HEAD"))
    private void rtxremix$captureExternal(ByteBuffer bytes, int position, ChunkVertexEncoder.Vertex[] vertices,
            Material material, CallbackInfo ci) {
        SodiumCapture.material(material);
        SodiumCapture.quad(vertices, material.bits());
    }
}
