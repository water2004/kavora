// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: Apache-2.0
// Adapted from Kavora's sky_atmosphere.comp, using its optical model and tables.
module air_medium;

import "rtx/pass/air_parameters.slang";

static const float kAirPi = 3.141592653589793f;
public static const uint kAirViewSteps = 8;

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

public interface IAirLightVisibility
{
  static float visibility(AirArgs args, uint light, float3 position);
};

// Sky-only transport fixtures have no scene geometry.
public struct UnobstructedAir : IAirLightVisibility
{
  public static float visibility(AirArgs args, uint light, float3 position) { return 1; }
};

public AirMedium sampleAirMedium<Visibility : IAirLightVisibility>(AirArgs args, AirTables tables,
  float3 position, float3 shadowPosition, AirRayPhase phase)
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
        * Visibility.visibility(args, index, shadowPosition)
        + (rayleigh + mie * args.mieAlbedo) * airMultipleScattering(args, tables, height, mu));
    }
  }
  medium.source *= args.worldToKilometers;
  return medium;
}

float2 outwardAirMass(AirArgs args, float height, float cosine, float2 scaleHeight)
{
  const float2 c = sqrt(0.5f * kAirPi * (args.innerRadius + height) / scaleHeight);
  const float2 relativeDensity = exp((args.lowerDepth - height) / scaleHeight);
  return scaleHeight * relativeDensity * c / (1.0f + (c - 1.0f) * cosine);
}

// Absolute optical-depth prefixes share the same left endpoint and tangent.
public struct AirOpticalDepthRay
{
  public float3 start;
  public float startDistance;
  public float startHeight;
  public float startAlong;
  public float tangentHeight;
  public float2 startMass;
  public float2 tangentMass;

  public float3 prefix(AirArgs args, float3 direction, float distance)
  {
    const float span = (distance - startDistance) * args.worldToKilometers;
    const float endHeight = airAltitude(args, start + direction * span);
    const float endAlong = startAlong + span;
    const bool crossesTangent = startAlong < 0 && endAlong > 0;
    const float minimum = crossesTangent ? tangentHeight : min(startHeight, endHeight);
    const float2 scaleHeight = float2(args.rayleighHeight, args.mieHeight);
    const bool2 nearlyConstant = max(startHeight, endHeight) - minimum < 0.002f * scaleHeight;
    float2 mass = 0;
    if (!all(nearlyConstant))
    {
      const float2 endMass = outwardAirMass(args, endHeight, abs(endAlong) / (args.innerRadius + endHeight), scaleHeight);
      mass = crossesTangent ? 2.0f * tangentMass - startMass - endMass
        : startAlong >= 0 ? startMass - endMass : endMass - startMass;
      mass = max(0.0f, mass);
    }
    if (any(nearlyConstant))
    {
      const float midpoint = airAltitude(args, start + (0.5f * span) * direction);
      const float2 shortMass = span * exp((args.lowerDepth - midpoint) / scaleHeight);
      mass = float2(nearlyConstant.x ? shortMass.x : mass.x, nearlyConstant.y ? shortMass.y : mass.y);
    }
    return args.rayleighExtinction * mass.x + args.mieExtinction * mass.y;
  }
};

public AirOpticalDepthRay createAirOpticalDepthRay(AirArgs args, float3 origin, float3 direction, float2 interval)
{
  AirOpticalDepthRay optical = {};
  optical.startDistance = interval.x;
  optical.start = airCoordinates(args, origin) + direction * (interval.x * args.worldToKilometers);
  optical.startHeight = airAltitude(args, optical.start);
  optical.startAlong = dot(optical.start, direction) + args.innerRadius * dot(args.up, direction);
  const float2 scaleHeight = float2(args.rayleighHeight, args.mieHeight);
  optical.startMass = outwardAirMass(args, optical.startHeight,
    abs(optical.startAlong) / (args.innerRadius + optical.startHeight), scaleHeight);
  const float span = (interval.y - interval.x) * args.worldToKilometers;
  if (optical.startAlong < 0 && optical.startAlong + span > 0)
  {
    optical.tangentHeight = airAltitude(args, optical.start - optical.startAlong * direction);
    optical.tangentMass = outwardAirMass(args, optical.tangentHeight, 0, scaleHeight);
  }
  return optical;
}

public float3 airSegmentOpticalDepth(AirArgs args, float3 origin, float3 direction, float2 interval)
{
  return createAirOpticalDepthRay(args, origin, direction, interval).prefix(args, direction, interval.y);
}

public float3 airTransmittance(AirArgs args, float3 origin, float3 direction, float distance)
{
  if (!args.enable)
  {
    return 1;
  }
  const float2 interval = airInterval(args, origin, direction);
  const float end = min(interval.y, distance);
  return end > interval.x ? exp(-airSegmentOpticalDepth(args, origin, direction, float2(interval.x, end))) : 1;
}
