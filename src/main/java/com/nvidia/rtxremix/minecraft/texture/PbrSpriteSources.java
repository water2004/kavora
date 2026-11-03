/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.function.Predicate;
import net.minecraft.resources.Identifier;

/** PBR sidecars are material inputs; directory atlas sources collect their albedo only. */
public final class PbrSpriteSources {
    private PbrSpriteSources() {}

    public static boolean isAuxiliary(Identifier file, Predicate<Identifier> resourceExists) {
        String path = file.getPath();
        int suffixLength;
        if (path.endsWith("_sss.png")) suffixLength = 8;
        else if (path.endsWith("_n.png") || path.endsWith("_s.png")) suffixLength = 6;
        else return false;
        Identifier albedo = file.withPath(path.substring(0, path.length() - suffixLength) + ".png");
        return resourceExists.test(albedo);
    }
}
