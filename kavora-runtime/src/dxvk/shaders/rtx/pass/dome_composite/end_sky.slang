/* SPDX-License-Identifier: LGPL-3.0-or-later */
module dome_end_sky;

import "rtx/pass/dome_composite/caustica_end.slang";

public float3 endSkyRadiance(float4 rotation, float4 intensity, float4 endParameters,
  Sampler2D<float3> background, float3 direction)
{
  const float3 view = intensity.w > 0 ? direction.xzy : direction;
  const float3 horizontal = float3(rotation.x * view.x - rotation.y * view.z, view.y,
    rotation.y * view.x + rotation.x * view.z);
  const float3 local = float3(horizontal.x, rotation.z * horizontal.y - rotation.w * horizontal.z,
    rotation.w * horizontal.y + rotation.z * horizontal.z);
  return endRadiance(local, endParameters.x, background, endParameters.y, endParameters.z) * intensity.y;
}
