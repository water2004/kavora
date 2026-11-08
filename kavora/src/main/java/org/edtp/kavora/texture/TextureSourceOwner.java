/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.texture;

import com.mojang.renderpearl.api.textures.GpuTexture;

/** The nullable device texture, including before a reloadable texture is initialized. */
public interface TextureSourceOwner {
    GpuTexture kavora$gpuTexture();
}
