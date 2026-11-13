/* SPDX-License-Identifier: MIT */
//!variant dome_composite.comp
//!variant dome_composite_night.comp
//!>       DOME_NIGHT_SKY
//!variant dome_composite_end.comp
//!>       DOME_END_SKY
//!end-variants

#include "rtx/pass/dome_composite/dome_composite_args.h"
#include "rtx/pass/dome_composite/night_sky_args.h"
#include "rtx/utility/math.slangh"
import "lunar_surface.slang";
import "rtx/utility/lat_long_projection.slang";
#if defined(DOME_NIGHT_SKY) || defined(DOME_END_SKY)
import "night_sky.slang";
#endif
#ifdef DOME_END_SKY
import "end_sky.slang";
#endif

layout(binding = DOME_COMPOSITE_SOURCE)
Sampler2D<float4> DomeSource;
layout(binding = DOME_COMPOSITE_TRANSMITTANCE)
Sampler2D<float3> DomeTransmittance;
layout(r11f_g11f_b10f, binding = DOME_COMPOSITE_OUTPUT)
RWTexture2D<float4> DomeOutput;
layout(r11f_g11f_b10f, binding = DOME_COMPOSITE_BACKGROUND)
RWTexture2D<float4> VisibleBackground;
#if defined(DOME_NIGHT_SKY) || defined(DOME_END_SKY)
layout(binding = DOME_COMPOSITE_NIGHT_FIELD)
Sampler2D<float> NightField;
layout(binding = DOME_COMPOSITE_GALAXY)
Sampler2D<float3> Galaxy;
layout(binding = DOME_COMPOSITE_NIGHT_ARGS)
ConstantBuffer<NightSkyArgs> night;
#endif
layout(push_constant)
ConstantBuffer<DomeCompositeArgs> cb;

float3 worldDirection(float2 uv) {
  float theta = uv.y * pi;
  float phi = (uv.x - 0.5f) * twoPi;
  float3 direction = float3(sin(theta) * sin(phi), sin(theta) * cos(phi), cos(theta));
  return normalize(mul(cb.outputToWorld, float4(direction, 0.0f)));
}

float3 compositeLayer(float3 background, float4 source, float3 transmission) {
  const float3 foreground = source.rgb * cb.radiance;
  float3 result;
  if (cb.flags & kDomeHasTransmittance) {
    result.r = transmitDomeChannel(background.r, foreground.r, transmission.r, cb.opacity);
    result.g = transmitDomeChannel(background.g, foreground.g, transmission.g, cb.opacity);
    result.b = transmitDomeChannel(background.b, foreground.b, transmission.b, cb.opacity);
  } else {
    result.r = blendDomeChannel(background.r, foreground.r, source.a, cb.opacity, cb.blendType);
    result.g = blendDomeChannel(background.g, foreground.g, source.a, cb.opacity, cb.blendType);
    result.b = blendDomeChannel(background.b, foreground.b, source.a, cb.opacity, cb.blendType);
  }
  return result;
}

float2 projectedUv(float3 direction) {
  const float3 local = mul(cb.worldToLight, float4(direction, 0.0f));
  return float2(0.5f, 0.5f) + float2(0.5f, -0.5f) * local.xy / local.z;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID) {
  if (pixel.x >= cb.width || pixel.y >= cb.height) {
    return;
  }
  const float2 uv = (float2(pixel) + 0.5f) / float2(cb.width, cb.height);
  const float3 direction = worldDirection(uv);
  const float3 directionX = worldDirection(uv + float2(1.0f / cb.width, 0.0f));
  const float3 directionY = worldDirection(uv + float2(0.0f, 1.0f / cb.height));
  const float3 localDirection = normalize(mul(cb.worldToLight, float4(direction, 0.0f)));
  float2 sourceUV = cartesianDirectionToLatLongSphere(localDirection);
  float4 source = float4(1.0f);
  if (cb.flags & kDomeRectilinear) {
    sourceUV = projectedUv(direction);
    if (localDirection.z <= 0 || any(sourceUV < 0) || any(sourceUV > 1)) {
      if (!(cb.flags & kDomeFirstLayer)) return;
      source = float4(0.0f);
    } else if (cb.flags & kDomeHasTexture) {
      if (cb.flags & kDomeLunarSurface) {
        source = LunarSurface::radiance(DomeSource, (sourceUV - 0.5f) * float2(2, -2),
          (projectedUv(directionX) - 0.5f) * float2(2, -2),
          (projectedUv(directionY) - 0.5f) * float2(2, -2), (cb.flags >> kDomeMoonPhaseShift) & 7u);
      } else {
        source = DomeSource.SampleGrad(sourceUV, projectedUv(directionX) - sourceUV, projectedUv(directionY) - sourceUV);
      }
    }
  } else if (cb.flags & kDomeHasTexture) {
    source = DomeSource.SampleLevel(sourceUV, 0);
  }
#ifdef DOME_END_SKY
  source = float4(endSkyRadiance(night.rotation, night.intensity, night.endParameters, Galaxy, direction), 1.0f);
#endif
  float3 background = float3(0.0f);
  float3 visible = float3(0.0f);
  if (!(cb.flags & kDomeFirstLayer)) {
    background = DomeOutput[pixel].rgb;
    if (cb.flags & kDomeSeparateBackground) visible = VisibleBackground[pixel].rgb;
  }
#ifdef DOME_NIGHT_SKY
  const float3 nightRadiance = nightSkyRadiance(night.rotation, night.intensity, night.meteorHead,
    night.meteorTail, NightField, Galaxy, direction, directionX, directionY);
  background += nightRadiance;
  visible += nightRadiance;
#endif
  float3 transmission = float3(1.0f);
  if (cb.flags & kDomeHasTransmittance) transmission = saturate(DomeTransmittance.SampleLevel(sourceUV, 0));
  if (!(cb.flags & kDomeBackgroundOnly)) background = compositeLayer(background, source, transmission);
  DomeOutput[pixel] = float4(background, 1.0f);
  if (cb.flags & kDomeSeparateBackground) {
    VisibleBackground[pixel] = float4(compositeLayer(visible, source, transmission), 1.0f);
  }
}
