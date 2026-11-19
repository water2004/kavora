/* SPDX-License-Identifier: MIT */
module cloud_sky;

import "rtx/pass/cloud_parameters.slang";
import "rtx/pass/volume_parameters.slang";
#include "rtx/pass/clouds/cloud_sky_binding_indices.h"

public static const uint kCloudSkyIrradianceLayer = 0;
public static const uint kCloudSkyTailRadianceLayer = 1;
public static const uint kCloudSkyTailTransmittanceLayer = 2;

static const float kCloudSkyPi = 3.141592653589793f;
public static const float kCloudSkyTexelAngle = kCloudSkyPi / float(CLOUD_SKY_RESOLUTION);
public static const uint kCloudAmbientSampleCount = 8192;

// Equal-area Hammersley nodes integrate upper-hemisphere radiance divided by 4 pi.
// The world-frame pattern stays fixed as the light directions change.
public struct CloudAmbientQuadrature
{
  public float3 up;
  public float3 tangent;
  public float3 bitangent;

  public float4 sample(uint index)
  {
    const float cosine = (float(index) + 0.5f) / float(kCloudAmbientSampleCount);
    const float sine = sqrt(1.0f - cosine * cosine);
    const float longitude = float(reversebits(index)) * (2.0f * kCloudSkyPi / 4294967296.0f);
    const float2 azimuth = float2(cos(longitude), sin(longitude));
    const float3 direction = cosine * up + sine * (azimuth.x * tangent + azimuth.y * bitangent);
    return float4(direction, 0.5f / float(kCloudAmbientSampleCount));
  }
};

public CloudAmbientQuadrature createCloudAmbientQuadrature(CloudArgs clouds)
{
  CloudAmbientQuadrature result;
  result.up = clouds.up;
  const float3 reference = abs(result.up.x) < 0.9f ? float3(1, 0, 0) : float3(0, 1, 0);
  result.tangent = normalize(reference - dot(reference, result.up) * result.up);
  result.bitangent = cross(result.tangent, result.up);
  return result;
}

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
  const float halfTexel = 0.5f / float(CLOUD_SKY_RESOLUTION);
  return float2(atan2(local.z, local.x) / (2.0f * kCloudSkyPi) + 0.5f,
    clamp(acos(clamp(local.y, -1.0f, 1.0f)) / kCloudSkyPi, halfTexel, 1.0f - halfTexel));
}

public float cloudSkyTailStart(VolumeArgs volume)
{
  const float fogDistance = volume.enable
    ? max(volume.froxelMaxDistance, volume.maxAttenuationDistanceForNoAtmosphere) : 0;
  // Leave room for paths which enter the fog interval from a secondary origin.
  return fogDistance + volume.clouds.shapeScale;
}

public float3 sampleCloudSkyIncidentRadiance(CloudArgs args, Sampler2DArray<float4> sky, float3 direction)
{
  return sky.SampleLevel(float3(cloudSkyUv(args, direction), kCloudSkyIrradianceLayer), 0).rgb;
}
