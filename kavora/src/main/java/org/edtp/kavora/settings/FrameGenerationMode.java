/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.settings;

import org.edtp.kavora.sdk.FrameGenerationCapabilities;
import java.util.EnumSet;
import java.util.Set;

/** Presentation multipliers supported by the runtime, restricted by the device's NGX capabilities. */
public enum FrameGenerationMode {
    OFF(1), X2(2), X3(3), X4(4), X5(5), X6(6), X7(7);

    final int multiplier;
    FrameGenerationMode(int multiplier) { this.multiplier = multiplier; }

    static Set<FrameGenerationMode> allowed(FrameGenerationCapabilities capabilities) {
        var modes = EnumSet.of(OFF);
        if (capabilities.initializationComplete && capabilities.supported) {
            for (var mode : values()) {
                if (mode.multiplier - 1 <= capabilities.maxGeneratedFrames) modes.add(mode);
            }
        }
        return modes;
    }

    static FrameGenerationMode fromMultiplier(int multiplier) {
        for (var mode : values()) if (mode.multiplier == multiplier) return mode;
        return OFF;
    }
}
