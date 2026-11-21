/* SPDX-License-Identifier: MIT */
#include "rtx/pass/volumetrics/volume_sky_source.slangh"

layout(binding = VOLUME_SKY_CDF_BINDING)
Texture2D<float> VolumeSkyCdf;
layout(binding = VOLUME_SKY_SAMPLES_BINDING)
RWTexture2D<float4> VolumeSkySamples;

uint findSkyRow(float target)
{
  uint first = 0;
  uint last = kVolumeSkyHeight - 1;
  while (first < last)
  {
    const uint middle = (first + last) / 2;
    if (VolumeSkyPrefix[middle] <= target)
    {
      first = middle + 1;
    }
    else
    {
      last = middle;
    }
  }
  return first;
}

uint findSkyColumn(float target, uint row)
{
  uint first = 0;
  uint last = kVolumeSkyWidth - 1;
  while (first < last)
  {
    const uint middle = (first + last) / 2;
    if (VolumeSkyCdf[uint2(middle, row)] <= target)
    {
      first = middle + 1;
    }
    else
    {
      last = middle;
    }
  }
  return first;
}

[shader("compute")]
[numthreads(VOLUME_SKY_GROUP_SIZE, 1, 1)]
void main(uint sample : SV_DispatchThreadID, uint lane : SV_GroupIndex)
{
  const float rowTotal = lane < kVolumeSkyHeight ? VolumeSkyCdf[uint2(kVolumeSkyWidth - 1, lane)] : 0.0f;
  volumeSkyInclusiveScan(lane, rowTotal);
  const float total = VolumeSkyPrefix[kVolumeSkyHeight - 1];
  float3 direction = 0;
  float3 contribution = 0;
  if (total > 0.0f)
  {
    // A fixed Hammersley set samples the joint texel CDF, amortizing its
    // inversion across all froxels. Remap inside the selected texel in solid angle.
    const float rowTarget = total * ((float(sample) + 0.5f) / VOLUME_SKY_SAMPLE_COUNT);
    const uint row = findSkyRow(rowTarget);
    const float rowStart = row > 0 ? VolumeSkyPrefix[row - 1] : 0.0f;
    const float columnTotal = VolumeSkyCdf[uint2(kVolumeSkyWidth - 1, row)];
    const float columnTarget = columnTotal * (float(reversebits(sample)) / 4294967296.0f +
      0.5f / VOLUME_SKY_SAMPLE_COUNT);
    const uint column = findSkyColumn(columnTarget, row);
    const float columnStart = column > 0 ? VolumeSkyCdf[uint2(column - 1, row)] : 0.0f;
    const float columnEnd = VolumeSkyCdf[uint2(column, row)];
    const float u = (float(column) + saturate((columnTarget - columnStart) / (columnEnd - columnStart))) / kVolumeSkyWidth;
    const float rowFraction = saturate((rowTarget - rowStart) / (VolumeSkyPrefix[row] - rowStart));
    const float cosLatitude = lerp(cos(float(row) * kVolumeSkyAngle), cos(float(row + 1) * kVolumeSkyAngle), rowFraction);
    const float v = acos(clamp(cosLatitude, -1.0f, 1.0f)) / 3.141592653589793f;
    direction = cloudSkyDirection(float2(u, v), cb.volumeArgs.clouds.up);

    // p(direction) = luma(texel) / integral(luma). The contribution's luma is
    // therefore the same even when the sample lands on a bright horizon lobe.
    const float3 radiance = volumeSkyTexel(uint2(column, row));
    contribution = radiance * (total / calcBt709Luminance(radiance));
  }
  VolumeSkySamples[uint2(sample, 0)] = float4(direction, 0);
  VolumeSkySamples[uint2(sample, 1)] = float4(contribution, 0);
}
