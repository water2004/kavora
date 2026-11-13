/* SPDX-License-Identifier: MIT */
module clouds;

import "rtx/pass/cloud_parameters.slang";
import "rtx/utility/cloud_density.slang";

static const float kFloatMax = 3.402823466e+38f;

[ForceInline]
CloudDensityArgs cloudDensityParameters(CloudArgs args)
{
  CloudDensityArgs density;
  density.groundReference = args.groundReference;
  density.up = args.up;
  density.planetRadius = args.planetRadius;
  density.windOffset = args.windOffset;
  density.shapeScale = args.shapeScale;
  density.baseHeight = args.baseHeight;
  density.thickness = args.thickness;
  density.coverage = args.coverage;
  density.weather = args.weather;
  density.thunder = args.thunder;
  density.night = args.night;
  return density;
}

float2 cloudSphereRoots(CloudArgs args, float3 origin, float3 direction, float altitude)
{
  const float3 p = origin - args.groundReference;
  const float h = dot(p, args.up);
  const float3 horizontal = p - h * args.up;
  const float b = dot(horizontal, direction) + (args.planetRadius + h) * dot(direction, args.up);
  const float c = dot(horizontal, horizontal) + (h - altitude) * (2.0f * args.planetRadius + h + altitude);
  const float discriminant = b * b - c;
  if (discriminant < 0)
  {
    return -1;
  }
  const float root = sqrt(discriminant);
  const float q = -b - (b >= 0 ? root : -root);
  if (abs(q) < 1e-6f)
  {
    return -b;
  }
  const float other = c / q;
  return float2(min(q, other), max(q, other));
}

float2 cloudOuterInterval(CloudArgs args, float3 origin, float3 direction, float distance)
{
  if (!args.enable || args.extinction <= 0)
  {
    return 0;
  }
  const float2 outer = cloudSphereRoots(args, origin, direction, args.baseHeight + args.thickness);
  return float2(max(0.0f, outer.x), min(min(distance, args.maxDistance), outer.y));
}

// View integration consumes the nearest continuous interval of the shell.
public float2 cloudInterval(CloudArgs args, float3 origin, float3 direction, float distance)
{
  const float2 outer = cloudOuterInterval(args, origin, direction, distance);
  if (outer.y <= outer.x)
  {
    return 0;
  }
  const float2 inner = cloudSphereRoots(args, origin, direction, args.baseHeight);
  float begin = outer.x;
  float end = outer.y;
  if (inner.y > begin)
  {
    if (inner.x <= begin)
    {
      begin = inner.y;
    }
    else
    {
      end = min(end, inner.x);
    }
  }
  return end > begin ? float2(begin, end) : float2(0);
}

public float cloudExtinction(CloudArgs args, float3 position, Sampler2D<float4> weatherSampler,
  Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  return args.extinction * cumulusDensity(cloudDensityParameters(args), position, weatherSampler,
    baseSampler, detailSampler);
}

public float cloudSampleSpacing(CloudArgs args, float3 direction)
{
  const float horizontalSpacing = args.shapeScale * 2.5f / float(args.steps);
  const float verticalSpacing = args.thickness * 0.125f / max(abs(dot(direction, args.up)), 1e-4f);
  return min(horizontalSpacing, verticalSpacing);
}

public static const float kOpaqueCloudTransmittance = 1e-8f;

// Clipped prefixes share the full ray's cells and density samples.
public struct CloudRayGrid {
  public float2 interval;
  public float spacing;

  public uint cellAt(float distance) {
    return uint(floor(max(0.0f, distance - interval.x) / spacing));
  }

  public float boundary(uint index) {
    return min(interval.y, interval.x + float(index) * spacing);
  }

  public float sampleAt(uint index) {
    return 0.5f * (boundary(index) + boundary(index + 1));
  }
};

