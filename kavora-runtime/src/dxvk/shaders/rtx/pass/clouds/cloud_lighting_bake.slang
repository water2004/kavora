/* SPDX-License-Identifier: MIT */
module cloud_lighting_bake;

import "rtx/pass/cloud_parameters.slang";
import "rtx/utility/cloud_density.slang";
import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/algorithm/cloud_scattering.slang";
import "rtx/utility/cloud_phase.slang";
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
  const float thickness = cloudLayerTop(args, layer) - cloudLayerBase(args, layer);
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
    const float extinction = cloudLayerExtinction(args, samplePosition, layer, weatherSampler, baseSampler, detailSampler);
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

// The diffuse exitance of a broken upper deck subtends a hemisphere at the
// top of the lower deck. Integrate it once per horizontal column. The narrow
// forward lobe still follows the actual light ray in cloudIncidentRadiance.
float2 upperDeckDiffuseFlux(CloudArgs args, float3 receiver, Sampler2DArray<float4> weather,
  Sampler3D<float4> base, Sampler3D<float4> detail)
{
  const uint samples = 16;
  const float altitude = args.highBaseHeight + 0.5f * args.highThickness;
  const float3 tangent = args.up.z > 0.5f ? float3(0, 1, 0) : float3(0, 0, 1);
  const CloudDensityArgs density = cloudDensityParameters(args);
  float2 flux = 0;
  for (uint i = 0; i < samples; ++i)
  {
    const float r = sqrt((float(i) + 0.5f) / float(samples));
    float s, c;
    sincos(float(i) * 2.39996323f, s, c);
    const float3 direction = r * (float3(c, 0, 0) + tangent * s) + args.up * sqrt(1.0f - r * r);
    const float distance = cloudSphereRoots(args, receiver, direction, altitude).y;
    const float3 position = receiver + direction * distance;
    const float tau = args.highThickness * args.highExtinction
      * cloudDensity(density, position, weather, base, detail, 1, true, distance * rsqrt(float(samples)));
    const float3 radialUp = normalize(position - args.groundReference + args.planetRadius * args.up);
    [unroll]
    for (uint light = 0; light < 2; ++light)
    {
      const float cosine = dot(args.lights[light].directionToLight, radialUp);
      if (cosine > 0)
      {
        const CloudBeamTransfer transfer = cloudBeamTransfer(tau, cloudMeanCosine(1), cosine);
        flux[light] += max(0.0f, transfer.downwardFlux - cosine * transfer.beam);
      }
    }
  }
  return flux / float(samples);
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
  if (layer == 0 && cloudLayerEnabled(args, 1) && args.multipleScattering > 0)
  {
    const float3 receiver = cloudLocalLightPosition(args, uv, cloudLayerTop(args, 0), 0);
    output[uint3(index.x, CLOUD_LOCAL_LIGHT_PACKED_HEIGHT - 1, index.y)] =
      upperDeckDiffuseFlux(args, receiver, weather, base, detail);
  }
  const float3 center = cloudLocalLightPosition(args, uv, bottom + 0.5f * thickness, layer);
  const CloudLocalColumn column = prepareCloudLocalColumn(args, layer, center, weather, base, detail);
  for (uint y = 0; y < samples; y++)
  {
    const float altitude = bottom + (float(y) + 0.5f) * thickness / float(samples);
    output[uint3(index.x, offset + y, index.y)] = cloudVerticalOpticalDepth(column, altitude);
  }
}
