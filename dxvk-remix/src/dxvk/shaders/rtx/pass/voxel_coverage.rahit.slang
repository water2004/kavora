/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: MIT
 */
#include "rtx/pass/common_bindings.slangh"
#include "rtx/algorithm/voxel_coverage.slangh"

[shader("anyhit")]
void main(in float2 barycentricCoordinates : SV_IntersectionAttributes)
{
  RAY_HIT_INFO_CREATE_TRACE_RAY_HIT(hit);
  Ray ray = rayCreateEmpty();
  ray.origin = WorldRayOrigin();
  ray.direction = WorldRayDirection();
  ray.tMax = RayTCurrent();
  if (isVoxelCoverageCandidateClipped(ray, hit))
  {
    IgnoreHit();
  }
}
