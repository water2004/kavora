/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import java.util.Queue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ParticleGroup.class)
public interface ParticleGroupAccessor {
    @Accessor("particles") Queue<Particle> getParticles();
}
