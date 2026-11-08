/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.terrain.SkyLightSurfaceScanPolicy;
import org.edtp.kavora.terrain.SkyLightSurfaceAccess;
import org.edtp.kavora.KavoraMod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Seeds the sky-light surface scan of a chunk the client receives from the packet's
 * {@code WORLD_SURFACE} heightmap. {@code loadFromPacket} is the client chunk apply; the
 * server's refresh during chunk generation is untouched.
 */
@Mixin(LevelChunk.class)
public abstract class WorldChunkMixin {

    @WrapOperation(
            method = "replaceWithPacketData",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;initializeLightSources()V"))
    private void kavora$seedSurfaceScan(
            LevelChunk self,
            Operation<Void> original,
            int chunkX, int chunkZ, ClientboundLevelChunkPacketData packet) {
        boolean worldSurfaceInPacket = packet.getHeightmaps().containsKey(Heightmap.Types.WORLD_SURFACE);
        if (!SkyLightSurfaceScanPolicy.seedFromHeightmap(SkyLightSurfaceScanPolicy.SEED_ALLOWED, worldSurfaceInPacket)) {
            if (SkyLightSurfaceScanPolicy.SEED_ALLOWED) {
                SkyLightSurfaceScanPolicy.noteMissingHeightmap();
            }
            original.call(self);
            return;
        }
        ((SkyLightSurfaceAccess) self.getSkyLightSources())
                .kavora$refreshSurfaceY(self, self.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE));
        // Counted only while capture is on, like the paced-chunk counters: the terrain
        // counters that drain it run only while capture and world rendering are on, and
        // seeding itself depends on neither toggle.
        if (KavoraMod.isRtxEnabled()) {
            SkyLightSurfaceScanPolicy.recordSeeded();
        }
    }
}
