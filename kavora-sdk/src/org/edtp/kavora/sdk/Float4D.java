/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

/**
 * 4D float vector.
 */
public class Float4D {
    public float x;
    public float y;
    public float z;
    public float w;

    public Float4D() {}

    public Float4D(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }
}
