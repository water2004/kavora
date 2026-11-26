/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import org.edtp.kavora.profiling.ProfileScope;
import org.edtp.kavora.particle.rapids.WaterRapidParticleSpawner;
import org.edtp.kavora.render.SceneAnimationClock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Adds lightweight profiling scopes around client-world work that contributes
 * to Remix frame cost.
 */
@Mixin(ClientLevel.class)
public abstract class ClientWorldMixin implements SceneAnimationClock {
    
    private static final ThreadLocal<ProfileScope> kavora$worldTickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$entityTickScope = new ThreadLocal<>();
    private static final ThreadLocal<ProfileScope> kavora$randomDisplayTickScope = new ThreadLocal<>();
    @Unique private static final int kavora$timeJumpTicks = 20;
    @Unique private long kavora$animationTicks = Long.MIN_VALUE;

    @Inject(method = "tickTime", at = @At("RETURN"))
    private void kavora$advanceAnimationClock(CallbackInfo ci) {
        kavora$animationTicks = kavora$animationTicks == Long.MIN_VALUE
                ? ((ClientLevel) (Object) this).getGameTime()
                : kavora$animationTicks + 1;
    }

    @Inject(method = "setTimeFromServer", at = @At("HEAD"))
    private void kavora$rebaseAnimationClock(long gameTime, CallbackInfo ci) {
        long previousTime = ((ClientLevel) (Object) this).getGameTime();
        if (kavora$animationTicks == Long.MIN_VALUE || Math.abs(gameTime - previousTime) > kavora$timeJumpTicks) {
            kavora$animationTicks = gameTime;
        }
    }

    @Override
    public long kavora$animationTicks() {
        return kavora$animationTicks == Long.MIN_VALUE ? ((ClientLevel) (Object) this).getGameTime() : kavora$animationTicks;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void kavora$tickHead(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        kavora$openScope(kavora$worldTickScope, ProfileScope.beginJfrOnly("ClientWorld.tick")
                .plotOnClose("Java.ClientWorld.tick.ms"));
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void kavora$tickReturn(BooleanSupplier shouldKeepTicking, CallbackInfo ci) {
        kavora$closeScope(kavora$worldTickScope);
    }

    @Inject(method = "tickEntities", at = @At("HEAD"))
    private void kavora$tickEntitiesHead(CallbackInfo ci) {
        int entityCount = ((ClientLevel) (Object) this).getEntityCount();
        kavora$openScope(kavora$entityTickScope, ProfileScope.beginJfrOnly("ClientWorld.tickEntities")
                .entityCount(entityCount)
                .plotOnClose("Java.ClientWorld.tickEntities.ms"));
    }

    @Inject(method = "tickEntities", at = @At("RETURN"))
    private void kavora$tickEntitiesReturn(CallbackInfo ci) {
        kavora$closeScope(kavora$entityTickScope);
    }

    @Inject(method = "animateTick", at = @At("HEAD"))
    private void kavora$randomBlockDisplayHead(int centerX, int centerY, int centerZ, CallbackInfo ci) {
        kavora$openScope(kavora$randomDisplayTickScope, ProfileScope.beginJfrOnly("ClientWorld.randomBlockDisplayTicks")
                .captureCount(1334)
                .plotOnClose("Java.ClientWorld.randomBlockDisplayTicks.ms"));
    }

    @Inject(method = "animateTick", at = @At("RETURN"))
    private void kavora$randomBlockDisplayReturn(int centerX, int centerY, int centerZ, CallbackInfo ci) {
        WaterRapidParticleSpawner.spawnRandomDisplayParticles((ClientLevel) (Object) this, centerX, centerY, centerZ);
        kavora$closeScope(kavora$randomDisplayTickScope);
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
