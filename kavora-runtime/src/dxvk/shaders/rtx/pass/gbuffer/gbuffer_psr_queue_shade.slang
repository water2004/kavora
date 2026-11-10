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
//!variant-matrix gbuffer_psr_queue_shade
//!> axis features - GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features debug GBUFFER_FEATURE_DEBUG_VIEW=1 GBUFFER_FEATURE_OBJECT_PICKING=1
//!> axis features lean_psr GBUFFER_FEATURE_DLSSRR=0 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=0 GBUFFER_FEATURE_PSR_REPLACEMENT=1 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features lean_no_psr GBUFFER_FEATURE_DLSSRR=0 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=0 GBUFFER_FEATURE_PSR_REPLACEMENT=0 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features rr_psr GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=0 GBUFFER_FEATURE_PSR_REPLACEMENT=1 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis features rr_no_psr GBUFFER_FEATURE_DLSSRR=1 GBUFFER_FEATURE_OBJECT_PICKING=0 GBUFFER_FEATURE_RAYTRACED_RENDER_TARGET=0 GBUFFER_FEATURE_STOCHASTIC_ALPHA=0 GBUFFER_FEATURE_PSR_REPLACEMENT=0 GBUFFER_FEATURE_DEBUG_VIEW=0
//!> axis phase -
//!> axis phase start PSR_QUEUE_INITIAL
//!> axis pipeline raygen.rgen RAY_PIPELINE
//!> axis pipeline raygen_ser.rgen RAY_PIPELINE RT_SHADER_EXECUTION_REORDERING
//!> axis radiance_cache -
//!> axis radiance_cache nrc ENABLE_NRC=1
//!> axis radiance_cache sharc SHARC_UPDATE=1
//!> axis wboit -
//!> axis wboit wboit UNORDERED_RESOLVE_WBOIT
//!> order features phase pipeline radiance_cache wboit
//!end-variants
#define GBUFFER_PSR 1
#define GBUFFER_PSR_WAVEFRONT 1
#if !defined(GBUFFER_FEATURE_PSR_REPLACEMENT) || GBUFFER_FEATURE_PSR_REPLACEMENT
#define RESOLVE_POM_TRANSMISSION_SILHOUETTES 1
#endif
#ifdef RT_SHADER_EXECUTION_REORDERING
#define RT_TRACE_RAY 1
#define GBUFFER_TRACE_HIT_OBJECT 1
#endif
#define RAY_TRACING_PRIMARY_RAY 1
#define RESOLVE_OPACITY_LIGHTING_APPROXIMATION 1
#define OPAQUE_MATERIAL_USE_THIN_FILM 1
#define OPAQUE_MATERIAL_USE_POM 1
#define RESOLVER_USE_VOLUMETRIC_ATTENUATION 1
#define RESOLVER_ATMOSPHERE_CALLABLE 1
#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/common_bindings.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
#include "rtx/pass/gbuffer/gbuffer_bindings.slangh"
#include "rtx/concept/camera/camera.slangh"
#include "rtx/pass/gbuffer/psr_queue.slangh"

#include "rtx/algorithm/geometry_resolver.slangh"

RayHitInfo traceQueuedPSRIntersection(RayDesc ray, uint flags, uint mask)
{
#ifdef RT_SHADER_EXECUTION_REORDERING
  return traceGbufferIntersection(ray, flags, mask, GBUFFER_SBT_OFFSET_STANDARD);
#else
  return traceOrderedIntersection(ray, flags, mask);
#endif
}

RayHitInfo traceQueuedPSRState(GeometryPSRResolverState state)
{
  RayDesc ray;
  ray.Origin = state.origin;
  ray.Direction = state.direction;
  ray.TMin = 0;
  ray.TMax = floatMax;
  const uint flags = RAY_FLAG_FORCE_OPAQUE | (state.insideMedium ? 0 : RAY_FLAG_CULL_BACK_FACING_TRIANGLES);
  return traceQueuedPSRIntersection(ray, flags, uint(state.rayMask));
}

[shader("raygeneration")]
void main()
{
  const uint2 invocation = DispatchRaysIndex().xy;
#ifdef PSR_QUEUE_INITIAL
  if (any(invocation >= cb.camera.resolution)) return;
  const uint pixelIndex = psrPixelIndex(invocation);
  resolverPixel = uint3(invocation, 0);
  const uint seedOffset = pixelIndex * GBUFFER_PSR_STATE_WORDS + 17;
  if (!push.psrTransmission)
  {
    // Transmission also needs the initial seed when reflection has no path.
    PathStorage[seedOffset] = createRNG(invocation, cb.frameIdx, 555).seed;
  }
  InitialPSRData initial;
  if (!loadInitialPSRData(ivec2(invocation), push.psrTransmission != 0, initial)) return;
  RayHitInfo firstHit;
  {
    const GeometryPSRResolverState rayState = decodeInitialPSRState(initial, push.psrTransmission != 0);
    firstHit = traceQueuedPSRState(rayState);
  }
  GeometryPSRResolverState state = geometryPSRResolverBegin(ivec2(invocation), push.psrTransmission != 0, initial);
#else
  const uint index = psrQueueIndex(invocation);
  if (index >= PSRQueueInput[0]) return;
  const uint pixelIndex = PSRQueueInput[GBUFFER_PSR_QUEUE_HEADER_WORDS + index];
  // Only the ray and pixel key are needed until traversal and reordering finish.
  RayHitInfo firstHit;
  {
    const GeometryPSRResolverState rayState = loadPSRState(pixelIndex);
    firstHit = traceQueuedPSRState(rayState);
  }
  GeometryPSRResolverState state = loadPSRState(pixelIndex);
#endif
  const uint2 pixel = psrPixelCoordinate(pixelIndex);
  resolverPixel = uint3(pixel, 0);
  resolveVertexUnorderedRng = createRNG(pixel, cb.frameIdx);
  resolveVertexUnorderedRng.seed = PathStorage[pixelIndex * GBUFFER_PSR_STATE_WORDS + 17];
  ++state.bounceIteration;
  state.continuePSRResolving = false;
  Ray ray;
  ray.origin = state.origin;
  ray.coneRadius = state.coneRadius;
  ray.direction = state.direction;
  ray.tMax = floatMax;
  const uint flags = RAY_FLAG_FORCE_OPAQUE | (state.insideMedium ? 0 : RAY_FLAG_CULL_BACK_FACING_TRIANGLES);
  EmptyExtraArgs extraArgs;
  RESOLVE_RAY_WITH_INITIAL_HIT(traceQueuedPSRIntersection, true, firstHit,
    extraArgs, ray, flags, geometryPSRResolverVertex,
    cb.psrRayMaxInteractions, state, state.segmentHitDistance,
    DEBUG_VIEW_PRIMARY_RAY_INTERACTIONS, DEBUG_VIEW_PRIMARY_RAY_AND_UNORDERED_INTERACTIONS, GBUFFER_FEATURE_DEBUG_VIEW);
  PathStorage[pixelIndex * GBUFFER_PSR_STATE_WORDS + 17] = resolveVertexUnorderedRng.seed;
  if (state.continuePSRResolving)
  {
    storePSRState(pixelIndex, state, resolveVertexUnorderedRng.seed);
    enqueuePSRPixel(pixelIndex);
  }
  else
  {
    if (GBUFFER_FEATURE_DEBUG_VIEW && cb.debugView == DEBUG_VIEW_PRIMARY_RAY_BOUNCES)
    {
      accumulateInDebugViewAdd(pixel, state.bounceIteration);
    }
    geometryPSRResolverEnd(state);
  }
}
