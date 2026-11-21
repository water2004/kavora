// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
module cloud_neural_scattering;

import "rtx/utility/cloud_phase.slang";
import "rtx/utility/cloud_phase_constants.slang";

import "rtx/pass/cloud_parameters.slang";
import "rtx/algorithm/cloud_lighting_cache.slang";
#include "rtx/pass/clouds/cloud_neural_scattering.h"

struct NeuralGrid
{
  float2 origin;   // First cell edge in the wind-aligned horizontal plane.
  float spacing;
  uint2 tile;      // First texel in the packed atlas.
  uint side;
};

NeuralGrid neuralGrid(CloudArgs args, uint layer, uint cascade)
{
  const uint tileSize = CLOUD_NEURAL_TILE_SIZE;
  const uint side = cascade == 2 ? 2 * tileSize : tileSize;
  const uint2 tile = cascade == 2 ? uint2(0) : uint2(2 * tileSize, cascade * tileSize);
  const float far = cloudLocalLightRadius(args, layer) / float(tileSize);
  const float spacing = cascade == 2 ? far : cascade == 1 ? far / 3.0f : 0.125f * args.shapeScale;
  const float2 wind = cloudLightHorizontal(args, args.windOffset - args.groundReference);
  const float2 center = wind + floor(-wind / spacing) * spacing;
  const float2 origin = center - 0.5f * float(side) * spacing;
  return { origin, spacing, tile, side };
}

public uint cloudNeuralRowOffset(uint layer)
{
  return layer == 0 ? 0 : CLOUD_NEURAL_HEIGHT;
}

public uint cloudNeuralHeightSamples(uint layer)
{
  return layer == 0 ? CLOUD_NEURAL_HEIGHT : CLOUD_NEURAL_HIGH_HEIGHT;
}

public float3 cloudNeuralPosition(CloudArgs args, uint2 index, uint layer, float altitude)
{
  const uint cascade = index.x < 2 * CLOUD_NEURAL_TILE_SIZE ? 2 : index.y / CLOUD_NEURAL_TILE_SIZE;
  const NeuralGrid grid = neuralGrid(args, layer, cascade);
  const float2 horizontal = grid.origin + (float2(index - grid.tile) + 0.5f) * grid.spacing;
  return cloudLightPositionAtAltitude(args, horizontal, altitude);
}

// Space knots logarithmically in the chord distance. The first two intervals
// resolve the forward peak of the liquid and ice phase inputs.
static const float kNeuralAngleOctaves = 6.5f;
static const float kNeuralAngleRange = exp2(kNeuralAngleOctaves) - 1.0f;

// At each polar knot the checkpoint samples the two reachable elevation extrema.
public float2 cloudNeuralKnot(uint slot, float lightUp)
{
  const float t = float(slot / 2) / float(CLOUD_NEURAL_POLAR_KNOTS - 1);
  const float chord = (exp2(kNeuralAngleOctaves * t) - 1.0f) / kNeuralAngleRange;
  const float mu = clamp(1.0f - 2.0f * chord * chord, -1.0f, 1.0f);
  const float radius = sqrt(max(0.0f, (1.0f - mu * mu) * (1.0f - lightUp * lightUp)));
  return float2(mu, mu * lightUp + ((slot & 1) == 0 ? -radius : radius));
}

public struct CloudNeuralAngle
{
  public uint interval;
  public float polarBlend;
  public float elevationBlend;
};

public CloudNeuralAngle cloudNeuralAngle(float cosine, float viewUp, float lightUp)
{
  const float mu = clamp(cosine, -1.0f, 1.0f);
  const float chord = sqrt(0.5f * (1.0f - mu));
  const float polar = log2(1.0f + kNeuralAngleRange * chord)
    * (float(CLOUD_NEURAL_POLAR_KNOTS - 1) / kNeuralAngleOctaves);
  const uint interval = min(uint(polar), uint(CLOUD_NEURAL_POLAR_KNOTS - 2));
  const float radiusSquared = max(0.0f, (1.0f - mu * mu) * (1.0f - lightUp * lightUp));
  const float elevation = (viewUp - mu * lightUp) * rsqrt(max(1e-12f, radiusSquared));
  return { interval, polar - float(interval), saturate(0.5f + 0.5f * elevation) };
}

// Fritsch–Butland tangents for equally spaced knots in the interpolation coordinate.
public float2 cloudNeuralKnotTangent(float2 before, float2 after)
{
  const float2 slope = 2.0f * before * abs(after) / max(abs(before + after), 1e-12f);
  return select(before * after > 0.0f, slope, float2(0.0f));
}

float sampleNeuralAngles(Sampler3D<float4> field, float2 uv, float v, CloudNeuralAngle angle)
{
  const float band = 1.0f / CLOUD_NEURAL_TOTAL_BANDS;
  const float row = v + float(angle.interval) * band;
  const float4 from = field.SampleLevel(float3(uv.x, row, uv.y), 0);
  const float4 to = field.SampleLevel(float3(uv.x, row + band, uv.y), 0);

  // Reuse the same tangent on both sides of each knot so brightness falls
  // continuously around the light even where the forward lobe becomes shallow.
  const float t = angle.polarBlend;
  const float s = 1.0f - t;
  const float2 radiance = lerp(from.xy, to.xy, t * t * (3.0f - 2.0f * t))
    + (t * s) * (s * from.zw - t * to.zw);
  return lerp(radiance.x, radiance.y, angle.elevationBlend);
}

