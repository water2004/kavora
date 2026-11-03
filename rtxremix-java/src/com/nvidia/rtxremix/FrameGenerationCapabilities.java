/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix;

/** NGX device and driver capabilities, independent of the selected frame-generation settings. */
public final class FrameGenerationCapabilities {
    public boolean initializationComplete;
    public boolean supported;
    /** Excludes the rendered frame. A value of two permits a 3x presentation mode. */
    public int maxGeneratedFrames;
    public String notSupportedReason = "DLSS is initializing.";
}
