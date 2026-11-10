/*
* Copyright (c) 2025, NVIDIA CORPORATION. All rights reserved.
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
//!variant particle_system_spawn.comp
//!variant particle_system_spawn_area.comp PARTICLE_AREA_SPAWN
//!end-variants
#include "rtx/pass/particles/particle_system_bindings.slangh"
#include "rtx/pass/particles/particle_spawn_distribution.h"

#include "rtx/utility/common.slangh"
#include "rtx/utility/noise.slangh"
#include "rtx/utility/procedural_noise.slangh"
#include "rtx/utility/brdf.slangh"
#include "rtx/utility/packing.slangh"
#include "rtx/utility/gbuffer_helpers.slangh"
#include "rtx/utility/debug_view_helpers.slangh"
#include "rtx/concept/camera/camera.slangh"
    
layout(binding = PARTICLE_SYSTEM_BINDING_SPAWN_CONTEXT_PARTICLE_MAPPING_INPUT)
StructuredBuffer<uint16_t> SpawnContextParticleMapping;

layout(binding = PARTICLE_SYSTEM_BINDING_SPAWN_CONTEXTS_INPUT)
StructuredBuffer<GpuSpawnContext> SpawnContexts;

// Outputs

layout(binding = PARTICLE_SYSTEM_BINDING_PARTICLES_BUFFER_INPUT_OUTPUT)
RWStructuredBuffer<GpuParticle> Particles;

#ifdef PARTICLE_AREA_SPAWN
[[vk::binding(PARTICLE_SYSTEM_BINDING_SPAWN_AREAS_INPUT)]]
StructuredBuffer<float> SpawnAreas;

uint findParticleArea(uint start, uint count, float target) {
  uint end = start + count;
  while (start < end) {
    const uint middle = start + (end - start) / 2;
    if (SpawnAreas[middle] <= target) {
      start = middle + 1;
    } else {
      end = middle;
    }
  }
  return start;
}

uint sampleParticleArea(GpuSpawnContext context, float randomValue) {
  const uint start = context.areaDistributionOffset;
  if (start == kInvalidParticleAreaOffset) {
    return kInvalidParticleAreaOffset;
  }
  const uint blockTotals = start + context.numTriangles;
  const uint blocks = particleAreaBlockCount(context.numTriangles);
  const float total = SpawnAreas[blockTotals + blocks - 1];
  if (!(total > 0.0f)) {
    return kInvalidParticleAreaOffset;
  }

  const float target = min(randomValue * total, asfloat(asuint(total) - 1u));
  const uint block = findParticleArea(blockTotals, blocks, target) - blockTotals;
  if (block >= blocks) {
    return kInvalidParticleAreaOffset;
  }
  const uint first = block * kParticleAreaBlockSize;
  const uint count = min(kParticleAreaBlockSize, context.numTriangles - first);
  const float prior = block == 0 ? 0.0f : SpawnAreas[blockTotals + block - 1];
  const float localTotal = SpawnAreas[start + first + count - 1];
  if (!(localTotal > 0.0f)) {
    return kInvalidParticleAreaOffset;
  }
  const float localTarget = min(max(target - prior, 0.0f), asfloat(asuint(localTotal) - 1u));
  const uint triangle = findParticleArea(start + first, count, localTarget) - start;
  return triangle < first + count ? triangle : kInvalidParticleAreaOffset;
}
#endif

GpuParticle rejectedSpawn(const GpuParticleSystem particleSystem, float seed) {
  // Retire rejected slots at the normal age to keep the ring contiguous.
  GpuParticle particle;
  particle.reset(particleSystem, float3(0.0f), float3(0.0f), f16vec4(0.0h), float4(0.0f), seed);
  particle.flags = GpuParticle::kRejectedSpawnFlag;
  return particle;
}

f16vec3 sampleDirectionInCone(f16vec3 axis, float cosConeHalfAngle, float2 rndUV)
{
  // Sample a direction in a cone about +Z, and get its PDF (we ignore PDF here)
  float solidAnglePdf;
  f16vec3 localDir = calcUniformConeDirectionSample(vec2(rndUV), cosConeHalfAngle, solidAnglePdf);

  f16vec3 tangent, bitangent;
  calcOrthonormalBasis(axis, tangent, bitangent);

  // Rotate the local-Z sample into the world-axis frame
  return localDir.x * tangent + localDir.y * bitangent + localDir.z * axis;
}

// Generate a new particle in-place with random properties
GpuParticle respawn(uint particleIdx, uint spawnCtxIdx, const GpuParticleSystem particleSystem)
{
  GpuParticle newParticle;
  
  // Get spawn context
  GpuSpawnContext spawnCtx = SpawnContexts[spawnCtxIdx];

  if (spawnCtx.numTriangles == 0) {
    return rejectedSpawn(particleSystem, randomFloat(uint2(particleIdx, particleCb.frameIdx)));
  }
  
  // Get random triangle
  
  float3 positions[3]; 
  float3 prevPositions[3];
  float4 color = (spawnCtx.spawnMeshColorsIdx != BINDING_INDEX_INVALID) ? 0.0f : 1.0f;
  float4 uvMinMax = float4(0..xx, 1..xx);
  
  uint randomSeed = particleIdx;

#ifdef PARTICLE_AREA_SPAWN
  const float areaSample = randomFloat(uint2(randomSeed++, particleCb.frameIdx));
  const uint triIdx = sampleParticleArea(spawnCtx, areaSample);
  if (triIdx == kInvalidParticleAreaOffset) {
    return rejectedSpawn(particleSystem, areaSample);
  }
  float2 uv = float2(randomFloat(uint2(randomSeed++, particleCb.frameIdx)), randomFloat(uint2(randomSeed++, particleCb.frameIdx)));
  if (uv.x + uv.y > 1.0f) {
    uv = 1.0f - uv;
  }
  const float3 randomBary = float3(1.0f - uv.x - uv.y, uv);
#else
  float3 random01 = float3(randomFloat(uint2(randomSeed++, particleCb.frameIdx)), randomFloat(uint2(randomSeed++, particleCb.frameIdx)), randomFloat(uint2(randomSeed++, particleCb.frameIdx)));
  float3 randomBary = saturate(random01 / dot(random01, 1..xxx));
  
  uint triIdx = randomFloat(uint2(randomSeed++, particleCb.frameIdx)) * spawnCtx.numTriangles;
#endif
  
  for (uint i=0 ; i<3 ; i++)
  {
    uint indexIdx = triIdx * 3 + i;
    uint index = spawnCtx.indices32bit ? BUFFER_ARRAY(indices32, (uint)spawnCtx.spawnMeshIndexIdx, indexIdx) : BUFFER_ARRAY(indices, (uint)spawnCtx.spawnMeshIndexIdx, indexIdx);
    
    const uint baseSrcPositionOffset = (spawnCtx.spawnMeshPositionsOffset + index * spawnCtx.spawnMeshPositionsStride) / 4;
    positions[i] = float3(BUFFER_ARRAY(geometries, (uint)spawnCtx.spawnMeshPositionsIdx, baseSrcPositionOffset + 0),
                          BUFFER_ARRAY(geometries, (uint)spawnCtx.spawnMeshPositionsIdx, baseSrcPositionOffset + 1),
                          BUFFER_ARRAY(geometries, (uint)spawnCtx.spawnMeshPositionsIdx, baseSrcPositionOffset + 2));
    
    if (spawnCtx.spawnMeshPrevPositionsIdx != BINDING_INDEX_INVALID)
    {
      const uint previousPositionOffset = (spawnCtx.spawnMeshPrevPositionsOffset + index * spawnCtx.spawnMeshPositionsStride) / 4;
      prevPositions[i].x = BUFFER_ARRAY(geometries, (uint)spawnCtx.spawnMeshPrevPositionsIdx, previousPositionOffset + 0);
      prevPositions[i].y = BUFFER_ARRAY(geometries, (uint)spawnCtx.spawnMeshPrevPositionsIdx, previousPositionOffset + 1);
      prevPositions[i].z = BUFFER_ARRAY(geometries, (uint)spawnCtx.spawnMeshPrevPositionsIdx, previousPositionOffset + 2);
    }
    else
    {
      prevPositions[i] = positions[i];
    }
    
    if(spawnCtx.spawnMeshColorsIdx != BINDING_INDEX_INVALID)
    {
      const uint baseSrcColorOffset = (spawnCtx.spawnMeshColorsOffset + index * spawnCtx.spawnMeshColorsStride) / 4;
      const uint colorBits = floatBitsToUint(BUFFER_ARRAY(geometries, (uint) spawnCtx.spawnMeshColorsIdx, baseSrcColorOffset));
      // Format is B8G8R8A8_UNORM
      color += unorm4x8ToFloat4x32(colorBits).zyxw * randomBary[i];
    }
    
    // Only fetch emitter UVs when a valid texcoord buffer is bound. For a mesh without UVs,
    // spawnMeshTexcoordsIdx == BINDING_INDEX_INVALID (0xFFFF); reading geometries[0xFFFF] is an
    // out-of-range bindless descriptor access and faults the GPU (device loss). Mirrors the
    // existing guards on the color and prev-position reads above.
    float2 texcoord = float2(0.0f, 0.0f);
    if (spawnCtx.spawnMeshTexcoordsIdx != BINDING_INDEX_INVALID)
    {
      const uint baseSrcTexcoordOffset = (spawnCtx.spawnMeshTexcoordsOffset + index * spawnCtx.spawnMeshTexcoordsStride) / 4;
      texcoord = float2(BUFFER_ARRAY(geometries, (uint)spawnCtx.spawnMeshTexcoordsIdx, baseSrcTexcoordOffset + 0),
                        BUFFER_ARRAY(geometries, (uint)spawnCtx.spawnMeshTexcoordsIdx, baseSrcTexcoordOffset + 1));
    }
    
    if (particleSystem.desc.useSpawnTexcoords && spawnCtx.spawnMeshTexcoordsIdx != BINDING_INDEX_INVALID)
    {
      if (i == 0)
      {
        uvMinMax.xy = texcoord;
        uvMinMax.zw = texcoord;
      }
      else
      {
        uvMinMax.xy = min(uvMinMax.xy, texcoord);
        uvMinMax.zw = max(uvMinMax.zw, texcoord);
      }
    }
  }
  
  // Sample random point on triangle
  const float randSeed = randomFloat(uint2(randomSeed++, particleCb.frameIdx));

  float3 prevWorldPosition = mul(spawnCtx.spawnPrevObjectToWorld, float4(interpolateHitAttribute(prevPositions, randomBary), 1.f)).xyz;
  float3 worldPosition = mul(spawnCtx.spawnObjectToWorld, float4(interpolateHitAttribute(positions, randomBary), 1.f));
  
  float3 worldSpawnVelocity = (worldPosition - prevWorldPosition) * particleCb.invDeltaTimeSecs;
  
  const float3 kFallbackNormal = float3(0.f, 0.f, 1.f);
  float3 localNormal = cross(positions[1] - positions[0], positions[2] - positions[0]);
  float3 worldNormal = safeNormalize(mul(spawnCtx.spawnObjectToWorld, float4(localNormal, 0.f)), kFallbackNormal);
  
  float cosCone = cos(radians(particleSystem.desc.initialVelocityConeAngleDegrees));
  f16vec3 dir = sampleDirectionInCone(worldNormal, cosCone, float2(randomFloat(uint2(randomSeed++, particleCb.frameIdx)), randomFloat(uint2(randomSeed++, particleCb.frameIdx))));
  
  float3 initialVelocity = worldSpawnVelocity * particleSystem.desc.initialVelocityFromMotion + dir * particleSystem.desc.initialVelocityFromNormal * particleCb.sceneScale;
  
  float3 spawnPosition = lerp(worldPosition, prevWorldPosition, randSeed);
  
  // Spawn a particle
  newParticle.reset(particleSystem, spawnPosition, initialVelocity, uvMinMax, color, randSeed);
  
  return newParticle;
}


[shader("compute")]
[numthreads(128, 1, 1)]
void main(uint2 threadIdx : SV_DispatchThreadID)
{
  const GpuParticleSystem particleSystem = particleCb.particleSystem;
  
  if(threadIdx.x >= particleSystem.spawnParticleCount)
  {
    return;
  }
  
  const uint particleIdx = (threadIdx.x + particleSystem.spawnParticleOffset) % particleSystem.desc.maxNumParticles;
  const uint spawnCtxIdx = SpawnContextParticleMapping[threadIdx.x];
  const bool shouldRespawn = Particles[particleIdx].isDead() || Particles[particleIdx].isSleeping();
  if (shouldRespawn)
  {
    Particles[particleIdx] = respawn(particleIdx, spawnCtxIdx, particleSystem);
  }
}
