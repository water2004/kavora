/* SPDX-License-Identifier: MIT */
#include "rtx/pass/volumetrics/volume_sky_source.slangh"

layout(binding = VOLUME_SKY_CDF_BINDING)
RWTexture2D<float> VolumeSkyCdf;

[shader("compute")]
[numthreads(VOLUME_SKY_GROUP_SIZE, 1, 1)]
void main(uint lane : SV_GroupIndex, uint3 group : SV_GroupID)
{
  const uint row = group.x;
  const float solidAngle = kVolumeSkyAngle *
    (cos(float(row) * kVolumeSkyAngle) - cos(float(row + 1) * kVolumeSkyAngle));
  float weight = 0.0f;
  if (lane < kVolumeSkyWidth)
  {
    weight = calcBt709Luminance(volumeSkyTexel(uint2(lane, row))) * solidAngle;
  }
  const float prefix = volumeSkyInclusiveScan(lane, weight);
  if (lane < kVolumeSkyWidth)
  {
    VolumeSkyCdf[uint2(lane, row)] = prefix;
  }
}
