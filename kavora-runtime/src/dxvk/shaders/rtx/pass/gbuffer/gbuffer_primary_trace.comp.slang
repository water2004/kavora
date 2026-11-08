/*
* Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/

#define RAY_TRACING_PRIMARY_RAY
#define GBUFFER_FEATURE_DEBUG_VIEW 0

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/gbuffer/primary_intersection.slangh"
#include "rtx/algorithm/ordered_intersection.slangh"
#include "rtx/pass/gbuffer/primary_atmosphere_cache.slangh"

[shader("compute")]
[numthreads(16, 8, 1)]
void main(uint2 pixel : SV_DispatchThreadID)
{
  if (any(pixel >= cb.camera.resolution))
  {
    return;
  }

  const Ray cameraRay = rayCreatePrimaryFromPixel(cb.camera, pixel);
  RayDesc ray;
  ray.Origin = cameraRay.origin;
  ray.Direction = cameraRay.direction;
  ray.TMin = 0.0f;
  ray.TMax = cameraRay.tMax;
  const RayHitInfo hit = traceOrderedIntersection(ray, primaryRayFlags(), primaryRayMask());
  storePrimaryIntersection(pixel, hit);
  if (cb.volumeArgs.enable || cb.volumeArgs.clouds.enable)
  {
    storeAtmosphereEndpoint(pixel, hit);
  }
}
