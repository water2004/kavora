// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
import "rtx/algorithm/cloud_scattering.slang";
import "rtx/algorithm/cloud_sky.slang";
import "rtx/pass/cloud_parameters.slang";
import "rtx/utility/cloud_density.slang";
import "rtx/utility/hanpi_cloud.slang";

StructuredBuffer<float4> Inputs;
RWStructuredBuffer<float4> Results;
uint Count;

[shader("compute")]
[numthreads(64, 1, 1)]
void hemisphereQuadrature(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  CloudArgs args = {};
  args.up = Inputs[index].xyz;
  const CloudAmbientQuadrature quadrature = createCloudAmbientQuadrature(args);
  Results[index] = quadrature.sample(index % kCloudAmbientSampleCount);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void boundaryRadiance(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 sample = Inputs[index];
  const float3 boundary = cloudDiffuseBoundaryRadiance(float3(0.25f, 1.5f, 8.0f), 0,
    sample.xy, sample.z);
  const float furnace = cloudDiffuseBoundaryRadiance(1.56f, 1.56f, sample.xy, sample.z).x;
  Results[index] = float4(boundary, furnace);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void beamRadiance(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 sample = Inputs[index];
  const float2 depth = sample.z >= 0 ? sample.xy : sample.yx;
  Results[index] = float4(cloudDiffuseBeamRadiance(depth, abs(sample.z)), 0, 0, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void groundBoundary(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 sample = Inputs[index];
  const float3 ground = cloudGroundBoundaryRadiance(float3(0.25f, 1.5f, 8.0f), sample.w,
    sample.x, sample.y, sample.z);
  Results[index] = float4(ground, 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void columnSupport(uint index : SV_DispatchThreadID)
{
  if (index >= Count)
  {
    return;
  }
  const float4 sample = Inputs[index];
  CloudDensityArgs args = {};
  args.thickness = sample.x;
  CloudColumn column = {};
  column.type = sample.y;
  column.topScale = sample.z;
  Results[index] = float4(hanPiColumnSupport(args.thickness, column.topScale, sample.w), 0, 0, 0);
}
