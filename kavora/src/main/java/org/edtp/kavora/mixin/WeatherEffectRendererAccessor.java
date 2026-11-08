/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.mixin;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses vanilla's precipitation geometry and distance fade for the Remix compositor. */
@Mixin(WeatherEffectRenderer.class)
public interface WeatherEffectRendererAccessor {
    @Invoker("prepareInstances")
    void kavora$prepareInstances(VertexConsumer vertices,
            List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 camera,
            float opacity, int radius, float intensity);
}
