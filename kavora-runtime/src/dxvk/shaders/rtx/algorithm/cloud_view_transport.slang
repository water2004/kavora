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

public float3 composeCloudMoments(float3 first, float3 second)
{
  return first + (1.0f - first.y) * second;
}

// Raw premultiplied radiance, opacity and signed centroid distance. Reproject
// each observation once so a moving camera cannot recursively blur cloud detail.
public struct CloudHistoryGather
{
  public float4 sum;
  public float weight;
  public float depthCorrection;

  public static uint2 encode(float4 transfer, float signedDepth)
  {
    const uint3 h = f32tof16(clamp(transfer.rgb, 0.0f, 64000.0f));
    const uint radiance = ((h.x + 8) >> 4) | (((h.y + 8) >> 4) << 11) | (((h.z + 16) >> 5) << 22);
    // The distance scale covers a complete planetary ray in signed FP16.
    return uint2(radiance, f32tof16(transfer.w) | (f32tof16(signedDepth / 1024.0f) << 16));
  }

  [mutating] public void add(uint2 entry, float expectedSignedDepth, float4 current,
    float parallaxInColumns, float bilinear, float cosine)
  {
    const float4 old = float4(f16tof32((entry.x & 2047u) << 4), f16tof32(((entry.x >> 11) & 2047u) << 4),
      f16tof32((entry.x >> 22) << 5), f16tof32(entry.y & 0xffffu));
    const float oldDepth = 1024.0f * f16tof32(entry.y >> 16);
    // Reprojection also mixes adjacent columns during rotation. Optical coverage
    // downweights a stale opaque silhouette and retains a weak quadrature miss.
    const float disparity = max(1.0f, parallaxInColumns) * abs(oldDepth - expectedSignedDepth) / max(abs(oldDepth), 1e-6f);
    const float confidence = (oldDepth * expectedSignedDepth > 0.0f) ? saturate(1.0f - disparity) : 0.0f;
    const float coverage = 1.0f - abs(old.w - current.w);
    // Compare source colors after removing the opacity difference. Thin
    // quadrature samples supply too little color evidence for strong rejection.
    const float3 previousColor = old.rgb * current.w;
    const float3 currentColor = current.rgb * old.w;
    const float colorDifference = dot(abs(previousColor - currentColor), float3(1.0f));
    const float colorEnergy = dot(previousColor + currentColor, float3(1.0f));
    const float agreement = 1.0f - min(old.w, current.w) * colorDifference / max(colorEnergy, 1e-6f);
    const float support = coverage * agreement;
    const float samples = bilinear * confidence * support * support;
    sum += old * samples;
    depthCorrection += old.w * samples * (abs(oldDepth) - abs(expectedSignedDepth)) * cosine;
    weight += samples;
  }

  public float4 resolve(float4 current, inout float depth, float2 arc)
  {
    if (sum.w > 0.0f)
    {
      // Project the opacity-weighted previous depth moment onto the current ray.
      depth = clamp(depth + depthCorrection / (current.w + sum.w), arc.x, arc.y);
    }
    return (current + sum) / (1.0f + weight);
  }
};

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

  uint layer;
  float3 ambient;
  CloudLightingCache lighting;
  CloudRayPhase phase;
  Sampler2DArray<float4> weather;
  Sampler3D<float4> base;
  Sampler3D<float4> detail;

  public __init(uint layer, float3 ambient, CloudLightingCache lighting,
    CloudRayPhase phase, Sampler2DArray<float4> weather, Sampler3D<float4> base, Sampler3D<float4> detail)
  {
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

  [mutating] public void add<let Column : bool>(CloudArgs args, float3 position, float sigma, float begin, float end)
  {
    const float span = end - begin;
    const float stepTransmission = exp(-sigma * span);
    const float2 cell = cloudCellMoments(sigma, begin, span, cloudMaxViewDistance(args));
    const float3 source = cloudIncidentRadiance<Column>(args, position, layer,
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
