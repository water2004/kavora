// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/pass/cloud_parameters.slang";
import "cloud_preview_integral.slang";
import "rtx/utility/cloud_density.slang";

ConstantBuffer<CloudArgs> Clouds;
StructuredBuffer<float4> Inputs;
StructuredBuffer<float4> Directions;
RWStructuredBuffer<float4> Results;
Sampler2DArray<float4>.Handle Weather;
Sampler3D<float4>.Handle Base;
Sampler3D<float4>.Handle Detail;
Sampler3D<float>.Handle CloudSunDepth;
Sampler3D<float>.Handle CloudMoonDepth;
Sampler3D<float2>.Handle VerticalDepth;
uint Count;

CloudLightingCache lightingCache()
{
  const Sampler3D<float4> unused = Sampler3D<float4>(Base);
  return { Sampler3D<float>(CloudSunDepth), Sampler3D<float>(CloudMoonDepth), Sampler3D<float2>(VerticalDepth), unused, unused };
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
    Sampler3D<float4>(Base), Sampler3D<float4>(Detail), sample.w)
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
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail), lightingCache());
  Results[index] = float4(transmission, 0, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void lightDepthParts(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 ray = Inputs[index];
  const float4 direction = Directions[index];
  const CloudOpticalDepth depth = cloudLightOpticalDepth(Clouds, ray.xyz, direction.xyz, ray.w,
    Sampler2DArray<float4>(Weather), Sampler3D<float4>(Base), Sampler3D<float4>(Detail), lightingCache(), direction.w);
  Results[index] = float4(depth.local, depth.external, depth.total, max(4u, 2u * Clouds.lightSteps));
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

// Independent fine-step reference: it reads the production field directly and
// does not use the cached transport implementation or its quality parameter.
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
        Sampler3D<float4>(Base), Sampler3D<float4>(Detail), span,
        cloudHeightFootprint(Clouds, position, direction, span));
      if (opticalDepth > 20)
      {
        Results[index] = float4(exp(-opticalDepth), 0, 0, 0);
        return;
      }
    }
  }
  Results[index] = float4(exp(-opticalDepth), 0, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void heightFootprints(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 input = Inputs[index];
  const float4 direction = Directions[index];
  Results[index] = float4(
    cloudHeightFootprint(Clouds, input.xyz, direction.xyz, input.w, direction.w, 0u),
    cloudHeightFootprint(Clouds, input.xyz, direction.xyz, input.w, direction.w, 1u),
    cloudHeightFootprint(Clouds, input.xyz, direction.xyz, input.w, 0, 1u),
    cloudHeightFootprint(Clouds, input.xyz, direction.xyz, input.w, direction.w));
}