// The lower boundary sees the same phase quadrature reflected about the horizon.
public float2 cloudDiffuseBoundaryResponse(float2 depth, uint knot, float multiple, float strength, uint layer)
{
  const uint base = 4 * cloudPhaseMaterial(layer);
  const float4 upper = kCloudPhaseSkyWeights[base + knot];
  const float4 lower = kCloudPhaseSkyWeights[base + 3 - knot];
  const float2 single = float2(dot(upper, exp(-depth.x * kCloudPhaseSkyAirmass[base + knot])),
    dot(lower, exp(-depth.y * kCloudPhaseSkyAirmass[base + 3 - knot]))) / dot(upper + lower, float4(1));
  // Unit-albedo droplets conserve equal illumination from both boundaries.
  const float residual = saturate(1.0f - single.x - single.y);
  const float sky = clamp(multiple, 0.0f, residual);
  return single + strength * float2(sky, residual - sky);
}

public CloudNeuralAngle cloudSkyAngle(float viewUp)
{
  const float coordinate = saturate(0.5f * (viewUp + 1.0f)) * 3.0f;
  const uint interval = min(uint(coordinate), 2u);
  return { interval, coordinate - float(interval), 0 };
}

float2 sampleSkyAngles(Sampler3D<float4> field, float2 uv, float v, CloudNeuralAngle angle)
{
  const float4 upper = field.SampleLevel(float3(uv.x, v, uv.y), 0);
  const float4 lower = field.SampleLevel(float3(uv.x, v + 1.0f / CLOUD_NEURAL_TOTAL_BANDS, uv.y), 0);
  const uint i = angle.interval;
  return lerp(float2(upper[i], lower[i]), float2(upper[i+1], lower[i+1]), angle.polarBlend);
}

public float2 cloudNeuralSky(CloudArgs args, Sampler3D<float4> field, float3 position, float altitude,
  CloudNeuralAngle angle, uint layer)
{
  const float bottom = layer == 0 ? args.baseHeight : args.highBaseHeight;
  const float thickness = layer == 0 ? args.thickness : args.highThickness;
  const float height = saturate((altitude - bottom) / thickness);
  const float row = cloudNeuralRowOffset(layer) + 0.5f + height * float(cloudNeuralHeightSamples(layer) - 1);
  const float v = (CLOUD_NEURAL_SKY_BAND + row / CLOUD_NEURAL_COLUMN_HEIGHT) / CLOUD_NEURAL_TOTAL_BANDS;
  const float2 horizontal = cloudLightHorizontal(args, position - args.groundReference);
  float2 radiance = 0;
  float remaining = 1;
  [unroll]
  for (uint cascade = 0; cascade < 3; cascade++)
  {
    const NeuralGrid grid = neuralGrid(args, layer, cascade);
    const float2 cell = (horizontal - grid.origin) / grid.spacing;
    const float coverage = cascade == 2 ? 1.0f : cloudLightGridCoverage(cell / float(grid.side));
    if (coverage > 0)
    {
      // Clamp in tile space so hardware filtering cannot pull from its neighbor.
      const float2 uv = (float2(grid.tile) + clamp(cell, 0.5f, float(grid.side) - 0.5f))
        / float2(CLOUD_NEURAL_ATLAS_WIDTH, CLOUD_NEURAL_ATLAS_DEPTH);
      radiance += (remaining * coverage) * sampleSkyAngles(field, uv, v, angle);
      if (coverage == 1)
        break;
      remaining *= 1.0f - coverage;
    }
  }
  return radiance;
}

// The solar source is tabulated over the medium seen at the interaction.
// X and Z encode optical depth toward the light and into the cloud along
// the view ray. Rows measure the optical thickness of the surrounding column.
static const float3 kSolarMaxDepth = float3(24, 24, 128);

public float3 cloudSolarStateAtNode(uint3 cell, uint layer)
{
  const uint row = cell.y % CLOUD_NEURAL_COLUMN_HEIGHT - cloudNeuralRowOffset(layer);
  const float3 node = float3(float(cell.x) / (CLOUD_NEURAL_ATLAS_WIDTH - 1),
    float(cell.z) / (CLOUD_NEURAL_ATLAS_DEPTH - 1), float(row) / float(cloudNeuralHeightSamples(layer) - 1));
  return node * log(1.0f + kSolarMaxDepth);
}

public float cloudNeuralDiffuse(Sampler3D<float4> field, float3 opticalDepth,
  CloudNeuralAngle angle, uint layer, uint light)
{
  const float2 size = float2(CLOUD_NEURAL_ATLAS_WIDTH, CLOUD_NEURAL_ATLAS_DEPTH);
  const float3 state = saturate(log(1.0f + opticalDepth) / log(1.0f + kSolarMaxDepth));
  const float2 uv = (0.5f + state.xy * (size - 1.0f)) / size;
  const float row = cloudNeuralRowOffset(layer) + 0.5f + state.z * float(cloudNeuralHeightSamples(layer) - 1);
  const float v = (float(light * CLOUD_NEURAL_POLAR_KNOTS) + row / CLOUD_NEURAL_COLUMN_HEIGHT) / CLOUD_NEURAL_TOTAL_BANDS;
  return sampleNeuralAngles(field, uv, v, angle);
}
