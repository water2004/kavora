/*
* Copyright (c) 2022-2023, NVIDIA CORPORATION. All rights reserved.
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
module ray_resolve;

public struct RayHitInfo
{
  public float hitDistance;
  public uint barycentricCoordinates;
  public uint primitiveIndex;
  public uint customIndex;
  public uint geometryIndex;
  public bool hasHit;
  public bool frontHit;
};

public interface IBasePayloadState
{
  property float3 origin { get; set; }
  property float coneRadius { get; set; }
  property float segmentHitDistance { get; set; }
  property uint8_t rayMask { get; set; }
  property uint8_t portalSpace { get; set; }
  property float3 direction { get; set; }
  property bool decalEncountered { get; set; }
  property bool continueResolving { get; set; }
  property bool directionAltered { get; set; }
  property float4 accumulatedRotation { get; set; }

  // Supplies coherence hints when this payload permits reordering before hit invocation.
  bool shouldReorder(out uint coherenceHints, out uint numCoherenceHints);
};

public struct EmptyExtraArgs {};

// The pass supplies a concrete traversal policy. TraceRay resolves in its hit shader.
public interface IRayResolveStep<Payload : IBasePayloadState, Context>
{
  static RayHitInfo trace(RayDesc ray, uint flags, uint sbtRecordOffset, float spreadAngle, __ref Payload payload);
  static void resolve(__ref Context context, RayHitInfo hit, __ref Payload payload);
}

[ForceInline]
public uint8_t resolveRayFromPayload<Payload : IBasePayloadState, Context, Step : IRayResolveStep<Payload, Context>,
  let HasInitialHit : bool, let ShortenRay : bool, let FirstInteraction : uint8_t = 1>(
  RayDesc ray, float spreadAngle, RayHitInfo initialHit,
  uint flags, uint sbtRecordOffset, uint8_t maxInteractions, __ref Context context, __ref Payload payload)
{
  RayHitInfo hit = initialHit;
  uint8_t iteration;
  for (iteration = FirstInteraction;; ++iteration)
  {
    payload.continueResolving = iteration < maxInteractions;
    if (!HasInitialHit)
    {
      hit = Step.trace(ray, flags, sbtRecordOffset, spreadAngle, payload);
    }
    Step.resolve(context, hit, payload);

    if (!payload.continueResolving)
    {
      break;
    }

    ray.Origin = payload.origin;
    ray.Direction = payload.direction;
    if (ShortenRay)
    {
      ray.TMax = max(ray.TMax - payload.segmentHitDistance, 0.0f);
    }
    if (HasInitialHit)
    {
      hit = Step.trace(ray, flags, sbtRecordOffset, spreadAngle, payload);
    }
  }
  return iteration;
}

[ForceInline]
public uint8_t resolveRay<Payload : IBasePayloadState, Context, Step : IRayResolveStep<Payload, Context>,
  let HasInitialHit : bool, let ShortenRay : bool>(
  RayDesc ray, float coneRadius, float spreadAngle, RayHitInfo initialHit,
  uint flags, uint sbtRecordOffset, uint8_t maxInteractions, __ref Context context, __ref Payload payload)
{
  payload.origin = ray.Origin;
  payload.coneRadius = coneRadius;
  payload.direction = ray.Direction;
  return resolveRayFromPayload<Payload, Context, Step, HasInitialHit, ShortenRay>(
    ray, spreadAngle, initialHit, flags, sbtRecordOffset, maxInteractions, context, payload);
}
