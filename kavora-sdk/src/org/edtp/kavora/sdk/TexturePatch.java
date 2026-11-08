/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

import java.nio.ByteBuffer;

/**
 * Describes a rectangular update. Nonzero mip levels require an authored mip-chain texture.
 */
public class TexturePatch {
    public int dstX;
    public int dstY;
    public int width;
    public int height;
    public ByteBuffer pixelData;
    public int rowPitch;
    public int format = TextureFormat.RGBA8_UNORM;
    public int mipLevel;
}
