// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
import "rtx/algorithm/clouds.slang";
import "rtx/utility/cloud_phase.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/algorithm/cloud_neural_scattering.slang";
import "rtx/pass/cloud_parameters.slang";
ConstantBuffer<CloudArgs> Clouds;
Sampler3D<float4>.Handle UnusedLighting;
Sampler2D<float>.Handle Phase;

StructuredBuffer<float4> Origins;
StructuredBuffer<float4> Directions;
RWStructuredBuffer<float4> Results;
uint Count;

[shader("compute")]
[numthreads(64, 1, 1)]
void skyResponse(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
    return;
  const float4 input = Origins[index];
  const uint knot = uint(Directions[index].x);
  const float2 response = cloudDiffuseBoundaryResponse(input.xy, knot, input.z, input.w, uint(Directions[index].z));
  const float2 cached = cloudNeuralSky(Clouds, Sampler3D<float4>(UnusedLighting), Clouds.groundReference,
    1000, cloudSkyAngle(Directions[index].y), 0);
  Results[index] = float4(response, cached);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void neuralAngles(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
    return;
  const float4 request = Origins[index];
  const uint light = uint(request.z);
  const float lightUp = dot(Clouds.lights[light].directionToLight, Clouds.up);
  const CloudNeuralAngle angle = cloudNeuralAngle(request.x, request.y, lightUp);
  const float2 knot = cloudNeuralKnot(uint(request.w), lightUp);
  const float value = cloudNeuralDiffuse(Clouds, Sampler3D<float4>(UnusedLighting),
    Directions[index].xyz, 1000, angle, 0, light);
  Results[index] = float4(knot, value, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void neuralNodes(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
    return;
  Results[index] = float4(cloudNeuralPosition(Clouds, uint2(Origins[index].xy), 0, 1000), 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void grids(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 input = Origins[index];
  const CloudRayGrid grid = createCloudRayGrid(float2(input.x, input.y), input.z, input.w, uint(Directions[index].x));
  float maximumError = 0;
  float previous = grid.interval.x;
  for (uint cell = 0; cell < grid.cells; cell++)
  {
    const float middle = grid.sampleAt(cell);
    maximumError = max(maximumError, abs(float(grid.cellAt(middle)) - float(cell)));
    const float boundary = grid.boundary(cell + 1);
    if (boundary <= previous)
    {
      maximumError = 100000;
    }
    previous = boundary;
  }
  Results[index] = float4(grid.cells, previous, maximumError, grid.boundary(1) - input.x);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void viewGrids(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 interval = Origins[index];
  const float4 view = Directions[index];
  const CloudRayGrid grid = createCloudViewGrid(Clouds, interval.xy, view.xyz, interval.z, uint(view.w));
  Results[index] = float4(grid.cells, grid.boundary(grid.cells - 1), grid.boundary(grid.cells),
    grid.sampleAt(uint(interval.w)));
}

[shader("compute")]
[numthreads(64, 1, 1)]
void rayPhases(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
    return;
  const float cosine = Origins[index].x;
  const float3 direction = float3(cosine, sqrt(max(0.0f, 1.0f - cosine * cosine)), 0);
  const Sampler2D<float> table = Sampler2D<float>(Phase);
  const CloudRayPhase phase = createCloudRayPhase(Clouds, direction, table);
  Results[index] = float4(phase.phase[0], cloudPhase(table, cosine, 0), cloudPhase(table, cosine, 1));
}
