// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: Apache-2.0
// Adapted from Kavora's sky_atmosphere.comp, using its optical model and tables.
module air_medium;

import "rtx/pass/air_parameters.slang";

static const float kAirPi = 3.141592653589793f;
public static const uint kAirViewSteps = 32;

public struct AirTables
{
  public Texture2D<float4> transmittance;
  public Texture2D<float4> scattering;
  public SamplerState sampler;
};

public struct AirMedium
{
  public float3 extinction;
  public float3 source;
};

float airRadiusDifference(AirArgs args, float height)
{
  return height * (2.0f * args.innerRadius + height);
}

public float3 airCoordinates(AirArgs args, float3 position)
{
  return (position - args.groundReference) * args.worldToKilometers + args.lowerDepth * args.up;
}

public float airAltitude(AirArgs args, float3 coordinates)
{
  const float difference = dot(coordinates, coordinates) + 2.0f * args.innerRadius * dot(coordinates, args.up);
  return max(0.0f, difference / (sqrt(max(0.0f, args.innerRadius * args.innerRadius + difference)) + args.innerRadius));
}

public float3 airVertical(AirArgs args, float3 coordinates)
{
  return normalize(coordinates + args.innerRadius * args.up);
}

float2 airSphereRoots(AirArgs args, float3 origin, float3 direction, float height)
{
  const float b = dot(origin, direction) + args.innerRadius * dot(direction, args.up);
  const float vertical = dot(origin, args.up);
  const float3 horizontal = origin - args.up * vertical;
  const float c = dot(horizontal, horizontal) + (vertical - height) * (2.0f * args.innerRadius + vertical + height);
  const float discriminant = b * b - c;
  if (discriminant < 0)
  {
    return float2(1, -1);
  }
  const float root = sqrt(discriminant);
  const float q = -b - (b >= 0 ? root : -root);
  const float other = abs(q) > 1e-8f ? c / q : -b;
  return float2(min(q, other), max(q, other));
}

// The lower optical boundary is deep air, not a visible ground proxy.
public float2 airInterval(AirArgs args, float3 origin, float3 direction)
{
  if (!args.enable)
  {
    return 0;
  }
  const float3 coordinates = airCoordinates(args, origin);
  const float2 top = airSphereRoots(args, coordinates, direction, args.atmosphereHeight);
  const float begin = max(0.0f, top.x);
  float end = top.y;
  const float2 lower = airSphereRoots(args, coordinates, direction, 0);
  if (lower.x >= begin && lower.y >= lower.x)
  {
    end = min(end, lower.x);
  }
  return end > begin ? float2(begin, end) / args.worldToKilometers : float2(0);
}

public struct AirRayGrid
{
  public float2 interval;

  public float boundary(uint index)
  {
    // The terminal cell must end exactly at the interval exit. Reconstructing
    // it as start + (exit - start) can round down and strand the merged grid.
    if (index >= kAirViewSteps)
    {
      return interval.y;
    }
    const float fraction = float(index) / float(kAirViewSteps);
    return interval.x + (interval.y - interval.x) * fraction * fraction;
  }

  public uint cellAt(float distance)
  {
    const float fraction = saturate((distance - interval.x) / max(interval.y - interval.x, 1e-6f));
    uint cell = min(uint(sqrt(fraction) * float(kAirViewSteps)), kAirViewSteps - 1);
    if (cell > 0 && boundary(cell) > distance)
    {
      cell--;
    }
    if (cell + 1 < kAirViewSteps && boundary(cell + 1) <= distance)
    {
      cell++;
    }
    return cell;
  }

  public float sampleAt(uint index)
  {
    return 0.5f * (boundary(index) + boundary(index + 1));
  }
};

float airHeightCoordinate(AirArgs args, float height)
{
  const float h = height - args.lowerDepth;
  return h < 0 ? 0.5f - 0.5f * sqrt(saturate(-h / args.lowerDepth))
    : 0.5f + 0.5f * sqrt(saturate(h / (args.atmosphereHeight - args.lowerDepth)));
}

float2 airTexelUv(float2 fraction, float2 extent)
{
  return (saturate(fraction) * (extent - 1.0f) + 0.5f) / extent;
}

float airLowerBoundaryCosine(AirArgs args, float height)
{
  return -sqrt(airRadiusDifference(args, height)) / (args.innerRadius + height);
}

public float3 airTransmittanceToSpace(AirArgs args, AirTables tables, float height, float lightCosine)
{
  const float cosine = max(lightCosine, airLowerBoundaryCosine(args, height));
  const float radius = args.innerRadius + height;
  const float rho = sqrt(airRadiusDifference(args, height));
  const float top = sqrt(airRadiusDifference(args, args.atmosphereHeight));
  const float distance = -radius * cosine + sqrt(max(0.0f, radius * radius * cosine * cosine
    + airRadiusDifference(args, args.atmosphereHeight) - airRadiusDifference(args, height)));
  const float minimum = args.atmosphereHeight - height;
  const float2 fraction = float2((distance - minimum) / max(rho + top - minimum, 1e-6f),
    airHeightCoordinate(args, height));
  return tables.transmittance.SampleLevel(tables.sampler,
    airTexelUv(fraction, float2(args.transmittanceExtent)), 0).rgb;
}

