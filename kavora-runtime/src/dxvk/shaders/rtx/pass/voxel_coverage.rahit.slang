/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: MIT
 */
//!variant-matrix ordered_coverage
//!> axis payload traversal
//!> axis payload path_nee COVERAGE_PATH_PAYLOAD NEE_CACHE_ENABLE=1
//!> axis payload path_nrc_nee COVERAGE_PATH_PAYLOAD ENABLE_NRC=1 NEE_CACHE_ENABLE=1
//!> axis payload path_sharc_combined_nee COVERAGE_PATH_PAYLOAD SHARC_QUERY=1 SHARC_UPDATE=1 NEE_CACHE_ENABLE=1
//!end-variants

#define RAY_PIPELINE
#include "rtx/pass/common_bindings.slangh"
#include "rtx/algorithm/ordered_intersection.slangh"

#if defined(COVERAGE_PATH_PAYLOAD)
#define INTEGRATE_INDIRECT
#define RAB_HAS_PREVIOUS_GBUFFER 1
#define RAB_HAS_RTXDI_RESERVOIRS 1
#include "rtx/pass/integrate/integrate_indirect_bindings.slangh"
#include "rtx/algorithm/integrator.slangh"
#include "rtx/algorithm/path_state.slangh"
typedef PathState CoveragePayload;
#else
typedef uint CoveragePayload;
#endif

[shader("anyhit")]
void main(inout CoveragePayload payload : SV_RayPayload, in float2 barycentricCoordinates : SV_IntersectionAttributes)
{
  RAY_HIT_INFO_CREATE_TRACE_RAY_HIT(hit);
  Ray ray = rayCreateEmpty();
  ray.origin = WorldRayOrigin();
  ray.direction = WorldRayDirection();
  ray.tMax = RayTCurrent();
#if defined(COVERAGE_PATH_PAYLOAD)
  ray.coneRadius = payload.coneRadius;
  ray.spreadAngle = payload.coneSpreadAngle;
#else
  ray.coneRadius = asfloat(payload);
  ray.spreadAngle = cb.screenSpacePixelSpreadHalfAngle;
#endif
  if (isOrderedCandidateClipped(ray, hit, shouldTestAlphaCoverage()))
  {
    IgnoreHit();
  }
}
