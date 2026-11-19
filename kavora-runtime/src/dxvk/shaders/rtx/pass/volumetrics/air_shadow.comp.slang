/* SPDX-License-Identifier: MIT */
#include "rtx/pass/common_bindings.slangh"
#include "rtx/concept/ray/ray.slangh"
#include "rtx/algorithm/ordered_intersection.slangh"
#include "rtx/algorithm/air_shadow_grid.slangh"
import "rtx/algorithm/air_medium.slang";

layout(binding = AIR_SHADOW_DEPTH_BINDING)
RWTexture2DArray<float> ShadowDepth;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 cell : SV_DispatchThreadID)
{
  const AirArgs air = cb.volumeArgs.air;
  float depth = -floatMax;
  if (any(air.lights[cell.z].irradiance > 0) && all(cell.xy > 0) && all(cell.xy < AIR_SHADOW_RESOLUTION - 1))
  {
    const AirShadowGrid grid = AirShadowGrid(air, cell.z);
    const float3 plane = cameraGetWorldPosition(cb.camera) + grid.offset(cell.xy);
    const float2 toward = airInterval(air, plane, grid.light);
    const float2 away = airInterval(air, plane, -grid.light);
    if (toward.y + away.y > 0)
    {
      RayDesc ray;
      ray.Origin = plane + toward.y * grid.light;
      ray.Direction = -grid.light;
      ray.TMin = 0;
      ray.TMax = toward.y + away.y;
      const RayHitInfo hit = traceOrderedIntersection(ray, 0, OBJECT_MASK_OPAQUE, 0, 0);
      if (hit.hasHit)
        depth = toward.y - hit.hitDistance;
    }
  }
  ShadowDepth[cell] = depth;
}
