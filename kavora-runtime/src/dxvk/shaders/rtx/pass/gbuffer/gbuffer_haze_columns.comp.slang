/* SPDX-License-Identifier: MIT */
#define RAY_TRACING_PRIMARY_RAY 1
#define GBUFFER_FEATURE_DEBUG_VIEW 0
import "rtx/algorithm/fog_froxel_sampling.slang";
import "rtx/concept/surface_material/translucent_medium.slang";

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/concept/ray/ray.slangh"
#include "rtx/algorithm/scene_air.slangh"
#include "rtx/algorithm/scene_air_shadow.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
#include "rtx/utility/brdf.slangh"
#include "rtx/algorithm/volume_lighting.slangh"

layout(binding = GBUFFER_BINDING_LINEAR_WRAP_SAMPLER)
SamplerState LinearWrapSampler;
layout(binding = GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_Y_INPUT)
Sampler3D<float4> VolumeFilteredRadianceY;
layout(binding = GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_CO_CG_INPUT)
Sampler3D<float2> VolumeFilteredRadianceCoCg;

#include "rtx/pass/gbuffer/primary_haze_cache.slangh"

float2 hazeSceneDepths(uint2 begin, uint2 end)
{
  float nearest = floatMax;
  float farthest = 0.0f;
  for (uint y = begin.y; y <= end.y; ++y)
  {
    for (uint x = begin.x; x <= end.x; ++x)
    {
      const float depth = loadAtmosphereEndpoint(uint2(x, y));
      nearest = min(nearest, depth);
      if (depth < floatMax)
      {
        farthest = max(farthest, depth);
      }
    }
  }
  return float2(nearest, farthest);
}

void integrateHazeColumn(uint2 column, AtmosphereRay ray, float2 scene, VolumeArgs volume)
{
  const float2 air = ray.airInterval;
  const float2 fog = ray.fogInterval;
  const float end = max(air.y, fog.y);
  const float finiteNear = scene.x == floatMax ? 0.0f : min(scene.x, end);
  const float finiteFar = scene.x == floatMax ? end : min(scene.y, end);
  // Keep scene depths, the fog boundaries and depth samples through the air.
  // The uppermost anchor covers the end of all radiance-producing media.
  float depths[GBUFFER_HAZE_COLUMN_ENDPOINTS] = { finiteNear, finiteFar, fog.x, fog.y,
    lerp(air.x, air.y, 0.03125f), lerp(air.x, air.y, 0.125f), lerp(air.x, air.y, 0.5f), end };
  [unroll]
  for (uint i = 0; i < GBUFFER_HAZE_COLUMN_ENDPOINTS; ++i)
  {
    [unroll]
    for (uint j = i + 1; j < GBUFFER_HAZE_COLUMN_ENDPOINTS; ++j)
    {
      const float near = min(depths[i], depths[j]);
      depths[j] = max(depths[i], depths[j]);
      depths[i] = near;
    }
  }
  let haze = IntegratedAtmosphereHaze<SceneAtmosphereFog, FroxelFogRadiance, SceneAirVisibility>(
    FroxelFogRadiance(VolumeFilteredRadianceY, VolumeFilteredRadianceCoCg));
  const AirTables airTables = sceneAirTables(volume.air, LinearWrapSampler);
  const uint base = hazeColumnOffset(column);
  AtmosphereSegment prefix = emptyAtmosphereSegment();
  float previous = 0;
  for (uint i = 0; i < GBUFFER_HAZE_COLUMN_ENDPOINTS; ++i)
  {
    const float next = depths[i];
    if (next > previous)
    {
      prefix = composeAtmosphereSegments(prefix, haze.range(volume, ray, previous, next, airTables));
    }
    storeHazePrefix(base + i * GBUFFER_HAZE_COLUMN_ENDPOINT_WORDS, next, prefix);
    previous = next;
  }
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 column : SV_DispatchThreadID)
{
  const uint stride = hazeColumnStride();
  if (any(column >= columnExtent(stride)))
  {
    return;
  }

  const uint2 begin = column * stride;
  const uint2 end = min(begin + stride - 1, cb.camera.resolution - 1);
  const float2 center = 0.5f * float2(begin + end) + 0.5f;
  const Ray cameraRay = rayCreatePrimary(cb.camera, center / float2(cb.camera.resolution), cb.screenSpacePixelSpreadHalfAngle, true);
  const float3 medium = translucentMediumExtinction(cb.startInMediumMaterialIndex, surfaceMaterials);
  VolumeArgs volume = cb.volumeArgs;
  volume.clouds.enable = 0;
  const AtmosphereRay ray = createAtmosphereRay<SceneAtmosphereFog>(volume, CloudPhaseSampler, cameraRay.origin, cameraRay.direction, 0, medium);
  integrateHazeColumn(column, ray, hazeSceneDepths(begin, end), volume);
}
