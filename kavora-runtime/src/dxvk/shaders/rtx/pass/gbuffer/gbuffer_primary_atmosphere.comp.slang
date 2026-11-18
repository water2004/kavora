/* SPDX-License-Identifier: MIT */
#define RAY_TRACING_PRIMARY_RAY 1
#define GBUFFER_FEATURE_DEBUG_VIEW 0
#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/gbuffer/primary_atmosphere_cache.slangh"

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID)
{
  if (any(pixel >= cb.camera.resolution))
  {
    return;
  }
  const float distance = loadAtmosphereEndpoint(pixel);
  const uint stride = atmosphereColumnStride();
  const uint2 center = pixel / stride;
  const float2 sampleOffset = (float2(pixel % stride) + 0.5f) / float(stride) - 0.5f;
  const int2 neighborDirection = int2(select(sampleOffset < 0.0f, -1, 1));
  const float2 neighborWeight = abs(sampleOffset);
  const uint2 last = atmosphereColumnExtent() - 1;

  AtmosphereSegment result = {};
  float weightSum = 0.0f;
  [unroll]
  for (uint tap = 0; tap < 4; tap++)
  {
    const int2 delta = int2(tap & 1, tap >> 1);
    const int2 column = int2(center) + delta * neighborDirection;
    if (any(column < 0) || any(column > int2(last)))
    {
      continue;
    }
    const float4 depths = loadAtmosphereColumnDepths(uint2(column));
    // Never borrow a sky sample across a terrain silhouette. Only endpoints
    // close enough in depth can contribute from another tile.
    if (tap != 0 && (distance == floatMax ? depths.w != floatMax
      : !any(abs(depths - distance) <= max(0.25f, distance * 0.02f))))
    {
      continue;
    }
    const AtmosphereSegment transfer = sampleAtmosphereColumn(uint2(column), depths, distance);
    const float2 bilinear = select(delta == 0, 1.0f - neighborWeight, neighborWeight);
    const float w = bilinear.x * bilinear.y;
    result.radiance += transfer.radiance * w;
    result.transmittance += transfer.transmittance * w;
    result.cloudMoments += transfer.cloudMoments * w;
    weightSum += w;
  }
  const float normalizer = rcp(weightSum);
  result.radiance *= normalizer;
  result.transmittance *= normalizer;
  result.cloudMoments *= normalizer;
  storePrimaryAtmosphere(pixel, result);
}
