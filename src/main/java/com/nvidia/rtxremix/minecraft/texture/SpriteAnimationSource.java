/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Supplies atlas placement to the state that advances an animated sprite. */
public interface SpriteAnimationSource {
    void rtxremix$setSprite(TextureAtlasSprite sprite);
}