public float cloudTransmittance(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  const CloudRayGrid grid = { cloudInterval(args, origin, direction, args.maxDistance),
    cloudSampleSpacing(args, direction) };
  const float end = min(grid.interval.y, distance);
  float transmittance = 1;
  float previous = grid.interval.x;
  for (uint cell = 0; previous < end; cell++) {
    const float next = min(end, grid.boundary(cell + 1));
    const float extinction = cloudExtinction(args, origin + direction * grid.sampleAt(cell), weatherSampler,
      baseSampler, detailSampler);
    transmittance *= exp(-extinction * (next - previous));
    if (transmittance < kOpaqueCloudTransmittance) {
      break;
    }
    previous = next;
  }
  return transmittance;
}

float cloudLightOpticalDepth(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  float2 interval = cloudOuterInterval(args, origin, direction, distance);
  if (interval.y <= interval.x)
  {
    return 0;
  }
  // Skip the clear air before entering the layer. Once in the shell, keep the
  // sampling range intact even when the ray grazes the inner boundary.
  if (cloudAltitude(cloudDensityParameters(args), origin) < args.baseHeight)
  {
    interval.x = max(interval.x, cloudSphereRoots(args, origin, direction, args.baseHeight).y);
    if (interval.y <= interval.x)
    {
      return 0;
    }
  }
  // Concentrate self-shadow samples near the receiver while covering the entire layer.
  const float spacing = cloudSampleSpacing(args, direction);
  const float stretch = log(1.0f + (interval.y - interval.x) / spacing);
  float opticalDepth = 0;
  float previous = interval.x;
  for (uint i = 0; i < args.lightSteps; i++)
  {
    const float next = interval.x + spacing * (exp(stretch * float(i + 1) / float(args.lightSteps)) - 1.0f);
    const float t = (previous + next) * 0.5f;
    opticalDepth += cloudExtinction(args, origin + direction * t, weatherSampler, baseSampler, detailSampler)
      * (next - previous);
    previous = next;
  }
  return opticalDepth;
}

public float cloudVisibilityTransmittance(CloudArgs args, float3 origin, float3 direction, float distance,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  return exp(-cloudLightOpticalDepth(args, origin, direction, distance, weatherSampler, baseSampler, detailSampler));
}

float cloudPhase(float cosine, float g)
{
  const float denominator = max(1.0f + g * g - 2.0f * g * cosine, 0.001f);
  return (1.0f - g * g) / (4.0f * 3.14159265359f * denominator * sqrt(denominator));
}

public float3 cloudIncidentRadiance(CloudArgs args, float3 position, float3 direction, float3 ambientRadiance,
  Sampler2D<float4> weatherSampler, Sampler3D<float4> baseSampler, Sampler3D<float4> detailSampler)
{
  float3 source = ambientRadiance;
  for (uint lightIndex = 0; lightIndex < 2; lightIndex++)
  {
    const CloudLight light = args.lights[lightIndex];
    if (!any(light.irradiance > 0))
    {
      continue;
    }
    const float opticalDepth = cloudLightOpticalDepth(args, position, light.directionToLight, kFloatMax,
      weatherSampler, baseSampler, detailSampler);
    const float cosine = dot(direction, light.directionToLight);
    const float phase = 0.85f * cloudPhase(cosine, args.anisotropy) + 0.15f * cloudPhase(cosine, -0.25f);
    float scattering = phase * exp(-opticalDepth);
    float orderWeight = args.multipleScattering;
    const float meanCosine = 0.85f * args.anisotropy - 0.15f * 0.25f;
    float orderG = meanCosine;
    float orderDepth = opticalDepth;
    // Approximate diffuse incident light while preserving physical view extinction.
    // Successive phases retain the first moment of the full dual-lobe distribution.
    for (uint order = 2; order <= 5; order++)
    {
      orderG *= meanCosine;
      orderDepth *= 0.5f;
      scattering += orderWeight * cloudPhase(cosine, orderG) * exp(-orderDepth);
      orderWeight *= args.multipleScattering * args.albedo;
    }
    source += light.irradiance * scattering;
  }
  return source;
}
