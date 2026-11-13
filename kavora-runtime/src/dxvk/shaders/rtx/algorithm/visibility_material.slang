// SPDX-FileCopyrightText: Copyright (c) 2023-2024 NVIDIA CORPORATION. All rights reserved.
// SPDX-License-Identifier: MIT
module visibility_material;

import "rtx/utility/material_optics.slang";

public struct RayPortalPlaneIntersection
{
  public float t;
  public float2 uv;
  public bool intersects;
};

// Computes the ray/portal-plane hit and its local UV coordinates.
[ForceInline]
public RayPortalPlaneIntersection intersectRayPortalPlane(
  float3 rayOrigin,
  float3 rayDirection,
  float rayTMax,
  float3 centroid,
  float3 normal,
  float3 xAxis,
  float3 yAxis,
  float inverseHalfWidth,
  float inverseHalfHeight)
{
  RayPortalPlaneIntersection result;

  // Keep the original plane-intersection operation order and reject non-finite t.
  const float NdotD = dot(rayDirection, normal);
  result.t = dot(centroid - rayOrigin, normal) / NdotD;
  if (isinf(result.t) || isnan(result.t))
  {
    result.t = -1.0;
  }

  result.uv = float2(0.0f);
  result.intersects = false;
  if (0 <= result.t && result.t <= rayTMax && NdotD < 0)
  {
    const float3 p = rayOrigin + rayDirection * result.t;
    const float3 cToP = p - centroid;
    result.uv.x = dot(cToP, xAxis) * inverseHalfWidth;
    result.uv.y = dot(cToP, yAxis) * inverseHalfHeight;
    result.intersects = max(abs(result.uv.x), abs(result.uv.y)) < 1.0;
  }

  return result;
}

// Evaluates thin-sheet visibility using the existing transmittance convention.
[ForceInline]
public float3 thinTransparentVisibilityAttenuation(
  float thickness,
  float3 geometryNormal,
  float3 rayDirection,
  float3 transmittanceColor)
{
  const float attenuationDistance = abs(evalThinAttenuationDistance(thickness, geometryNormal, rayDirection));
  const float3 attenuationCoefficient = transmittanceToAttenuationCoefficient(transmittanceColor, 1.0);
  return evalBeerLambertAttenuation(attenuationCoefficient, attenuationDistance);
}
