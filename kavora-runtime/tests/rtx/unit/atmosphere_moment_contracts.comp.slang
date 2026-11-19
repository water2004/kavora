// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
import "rtx/algorithm/atmosphere.slang";
import "rtx/algorithm/cloud_view_transport.slang";
import "rtx/algorithm/clouds.slang";

StructuredBuffer<float4> Inputs;
RWStructuredBuffer<float4> Results;
uint Count;
float MaxDistance;

[shader("compute")]
[numthreads(64, 1, 1)]
void moments(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 cell = Inputs[index];
  const float2 value = cloudCellMoments(cell.x, cell.y, cell.z, MaxDistance);
  Results[index] = float4(value, unpackCloudMoments(packCloudMoments(value)));
}

[shader("compute")]
[numthreads(64, 1, 1)]
void composition(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 cell = Inputs[index];
  const uint count = uint(cell.w);
  const float length = cell.z / float(count);
  AtmosphereSegment result = emptyAtmosphereSegment();
  for (uint step = 0; step < count; step++)
  {
    AtmosphereSegment segment = emptyAtmosphereSegment();
    // Chromatic fog attenuation must not change the cloud-only motion guide.
    segment.transmittance = float3(0.2f, 0.6f, 0.9f);
    segment.cloudMoments = cloudCellMoments(cell.x, cell.y + float(step) * length, length, MaxDistance);
    result = composeAtmosphereSegments(result, segment);
  }
  Results[index] = float4(result.cloudMoments, result.radiance.x, result.transmittance.x);
}

float integrateGridProbe(CloudRayGrid grid, float begin, float end)
{
  float depth = 0;
  uint cell = grid.cellAt(begin);
  for (float previous = begin; previous < end; cell++)
  {
    const float next = min(end, grid.boundary(cell + 1));
    // A smooth medium with an independent closed-form integral. Checkpoints
    // retain the full cell's sample, even when they split that cell.
    const float extinction = 1e-6f * (1.0f + grid.sampleAt(cell) / 10000.0f);
    depth += extinction * (next - previous);
    previous = next;
  }
  return depth;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void rayGrid(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 input = Inputs[index];
  const CloudRayGrid grid = createCloudRayGrid(float2(input.x, MaxDistance), input.y, input.z, 160u);
  const float split = lerp(input.x, MaxDistance, input.w);
  const float full = integrateGridProbe(grid, input.x, MaxDistance);
  const float partitioned = integrateGridProbe(grid, input.x, split) + integrateGridProbe(grid, split, MaxDistance);
  uint cells = 0;
  float failure = 0;
  for (float previous = input.x; previous < MaxDistance; cells++)
  {
    const float next = grid.boundary(cells + 1);
    const float sample = grid.sampleAt(cells);
    failure = max(failure, float(next <= previous || grid.cellAt(sample) != cells));
    failure = max(failure, float(sample < previous || sample > next || grid.spanAt(cells) <= 0));
    previous = next;
  }
  Results[index] = float4(full, partitioned, failure, float(cells));
}
