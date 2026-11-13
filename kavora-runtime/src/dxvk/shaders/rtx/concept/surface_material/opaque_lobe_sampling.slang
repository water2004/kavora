/*
* Copyright (c) 2023-2026, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/
module opaque_lobe_sampling;

import "rtx/concept/surface_material/material_scattering.slang";
import "rtx/concept/surface_material/surface_material_types.slang";
import "rtx/concept/surface_material/subsurface_material.slang";
import "rtx/utility/color_transfer.slang";
import "rtx/utility/material_optics.slang";

public struct OpaqueLobeSampling
{
  public LobeSamplingRange diffuseReflection;
  public LobeSamplingRange specularReflection;
  public LobeSamplingRange opacityTransmission;
  public LobeSamplingRange diffuseTransmission;
};

[ForceInline]
public bool opaqueSurfaceMaterialInteractionCalcLobeProbability(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  float3 viewDirection,
  OpaqueLobeSampling sampling,
  out float diffuseReflectionProbability,
  out float specularReflectionProbability,
  out float opacityTransmissionProbability,
  out float diffuseTransmissionProbability)
{
  const bool isThinOpaqueSubsurface = isThinOpaqueSubsurfaceMaterial(opaqueSurfaceMaterialInteraction);

  // Calculate dot products used for evaluation

  // Note: Normal dot output direction used as a "best" approximation in this case, usually aligned with the actual sampled
  // microfacet normal on surfaces with low roughness (identical at 0), but will become more and more potentially inaccurate
  // as roughness increases.
  const float /* n.v */ normalDotOutputDirection =
    dot(opaqueSurfaceMaterialInteraction.shadingNormal, viewDirection);

  // Compute material-related quantities

  // Note: Albedo and Base Reflectivity already have opacity baked into them which is why opacity does not need to be specified
  // in the albedo approximations below.
  const float3 albedo = opaqueSurfaceMaterialInteraction.albedo;
  const float3 fresnel = evalOpaqueSchlickFresnel(opaqueSurfaceMaterialInteraction.baseReflectivity,
    normalDotOutputDirection);

  // Note: I think these should be using a preintegrated albedo calculation (e.g. the EnvBRDF approximation for specular
  // albedo), but I am not 100% sure if that is the intended "ideal" quantity to be using for a lobe's weight (when only the outgoing
  // view direction is known, not the yet to be sampled input direction). For now, just using simplified albedo approximations as these
  // at least directly cancel with terms in the BRDF which should help reduce variance a little.
  // Note: Perceptual luminance also used to reduce tristimulus color to a single probability value to hopefully look better to
  // the human eye than a simple average. This is done at the very end of calculations so that interactions between colors take
  // effect rather than being reduced to a single value too soon.
  const float diffuseReflectionAlbedoApprox = calcBt709Luminance((float3(1.0f) - fresnel) * albedo);
  const float specularReflectionAlbedoApprox = calcBt709Luminance(fresnel);
  const float opacityTransmissionAlbedoApprox = 1.0f - opaqueSurfaceMaterialInteraction.opacity;
  const float diffuseTransmissionAlbedoApprox = calcBt709Luminance(float3(1.0f) - fresnel);

  // Calculate lobe probabilities

  diffuseReflectionProbability = diffuseReflectionAlbedoApprox;
  specularReflectionProbability = specularReflectionAlbedoApprox;
  opacityTransmissionProbability = opacityTransmissionAlbedoApprox;
  diffuseTransmissionProbability = isThinOpaqueSubsurface ? diffuseTransmissionAlbedoApprox : 0.0f;

  adjustProbabilityValue(diffuseReflectionProbability, sampling.diffuseReflection);
  adjustProbabilityValue(specularReflectionProbability, sampling.specularReflection);
  adjustProbabilityValue(opacityTransmissionProbability, sampling.opacityTransmission);
  adjustProbabilityValue(diffuseTransmissionProbability, sampling.diffuseTransmission);

  const float lobeProbabilitySum =
    opacityTransmissionProbability +
    diffuseTransmissionProbability +
    specularReflectionProbability +
    diffuseReflectionProbability;

  if (lobeProbabilitySum == 0.0f)
  {
    return false;
  }

  const float lobeProbabilityNormalizationFactor = 1.0f / lobeProbabilitySum;

  diffuseReflectionProbability *= lobeProbabilityNormalizationFactor;
  specularReflectionProbability *= lobeProbabilityNormalizationFactor;
  opacityTransmissionProbability *= lobeProbabilityNormalizationFactor;
  diffuseTransmissionProbability *= lobeProbabilityNormalizationFactor;

  return true;
}

[ForceInline]
public SurfaceMaterialInteractionLobeSample opaqueSurfaceMaterialInteractionCalcLobeSample(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  float random,
  float3 viewDirection,
  OpaqueLobeSampling sampling)
{

  float diffuseReflectionProbability = 0.0f;
  float specularReflectionProbability = 0.0f;
  float opacityTransmissionProbability = 0.0f;
  float diffuseTransmissionProbability = 0.0f;

  const bool isValid = opaqueSurfaceMaterialInteractionCalcLobeProbability(
    opaqueSurfaceMaterialInteraction, viewDirection, sampling,
    diffuseReflectionProbability, specularReflectionProbability, opacityTransmissionProbability, diffuseTransmissionProbability);

  SurfaceMaterialInteractionLobeSample surfaceMaterialInteractionLobeSample;

  if (!isValid)
  {
    // Note: Default to sampling specular lobe when no lobe desires to be sampled from. Ideally this could just be skipped entirely as
    // a sample though, but we currently do not support that logic, so for now we pick specular since it will generate more coherent
    // rays ideally to minimize the performance impact.
    surfaceMaterialInteractionLobeSample.lobe = opaqueLobeTypeSpecularReflection;
    surfaceMaterialInteractionLobeSample.pdf = 0.0f;

    return surfaceMaterialInteractionLobeSample;
  }

  // Sample a lobe

  const float lobeSample = random;

  // Note: < used for conditions to ensure lobes never have a chance to be sampled from if their
  // probability is 0 (and to prevent NaNs from appearing due to 0 PDF).
  if (lobeSample < opacityTransmissionProbability)
  {
    surfaceMaterialInteractionLobeSample.lobe = opaqueLobeTypeOpacityTransmission;
    surfaceMaterialInteractionLobeSample.pdf = opacityTransmissionProbability;
  }
  else if (lobeSample < (opacityTransmissionProbability + diffuseTransmissionProbability)) {
    surfaceMaterialInteractionLobeSample.lobe = opaqueLobeTypeDiffuseTransmission;
    surfaceMaterialInteractionLobeSample.pdf = diffuseTransmissionProbability;
  }
  else if (lobeSample < (opacityTransmissionProbability + diffuseTransmissionProbability + specularReflectionProbability))
  {
    surfaceMaterialInteractionLobeSample.lobe = opaqueLobeTypeSpecularReflection;
    surfaceMaterialInteractionLobeSample.pdf = specularReflectionProbability;
  }
  else
  {
    surfaceMaterialInteractionLobeSample.lobe = opaqueLobeTypeDiffuseReflection;
    surfaceMaterialInteractionLobeSample.pdf = diffuseReflectionProbability;
  }

  return surfaceMaterialInteractionLobeSample;
}