float3 airTransmittanceToLight(AirArgs args, AirTables tables, float height, float cosine, float angularRadius)
{
  const float extent = sin(angularRadius) * sqrt(max(0.0f, 1.0f - cosine * cosine));

  // Away from the horizon, the finite solar/lunar disk is a very small angular
  // perturbation of the central ray. A single LUT lookup is then a much cheaper
  // and well-conditioned estimate than four-point disk quadrature. Keep the
  // full quadrature for grazing rays, partially occulted disks, and non-celestial
  // emitters: those are precisely where angular variation matters most.
  // The CPU regression test bounds this branch for radii up to 0.01 rad and
  // light cosines >= 0.45 against the existing four-point rule.
  const float lowerBoundary = airLowerBoundaryCosine(args, height);
  if (angularRadius <= 0.01f && cosine >= 0.45f && cosine - extent > lowerBoundary)
  {
    return airTransmittanceToSpace(args, tables, height, cosine);
  }

  const float first = clamp((lowerBoundary - cosine) / max(extent, 1e-6f), -1.0f, 1.0f);
  const float visible = (acos(first) - first * sqrt(max(0.0f, 1.0f - first * first))) / kAirPi;
  if (visible <= 0)
  {
    return 0;
  }
  const float4 nodes = float4(-0.861136312f, -0.339981044f, 0.339981044f, 0.861136312f);
  const float4 weights = float4(0.347854845f, 0.652145155f, 0.652145155f, 0.347854845f);
  const float centre = 0.5f * (1.0f + first);
  const float radius = 0.5f * (1.0f - first);
  float3 sum = 0;
  float normalization = 0;
  [unroll]
  for (uint index = 0; index < 4; index++)
  {
    const float chord = centre + radius * nodes[index];
    const float weight = weights[index] * sqrt(max(0.0f, 1.0f - chord * chord));
    sum += weight * airTransmittanceToSpace(args, tables, height, cosine + extent * chord);
    normalization += weight;
  }
  return sum * (visible / max(normalization, 1e-8f));
}

float3 airMultipleScattering(AirArgs args, AirTables tables, float height, float cosine)
{
  const float2 fraction = float2(0.5f + 0.5f * sign(cosine) * sqrt(abs(cosine)),
    airHeightCoordinate(args, height));
  return tables.scattering.SampleLevel(tables.sampler,
    airTexelUv(fraction, float(args.scatteringSize)), 0).rgb;
}

public float3 airExtinction(AirArgs args, float3 position)
{
  const float height = airAltitude(args, airCoordinates(args, position)) - args.lowerDepth;
  const float3 rayleigh = args.rayleighExtinction * exp(-height / args.rayleighHeight);
  const float mie = args.mieExtinction * exp(-height / args.mieHeight);
  return (rayleigh + mie) * args.worldToKilometers;
}

// Scattering phase depends on the view-ray direction and light directions,
// not on the sample position. Compute it once per atmosphere ray instead of
// repeating two phase evaluations (including the Mie pow) at every air cell.
public struct AirRayPhase
{
  public float2 rayleigh;
  public float2 mie;
};

public AirRayPhase createAirRayPhase(AirArgs args, float3 direction)
{
  AirRayPhase phase = {};
  const float g = args.mieAnisotropy;
  [unroll]
  for (uint index = 0; index < 2; index++)
  {
    if (any(args.lights[index].irradiance > 0))
    {
      const float cosine = dot(direction, args.lights[index].directionToLight);
      const float cosineSquared = cosine * cosine;
      phase.rayleigh[index] = 3.0f / (16.0f * kAirPi) * (1.0f + cosineSquared);
      phase.mie[index] = 3.0f / (8.0f * kAirPi) * (1.0f - g * g) * (1.0f + cosineSquared)
        / ((2.0f + g * g) * pow(1.0f + g * g - 2.0f * g * cosine, 1.5f));
    }
  }
  return phase;
}

public AirMedium sampleAirMedium(AirArgs args, AirTables tables, float3 position, float3 direction,
  AirRayPhase phase)
{
  const float3 coordinates = airCoordinates(args, position);
  const float height = airAltitude(args, coordinates);
  const float3 up = airVertical(args, coordinates);
  const float seaHeight = height - args.lowerDepth;
  const float3 rayleigh = args.rayleighExtinction * exp(-seaHeight / args.rayleighHeight);
  const float mie = args.mieExtinction * exp(-seaHeight / args.mieHeight);
  AirMedium medium;
  medium.extinction = (rayleigh + mie) * args.worldToKilometers;
  medium.source = 0;
  [unroll]
  for (uint index = 0; index < 2; index++)
  {
    const AirLight light = args.lights[index];
    if (any(light.irradiance > 0))
    {
      const float mu = dot(up, light.directionToLight);
      medium.source += light.irradiance * ((rayleigh * phase.rayleigh[index]
        + mie * args.mieAlbedo * phase.mie[index])
        * airTransmittanceToLight(args, tables, height, mu, light.angularRadius)
        + (rayleigh + mie * args.mieAlbedo) * airMultipleScattering(args, tables, height, mu));
    }
  }
  medium.source *= args.worldToKilometers;
  return medium;
}

// Compatibility entry point for callers that only have a single sample.
public AirMedium sampleAirMedium(AirArgs args, AirTables tables, float3 position, float3 direction)
{
  return sampleAirMedium(args, tables, position, direction, createAirRayPhase(args, direction));
}

// Uses the same absolute cells as radiance integration, including clipped prefixes.
public float3 airTransmittance(AirArgs args, float3 origin, float3 direction, float distance)
{
  if (!args.enable)
  {
    return 1;
  }
  const AirRayGrid grid = { airInterval(args, origin, direction) };
  const float end = min(grid.interval.y, distance);
  float3 opticalDepth = 0;
  for (uint cell = 0; cell < kAirViewSteps && grid.boundary(cell) < end; cell++)
  {
    const float span = min(grid.boundary(cell + 1), end) - grid.boundary(cell);
    opticalDepth += airExtinction(args, origin + direction * grid.sampleAt(cell)) * span;
  }
  return exp(-opticalDepth);
}
