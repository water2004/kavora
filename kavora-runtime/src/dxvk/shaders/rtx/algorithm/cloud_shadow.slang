// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
module cloud_shadow;

import "rtx/pass/cloud_parameters.slang";
import "rtx/algorithm/clouds.slang";
import "rtx/utility/cloud_density.slang";
import "rtx/utility/orthonormal_basis.slang";
#include "rtx/pass/clouds/cloud_shadow.h"

bool supportsCloudShadow(CloudArgs args, CloudLight light)
{
  return light.hasRadianceProfile && any(light.irradiance > 0) &&
    light.angularRadius <= 0.02f && dot(light.directionToLight, args.up) > 0;
}

// A zero slot identifies lights that require the actual ray integral.
public uint cloudShadowLight(CloudArgs args, float3 directionToLight)
{
  [unroll]
  for (uint index = 0; index < 2; ++index)
  {
    const CloudLight light = args.lights[index];
    if (all(directionToLight == light.directionToLight) && supportsCloudShadow(args, light))
    {
      return index + 1;
    }
  }
  return 0;
}

struct CloudShadowProjection
{
  float3 tangent;
  float3 bitangent;
  float width;

  __init(CloudArgs args, float3 lightDirection)
  {
    orthonormalBasis(lightDirection, tangent, bitangent);
    width = 8192.0f * args.meterToWorld;
  }

  float2 uv(float3 offset)
  {
    return 0.5f + float2(dot(offset, tangent), dot(offset, bitangent)) / width;
  }

  float3 offset(float2 uv)
  {
    const float2 plane = (uv - 0.5f) * width;
    return tangent * plane.x + bitangent * plane.y;
  }
};

// Positive outside the sphere. Subtract the vertical radii before squaring.
float cloudShellMargin(CloudArgs args, float3 offset, float altitude)
{
  const float height = dot(offset, args.up);
  const float3 horizontal = offset - height * args.up;
  return height - altitude + dot(horizontal, horizontal) / (2.0f * args.planetRadius + height + altitude);
}

// Returns the share of the estimate that can use a complete parallel column.
public float cloudShadowCoordinates(CloudArgs args, float3 origin, float3 direction, float distance,
  uint slot, out float3 uvw)
{
  uvw = 0;
  if (slot == 0)
  {
    return 0;
  }

  const bool low = cloudLayerEnabled(args, 0);
  const bool high = cloudLayerEnabled(args, 1);
  if (!low && !high)
  {
    return 0;
  }
  const float3 offset = origin - args.groundReference;
  const float base = cloudLayerBase(args, low ? 0 : 1);
  const float top = cloudLayerTop(args, high ? 1 : 0);
  const float below = -cloudShellMargin(args, offset, base);
  if (below <= 0)
  {
    return 0;
  }
  const float3 axis = args.lights[slot - 1].directionToLight;
  const CloudShadowProjection projection = CloudShadowProjection(args, axis);
  const float2 uv = projection.uv(offset);
  const float2 border = min(uv, 1.0f - uv);
  const float texelsToEdge = min(border.x, border.y) * CLOUD_SHADOW_RESOLUTION;
  if (texelsToEdge <= 0.5f)
  {
    return 0;
  }
  const float end = min(distance, cloudHemisphereEnd(args, origin, direction));
  const float beyond = cloudShellMargin(args, offset + direction * end, top);
  if (beyond <= 0)
  {
    return 0;
  }
  uvw = float3(uv, slot - 1);
  const float texelWidth = projection.width / CLOUD_SHADOW_RESOLUTION;
  return smoothstep(0.5f, 4.5f, texelsToEdge) * smoothstep(0, texelWidth, min(below, beyond));
}

public float bakeCloudShadow(CloudArgs args, uint2 texel, uint lightIndex,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail)
{
  const CloudLight light = args.lights[lightIndex];
  if (!supportsCloudShadow(args, light))
  {
    return 1;
  }
  const float3 axis = light.directionToLight;
  const CloudShadowProjection projection = CloudShadowProjection(args, axis);
  const float3 plane = args.groundReference + projection.offset((float2(texel) + 0.5f) / CLOUD_SHADOW_RESOLUTION);
  const CloudDensityArgs density = cloudDensityParameters(args);
  float tau = 0;
  [unroll]
  for (uint layer = 0; layer < 2; ++layer)
  {
    if (!cloudLayerEnabled(args, layer))
    {
      continue;
    }
    const float2 entry = cloudSphereRoots(args, plane, axis, cloudLayerBase(args, layer));
    if (entry.x == entry.y)
    {
      continue;
    }
    const float exit = cloudSphereRoots(args, plane, axis, cloudLayerTop(args, layer)).y;
    const float length = exit - entry.y;
    const uint steps = 16u + uint(16.0f * saturate(length / (100000.0f * args.meterToWorld)));
    const float stepLength = length / float(steps);
    const float sigma = layer == 0 ? args.extinction : args.highExtinction;
    for (uint tap = 0; tap < steps; ++tap)
    {
      const float t = entry.y + (float(tap) + 0.5f) * stepLength;
      tau += sigma * stepLength * cloudDensity(density, plane + axis * t, weather, base, detail, layer, false);
      if (tau >= 12.0f)
      {
        return exp(-tau);
      }
    }
  }
  return exp(-tau);
}
