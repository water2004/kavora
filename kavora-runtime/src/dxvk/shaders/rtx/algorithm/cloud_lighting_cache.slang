// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
module cloud_lighting_cache;

import "rtx/pass/cloud_parameters.slang";
#include "rtx/pass/clouds/cloud_lighting_cache.h"

// The local atlas stores vertical optical depths for diffuse illumination.
public struct CloudLightingCache
{
  public Sampler3D<float2> local;
  public Sampler3D<float4> neural;
};

[ForceInline]
float3 cloudSecondHorizontalAxis(CloudArgs args)
{
  return args.up.z > 0.5f ? float3(0, 1, 0) : float3(0, 0, 1);
}

public float2 cloudLightHorizontal(CloudArgs args, float3 position)
{
  return float2(position.x, dot(position, cloudSecondHorizontalAxis(args)));
}

public float cloudLightGridCoverage(float2 uv)
{
  const float2 edge = abs(uv - 0.5f);
  return 1.0f - smoothstep(0.375f, 0.5f, max(edge.x, edge.y));
}

[ForceInline]
public float cloudLightingRadius(CloudArgs args)
{
  return max(args.lightMaxDistance, 4.0f * args.shapeScale);
}

public uint cloudLocalLightHeightSamples(uint layer)
{
  return layer == 0 ? CLOUD_LOCAL_LIGHT_HEIGHT_SAMPLES : CLOUD_LOCAL_LIGHT_HIGH_HEIGHT_SAMPLES;
}

public uint cloudLocalLightHeightOffset(uint layer)
{
  return layer == 0 ? 0 : CLOUD_LOCAL_LIGHT_HEIGHT_SAMPLES;
}

public float cloudLocalLightRadius(CloudArgs args, uint layer)
{
  return max(layer == 0 ? args.maxDistance : args.highMaxDistance, cloudLightingRadius(args));
}

public float3 cloudLocalLightCoordinates(CloudArgs args, float3 position, float altitude, uint layer)
{
  const float diameter = 2.0f * cloudLocalLightRadius(args, layer);
  const float edge = 0.5f / float(CLOUD_LOCAL_LIGHT_RESOLUTION);
  const float2 uv = clamp(cloudLightHorizontal(args, position - args.groundReference) / diameter + 0.5f,
    edge, 1.0f - edge);
  const float layerBase = layer == 0 ? args.baseHeight : args.highBaseHeight;
  const float thickness = layer == 0 ? args.thickness : args.highThickness;
  const float samples = float(cloudLocalLightHeightSamples(layer));
  // Keep interpolation within the selected shell at the top and bottom.
  const float slice = clamp((altitude - layerBase) / thickness * samples, 0.5f, samples - 0.5f);
  return float3(uv.x, (cloudLocalLightHeightOffset(layer) + slice) / CLOUD_LOCAL_LIGHT_PACKED_HEIGHT, uv.y);
}

public float3 cloudLocalLightPosition(CloudArgs args, float2 uv, float altitude, uint layer)
{
  const float2 horizontal = (uv - 0.5f) * (2.0f * cloudLocalLightRadius(args, layer));
  return cloudLightPositionAtAltitude(args, horizontal, altitude);
}

public float3 cloudLightPositionAtAltitude(CloudArgs args, float2 horizontal, float altitude)
{
  const float radius = args.planetRadius + altitude;
  const float horizontalSquared = dot(horizontal, horizontal);
  const float offset = horizontalSquared / (radius + sqrt(max(0.0f, radius * radius - horizontalSquared)));
  return args.groundReference + float3(horizontal.x, 0, 0) + horizontal.y * cloudSecondHorizontalAxis(args)
    + args.up * (altitude - offset);
}

public bool cloudLightHasEnergy(CloudLight light)
{
  if (any(light.irradiance > 0))
  {
    return true;
  }
  if (light.hasRadianceProfile != 0)
  {
    [unroll]
    for (uint sample = 0; sample < 4; sample++)
    {
      if (any(light.radianceProfile[sample].rgb > 0))
      {
        return true;
      }
    }
  }
  return false;
}
