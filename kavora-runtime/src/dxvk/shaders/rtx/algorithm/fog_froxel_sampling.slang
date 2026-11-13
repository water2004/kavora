/* SPDX-License-Identifier: MIT */
module fog_froxel_sampling;

import "rtx/utility/depth_slice.slang";

// Projected ray inputs and froxel coordinates independent of frame resources.
public struct FogRayProjection
{
  public float3 projectionOrigin;
  public float3 projectionDirection;
  public float originDepth;
  public float directionDepth;
  public float nearPlane;
  public uint volume;
}

[ForceInline]
public float3 fogRayFroxelUVW(uint depthSlices, float depthSliceDistributionExponent, float froxelMaxDistance,
  FogRayProjection ray, float distance)
{
  float3 projected = ray.projectionOrigin + ray.projectionDirection * distance;
  float2 uv = projected.xy / projected.z * float2(0.5f, -0.5f) + 0.5f;
  float viewDepth = ray.originDepth + ray.directionDepth * distance;
  float slice = viewZToDepthSlice(depthSlices, depthSliceDistributionExponent, froxelMaxDistance,
    ray.nearPlane, viewDepth, false);
  return float3(uv, slice / depthSlices);
}

[ForceInline]
public bool fogFroxelContains(float3 uvw)
{
  return all(uvw > 0) && all(uvw < 1);
}
