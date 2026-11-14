// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
module water_surface;

#include "rtx/pass/water_sim/water_sim_args.h"
#include "rtx/utility/shared_constants.h"
import "rtx/pass/water_sim/water_surface_args.slang";

public struct WaterSurfaceSettings
{
  public bool isZUp;
  public WaterSurfaceArgs water;
  public float time;
};

public interface IWaterSurfaceResources
{
  static WaterSurfaceSettings settings();
  static Texture2D<float4> simulation();
  static Texture2DArray<float4> wind();
  static Texture2D<float2> rings();
  static Texture2D<float2> impacts();
  static SamplerState clipSampler();
  static SamplerState tileSampler();
};

float2 waterPlanarPosition(float3 position, bool isZUp) {
  return isZUp ? position.xy : position.xz;
}

float waterClipFade(float2 uv) {
  const float2 edge = min(uv, 1.0f - uv);
  return smoothstep(0.025f, 0.12f, min(edge.x, edge.y));
}

float2 sampleWaterImpacts(float2 planar, float3 clip, Texture2D<float2> impacts, SamplerState clipSampler) {
  const float2 uv = (planar - clip.xy) * clip.z;
  const float fade = waterClipFade(uv);
  return fade > 0.0f ? impacts.SampleLevel(clipSampler, uv, 0.0f) * fade : float2(0.0f);
}

// The long cascade moves the refraction origin. Fine wind and rings only affect shading.
[ForceInline]
float3 sampleWaterSimulation<let Detail : bool>(float3 position, float footprint, WaterSurfaceSettings settings,
  Texture2D<float4> simulation, Texture2DArray<float4> wind, Texture2D<float2> rings, Texture2D<float2> impacts,
  SamplerState clipSampler, SamplerState tileSampler) {
  const float2 planar = waterPlanarPosition(position, settings.isZUp);
  const float2 tilePosition = planar + settings.water.tileOrigin;
  float3 wave = 0.0f;
  if (settings.water.effects & WATER_EFFECT_RIPPLES) {
    const float2 uv = (planar - settings.water.simulationClip.xy) * settings.water.simulationClip.z;
    const float fade = waterClipFade(uv);
    if (fade > 0.0f) {
      wave = simulation.SampleLevel(clipSampler, uv, 0.0f).xzw * (fade * settings.water.simulationClip.w);
    }
  }
  if (settings.water.effects & WATER_EFFECT_WIND) {
    const float gust = 0.75f + 0.25f * sin(dot(planar, float2(0.058f, 0.041f)) + (settings.time * 0.17f + settings.water.gustPhase));
    wave += wind.SampleLevel(tileSampler, float3(tilePosition / kWaterWindTileLong, 0.0f), 0.0f).xyz * gust;
    if (Detail && footprint < 0.75f) {
      const float fineFade = 1.0f - smoothstep(0.12f, 0.75f, footprint);
      wave.yz += wind.SampleLevel(tileSampler, float3(tilePosition / kWaterWindTileShort, 1.0f), 0.0f).yz * (gust * fineFade);
    }
  }
  if (Detail && (settings.water.effects & WATER_EFFECT_RINGS) && footprint < 0.24f) {
    const float spray = (settings.water.effects & WATER_EFFECT_IMPACTS) ? sampleWaterImpacts(planar, settings.water.impactClip, impacts, clipSampler).y : 0.0f;
    const float intensity = max(settings.water.rain, spray) * (1.0f - smoothstep(0.055f, 0.24f, footprint));
    if (intensity > 0.0f) {
      wave.yz += rings.SampleLevel(tileSampler, tilePosition / kWaterRingTile, 0.0f) * intensity;
    }
  }
  return wave;
}

[ForceInline]
public float3 sampleWater<Resources : IWaterSurfaceResources, let Detail : bool>(float3 position, float footprint)
{
  return sampleWaterSimulation<Detail>(position, footprint, Resources.settings(), Resources.simulation(),
    Resources.wind(), Resources.rings(), Resources.impacts(), Resources.clipSampler(), Resources.tileSampler());
}

[ForceInline]
public bool waterCanOffsetTransmission(uint effects, bool isTranslucent, uint flags)
{
  return (effects & (WATER_EFFECT_RIPPLES | WATER_EFFECT_WIND)) && isTranslucent &&
    (flags & TRANSLUCENT_SURFACE_MATERIAL_FLAG_SIMULATED_WATER);
}

