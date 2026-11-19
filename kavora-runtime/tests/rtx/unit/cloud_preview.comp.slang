// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_sky.slang";
import "rtx/pass/cloud_parameters.slang";
import "cloud_preview_integral.slang";
import "rtx/utility/lat_long_projection.slang";
#if defined(CLOUD_PREVIEW_HAS_LIGHTING_CACHE)
import "rtx/algorithm/cloud_lighting_cache.slang";
#endif
#include "rtx/pass/tonemap/ACES.hlsl"

#if defined(CLOUD_PREVIEW_AIR)
import "rtx/algorithm/atmosphere.slang";
import "rtx/algorithm/air_medium.slang";
import "rtx/algorithm/fog_medium.slang";
import "rtx/pass/air_parameters.slang";
import "rtx/pass/volume_parameters.slang";
ConstantBuffer<AirArgs> Air;
Texture2D<float4>.Handle AirTransmission;
Texture2D<float4>.Handle AirScattering;
SamplerState.Handle AirSampler;
Sampler3D<float2>.Handle FogChroma;
RWStructuredBuffer<float4> TransmissionResults;

struct PreviewFog : IAtmosphereFog
{
  static bool interval(VolumeArgs args, float3 origin, float3 direction, float distance, out float2 range)
  {
    range = 0;
    return false;
  }

  static FogMedium sampleMedium(VolumeArgs args, float3 from, float3 to, float distance)
  {
    return {};
  }

  static float3 transmittance(VolumeArgs args, float3 origin, float3 direction, float distance)
  {
    return 1;
  }
};
#endif

ConstantBuffer<CloudArgs> Clouds;
Sampler2DArray<float4>.Handle Weather;
Sampler3D<float4>.Handle Base;
Sampler3D<float4>.Handle Detail;
#if defined(CLOUD_PREVIEW_HAS_LIGHTING_CACHE)
Sampler3D<float>.Handle CloudSunDepth;
Sampler3D<float>.Handle CloudMoonDepth;
#if defined(CLOUD_PREVIEW_HAS_LOCAL_LIGHT)
Sampler3D<float2>.Handle CloudLocalLight;
#else
Sampler3D<float2> UnusedVerticalDepth;
#endif
#if defined(CLOUD_PREVIEW_HAS_DIRECT_LIGHT)
Sampler3D<float4>.Handle CloudDirectLight;
#endif

CloudLightingCache previewCloudLighting()
{
#if defined(CLOUD_PREVIEW_HAS_LOCAL_LIGHT)
  const Sampler3D<float2> local = Sampler3D<float2>(CloudLocalLight);
#else
  const Sampler3D<float2> local = UnusedVerticalDepth;
#endif
  return { Sampler3D<float>(CloudSunDepth), Sampler3D<float>(CloudMoonDepth), local, Sampler3D<float4>(Base)
#if defined(CLOUD_PREVIEW_HAS_DIRECT_LIGHT)
    , Sampler3D<float4>(CloudDirectLight)
#endif
  };
}
#endif
RWStructuredBuffer<float4> Results;
uint2 Size;
float3 Origin;
float3 Forward;
float3 Right;
float3 CameraUp;
float TanHalfFov;
float TopSpan;
#if defined(CLOUD_PREVIEW_ATMOSPHERE)
Sampler2D<float4>.Handle Atmosphere;
RWStructuredBuffer<float4> EnvironmentResults;
float AtmosphereVerticalScale;
float AmbientScale;
#else
float3 Ambient;
#endif
uint Projection;
uint Radiance;

StructuredBuffer<float4> DisplayInput;
RWStructuredBuffer<float4> DisplayOutput;
uint DisplayCount;
float DisplayExposure;

// Reuse the shipped display curve without local exposure fusion or color grading.
[shader("compute")]
[numthreads(256, 1, 1)]
void mainDisplay(uint index : SV_DispatchThreadID)
{
  if (index < DisplayCount)
  {
    DisplayOutput[index] = float4(ACESFitted(max(DisplayInput[index].rgb, 0.0f) * DisplayExposure), 1);
  }
}

#if defined(CLOUD_PREVIEW_ATMOSPHERE)
float3 sampleAtmosphere(float3 direction)
{
  // Inverts the Y/Z-swapped, vertically scaled dome transform used by SkyRenderer.
  const float3 domeDirection = normalize(float3(direction.x, direction.z, direction.y * AtmosphereVerticalScale));
  return Sampler2D<float4>(Atmosphere).SampleLevel(cartesianDirectionToLatLongSphere(domeDirection), 0).rgb;
}

