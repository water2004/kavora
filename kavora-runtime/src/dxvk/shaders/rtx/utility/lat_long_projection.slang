// SPDX-FileCopyrightText: Copyright (c) NVIDIA CORPORATION. All rights reserved.
// SPDX-License-Identifier: MIT
module lat_long_projection;

import "rtx/utility/math_constants.slang";

[ForceInline]
public float2 cartesianDirectionToLatLongSphere(float3 direction)
{
  float theta = acos(direction.z);
  float phi = atan2(direction.x, direction.y);
  return float2(0.5 + phi / twoPi, theta / pi);
}
