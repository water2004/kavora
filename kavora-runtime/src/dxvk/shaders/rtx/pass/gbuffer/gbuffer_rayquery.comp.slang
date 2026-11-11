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

#include "rtx/concept/surface_material/surface_material_hitgroup.h"

//!variant-matrix gbuffer_rayquery
//!> common RAY_TRACING_PRIMARY_RAY
//!> axis features no_psr GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=0 GBUFFER_FEATURE_PSR_REPLACEMENT=0 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=0 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features no_psr_raytraced_render_target GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=1 GBUFFER_FEATURE_STOCHASTIC_ALPHA=0 GBUFFER_FEATURE_PSR_REPLACEMENT=0 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=0 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features no_psr_stochastic_alpha GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=1 GBUFFER_FEATURE_PSR_REPLACEMENT=0 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=0 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features no_psr_raytraced_render_target_stochastic_alpha GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=1 GBUFFER_FEATURE_STOCHASTIC_ALPHA=1 GBUFFER_FEATURE_PSR_REPLACEMENT=0 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=0 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features psr_inline GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=0 GBUFFER_FEATURE_PSR_REPLACEMENT=1 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=1 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features psr_inline_raytraced_render_target GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=1 GBUFFER_FEATURE_STOCHASTIC_ALPHA=0 GBUFFER_FEATURE_PSR_REPLACEMENT=1 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=1 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features psr_inline_stochastic_alpha GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=1 GBUFFER_FEATURE_PSR_REPLACEMENT=1 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=1 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features psr_inline_raytraced_render_target_stochastic_alpha GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=1 GBUFFER_FEATURE_STOCHASTIC_ALPHA=1 GBUFFER_FEATURE_PSR_REPLACEMENT=1 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=1 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features psr_prepare GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=0 GBUFFER_FEATURE_PSR_REPLACEMENT=1 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=0 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features psr_prepare_stochastic_alpha GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=1 GBUFFER_FEATURE_PSR_REPLACEMENT=1 GBUFFER_FEATURE_INLINE_PSR_SAMPLING=0 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features debug GBUFFER_FEATURE_DEBUG_VIEW=1 GBUFFER_FEATURE_OBJECT_PICKING=1
//!> axis phase -
//!> axis phase continue GBUFFER_PRIMARY_CONTINUATION=1
//!> axis radiance_cache -
//!> axis radiance_cache nrc ENABLE_NRC=1
//!> axis radiance_cache sharc SHARC_UPDATE=1
//!> axis wboit -
//!> axis wboit wboit UNORDERED_RESOLVE_WBOIT
//!> order features phase radiance_cache wboit
//!end-variants

// Note: Opacity lighting approximation used in the GBuffer pass due to it being most visually
// prominent on particles in primary rays and important in PSR.
#define RESOLVE_OPACITY_LIGHTING_APPROXIMATION 1
// Note: Use thin film in the GBuffer pass for its usage in PSR on mirrorlike surfaces.
#define OPAQUE_MATERIAL_USE_THIN_FILM 1
#define OPAQUE_MATERIAL_USE_POM 1

#define RESOLVER_USE_VOLUMETRIC_ATTENUATION 1
#define RESOLVER_PRECOMPUTED_PRIMARY_ATMOSPHERE 1

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
#define WATER_SIM_SAMPLE 1
#define GBUFFER_FG_GUIDE 1
#define GBUFFER_FG_FOREGROUND_GUIDE 1
#include "rtx/pass/gbuffer/gbuffer_bindings.slangh"
#include "rtx/algorithm/geometry_resolver_state.slangh"

#include "rtx/concept/camera/camera.slangh"
#include "rtx/algorithm/geometry_resolver.slangh"
#include "rtx/pass/gbuffer/primary_queue.slangh"

[shader("compute")]
#ifdef GBUFFER_PRIMARY_CONTINUATION
[numthreads(128, 1, 1)]
#else
[numthreads(16, 8, 1)]
#endif
void main(uint2 invocation : SV_DispatchThreadID)
{
  uint2 pixel;
  GeometryResolverState state;
  float remainingDistance;
  uint8_t iteration;
#ifdef GBUFFER_PRIMARY_CONTINUATION
  const uint index = invocation.x + invocation.y * GBUFFER_PSR_QUEUE_ROW_WIDTH;
  if (index >= PrimaryQueue[0]) {
    return;
  }
  const uint pixelIndex = PrimaryQueue[GBUFFER_PSR_QUEUE_HEADER_WORDS + index];
  pixel = uint2(pixelIndex % cb.camera.resolution.x, pixelIndex / cb.camera.resolution.x);
  resolverPixel = uint3(pixel, 0);
  uint seed;
  state = loadPrimaryResolveState(pixelIndex, remainingDistance, seed);
  resolveVertexUnorderedRng = createRNG(pixel, cb.frameIdx);
  resolveVertexUnorderedRng.seed = seed;

  RayDesc ray;
  ray.TMin = 0.0f;
  ray.TMax = remainingDistance;
  const uint flags = primaryRayFlags();
  PrimaryResolveArgs extraArgs = { false };
  for (iteration = uint8_t(2);; ++iteration) {
    state.continueResolving = iteration < cb.primaryRayMaxInteractions;
    ray.Origin = state.origin;
    ray.Direction = state.direction;
    const RayHitInfo hit = traceOrderedIntersection(ray, flags, uint(state.rayMask));
    geometryResolverVertex(extraArgs, hit, state);
    if (!state.continueResolving) {
      break;
    }
    ray.TMax = max(ray.TMax - state.segmentHitDistance, 0.0f);
  }
#else
  if (any(invocation >= cb.camera.resolution)) {
    return;
  }
  pixel = invocation;
  state = geometryResolverBegin(ivec2(pixel));
  Ray primaryRay;
  preprocessPrimaryRayState(primaryRay, state);
  state.origin = primaryRay.origin;
  state.coneRadius = primaryRay.coneRadius;
  state.direction = primaryRay.direction;
  state.continueResolving = uint8_t(1) < cb.primaryRayMaxInteractions;
  PrimaryResolveArgs extraArgs = { true };
  geometryResolverVertex(extraArgs, loadPrimaryIntersection(pixel), state);
  if (state.continueResolving) {
    remainingDistance = max(primaryRay.tMax - state.segmentHitDistance, 0.0f);
    const uint pixelIndex = pixel.y * cb.camera.resolution.x + pixel.x;
    storePrimaryResolveState(pixelIndex, state, remainingDistance, resolveVertexUnorderedRng.seed);
    enqueuePrimaryPixel(pixelIndex);
    return;
  }
  iteration = uint8_t(1);
#endif
  if (gbufferDebugViewEnabled) {
    if (cb.debugView == DEBUG_VIEW_PRIMARY_RAY_INTERACTIONS || cb.debugView == DEBUG_VIEW_PRIMARY_RAY_AND_UNORDERED_INTERACTIONS) {
      accumulateInDebugViewAdd(pixel, iteration);
    }
    if (cb.debugView == DEBUG_VIEW_PRIMARY_RAY_BOUNCES) {
      accumulateInDebugViewAdd(pixel, 1);
    }
  }
  geometryResolverEnd(state);
}
