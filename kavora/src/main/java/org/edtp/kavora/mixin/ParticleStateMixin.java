/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.profiling.ParticleTickProfiler;
import org.edtp.kavora.particle.KavoraParticleStateAccess;
import org.edtp.kavora.KavoraMod;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

@Mixin(Particle.class)
public abstract class ParticleStateMixin implements KavoraParticleStateAccess {
    private static final boolean kavora$FAST_AIR_COLLISION =
            Boolean.parseBoolean(System.getProperty("kavora.particles.fastAirCollision", "true"));
    private static final int kavora$FAST_AIR_COLLISION_MAX_BLOCKS = Math.max(1,
            Integer.getInteger("kavora.particles.fastAirCollisionMaxBlocks", 8));
    private static final double kavora$COLLISION_EPSILON = 1.0E-7D;
    private static final ThreadLocal<BlockPos.MutableBlockPos> kavora$COLLISION_POS =
            ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private Object kavora$particleBucket;
    private int kavora$particleSlot = -1;

    @Redirect(
        method = "move(DDD)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collideBoundingBox(Lnet/minecraft/world/phys/shapes/CollisionContext;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Lnet/minecraft/world/level/Level;Ljava/util/List;)Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private Vec3 kavora$adjustMovementForParticleCollision(
            CollisionContext context,
            Vec3 movement,
            AABB box,
            Level world,
            List<VoxelShape> collisions) {
        if (kavora$canFastPathAirCollision(world, box, movement, collisions)) {
            ParticleTickProfiler.recordSkippedCollisionCheck();
            return movement;
        }

        ParticleTickProfiler.beginCollisionCheck();
        try {
            return Entity.collideBoundingBox(context, movement, box, world, collisions);
        } finally {
            ParticleTickProfiler.endCollisionCheck();
        }
    }

    @Override
    public Object kavora$getParticleBucket() {
        return kavora$particleBucket;
    }

    @Override
    public void kavora$setParticleBucket(Object bucket) {
        kavora$particleBucket = bucket;
    }

    @Override
    public int kavora$getParticleSlot() {
        return kavora$particleSlot;
    }

    @Override
    public void kavora$setParticleSlot(int slot) {
        kavora$particleSlot = slot;
    }

    private static boolean kavora$canFastPathAirCollision(
            Level world,
            AABB box,
            Vec3 movement,
            List<VoxelShape> collisions) {
        if (!kavora$FAST_AIR_COLLISION
                || !kavora$isRtxParticleOptimizationActive()
                || !collisions.isEmpty()) {
            return false;
        }

        double minX = movement.x < 0.0D ? box.minX + movement.x : box.minX;
        double maxX = movement.x > 0.0D ? box.maxX + movement.x : box.maxX;
        double minY = movement.y < 0.0D ? box.minY + movement.y : box.minY;
        double maxY = movement.y > 0.0D ? box.maxY + movement.y : box.maxY;
        double minZ = movement.z < 0.0D ? box.minZ + movement.z : box.minZ;
        double maxZ = movement.z > 0.0D ? box.maxZ + movement.z : box.maxZ;

        int blockMinX = Mth.floor(minX - kavora$COLLISION_EPSILON);
        int blockMaxX = Mth.floor(maxX + kavora$COLLISION_EPSILON);
        int blockMinY = Mth.floor(minY - kavora$COLLISION_EPSILON);
        int blockMaxY = Mth.floor(maxY + kavora$COLLISION_EPSILON);
        int blockMinZ = Mth.floor(minZ - kavora$COLLISION_EPSILON);
        int blockMaxZ = Mth.floor(maxZ + kavora$COLLISION_EPSILON);

        int checked = 0;
        BlockPos.MutableBlockPos pos = kavora$COLLISION_POS.get();
        for (int x = blockMinX; x <= blockMaxX; x++) {
            for (int y = blockMinY; y <= blockMaxY; y++) {
                for (int z = blockMinZ; z <= blockMaxZ; z++) {
                    if (++checked > kavora$FAST_AIR_COLLISION_MAX_BLOCKS) {
                        return false;
                    }
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (!state.getCollisionShape(world, pos).isEmpty()) {
                        return false;
                    }
                }
            }
        }

        return checked > 0;
    }

    private static boolean kavora$isRtxParticleOptimizationActive() {
        if (!KavoraMod.isRtxEnabled()
                || !KavoraMod.isWorldEnabled()
                || !KavoraMod.isParticlesEnabled()) {
            return false;
        }
        KavoraMod mod = KavoraMod.getInstance();
        return mod != null
                && mod.getRenderer() != null
                && mod.getRenderer().isInitialized();
    }
}
