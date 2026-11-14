/* SPDX-License-Identifier: MIT */
module dome_effects;

#include "rtx/pass/dome_composite/dome_composite_args.h"
import "lunar_surface.slang";
#if defined(DOME_NIGHT_SKY) || defined(DOME_END_SKY)
import "night_sky.slang";
#endif
#ifdef DOME_END_SKY
import "end_sky.slang";
#endif

public struct DomeEffectOwner
{
  [ForceInline]
  public static float4 projectedSource(Sampler2D<float4> domeSource, float2 sourceUv,
    float2 sourceUvX, float2 sourceUvY, uint flags)
  {
    if (flags & kDomeLunarSurface)
    {
      return LunarSurface::radiance(domeSource, (sourceUv - 0.5f) * float2(2, -2),
        (sourceUvX - 0.5f) * float2(2, -2), (sourceUvY - 0.5f) * float2(2, -2),
        (flags >> kDomeMoonPhaseShift) & 7u);
    }
    return domeSource.SampleGrad(sourceUv, sourceUvX - sourceUv, sourceUvY - sourceUv);
  }

#ifdef DOME_NIGHT_SKY
  [ForceInline]
  public static float3 night(float4 rotation, float4 intensity, float4 meteorHead[2], float4 meteorTail[2],
    Sampler2D<float> nightField, Sampler2D<float3> galaxy,
    float3 direction, float3 directionX, float3 directionY)
  {
    return nightSkyRadiance(rotation, intensity, meteorHead, meteorTail,
      nightField, galaxy, direction, directionX, directionY);
  }
#endif

#ifdef DOME_END_SKY
  [ForceInline]
  public static float3 end(float4 rotation, float4 intensity, float4 parameters, Sampler2D<float3> galaxy, float3 direction)
  {
    return endSkyRadiance(rotation, intensity, parameters, galaxy, direction);
  }
#endif
};
