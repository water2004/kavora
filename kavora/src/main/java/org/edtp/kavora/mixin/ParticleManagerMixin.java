/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.profiling.ParticleTickProfiler;
import org.edtp.kavora.profiling.ProfileScope;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Coarse particle ticking markers. These intentionally avoid per-particle
 * zones because the benchmark scene can tick thousands of particles per frame.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleManagerMixin {
    @Shadow @Final private Map<ParticleRenderType, ParticleGroup<?>> particles;

    private static final ThreadLocal<ProfileScope> kavora$tickScope = new ThreadLocal<>();

    @Inject(method = "tick", at = @At("HEAD"))
    private void kavora$tickHead(CallbackInfo ci) {
        ParticleTickProfiler.beginTick();
        int activeParticles = 0;
        int activeSheets = 0;
        for (ParticleGroup<?> queue : particles.values()) {
            int size = queue.size();
            activeParticles += size;
            if (size > 0) activeSheets++;
        }
        kavora$openScope(kavora$tickScope, ProfileScope.beginJfrOnly("ParticleManager.tick")
                .captureCount(activeParticles)
                .layerCount(activeSheets)
                .plotOnClose("Java.ParticleManager.tick.ms"));
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void kavora$tickReturn(CallbackInfo ci) {
        ParticleTickProfiler.Stats stats = ParticleTickProfiler.current();
        ProfileScope tickScope = kavora$tickScope.get();
        if (tickScope != null) {
            tickScope.collisionChecks(stats.collisionChecks())
                    .collisionSkips(stats.skippedCollisionChecks())
                    .collisionNanos(stats.collisionNanos());
            ProfileScope.plot("Java.ParticleManager.collisionChecks", stats.collisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionSkips", stats.skippedCollisionChecks());
            ProfileScope.plot("Java.ParticleManager.collisionMs", stats.collisionNanos() / 1_000_000.0);
        }
        kavora$closeScope(kavora$tickScope);
    }

    private static void kavora$openScope(ThreadLocal<ProfileScope> holder, ProfileScope scope) {
        kavora$closeScope(holder);
        holder.set(scope);
    }

    private static void kavora$closeScope(ThreadLocal<ProfileScope> holder) {
        ProfileScope scope = holder.get();
        if (scope != null) {
            holder.remove();
            scope.close();
        }
    }
}
