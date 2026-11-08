/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

import java.nio.ByteBuffer;

/**
 * Information for creating a texture from raw pixel data in memory.
 */
public class TextureFromMemoryInfo {
    public long hash;
    public ByteBuffer pixelData;
    public int width;
    public int height;
    public int format;
    public int purpose;
    /** Zero generates mips; positive values supply packed authored mip levels in pixelData. */
    public int mipLevels;
    /** Rejects texture patches when true. Native uploads retain no persistent CPU pixel copy. */
    public boolean immutable;

    public TextureFromMemoryInfo() {}

    public static class Builder {
        private long hash;
        private ByteBuffer pixelData;
        private int width;
        private int height;
        private int format = TextureFormat.RGBA8_SRGB;
        private int purpose = TexturePurpose.COLOR;
        private int mipLevels;
        private boolean immutable;

        public Builder hash(long hash) {
            this.hash = hash;
            return this;
        }

        public Builder pixelData(ByteBuffer data) {
            this.pixelData = data;
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder format(int format) {
            this.format = format;
            return this;
        }

        public Builder purpose(int purpose) {
            this.purpose = purpose;
            return this;
        }

        public Builder mipLevels(int levels) { this.mipLevels = levels; return this; }
        public Builder immutable(boolean immutable) { this.immutable = immutable; return this; }

        public TextureFromMemoryInfo build() {
            TextureFromMemoryInfo info = new TextureFromMemoryInfo();
            info.hash = this.hash;
            info.pixelData = this.pixelData;
            info.width = this.width;
            info.height = this.height;
            info.format = this.format;
            info.purpose = this.purpose;
            info.mipLevels = this.mipLevels;
            info.immutable = this.immutable;
            return info;
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
