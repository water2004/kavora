// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_view_transport.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/pass/cloud_parameters.slang";
import "cloud_preview_integral.slang";
import "rtx/utility/cloud_density.slang";
import "rtx/utility/revelation_cloud.slang";

ConstantBuffer<CloudArgs> Clouds;
StructuredBuffer<float4> Inputs;
StructuredBuffer<float4> Directions;
RWStructuredBuffer<float4> Results;
Sampler2DArray<float4>.Handle Weather;
Sampler3D<float4>.Handle Base;
Sampler3D<float4>.Handle Detail;
Sampler3D<float2>.Handle VerticalDepth;
uint Count;

[shader("compute")]
[numthreads(64, 1, 1)]
void airSkylight(uint index : SV_DispatchThreadID)
{
  if (index >= Count) return;
  Results[index] = float4(cloudDiffuseAirIllumination(Clouds, Sampler3D<float2>(VerticalDepth),
    Inputs[index].xyz, Directions[index].rgb), 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void columnCoordinates(uint index : SV_DispatchThreadID)
{
  if (index >= Count) return;
  const float4 entry = Inputs[index];
  const float3 position = cloudLocalLightPosition(Clouds, entry.xy, entry.z, uint(entry.w));
  const float3 coordinates = cloudLocalLightCoordinates(Clouds, position + Directions[index].xyz, entry.z, uint(entry.w));
  Results[index] = float4(coordinates.xz, cloudAltitude(cloudDensityParameters(Clouds), position), 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void columnHistory(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 old = Inputs[index];
  const float4 current = Directions[index];
  const uint3 entry = CloudHistoryGather.encode(float4(2, 4, 8, old.y), old.x);
  CloudHistoryGather history = {};
  history.add(entry, current.x, current.y, current.z, 1);
  Results[index] = float4(history.sum.rgb, history.weight);
}

struct CloudOpacityProbe : ICloudViewIntegral
{
  float2 moments;

  [mutating] void add<let Column : bool>(CloudArgs args, float3 position, float sigma, float begin, float end)
  {
    moments = composeCloudMoments(moments, cloudCellMoments(sigma, begin, end - begin, cloudMaxViewDistance(args)));
  }

  bool opaque() { return 1.0f - moments.y < kOpaqueCloudTransmittance; }
};

[shader("compute")]
[numthreads(64, 1, 1)]
void stationaryColumnNoise(uint index : SV_DispatchThreadID)
{
  if (index >= Count) return;
  const float3 origin = Inputs[index].xyz;
  const float3 direction = Directions[index].xyz;
  const float2 shell = cloudLayerInterval(Clouds, origin, direction, Clouds.maxDistance, 0, 0);
  float2 mean = 0;
  float2 squaredDeviation = 0;
  uint3 old = 0;
  uint3 older = 0;
  uint misses = 0;
  float reference = 0;
  for (uint frame = 0; frame < 64; ++frame)
  {
    CloudOpacityProbe sample = {};
    marchCloudView(Clouds, origin, direction, 0, shell, shell, Clouds.viewAngularFootprint,
      Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail), sample,
      cloudColumnSampleOffset(uint2(index, 0), frame));
    if (frame >= 32)
    {
      reference += sample.moments.y / 32.0f;
      continue;
    }
    float2 value = sample.moments.yy;
    misses += uint(value.x == 0);
    float depth = value.x > 0 ? cloudMaxViewDistance(Clouds) * sample.moments.x / value.x : 0.5f * (shell.x + shell.y);
    const float expectedDepth = depth;
    CloudHistoryGather history = {};
    history.add(old, expectedDepth, 0, 1, 1);
    history.add(older, expectedDepth, 0, 1, 1);
    value.y = history.resolve(float4(0, 0, 0, value.x), depth, shell).w;
    older = old;
    old = CloudHistoryGather.encode(float4(0, 0, 0, value.x), expectedDepth);
    if (frame >= 8)
    {
      const float2 delta = value - mean;
      mean += delta / float(frame - 7);
      squaredDeviation += delta * (value - mean);
    }
  }
  Results[index * 2] = float4(mean, squaredDeviation / 24.0f);
  Results[index * 2 + 1] = float4(float(misses), reference, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void sourceDensities(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
    return;
  const float4 point = Inputs[index];
  const RevelationField field = {Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail)};
  const float low = revelationLowDensity(field, point.xyz, point.w, Clouds.thickness, Clouds.coverage, Clouds.wetness);
  const float high = revelationHighDensity(field, point.xz, Clouds.highMapOrigin, Clouds.highLookupOrigin, Clouds.highCoverage);
  Results[index] = float4(low, high, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void homogeneous(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 sample = Inputs[index];
  const uint steps = uint(sample.w);
  float3 radiance = 0;
  float transmittance = 1;
  for (uint step = 0; step < steps; step++)
  {
    accumulateCloudPreviewStep(sample.x, sample.y / float(steps), sample.z, float3(1, 2, 4),
      radiance, transmittance);
  }
  Results[index] = float4(radiance, transmittance);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void intervals(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float distance = min(Inputs[index].w, cloudLayerMaxDistance(Clouds, 0));
  const float2 interval = cloudLayerInterval(Clouds, Inputs[index].xyz, Directions[index].xyz, distance, 0);
  Results[index] = float4(interval, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void layerIntervals(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 ray = Inputs[index];
  const float4 direction = Directions[index];
  const uint layer = uint(direction.w);
  Results[index] = float4(cloudLayerInterval(Clouds, ray.xyz, direction.xyz, ray.w, layer, 0),
    cloudLayerInterval(Clouds, ray.xyz, direction.xyz, ray.w, layer, 1));
}

[shader("compute")]
[numthreads(64, 1, 1)]
void lightProfiles(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  Results[index] = float4(cloudLightIrradiance(Clouds.lights[0], Inputs[index].x), 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void densities(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 sample = Inputs[index];
  const float density = cloudExtinction(Clouds, sample.xyz, Sampler2DArray<float4>(Weather),
    Sampler3D<float4>(Base), Sampler3D<float4>(Detail))
    / max(max(Clouds.extinction, Clouds.highExtinction), 1e-20f);
  Results[index] = float4(density, 0, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void visibility(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 origin = Inputs[index];
  const float transmission = cloudVisibilityTransmittance(Clouds, origin.xyz, Directions[index].xyz, origin.w,
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail));
  Results[index] = float4(transmission, 0, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void shortLightDepth(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 ray = Inputs[index];
  const float4 direction = Directions[index];
  const float2 depth = cloudLightOpticalDepth(Clouds, ray.xyz, direction.xyz, ray.w,
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail));
  Results[index] = float4(depth, exp(-(depth.x + depth.y)), 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void viewTransmission(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 origin = Inputs[index];
  const float3 direction = Directions[index].xyz;
  const float transmission = cloudTransmittance(Clouds, origin.xyz, direction, origin.w,
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail));
  Results[index] = float4(transmission, 0, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void visibilityReference(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 ray = Inputs[index];
  const float3 direction = Directions[index].xyz;
  float opticalDepth = 0;
  for (uint segment = 0; segment < 2; segment++)
  {
    const float2 interval = cloudLayerInterval(Clouds, ray.xyz, direction,
      min(ray.w, Clouds.lightMaxDistance), 0, segment);
    const uint cells = uint(ceil(max(0.0f, interval.y - interval.x) / 2.0f));
    const float span = (interval.y - interval.x) / float(max(cells, 1u));
    for (uint cell = 0; cell < cells; cell++)
    {
      const float distance = interval.x + (float(cell) + 0.5f) * span;
      const float3 position = ray.xyz + direction * distance;
      opticalDepth += span * cloudExtinction(Clouds, position, Sampler2DArray<float4>(Weather),
        Sampler3D<float4>(Base), Sampler3D<float4>(Detail));
      if (opticalDepth > 20)
      {
        Results[index] = float4(exp(-opticalDepth), 0, 0, 0);
        return;
      }
    }
  }
  Results[index] = float4(exp(-opticalDepth), 0, 0, 0);
}
