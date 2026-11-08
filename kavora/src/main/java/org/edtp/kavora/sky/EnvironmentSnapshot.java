/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sky;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;

/** Reused frame snapshot of camera-local environment attributes. */
public final class EnvironmentSnapshot {
    public DimensionType.Skybox skybox;
    public float sunAngle;
    public float moonAngle;
    public float starAngle;
    public float starBrightness;
    public int moonPhase;
    public float cloudHeight;
    public float cloudRed;
    public float cloudGreen;
    public float cloudBlue;
    public float cloudAlpha;
    public float rain;
    public float thunder;
    public double gameTime;

    /** Capture on the extraction thread before any Remix scene publication. */
    public void capture(ClientLevel level, Camera camera, float partialTick) {
        var probe = camera.attributeProbe();
        skybox = level.dimensionType().skybox();
        sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) / 360.0f;
        moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick) / 360.0f;
        starAngle = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partialTick) / 360.0f;
        starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partialTick);
        moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partialTick).index();
        cloudHeight = probe.getValue(EnvironmentAttributes.CLOUD_HEIGHT, partialTick);
        var color = probe.getValue(EnvironmentAttributes.CLOUD_COLOR, partialTick);
        cloudRed = color.x();
        cloudGreen = color.y();
        cloudBlue = color.z();
        cloudAlpha = color.w();
        rain = level.getRainLevel(partialTick);
        thunder = level.getThunderLevel(partialTick);
        gameTime = level.getGameTime() + (double) partialTick;
    }
}
