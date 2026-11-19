// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
module cloud_view_transport;

import "rtx/algorithm/clouds.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/pass/cloud_parameters.slang";

public float2 composeCloudMoments(float2 first, float2 second)
{
  return first + (1.0f - first.y) * second;
}

// The exact first extinction moment of a constant quadrature cell. Absolute ray
// distances let prefix checkpoints split cells without changing the guide.
public float2 cloudCellMoments(float extinction, float begin, float length, float maxDistance)
{
  const float opticalDepth = extinction * length;
  if (opticalDepth <= 0.0f)
  {
    return 0;
  }
  float opacity;
  float localMoment;
  if (opticalDepth < 0.1f)
  {
    opacity = opticalDepth * (1.0f - opticalDepth * (0.5f - opticalDepth * (1.0f / 6.0f - opticalDepth / 24.0f)));
    localMoment = length * opticalDepth
      * (0.5f - opticalDepth * (1.0f / 3.0f - opticalDepth * (1.0f / 8.0f - opticalDepth / 30.0f)));
  }
  else
  {
    const float transmittance = exp(-opticalDepth);
    opacity = 1.0f - transmittance;
    localMoment = opacity / extinction - length * transmittance;
  }
  return float2((begin * opacity + localMoment) / max(maxDistance, 1e-6f), opacity);
}

public struct CloudViewTransfer : ICloudViewIntegral
{
  public float3 radiance;
  public float transmission;
  public float2 moments;

  float3 eye;
  uint layer;
  float3 ambient;
  CloudLightingCache lighting;
  CloudRayPhase phase;
  Sampler2DArray<float4> weather;
  Sampler3D<float4> base;
  Sampler3D<float4> detail;

  public __init(float3 eye, uint layer, float3 ambient, CloudLightingCache lighting,
    CloudRayPhase phase, Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail)
  {
    this.eye = eye;
    this.layer = layer;
    this.ambient = ambient;
    this.lighting = lighting;
    this.phase = phase;
    this.weather = weather;
    this.base = base;
    this.detail = detail;
    this.radiance = 0;
    this.transmission = 1;
    this.moments = 0;
  }

  [mutating] public void add(CloudArgs args, float3 position, float sigma, float begin, float end)
  {
    const float span = end - begin;
    const float stepTransmission = exp(-sigma * span);
    const float2 cell = cloudCellMoments(sigma, begin, span, cloudMaxViewDistance(args));
    const float3 source = cloudIncidentRadiance(args, position, length(position - eye), layer, sigma,
      ambient, weather, base, detail, lighting, phase);
    radiance += transmission * (args.albedo * cell.y) * source;
    transmission *= stepTransmission;
    moments = composeCloudMoments(moments, cell);
  }

  public bool opaque()
  {
    return transmission < kOpaqueCloudTransmittance;
  }
}
