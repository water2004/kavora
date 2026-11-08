/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.sdk;

/** Exact submitted-frame status, copied while its native output extension is alive. */
public final class InteropFrameStatus {
    public static final int RAYTRACED = 1;
    public static final int CAMERA_VALID = 2;
    public static final int COMPILING = 4;
    public long frameId;
    public int flags;
    public int shaderCompileCount;

    public boolean isRaytraced() { return (flags & RAYTRACED) != 0; }
}
