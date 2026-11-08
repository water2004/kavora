/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.edtp.kavora.compat.sodium;

/** Raster publication state of the last accepted mesh, retained for later sort results. */
public interface SodiumRasterSection {
    boolean kavora$isRasterUploadOmitted();
    void kavora$setRasterUploadOmitted(boolean omitted);
}
