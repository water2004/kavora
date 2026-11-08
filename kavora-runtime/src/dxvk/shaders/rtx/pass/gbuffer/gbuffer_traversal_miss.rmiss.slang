/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

// Hit-object traversal records misses without invoking a material shader.
[shader("miss")]
void main(inout uint payload : SV_RayPayload)
{
}
