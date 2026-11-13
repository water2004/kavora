/* SPDX-License-Identifier: MIT */
module fog_medium;

import "rtx/pass/volume_parameters.slang";
import "rtx/utility/value_noise_sampling.slang";

#include "rtx/utility/height_fog.h"
#include "rtx/utility/shader_types.slangh"

public struct FogMedium {
  public float3 extinction;
  public float3 scattering;
};

public float heightFogBetween(VolumeArgs args, float3 fromWorld, float3 toWorld)
{
  return heightFogSegmentDensity(dot(fromWorld, args.sceneUpDirection), dot(toWorld, args.sceneUpDirection),
    args.heightFogBaseHeight, args.heightFogFalloff, uint16BitsToF32(args.heightFogLowerFalloff));
}

public bool fogInterval(VolumeArgs args, float3 origin, float3 direction, float distance, out float2 interval)
{
  interval = float2(0, min(distance, args.maxAttenuationDistanceForNoAtmosphere));
  if (!args.enable) return false;
  if (args.enableAtmosphere)
  {
    float3 relative = origin - args.planetCenter;
    float projection = dot(relative, direction);
    float discriminant = projection * projection - dot(relative, relative) + args.atmosphereRadiusSquared;
    if (discriminant <= 0) return false;
    float radius = sqrt(discriminant);
    interval = float2(max(0.0f, -projection - radius), min(distance, -projection + radius));
  }
  return interval.y > interval.x;
}

float groundMistSegmentDensity(VolumeArgs args, Sampler3D<float4> noise, float3 fromWorld, float3 toWorld, float distance)
{
  if (args.groundMistExtinction <= 0)
  {
    return 0;
  }
  uint steps = uint(clamp(ceil(distance / (args.groundMistRegionSize * 0.125f)), 1.0f, 16.0f));
  float sum = 0;
  for (uint i = 0; i < steps; i++)
  {
    float3 a = lerp(fromWorld, toWorld, float(i) / float(steps));
    float3 b = lerp(fromWorld, toWorld, float(i + 1) / float(steps));
    float3 p = (a + b) * (0.5f / args.groundMistRegionSize);
    p -= args.sceneUpDirection * dot(p, args.sceneUpDirection);
    float broad = sampleLinearValueNoise(noise, p + float3(11.3f, 7.1f, 23.9f)).x * 0.5f + 0.5f;
    float detail = sampleLinearValueNoise(noise, p * 3.1f + 31.7f).y * 0.5f + 0.5f;
    float patch = smoothstep(0.3f, 0.65f, broad * 0.7f + detail * 0.3f);
    sum += patch * heightFogSegmentDensity(dot(a, args.sceneUpDirection), dot(b, args.sceneUpDirection),
      args.groundMistBaseHeight, args.groundMistFalloff);
  }
  return sum / float(steps);
}

public FogMedium fogSegmentMedium(VolumeArgs args, Sampler3D<float4> noise, float3 fromWorld, float3 toWorld, float distance, float baseDensity)
{
  float mistExtinction = args.groundMistExtinction * groundMistSegmentDensity(args, noise, fromWorld, toWorld, distance);
  FogMedium medium;
  medium.extinction = args.attenuationCoefficient * baseDensity + mistExtinction;
  medium.scattering = args.scatteringCoefficient * baseDensity + mistExtinction * 0.98f;
  return medium;
}
