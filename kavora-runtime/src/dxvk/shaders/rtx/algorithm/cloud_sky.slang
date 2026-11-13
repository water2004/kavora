/* SPDX-License-Identifier: MIT */
module cloud_sky;

import "rtx/pass/cloud_parameters.slang";

static const float kCloudSkyPi = 3.141592653589793f;

public float3 cloudSkyDirection(float2 uv, float3 up)
{
  const float longitude = (uv.x - 0.5f) * (2.0f * kCloudSkyPi);
  const float latitude = uv.y * kCloudSkyPi;
  const float3 direction = float3(sin(latitude) * cos(longitude), cos(latitude), sin(latitude) * sin(longitude));
  return up.z > 0.5f ? direction.xzy : direction;
}

public float2 cloudSkyUv(CloudArgs args, float3 direction)
{
  const float3 local = args.up.z > 0.5f ? direction.xzy : direction;
  const float halfTexel = 0.5f / float(args.skyResolution);
  return float2(atan2(local.z, local.x) / (2.0f * kCloudSkyPi) + 0.5f,
    clamp(acos(clamp(local.y, -1.0f, 1.0f)) / kCloudSkyPi, halfTexel, 1.0f - halfTexel));
}

public float4 sampleCloudSky(CloudArgs args, Sampler2D<float4> sky, float3 direction)
{
  if (!args.enable || args.transportMode != 1)
  {
    return float4(0, 0, 0, 1);
  }
  return sky.SampleLevel(cloudSkyUv(args, direction), 0);
}

public float3 applyCloudSky(CloudArgs args, Sampler2D<float4> sky, float3 direction, float3 background)
{
  const float4 cloud = sampleCloudSky(args, sky, direction);
  return cloud.rgb + cloud.a * background;
}
