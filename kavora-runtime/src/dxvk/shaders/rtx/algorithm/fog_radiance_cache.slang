/* SPDX-License-Identifier: MIT */
module fog_radiance_cache;

import "rtx/algorithm/fog_froxel_sampling.slang";
import "rtx/pass/volume_parameters.slang";
import "rtx/concept/camera/volume_camera.slang";
import "rtx/utility/depth_slice.slang";

#include "rtx/concept/light/spherical_harmonics.slangh"

// Frame camera selection stays at the call boundary. The imported module owns
// the independent projected ray and froxel coordinate calculations.
public FogRayProjection createFogRayProjection(VolumeArgs args, float3 origin, float3 direction, uint volumeHint)
{
  FogRayProjection ray;
  ray.volume = volumeHint % args.numActiveFroxelVolumes;
  VolumeDefinitionCamera camera = args.cameras[ray.volume];
  float3 translatedOrigin = worldToTranslatedWorld(camera, origin);
  ray.projectionOrigin = mul(camera.translatedWorldToProjectionJittered, float4(translatedOrigin, 1)).xyw;
  ray.projectionDirection = mul(camera.translatedWorldToProjectionJittered, float4(direction, 0)).xyw;
  float viewSign = (camera.flags & rightHandedFlag) != 0 ? -1.0f : 1.0f;
  ray.originDepth = mul(camera.translatedWorldToView, float4(translatedOrigin, 1)).z * viewSign;
  ray.directionDepth = mul(camera.translatedWorldToView, float4(direction, 0)).z * viewSign;
  ray.nearPlane = camera.nearPlane;
  return ray;
}

public struct FogRadianceSample
{
  SphericalHarmonic radiance;
  bool found;

  public float3 evaluate(float4 projectedPhase, float3 multiScattering)
  {
    return found
      ? max(0.0f, radiance.evaluateHenyeyGreenstein(projectedPhase)) + multiScattering
      : multiScattering;
  }
};

FogRadianceSample fogSampleIncidentRadiance(VolumeArgs args, Sampler3D<float4> radianceY,
  Sampler3D<float2> radianceCoCg, float3 uvw, uint volume)
{
  uvw.z = min(uvw.z, 1.0f - 0.5f / args.froxelDepthSlices);
  uvw = virtualFroxelUVWToPhysicalFroxelUVW(uvw, volume,
    args.minFilteredRadianceU, args.maxFilteredRadianceU, args.inverseNumFroxelVolumes);
  FogRadianceSample sample;
  sample.radiance = HarmonicsHelpers::loadFiltered3D<SphericalHarmonic>(radianceY, radianceCoCg, uvw);
  sample.found = true;
  return sample;
}

public FogRadianceSample fogIncidentRadiance(VolumeArgs args, Sampler3D<float4> radianceY, Sampler3D<float2> radianceCoCg,
  float3 position, FogRayProjection firstVolumeRay, float distance)
{
  float3 uvw = fogRayFroxelUVW(args.froxelDepthSlices, args.froxelDepthSliceDistributionExponent,
    args.froxelMaxDistance, firstVolumeRay, distance);
  if (fogFroxelContains(uvw))
  {
    return fogSampleIncidentRadiance(args, radianceY, radianceCoCg, uvw, firstVolumeRay.volume);
  }
  for (uint i = 1; i < args.numActiveFroxelVolumes; i++)
  {
    uint volume = (i + firstVolumeRay.volume) % args.numActiveFroxelVolumes;
    VolumeDefinitionCamera camera = args.cameras[volume];
    uvw = translatedWorldPositionToFroxelUVW(camera.translatedWorldToView,
      camera.translatedWorldToProjectionJittered, (camera.flags & rightHandedFlag) != 0,
      args.froxelDepthSlices, args.froxelDepthSliceDistributionExponent, args.froxelMaxDistance, camera.nearPlane,
      worldToTranslatedWorld(camera, position));
    if (!fogFroxelContains(uvw)) continue;
    return fogSampleIncidentRadiance(args, radianceY, radianceCoCg, uvw, volume);
  }
  FogRadianceSample sample;
  sample.radiance = SphericalHarmonic::create();
  sample.found = false;
  return sample;
}

public float4 projectFogPhase(float anisotropy, float3 direction)
{
  SphericalHarmonic phase;
  return float4(anisotropy.xxx, 1.0f) * phase.evalShBasis(direction);
}
