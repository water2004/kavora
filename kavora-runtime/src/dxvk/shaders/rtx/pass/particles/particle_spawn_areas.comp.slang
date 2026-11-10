// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
//!variant particle_spawn_areas.comp
//!variant particle_spawn_areas_finish.comp PARTICLE_AREA_FINISH
//!end-variants

#include "rtx/pass/particles/particle_system_bindings.slangh"
#include "rtx/pass/particles/particle_spawn_distribution_build.slangh"

[[vk::binding(PARTICLE_SYSTEM_BINDING_SPAWN_CONTEXTS_INPUT)]]
StructuredBuffer<GpuSpawnContext> SpawnContexts;
[[vk::binding(PARTICLE_SYSTEM_BINDING_SPAWN_AREA_JOBS)]]
StructuredBuffer<uint2> AreaJobs;
[[vk::binding(PARTICLE_SYSTEM_BINDING_SPAWN_AREAS_OUTPUT)]]
RWStructuredBuffer<float> Areas;
[[vk::push_constant]]
ConstantBuffer<uint4> Jobs;

#ifndef PARTICLE_AREA_FINISH
float3 loadEmitterPosition(GpuSpawnContext context, uint index) {
  const uint address = (context.spawnMeshPositionsOffset + index * context.spawnMeshPositionsStride) / 4;
  const uint buffer = context.spawnMeshPositionsIdx;
  return float3(BUFFER_ARRAY(geometries, buffer, address),
                BUFFER_ARRAY(geometries, buffer, address + 1),
                BUFFER_ARRAY(geometries, buffer, address + 2));
}

float worldTriangleArea(GpuSpawnContext context, uint triangle) {
  float3 vertices[3];
  [unroll]
  for (uint corner = 0; corner < 3; ++corner) {
    const uint address = triangle * 3 + corner;
    const uint buffer = context.spawnMeshIndexIdx;
    const uint index = context.indices32bit ? BUFFER_ARRAY(indices32, buffer, address) : BUFFER_ARRAY(indices, buffer, address);
    vertices[corner] = loadEmitterPosition(context, index);
  }
  const float3 first = mul(context.spawnObjectToWorld, float4(vertices[1] - vertices[0], 0.0f));
  const float3 second = mul(context.spawnObjectToWorld, float4(vertices[2] - vertices[0], 0.0f));
  const float area = length(cross(first, second));
  return isfinite(area) ? min(area, 1e24f) : 0.0f;
}
#endif

[shader("compute")]
[numthreads(kParticleAreaBlockSize, 1, 1)]
void main(uint3 group : SV_GroupID, uint lane : SV_GroupIndex) {
  const uint jobIndex = group.x + group.y * Jobs.y;
  if (jobIndex >= Jobs.z) {
    return;
  }
  const uint2 job = AreaJobs[Jobs.x + jobIndex];
  const GpuSpawnContext context = SpawnContexts[job.x];
  const uint blockTotals = context.areaDistributionOffset + context.numTriangles;
#ifdef PARTICLE_AREA_FINISH
  finishParticleAreaBlocks(Areas, blockTotals, particleAreaBlockCount(context.numTriangles), lane);
#else
  const uint triangle = job.y * kParticleAreaBlockSize + lane;
  const float area = triangle < context.numTriangles ? worldTriangleArea(context, triangle) : 0.0f;
  const float prefix = inclusiveAreaPrefix(area, lane);
  if (triangle < context.numTriangles) {
    Areas[context.areaDistributionOffset + triangle] = prefix;
  }
  if (lane == kParticleAreaBlockSize - 1) {
    Areas[blockTotals + job.y] = prefix;
  }
#endif
}