[ForceInline]
public bool applyWaterSurfaceNormal<Resources : IWaterSurfaceResources>(uint effects, uint materialFlags, bool isZUp,
  float3 position, float3 triangleNormal, float footprint, inout float3 normal)
{
  if (effects && (materialFlags & TRANSLUCENT_SURFACE_MATERIAL_FLAG_SIMULATED_WATER))
  {
    const float normalUp = isZUp ? triangleNormal.z : triangleNormal.y;
    if (abs(normalUp) > 0.55f)
    {
      const float3 wave = sampleWater<Resources, true>(position, footprint);
      normal = applyWaterSlope(normal, wave.yz, normalUp, isZUp);
      return any(abs(wave) > 1e-4f);
    }
  }
  return false;
}

// Positive crests stay at the mesh plane. Troughs may move the refracted
// origin under the mesh once a short visibility probe clears the shore.
[ForceInline]
public void offsetWaterTransmission<Resources : IWaterSurfaceResources>(inout float3 origin, inout float coneRadius,
  float3 incidentDirection, float spreadAngle, float3 position, float3 geometryNormal, uint rayMask, bool isZUp,
  RaytracingAccelerationStructure scene)
{
  const float normalUp = isZUp ? geometryNormal.z : geometryNormal.y;
  const float directionUp = isZUp ? incidentDirection.z : incidentDirection.y;
  if (abs(normalUp) < 0.85f || directionUp > -0.12f)
  {
    return;
  }

  const float3 wave = sampleWater<Resources, false>(position, 0.0f);
  const float2 planarDirection = isZUp ? incidentDirection.xy : incidentDirection.xz;
  const float relativeDirection = directionUp - dot(wave.yz, planarDirection);
  if (wave.x >= -0.008f || relativeDirection > -0.12f)
  {
    return;
  }

  const float distance = min(wave.x / relativeDirection, 0.2f);
  RayDesc shore;
  shore.Origin = origin;
  shore.Direction = incidentDirection;
  shore.TMin = 0.0f;
  shore.TMax = distance;
  RayQuery<RAY_FLAG_CULL_NON_OPAQUE | RAY_FLAG_SKIP_PROCEDURAL_PRIMITIVES | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
  query.TraceRayInline(scene, 0, rayMask, shore);
  while (query.Proceed()) {}
  if (query.CommittedStatus() == COMMITTED_NOTHING)
  {
    origin += incidentDirection * distance;
    coneRadius += spreadAngle * distance;
  }
}

[ForceInline]
public float3 applyWaterSlope(float3 normal, float2 slope, float normalUp, bool isZUp) {
  const float3 gradient = isZUp ? float3(slope, 0.0f) : float3(slope.x, 0.0f, slope.y);
  return normalize(normal - gradient * normalUp);
}

float sludgeLatticeValue(int2 p) {
  uint bits = uint(p.x) * 0x9e3779b1u ^ uint(p.y) * 0x85ebca77u;
  bits ^= bits >> 16;
  bits *= 0x7feb352du;
  return float((bits ^ (bits >> 15)) & 65535u) / 65535.0f;
}

[ForceInline]
public bool waterSludgeIsOpen(float3 position, bool isZUp, uint effects, float3 impactClip,
  Texture2D<float2> impacts, SamplerState clipSampler) {
  if (!(effects & WATER_EFFECT_SLUDGE)) {
    return false;
  }
  const float2 planar = waterPlanarPosition(position, isZUp);
  const float cleared = sampleWaterImpacts(planar, impactClip, impacts, clipSampler).x;
  if (cleared <= 0.25f || cleared >= 0.52f) {
    return cleared >= 0.52f;
  }
  const float2 noisePosition = planar * 5.0f;
  const int2 cell = int2(floor(noisePosition));
  const float2 f = smoothstep(0.0f, 1.0f, frac(noisePosition));
  const float noise = lerp(lerp(sludgeLatticeValue(cell), sludgeLatticeValue(cell + int2(1, 0)), f.x),
    lerp(sludgeLatticeValue(cell + int2(0, 1)), sludgeLatticeValue(cell + 1), f.x), f.y);
  return cleared > lerp(0.25f, 0.52f, noise);
}
