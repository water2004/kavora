/* SPDX-License-Identifier: MIT */
module atlas_coordinates;

#include "rtx/utility/atlas_mip_bounds.h"

public struct AtlasSampleRegion
{
  float2 origin;
  float2 extent;
  float2 minimum;
  float2 maximum;
  bool enabled;

  public float2 wrap(float2 coordinates)
  {
    if (!enabled)
    {
      return coordinates;
    }
    const float2 wrapped = origin + frac((coordinates - origin) / extent) * extent;
    return clamp(wrapped, minimum, maximum);
  }
};

// Bounds have zero extent when a surface does not opt into atlas wrapping.
[ForceInline]
public AtlasSampleRegion atlasSampleRegionCreate(float4 bounds, float2 textureSize, float mip = 0.f)
{
  AtlasSampleRegion region = {};
  region.origin = bounds.xy;
  region.extent = bounds.zw - bounds.xy;
  region.enabled = all(region.extent > 0.f) && all(isfinite(bounds));
  if (region.enabled)
  {
    region.minimum = float2(AtlasSampling::firstCenter(bounds.x, textureSize.x, mip),
      AtlasSampling::firstCenter(bounds.y, textureSize.y, mip));
    region.maximum = float2(AtlasSampling::lastCenter(bounds.z, textureSize.x, mip),
      AtlasSampling::lastCenter(bounds.w, textureSize.y, mip));
  }
  return region;
}

[ForceInline]
public float2 wrapAtlasCoordinates(float2 coordinates, float4 bounds, float2 textureSize, float mip = 0.f)
{
  return atlasSampleRegionCreate(bounds, textureSize, mip).wrap(coordinates);
}

[ForceInline]
public float2 packAtlasBounds(float4 bounds)
{
  const uint lo = f32tof16(bounds.x) | (f32tof16(bounds.y) << 16);
  const uint hi = f32tof16(bounds.z) | (f32tof16(bounds.w) << 16);
  return asfloat(uint2(lo, hi));
}

[ForceInline]
public float4 unpackAtlasBounds(float2 packed)
{
  const uint2 bits = asuint(packed);
  return float4(f16tof32(bits.x & 0xffff), f16tof32(bits.x >> 16),
    f16tof32(bits.y & 0xffff), f16tof32(bits.y >> 16));
}