groupshared float3 PreviewAmbientSum[256];

[shader("compute")]
[numthreads(256, 1, 1)]
void mainAmbient(uint lane : SV_GroupIndex)
{
#if defined(CLOUD_PREVIEW_HAS_AMBIENT_SAMPLE_COUNT)
  const uint sampleCount = kCloudAmbientSampleCount;
#else
  const uint sampleCount = 16;
#endif
  const CloudAmbientQuadrature quadrature = createCloudAmbientQuadrature(Clouds);
  float3 radiance = 0;
  if (AmbientScale > 0)
  {
    for (uint index = lane; index < sampleCount; index += 256)
    {
      const float4 sample = quadrature.sample(index);
      radiance += sampleAtmosphere(sample.xyz) * sample.w * AmbientScale;
    }
  }
  PreviewAmbientSum[lane] = radiance;
  GroupMemoryBarrierWithGroupSync();
  for (uint offset = 128; offset > 0; offset /= 2)
  {
    if (lane < offset)
    {
      PreviewAmbientSum[lane] += PreviewAmbientSum[lane + offset];
    }
    GroupMemoryBarrierWithGroupSync();
  }
  if (lane == 0)
  {
    EnvironmentResults[Size.x * Size.y] = float4(PreviewAmbientSum[0], 1);
  }
}
#endif

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID)
{
  if (any(pixel >= Size))
  {
    return;
  }
  const float2 uv = (float2(pixel) + 0.5f) / float2(Size) * 2.0f - 1.0f;
  const float aspect = float(Size.x) / float(Size.y);
  const float3 origin = Projection == 1
    ? Origin + Right * (uv.x * TopSpan * 0.5f) - CameraUp * (uv.y * TopSpan * 0.5f / aspect)
    : Origin;
  const float3 direction = Projection == 1 ? Forward
    : normalize(Forward + Right * (uv.x * aspect * TanHalfFov) - CameraUp * (uv.y * TanHalfFov));
  const Sampler2DArray<float4> weather = Sampler2DArray<float4>(Weather);
  const Sampler3D<float4> base = Sampler3D<float4>(Base);
  const Sampler3D<float4> detail = Sampler3D<float4>(Detail);
#if defined(CLOUD_PREVIEW_ATMOSPHERE)
  const float3 ambient = EnvironmentResults[Size.x * Size.y].rgb;
  EnvironmentResults[pixel.y * Size.x + pixel.x] = float4(sampleAtmosphere(direction), 1);
#else
  const float3 ambient = Ambient;
#endif

  if (Projection == 0 && Radiance == 0)
  {
    const float transmission = cloudTransmittance(Clouds, origin, direction, 3.402823466e+38f,
      weather, base, detail);
    Results[pixel.y * Size.x + pixel.x] = float4(0, 0, 0, transmission);
    return;
  }

#if defined(CLOUD_PREVIEW_AIR)
  if (Projection == 0 && Radiance != 0)
  {
    VolumeArgs volume = {};
    volume.clouds = Clouds;
    volume.air = Air;
    const AirTables tables = { Texture2D<float4>(AirTransmission), Texture2D<float4>(AirScattering), SamplerState(AirSampler) };
    const AtmosphereRay ray = createAtmosphereRay<PreviewFog>(volume, origin, direction);
    const AtmosphereSegment result = integrateAtmosphereRange<PreviewFog>(volume, base, Sampler3D<float2>(FogChroma),
      weather, base, detail,
#if defined(CLOUD_PREVIEW_HAS_LIGHTING_CACHE)
      previewCloudLighting(),
#endif
      ray, 0, 3.402823466e+38f, ambient, tables);
    Results[pixel.y * Size.x + pixel.x] = float4(result.radiance, 1.0f - result.cloudMoments.y);
    TransmissionResults[pixel.y * Size.x + pixel.x] = float4(result.transmittance, 1);
    return;
  }
#endif

  float2 intervals[4];
  [unroll]
  for (uint i = 0; i < 4; i++)
  {
    intervals[i] = cloudLayerInterval(Clouds, origin, direction,
      Projection == 1 ? 1000000.0f : cloudLayerMaxDistance(Clouds, i / 2), i / 2, i % 2);
  }
  float3 radiance = 0;
  float transmission = 1;
  float opticalDepth = 0;
#if defined(CLOUD_PREVIEW_HAS_LOCAL_LIGHT)
  const CloudRayPhase phase = createCloudRayPhase(Clouds, direction);
#endif
#if defined(CLOUD_PREVIEW_HAS_SAMPLE_OFFSET)
  const float viewOffset = cloudViewSampleOffset(direction);
#endif
  for (uint intervalIndex = 0; intervalIndex < 4; intervalIndex++)
  {
    uint nearest = 0;
    [unroll]
    for (uint i = 1; i < 4; i++)
    {
      if (intervals[i].x < intervals[nearest].x)
      {
        nearest = i;
      }
    }
#if defined(CLOUD_PREVIEW_HAS_VIEW_GRID)
    const CloudRayGrid grid = createCloudViewGrid(Clouds, intervals[nearest], direction,
      Projection == 1 ? 0.0f : Clouds.viewAngularFootprint, nearest / 2);
#elif defined(CLOUD_PREVIEW_HAS_ADAPTIVE_GRID)
    const CloudRayGrid grid = createCloudRayGrid(intervals[nearest], cloudSampleSpacing(Clouds, direction, nearest / 2),
      Projection == 1 ? 0.0f : Clouds.viewAngularFootprint
#if defined(CLOUD_PREVIEW_HAS_LIGHTING_CACHE)
      , Clouds.steps
#endif
      );
#else
    const CloudRayGrid grid = { intervals[nearest], cloudSampleSpacing(Clouds, direction, nearest / 2) };
#endif
    intervals[nearest].x = 3.402823466e+38f;
    float previous = grid.interval.x;
    for (uint cell = 0; previous < grid.interval.y; cell++)
    {
      const float next = grid.boundary(cell + 1);
#if defined(CLOUD_PREVIEW_HAS_SAMPLE_OFFSET)
      const float distance = grid.sampleAt(cell, nearest / 2 == 0 ? viewOffset : 0.5f);
#else
      const float distance = grid.sampleAt(cell);
#endif
      const float3 position = origin + direction * distance;
      const float pixelFootprint = Projection == 1 ? TopSpan / float(Size.x) : Clouds.viewAngularFootprint * distance;
#if defined(CLOUD_PREVIEW_HAS_CELL_SPAN)
      const float cellSpan = grid.spanAt(cell);
#else
      const float cellSpan = grid.spacing;
#endif
#if defined(CLOUD_PREVIEW_HAS_DENSITY_FOOTPRINT)
      const float footprint = cloudDensityFootprint(Clouds, direction, cellSpan, pixelFootprint, nearest / 2);
#else
      const float footprint = max(grid.spacing, pixelFootprint);
#endif
#if defined(CLOUD_PREVIEW_HAS_LAYER_HEIGHT_FOOTPRINT)
      const float heightFootprint = cloudHeightFootprint(Clouds, position, direction, cellSpan, pixelFootprint, nearest / 2);
#else
      const float heightFootprint = cloudHeightFootprint(Clouds, position, direction, cellSpan, pixelFootprint);
#endif
      const float extinction = Projection == 1
        ? cloudExtinction(Clouds, position, weather, base, detail, footprint, heightFootprint)
        : cloudViewExtinction(Clouds, position, distance, weather, base, detail, footprint, heightFootprint);
      opticalDepth += extinction * (next - previous);
      if (Radiance != 0 && extinction > 0)
      {
#if defined(CLOUD_PREVIEW_HAS_LOCAL_LIGHT)
        const float3 incident = cloudIncidentRadiance(Clouds, position, direction,
#if defined(CLOUD_PREVIEW_HAS_DIRECT_LIGHT)
          distance,
#endif
          nearest / 2,
#if defined(CLOUD_PREVIEW_HAS_SAMPLE_EXTINCTION)
          extinction,
#endif
          ambient,
          weather, base, detail, previewCloudLighting(), phase);
#else
        const float3 incident = cloudIncidentRadiance(Clouds, position, direction, ambient, weather, base, detail
#if defined(CLOUD_PREVIEW_HAS_LIGHTING_CACHE)
          , previewCloudLighting()
#endif
          );
#endif
        accumulateCloudPreviewStep(extinction, next - previous, Clouds.albedo, incident, radiance, transmission);
      }
      previous = next;
    }
  }
  Results[pixel.y * Size.x + pixel.x] = Radiance != 0
    ? float4(radiance, transmission) : float4(opticalDepth, 0, 0, exp(-opticalDepth));
}
