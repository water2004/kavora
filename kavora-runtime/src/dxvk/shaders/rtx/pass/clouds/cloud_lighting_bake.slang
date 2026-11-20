/* SPDX-License-Identifier: MIT */
module cloud_lighting_bake;

import "rtx/pass/cloud_parameters.slang";
import "rtx/utility/cloud_density.slang";
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/algorithm/cloud_direct_light.slang";
#include "rtx/pass/clouds/cloud_lighting_cache.h"

struct CloudLocalColumn
{
  float bottom;
  float span;
  float total;
  uint steps;
  float extinction[CLOUD_LIGHTING_MAX_QUALITY];
  float depthAtCenter[CLOUD_LIGHTING_MAX_QUALITY];
};

CloudLocalColumn prepareCloudLocalColumn(CloudArgs args, uint layer, float3 position, Sampler2DArray<float4> weatherSampler,
  Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  const CloudDensityArgs densityArgs = cloudDensityParameters(args);
  const float altitude = cloudAltitude(densityArgs, position);
  const float3 radialUp = normalize(position - args.groundReference + args.planetRadius * args.up);
  const uint steps = layer == 0 ? clamp(args.lightSteps, 2u, uint(CLOUD_LIGHTING_MAX_QUALITY)) : 2u;
  const float3 coordinates = cloudCoordinates(densityArgs, position);
  CloudColumn column = {};
  float thickness = args.highThickness;
  if (layer == 0)
  {
    // Keep weather and its support fixed across the local plane-parallel column.
    column = cloudColumn(densityArgs, coordinates.xz, weatherSampler, args.thickness / float(steps));
    thickness = cloudColumnThickness(densityArgs, column, weatherSampler);
  }
  CloudLocalColumn result;
  result.bottom = cloudLayerBase(args, layer);
  result.span = thickness / float(steps);
  result.steps = steps;
  float depth = 0.0f;
  float previous = 0.0f;
  for (uint cell = 0; cell < steps; cell++)
  {
    const float height = result.bottom + (float(cell) + 0.5f) * result.span;
    const float3 samplePosition = position + radialUp * (height - altitude);
    const float extinction = layer == 0
      ? args.extinction * cloudDensityInColumn(densityArgs, cloudCoordinates(densityArgs, samplePosition), column,
          weatherSampler, baseSampler, detailSampler, result.span, result.span)
      : cloudExtinction(args, samplePosition, weatherSampler, baseSampler, detailSampler, 0, result.span);
    depth += 0.5f * result.span * (previous + extinction);
    result.extinction[cell] = extinction;
    result.depthAtCenter[cell] = depth;
    previous = extinction;
  }
  result.total = depth + 0.5f * result.span * previous;
  return result;
}

float2 cloudVerticalOpticalDepth(CloudLocalColumn column, float altitude)
{
  if (column.total <= 0.0f)
  {
    return 0;
  }
  const float coordinate = clamp((altitude - column.bottom) / column.span - 0.5f, -0.5f, float(column.steps) - 0.5f);
  const int center = int(floor(coordinate));
  const uint lower = uint(max(center, 0));
  const uint upper = uint(clamp(center + 1, 0, int(column.steps) - 1));
  const float fraction = coordinate - float(lower);
  const float a = column.extinction[lower];
  const float b = column.extinction[upper];
  const float below = clamp(column.depthAtCenter[lower] + column.span * fraction
    * (a + 0.5f * fraction * (b - a)), 0.0f, column.total);
  return float2(column.total - below, below);
}

public void buildCloudLocalLightColumn(CloudArgs args, uint2 index, uint layer,
  Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail,
  RWTexture3D<float2> output)
{
  if (!cloudLayerEnabled(args, layer))
  {
    return;
  }
  const uint samples = cloudLocalLightHeightSamples(layer);
  const uint offset = cloudLocalLightHeightOffset(layer);
  const float bottom = cloudLayerBase(args, layer);
  const float thickness = cloudLayerTop(args, layer) - bottom;
  const float2 uv = (float2(index) + 0.5f) / float(CLOUD_LOCAL_LIGHT_RESOLUTION);
  const float3 center = cloudLocalLightPosition(args, uv, bottom + 0.5f * thickness);
  const CloudLocalColumn column = prepareCloudLocalColumn(args, layer, center, weather, base, detail);
  for (uint y = 0; y < samples; y++)
  {
    const float altitude = bottom + (float(y) + 0.5f) * thickness / float(samples);
    output[uint3(index.x, offset + y, index.y)] = cloudVerticalOpticalDepth(column, altitude);
  }
}

public float4 buildCloudDirectLightCell(CloudArgs args, uint3 index, Sampler2DArray<float4> weather,
  Sampler3D<float4> base, Sampler3D<float4> detail, CloudLightingCache cache)
{
  const uint layer = index.y < CLOUD_DIRECT_LIGHT_LOW_HEIGHT ? 0u : 1u;
  if (!cloudLayerEnabled(args, layer))
  {
    return 0;
  }
  const uint cascade = layer == 1 ? (index.y - CLOUD_DIRECT_LIGHT_LOW_HEIGHT) / CLOUD_DIRECT_LIGHT_HIGH_SAMPLES
    : index.y < cloudDirectLightHeightOffset(0, 1) ? 0 : index.y < cloudDirectLightHeightOffset(0, 2) ? 1 : 2;
  const uint sampleIndex = index.y - cloudDirectLightHeightOffset(layer, cascade);
  const float fraction = (float(sampleIndex) + 0.5f) / float(cloudDirectLightHeightSamples(layer, cascade));
  const float altitude = lerp(cloudLayerBase(args, layer), cloudLayerTop(args, layer), fraction);
  const CloudDirectLightGrid grid = cloudDirectLightGrid(args, cascade);
  const float3 position = cloudLightPositionAtAltitude(args, grid.position(index.xz), altitude);
  const float aboveDepth = cache.local.SampleLevel(cloudLocalLightCoordinates(args, position, altitude, layer), 0).x;
  const float extinction = cloudExtinction(args, position, weather, base, detail);
  const float3 radialUp = normalize(position - args.groundReference + args.planetRadius * args.up);
  float4 beam = 0;
  [unroll]
  for (uint lightIndex = 0; lightIndex < 2; lightIndex++)
  {
    const CloudLight light = args.lights[lightIndex];
    if (cloudLightHasEnergy(light))
    {
      const float cosine = dot(light.directionToLight, radialUp);
      const float localBeamDepth = cosine > 0 && args.groundAlbedo > 0 ? aboveDepth / cosine : 0;
      const float segment = cloudDirectSegment(args, altitude, layer, cosine);
      const float2 transmitted = traceCloudDirectLight(args, position + segment * light.directionToLight,
        lightIndex, localBeamDepth - segment * extinction,
        weather, base, detail, cache);
      beam[2 * lightIndex] = transmitted.x;
      beam[2 * lightIndex + 1] = transmitted.y;
    }
  }
  return beam;
}
