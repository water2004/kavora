// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
// Independent readback probe for production air mathematics; not a visual test.
import "rtx/algorithm/air_medium.slang";
import "rtx/algorithm/atmosphere.slang";
import "rtx/algorithm/fog_medium.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
import "rtx/pass/air_parameters.slang";
import "rtx/pass/volume_parameters.slang";

ConstantBuffer<AirArgs> Air;
StructuredBuffer<float4> Origins;
StructuredBuffer<float4> Directions;
RWStructuredBuffer<float4> Results;
Texture2D<float4>.Handle Transmission;
Texture2D<float4>.Handle Scattering;
SamplerState.Handle TableSampler;
Sampler2DArray<float4>.Handle Weather;
Sampler3D<float4>.Handle Volume;
Sampler3D<float2>.Handle Chroma;
Sampler3D<float>.Handle CloudDepth;
float3 MediumExtinction;
uint Count;

struct NoFog : IAtmosphereFog
{
  static bool interval(VolumeArgs args, float3 origin, float3 direction, float distance, out float2 range)
  { range = 0; return false; }
  static FogMedium sampleMedium(VolumeArgs args, float3 from, float3 to, float distance)
  { return {}; }
  static float3 transmittance(VolumeArgs args, float3 origin, float3 direction, float distance)
  { return 1; }
};

[shader("compute")]
[numthreads(64, 1, 1)]
void intervals(uint index : SV_DispatchThreadID)
{
  if (index >= Count) return;
  const float2 interval = airInterval(Air, Origins[index].xyz, Directions[index].xyz);
  Results[index] = float4(interval, airAltitude(Air, airCoordinates(Air, Origins[index].xyz)), 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void airGridEndpoints(uint index : SV_DispatchThreadID)
{
  if (index >= Count) return;
  const AirRayGrid grid = { Origins[index].xy };
  const uint last = kAirViewSteps + uint(Origins[index].z);
  Results[index] = float4(grid.boundary(0), grid.boundary(last - 1), grid.boundary(last), grid.boundary(last + 1));
}

void probeTransport<Visibility : IAirLightVisibility>(uint index)
{
  if (index >= Count) return;
  VolumeArgs volume = {};
  volume.air = Air;
  const AirTables tables = { Texture2D<float4>(Transmission), Texture2D<float4>(Scattering), SamplerState(TableSampler) };
  const AtmosphereRay ray = createAtmosphereRay<NoFog>(volume, Origins[index].xyz, Directions[index].xyz, 0, MediumExtinction);
  const float end = Origins[index].w;
  const float split = end * Directions[index].w;
  const CloudLightingCache noCloudLighting = { Sampler3D<float>(CloudDepth), Sampler3D<float>(CloudDepth),
    Sampler3D<float2>(Chroma), Sampler3D<float4>(Volume), Sampler3D<float4>(Volume) };
  const AtmosphereSegment full = integrateAtmosphereRange<NoFog, Visibility>(volume, Sampler3D<float4>(Volume),
    Sampler3D<float2>(Chroma), Sampler2DArray<float4>(Weather), Sampler3D<float4>(Volume), Sampler3D<float4>(Volume),
    noCloudLighting, ray, 0, end, 0, tables);
  const AtmosphereSegment first = integrateAtmosphereRange<NoFog, Visibility>(volume, Sampler3D<float4>(Volume),
    Sampler3D<float2>(Chroma), Sampler2DArray<float4>(Weather), Sampler3D<float4>(Volume), Sampler3D<float4>(Volume),
    noCloudLighting, ray, 0, split, 0, tables);
  const AtmosphereSegment second = integrateAtmosphereRange<NoFog, Visibility>(volume, Sampler3D<float4>(Volume),
    Sampler3D<float2>(Chroma), Sampler2DArray<float4>(Weather), Sampler3D<float4>(Volume), Sampler3D<float4>(Volume),
    noCloudLighting, ray, split, end, 0, tables);
  const AtmosphereSegment composed = composeAtmosphereSegments(first, second);
  Results[index * 5 + 0] = float4(full.radiance, 0);
  Results[index * 5 + 1] = float4(full.transmittance, 0);
  Results[index * 5 + 2] = float4(composed.radiance, 0);
  Results[index * 5 + 3] = float4(composed.transmittance, 0);
  Results[index * 5 + 4] = float4(airTransmittance(Air, Origins[index].xyz, Directions[index].xyz, end), 0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void integrate(uint index : SV_DispatchThreadID) { probeTransport<UnobstructedAir>(index); }

struct Roof : IAirLightVisibility
{
  static float visibility(AirArgs args, uint light, float3 position) { return float(position.x >= 32); }
}

[shader("compute")]
[numthreads(64, 1, 1)]
void underRoof(uint index : SV_DispatchThreadID) { probeTransport<Roof>(index); }
