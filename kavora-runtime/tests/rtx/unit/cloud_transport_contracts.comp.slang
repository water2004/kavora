// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_shadow.slang";
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
Sampler3D<float4>.Handle Neural;
uint Count;

[shader("compute")]
[numthreads(64, 1, 1)]
void neuralDensities(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
    return;
  const float4 point = Inputs[index];
  const CloudDensityArgs density = cloudDensityParameters(Clouds);
  float4 features;
  [unroll]
  for (uint level = 0; level < 4; ++level)
    features[level] = cloudDensityFeature(density, point.xyz, uint(point.w), level, Clouds.meterToWorld,
      Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail));
  Results[index] = features;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void neuralBaseDensity(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 point = Inputs[index];
  Results[index] = cloudDensity(cloudDensityParameters(Clouds), point.xyz,
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail), uint(point.w), false).xxxx;
}

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
void cloudBoundaryLight(uint index : SV_DispatchThreadID)
{
  if (index >= Count) return;
  const float4 point = Inputs[index];
  const CloudLightingCache cache = { Sampler3D<float2>(VerticalDepth), Sampler3D<float4>(Neural) };
  CloudRayPhase phase = {};
  Results[index] = float4(cloudIncidentRadiance(Clouds, point.xyz, uint(point.w), 0,
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail), cache, phase), 0);
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
void pixelSample(uint index : SV_DispatchThreadID)
{
  if (index >= Count) return;
  const uint3 sample = uint3(Inputs[index].xyz);
  Results[index] = cloudPixelSampleOffset(sample.xy, sample.z).xxxx;
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
void shadowCoordinates(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 ray = Inputs[index];
  const float4 direction = Directions[index];
  const uint slot = cloudShadowLight(Clouds, Clouds.lights[uint(direction.w)].directionToLight);
  float3 uvw;
  const float weight = cloudShadowCoordinates(Clouds, ray.xyz, direction.xyz, ray.w, slot, uvw);
  Results[index] = float4(uvw, weight);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void shadowTexels(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 request = Inputs[index];
  const float transmission = bakeCloudShadow(Clouds, uint2(request.xy), uint(request.z),
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail));
  Results[index] = float4(transmission, 0, 0, 0);
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
void viewTransmissionSplits(uint index : SV_DispatchThreadID)
{
  if (index >= Count) return;
  const float4 ray = Inputs[index];
  const float3 direction = Directions[index].xyz;
  const float split = Directions[index].w;
  const float2 shell = cloudLayerInterval(Clouds, ray.xyz, direction, ray.w, 0, 0);
  const float offset = cloudViewSampleOffset(direction);
  CloudOpacityProbe first = {};
  CloudOpacityProbe second = {};
  marchCloudView(Clouds, ray.xyz, direction, 0, shell, float2(shell.x, split), Clouds.viewAngularFootprint,
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail), first, offset);
  marchCloudView(Clouds, ray.xyz, direction, 0, shell, float2(split, shell.y), Clouds.viewAngularFootprint,
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail), second, offset);
  Results[index] = float4(1.0f - first.moments.y, 1.0f - second.moments.y, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void distantCloudSequence(uint index : SV_DispatchThreadID)
{
  if (index >= Count) return;
  const float3 origin = Inputs[index].xyz;
  const float4 ray = Directions[index];
  const float3 direction = ray.xyz;
  CloudArgs args = Clouds;
  args.windOffset += ray.w * float3(9.396926f, 5.0f, 3.420201f);
  const float2 shell = cloudLayerInterval(args, origin, direction, 3.402823466e+38f, 0, 0);
  const float begin = max(shell.x, args.maxDistance);
  const float length = shell.y - begin;
  const Sampler2DArray<float4> weather = Sampler2DArray<float4>(Weather);
  const Sampler3D<float4> base = Sampler3D<float4>(Base);
  const Sampler3D<float4> detail = Sampler3D<float4>(Detail);
  CloudOpacityProbe sample = {};
  marchCloudView(args, origin, direction, 0, shell, float2(begin, shell.y), args.viewAngularFootprint,
    weather, base, detail, sample, 0.5f);
  const float oldDepth = length * cloudLayerExtinction(args, origin + direction * (begin + length * 0.5f), 0,
    weather, base, detail);
  const uint steps = uint(ceil(length / 20.0f));
  const float step = length / steps;
  float opticalDepth = 0;
  for (uint i = 0; i < steps; ++i)
  {
    opticalDepth += step * cloudLayerExtinction(args, origin + direction * (begin + step * (i + 0.5f)), 0,
      weather, base, detail);
  }
  Results[index] = float4(1.0f - sample.moments.y, exp(-oldDepth), exp(-opticalDepth), length);
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
