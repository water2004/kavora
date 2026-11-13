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
module opaque_bsdf;

import "rtx/concept/surface_material/material_scattering.slang";
import "rtx/concept/surface_material/opaque_lobe_sampling.slang";
import "rtx/concept/surface_material/surface_material_types.slang";
import "rtx/concept/surface_material/subsurface_material.slang";
import "rtx/utility/math_constants.slang";
import "rtx/utility/quaternion.slang";
import "rtx/utility/material_optics.slang";

#include "rtx/utility/shader_types.h"
#include "rtx/utility/brdf.slangh"
#include "rtx/utility/packing.slangh"

// Opaque Surface Material Interaction Constants

// Note: Threshold value to use for considering the albedo zero or non-zero for some calculations.
static const vec3 albedoThreshold = vec3(1.0f / 256.0f);
// Note: Threshold value to use for considering the base reflectivity zero or non-zero for some calculations.
static const vec3 baseReflectivityThreshold = vec3(1.0f / 256.0f);
// Note: Threshold value to use for considering the roughness (not the perceptual roughness) zero or non-zero for some calculations.
// Must be greater than materialMinimumRoughness in the BRDF helpers as all roughnesses will be clamped to at minimum this value.
static const float roughnessThreshold = float(0.001f);

// All specular consumers use the same compile-time thin-film policy.
[ForceInline]
vec3 opaqueFresnel<let ThinFilm : bool>(OpaqueSurfaceMaterialInteraction material, float cosine)
{
  const vec3 fresnel = evalOpaqueSchlickFresnel(material.baseReflectivity, cosine);
  if (ThinFilm && material.thinFilmThickness > 0.0f)
  {
    const float refractiveIndex = baseReflectivityToIoR(materialIoRVacuum, calcBt709Luminance(material.baseReflectivity));
    const float thickness = material.thinFilmThickness * OPAQUE_SURFACE_MATERIAL_THIN_FILM_MAX_THICKNESS;
    return evalThinFilmFresnel(materialIoRVacuum, materialIoRThinFilmLayer, refractiveIndex, thickness, cosine);
  }
  return fresnel;
}

[ForceInline]
public SurfaceMaterialInteractionLobeInformation opaqueSurfaceMaterialInteractionGetLobeInformation(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction)
{
  SurfaceMaterialInteractionLobeInformation surfaceMaterialInteractionLobeInformation;

  surfaceMaterialInteractionLobeInformation.diffuseReflectionPresent =
    any(greaterThanEqual(opaqueSurfaceMaterialInteraction.albedo, albedoThreshold));
  surfaceMaterialInteractionLobeInformation.specularReflectionPresent =
    any(greaterThanEqual(opaqueSurfaceMaterialInteraction.baseReflectivity, baseReflectivityThreshold));
  surfaceMaterialInteractionLobeInformation.diffuseTransmissionPresent = false;
  surfaceMaterialInteractionLobeInformation.specularTransmissionPresent = false;

  surfaceMaterialInteractionLobeInformation.specularReflectionDirac =
    opaqueSurfaceMaterialInteraction.isotropicRoughness < roughnessThreshold;
  surfaceMaterialInteractionLobeInformation.specularTransmissionDirac = false;

  return surfaceMaterialInteractionLobeInformation;
}

[ForceInline]
public SurfaceMaterialInteractionSample opaqueSurfaceMaterialInteractionCalcDiffuseReflectionSample(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec2 u,
  vec4 tangentToWorldSpaceQuaternion,
  vec3 viewDirection)
{
  const vec4 worldToTangentSpaceQuaternion = quaternionInverse(tangentToWorldSpaceQuaternion);

  // Set up relevant input vectors in tangent space

  const vec3 outputDirection = quaternionTransformVector(worldToTangentSpaceQuaternion, viewDirection);

  // Sample a new input direction based on a cosine weighted hemisphere

  float cosineHemisphereSolidAnglePdf;
  const vec3 inputDirection = calcCosineHemisphereDirectionSample(u, cosineHemisphereSolidAnglePdf);

  // Todo: Sanity check if inputDirection is facing the wrong direction (Into the surface), currently this does not have much
  // effect and getting the triangle normal here requires a fairly high memory bandwidth cost, so for now it is not done

  // Calculate the microfacet normal from the input and output directions

  const vec3 microfacetNormal = normalize(outputDirection + inputDirection);

  // Calculate dot products used for evaluation
  // Note: Dot products against tangent space basis vectors are single components of the other vector

  const float /* l.v */ inputDirectionDotOutputDirection = dot(inputDirection, outputDirection);
  const float /* n.l */ normalDotInputDirection = inputDirection.z;
  const float /* n.v */ normalDotOutputDirection = outputDirection.z;
  const float /* n.h */ normalDotMicrofacetNormal = microfacetNormal.z;

  // Check if the sampled direction is valid
  // Note: Also checking the output direction here, in theory this should be valid with pure triangle normals, but normal maps
  // allow for surfaces to be viewed from outside the actual normal's hemisphere, resulting in an invalid sample.
  // Note: Input direction check here is currently not needed, diffuse sampling should always produce sampled directions valid within
  // the normal's hemisphere, but just kept here in case the sampling changes some day.

  // Note: < used rather than <= as parallel vectors to the triangle normal can be considered in the hemisphere without colliding
  // with the triangle. Convention can vary but in this case cosine sampling can produce parallel directions like this at times
  // so it's better to not reject those samples.
  if (normalDotOutputDirection < 0.0f || normalDotInputDirection < 0.0f)
  {
    SurfaceMaterialInteractionSample materialSample;

    materialSample.inputDirection = vec3(0.0, 0.0, 0.0);
    materialSample.throughput = vec3(0.0, 0.0, 0.0);
    materialSample.solidAnglePdf = 0.0f;

    return materialSample;
  }

  // Calculate the throughput of the sample

  const SubsurfaceMaterial subsurfaceMaterial = decodeSubsurfaceMaterial(opaqueSurfaceMaterialInteraction.subsurfaceMaterialInteraction);
  const bool isThinOpaqueSubsurface = isThinOpaqueSubsurfaceMaterial(opaqueSurfaceMaterialInteraction);

  // If we have lambert diffuse transmission lobe, we are actually doing lambert sampling on the whole sphere, so the diffuse reflection weight should be weighted with 1/2pi for energy conservation.
  // The original hammon diffuse does sampling on hemisphere, so we can just normalize the weight again with 1/2.
  const vec3 lambertTransmissionWeight = (isThinOpaqueSubsurface && subsurfaceMaterial.measurementDistance <= 0.05f) ? 0.5f : 1.0f;

  const vec3 weight =
    evalHammonDiffuse(opaqueSurfaceMaterialInteraction.albedo, opaqueSurfaceMaterialInteraction.isotropicRoughness,
      inputDirectionDotOutputDirection, normalDotInputDirection,
      normalDotOutputDirection, normalDotMicrofacetNormal) * lambertTransmissionWeight;
  const vec3 throughput =
    saferPositiveDivide(weight, vec3(cosineHemisphereSolidAnglePdf), materialEpsilon, materialMaximum) *
    normalDotInputDirection;

  // Return the material sample

  SurfaceMaterialInteractionSample materialSample;

  materialSample.inputDirection = quaternionTransformVector(tangentToWorldSpaceQuaternion, inputDirection);
  materialSample.throughput = throughput;
  materialSample.solidAnglePdf = cosineHemisphereSolidAnglePdf;

  return materialSample;
}

[ForceInline]
public SurfaceMaterialInteractionSample opaqueSurfaceMaterialInteractionCalcSpecularReflectionSample<let ThinFilm : bool>(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec2 u,
  vec4 tangentToWorldSpaceQuaternion,
  vec3 viewDirection)
{
  const vec4 worldToTangentSpaceQuaternion = quaternionInverse(tangentToWorldSpaceQuaternion);

  // Set up relevant input vectors in tangent space

  const vec3 outputDirection = quaternionTransformVector(worldToTangentSpaceQuaternion, viewDirection);

  // Sample a new microfacet normal based on the distribution of visible normals

  // Note: Manually calculate the lower bound scaling constant rather than using the usual calcGGXBoundedVisibleNormalDistributionSample
  // helper function. This is done so that this k value only needs to be computed once, as it is also needed for how the PDF is calculated
  // later (which is worked into the throughput math here).
  const float k = calcBoundedVNDFLowerBoundScalar(opaqueSurfaceMaterialInteraction.anisotropicRoughness, outputDirection);

  const vec3 microfacetNormal = calcGGXVisibleNormalDistributionSphericalCapSample(
    opaqueSurfaceMaterialInteraction.anisotropicRoughness,
    outputDirection,
    u,
    k);

  // Reflect the output direction across the microfacet normal to get the input direction

  const vec3 inputDirection = reflect(-outputDirection, microfacetNormal);

  // Todo: Sanity check if inputDirection is facing the wrong direction (Into the surface), currently this does not have much
  // effect and getting the triangle normal here requires a fairly high memory bandwidth cost, so for now it is not done

  // Calculate dot products used for evaluation
  // Note: Dot products against tangent space basis vectors are single components of the other vector

  const float /* v.h */ outputDirectionDotMicrofacetNormal = dot(outputDirection, microfacetNormal);
  const float /* t.v */ tangentDotOutputDirection = outputDirection.x;
  const float /* b.v */ bitangentDotOutputDirection = outputDirection.y;
  const float /* n.v */ normalDotOutputDirection = outputDirection.z;
  const float /* t.l */ tangentDotInputDirection = inputDirection.x;
  const float /* b.l */ bitangentDotInputDirection = inputDirection.y;
  const float /* n.l */ normalDotInputDirection = inputDirection.z;
  const float /* t.h */ tangentDotMicrofacetNormal = microfacetNormal.x;
  const float /* b.h */ bitangentDotMicrofacetNormal = microfacetNormal.y;
  const float /* n.h */ normalDotMicrofacetNormal = microfacetNormal.z;

  // Check if the sampled direction is valid
  // Note: Also checking the output direction here, in theory this should be valid with pure triangle normals, but normal maps
  // allow for surfaces to be viewed from outside the actual normal's hemisphere, resulting in an invalid sample. Additionally,
  // while bounded VNDF sampling does not produce any invalid directions outside the normal's hemisphere in normal cases, it
  // is not tightly bounded in cases with anisotropy, so this check is still needed. If a tighter bound is found in the future
  // which handles anisotropy as well though, the sampled reflected direction check may be able to be removed.

  // Note: < used rather than <= as parallel vectors to the triangle normal can be considered in the hemisphere without colliding
  // with the triangle. Convention can vary but using this here to be consistent with diffuse sampling.
  if (normalDotOutputDirection < 0.0f || normalDotInputDirection < 0.0f)
  {
    SurfaceMaterialInteractionSample materialSample;

    materialSample.inputDirection = vec3(0.0, 0.0, 0.0);
    materialSample.throughput = vec3(0.0, 0.0, 0.0);
    materialSample.solidAnglePdf = 0.0f;

    return materialSample;
  }

  // Calculate the throughput of the sample

  const vec3 finalFresnel = opaqueFresnel<ThinFilm>(opaqueSurfaceMaterialInteraction, outputDirectionDotMicrofacetNormal);

  // Note: More typical G2/G1 calculation with special bounded G1 using the bounded scalar k calculated earlier for sampling
  // the bounded VNDF lobe. This must be done instead of the usual G2/G1 optimization as the bounding of G1 does not allow for
  // the same mathematical optimization to be performed.
  const float G2 = evalHeightCorrelatedGGXShadowingMasking(
    opaqueSurfaceMaterialInteraction.anisotropicRoughness,
    tangentDotInputDirection, tangentDotOutputDirection,
    bitangentDotInputDirection, bitangentDotOutputDirection,
    normalDotInputDirection, normalDotOutputDirection);
  const float G1 = evalGGXShadowing(
    opaqueSurfaceMaterialInteraction.anisotropicRoughness,
    tangentDotOutputDirection, bitangentDotOutputDirection, normalDotOutputDirection,
    k);
  const float G2OverG1 = saferPositiveDivide(G2, G1, materialEpsilon, materialMaximum);
  // Note: Simplified version of (weight / solidAnglePdf) * (n.l) when sampling from the distribution of
  // visible normals.
  // [Heitz 2014, "Importance sampling microfacet-based BSDFs using the distribution of visible normals"]
  const vec3 throughput = finalFresnel * G2OverG1;

  // Calculate the solid angle PDF of the sample
  // Note: Ideally the PDF wouldn't be needed here as it's already been baked into the throughput for a simpler calculation
  // but some logic needs the PDF so it is included here for those cases (whereas others can optimize this part out).
  // Todo: Reuse the G1 calculation from earlier in this PDF rather than redundantly recalculating it (as G1
  // is part of the VNDF PDF).

  // Note: Normal version instead of evalGGXBoundedVisibleNormalDistributionSamplePdf used here so that the shared k
  // value computed from earlier can be used here to save some computation. Note that really the entire G1 function
  // evaluation can be shared between the throughput calculation and the PDF here.
  const float solidAnglePdf = evalGGXVisibleNormalDistributionSamplePdf(
    opaqueSurfaceMaterialInteraction.anisotropicRoughness,
    tangentDotOutputDirection, bitangentDotOutputDirection, normalDotOutputDirection,
    tangentDotMicrofacetNormal, bitangentDotMicrofacetNormal, normalDotMicrofacetNormal,
    outputDirectionDotMicrofacetNormal,
    k);

  // Return the material sample

  SurfaceMaterialInteractionSample materialSample;

  materialSample.inputDirection = quaternionTransformVector(tangentToWorldSpaceQuaternion, inputDirection);
  materialSample.throughput = throughput;
  materialSample.solidAnglePdf = solidAnglePdf;

  return materialSample;
}

[ForceInline]
public SurfaceMaterialInteractionSample opaqueSurfaceMaterialInteractionCalcDiffuseTransmissionSample(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec2 u,
  vec4 tangentToWorldSpaceQuaternion,
  vec3 viewDirection,
  inout bool insideMedium, inout bool penetrateSurface)
{
  unused(insideMedium);

  const vec4 worldToTangentSpaceQuaternion = quaternionInverse(tangentToWorldSpaceQuaternion);

  // Set up relevant input vectors in tangent space

  const vec3 outputDirection = quaternionTransformVector(worldToTangentSpaceQuaternion, viewDirection);

  float cosineHemisphereSolidAnglePdf = 0.0f;
  vec3 inputDirection = -calcCosineHemisphereDirectionSample(u, cosineHemisphereSolidAnglePdf);

  const float /* l.h */ inputDirectionDotMicrofacetNormal = -inputDirection.z;
  const float /* o.h */ outputDirectionDotMicrofacetNormal = outputDirection.z;

  const vec3 fresnel = evalOpaqueSchlickFresnel(
    opaqueSurfaceMaterialInteraction.baseReflectivity, outputDirectionDotMicrofacetNormal);
  const vec3 transmissionFresnel = vec3(1.0f, 1.0f, 1.0f) - fresnel;

  SurfaceMaterialInteractionSample materialSample;
  materialSample.inputDirection = quaternionTransformVector(tangentToWorldSpaceQuaternion, inputDirection);
  materialSample.throughput = safePositiveDivide(transmissionFresnel * opaqueSurfaceMaterialInteraction.albedo * rcp(twoPi), vec3(cosineHemisphereSolidAnglePdf), materialEpsilon) * inputDirectionDotMicrofacetNormal;
  materialSample.solidAnglePdf = cosineHemisphereSolidAnglePdf;

  // Note: Transmission rays penetrate the surface like translucent materials.
  penetrateSurface = true;

  return materialSample;
}

[ForceInline]
public SurfaceMaterialInteractionSample thinOpaqueSurfaceMaterialInteractionCalcDiffuseTransmissionSample(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec2 u,
  vec4 tangentToWorldSpaceQuaternion,
  vec3 viewDirection,
  SubsurfaceMaterial subsurfaceMaterial,
  inout bool insideMedium, inout bool penetrateSurface)
{
  unused(insideMedium);

  const vec4 worldToTangentSpaceQuaternion = quaternionInverse(tangentToWorldSpaceQuaternion);

  // Set up relevant input vectors in tangent space

  const vec3 outputDirection = quaternionTransformVector(worldToTangentSpaceQuaternion, viewDirection);

  float cosineHemisphereSolidAnglePdf = 0.0f;
  const vec3 inputDirection = -calcCosineHemisphereDirectionSample(u, cosineHemisphereSolidAnglePdf);

  const float /* n.v */ normalDotOutputDirection = outputDirection.z;
  // Note: Negative as input direction always points into surface
  const float /*-n.l */ transmissionNormalDotInputDirection = -inputDirection.z;
  const float /*-v.l */ inputPropagationDirectionDotOutputDirection = dot(-outputDirection, inputDirection);

  const vec3 weight = evalHanrahanSingleScatteringDiffuseTransmission(
    opaqueSurfaceMaterialInteraction.baseReflectivity,
    subsurfaceMaterial.volumetricAttenuationCoefficient, subsurfaceMaterial.measurementDistance,
    subsurfaceMaterial.singleScatteringAlbedo, subsurfaceMaterial.volumetricAnisotropy,
    normalDotOutputDirection,
    transmissionNormalDotInputDirection,
    inputPropagationDirectionDotOutputDirection);

  if (all(weight == 0.0f))
  {
    SurfaceMaterialInteractionSample materialSample;
    materialSample.inputDirection = vec3(0.0f, 0.0f, 0.0f);
    materialSample.throughput = vec3(0.0f, 0.0f, 0.0f);
    materialSample.solidAnglePdf = 0.0f;

    penetrateSurface = false;

    return materialSample;
  }

  SurfaceMaterialInteractionSample materialSample;
  materialSample.inputDirection = quaternionTransformVector(tangentToWorldSpaceQuaternion, inputDirection);
  materialSample.throughput = safePositiveDivide(weight, vec3(cosineHemisphereSolidAnglePdf), materialEpsilon) * transmissionNormalDotInputDirection;
  materialSample.solidAnglePdf = cosineHemisphereSolidAnglePdf;

  // Note: Transmission rays penetrate the surface like translucent materials.
  penetrateSurface = true;

  return materialSample;
}

// Note: Special case lobe for opacity.
[ForceInline]
public SurfaceMaterialInteractionSample opaqueSurfaceMaterialInteractionCalcOpacityTransmissionSample(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  inout bool insideMedium, inout bool penetrateSurface)
{
  unused(insideMedium);

  SurfaceMaterialInteractionSample materialSample;

  materialSample.inputDirection = -viewDirection;
  materialSample.throughput = vec3(
    float(1.0f) - opaqueSurfaceMaterialInteraction.opacity,
    float(1.0f) - opaqueSurfaceMaterialInteraction.opacity,
    float(1.0f) - opaqueSurfaceMaterialInteraction.opacity);
  // Note: Dirac delta PDF, always a probability of 1 to sample this singular direction.
  materialSample.solidAnglePdf = 1.0f;

  // Note: Opacity rays penetrate the surface like translucent materials.
  penetrateSurface = true;

  return materialSample;
}

SurfaceMaterialInteractionPSRSample opaqueSurfaceMaterialInteractionCalcPSRReflectionSample<let ThinFilm : bool>(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  float normalDetailThreshold)
{
  // Determine if Reflection PSR should happen based on the lobe information

  const SurfaceMaterialInteractionLobeInformation lobeInformation =
    opaqueSurfaceMaterialInteractionGetLobeInformation(opaqueSurfaceMaterialInteraction);

  // Note: Indicate reflection PSR should occur only if no diffuse lobe is present (to prevent
  // the need to split paths) and if a specular dirac lobe is present.
  if (
    lobeInformation.diffuseReflectionPresent ||
    !lobeInformation.specularReflectionPresent ||
    !lobeInformation.specularReflectionDirac ||
    any(opaqueSurfaceMaterialInteraction.emissiveRadiance > 0.0)
  )
  {
    SurfaceMaterialInteractionPSRSample materialPSRSample;
    materialPSRSample.performPSR = false;

    return materialPSRSample;
  }

  // Set up relevant input vectors

  const vec3 normal = opaqueSurfaceMaterialInteraction.shadingNormal;
  const vec3 outputDirection = viewDirection;

  // Calculate the microfacet normal
  // Note: Typically the microfacet normal would be sampled from so this would be known upfront in a sampling
  // method, due to being a dirac distribution though we always know it'll be the same as the normal.

  const vec3 microfacetNormal = normal;

  // Reflect the output direction across the microfacet normal to get the input direction

  const vec3 inputDirection = reflect(-outputDirection, microfacetNormal);

  // Calculate dot products used for evaluation

  const float /* v.h */ outputDirectionDotMicrofacetNormal = dot(outputDirection, microfacetNormal);
  const float /* n.v */ normalDotOutputDirection = outputDirectionDotMicrofacetNormal;
  const float /* n.l */ normalDotInputDirection = dot(inputDirection, normal);

  if (normalDotOutputDirection < 0.0f || normalDotInputDirection < 0.0f)
  {
    SurfaceMaterialInteractionPSRSample materialPSRSample;

    materialPSRSample.performPSR = false;

    return materialPSRSample;
  }

  // Calculate the attenuation of the sample

  const vec3 finalFresnel = opaqueFresnel<ThinFilm>(opaqueSurfaceMaterialInteraction, outputDirectionDotMicrofacetNormal);

  const vec3 attenuation = finalFresnel;

  // Return the material sample

  SurfaceMaterialInteractionPSRSample materialPSRSample;

  materialPSRSample.performPSR = true;
  materialPSRSample.useAlternateDisocclusionThreshold = opaqueSurfaceMaterialInteraction.normalDetail > normalDetailThreshold;
  materialPSRSample.inputDirection = inputDirection;
  materialPSRSample.attenuation = attenuation;
  materialPSRSample.vectorTransform = getReflectionQuaternion(microfacetNormal);

  return materialPSRSample;
}

SurfaceMaterialInteractionPSRSample opaqueSurfaceMaterialInteractionCalcPSRTransmissionSample(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  inout bool insideMedium, inout bool penetrateSurface)
{
  unused(insideMedium);
  unused(penetrateSurface);

  SurfaceMaterialInteractionPSRSample materialPSRSample;

  // Todo: Experimentally test transmission PSR for opacity in the future, for now not needed though.
  materialPSRSample.performPSR = false;

  return materialPSRSample;
}

[ForceInline]
public void opaqueSurfaceMaterialInteractionCalcPSRSample<let ThinFilm : bool>(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  float normalDetailThreshold,
  inout SurfaceMaterialInteractionPSRSample surfaceMaterialInteractionReflectionPSRSample,
  inout SurfaceMaterialInteractionPSRSample surfaceMaterialInteractionTransmissionPSRSample,
  inout vec3 diffuseLayerWeight,
  inout bool reflectionSelectedIntegrationSurface,
  inout float selectedIntegrationSurfacePdf,
  inout bool insideMedium, inout bool penetrateSurface)
{
  unused(diffuseLayerWeight);

  // Sample Reflection PSR

  surfaceMaterialInteractionReflectionPSRSample = opaqueSurfaceMaterialInteractionCalcPSRReflectionSample<ThinFilm>(
    opaqueSurfaceMaterialInteraction, viewDirection, normalDetailThreshold);

  // Sample Transmission PSR

  surfaceMaterialInteractionTransmissionPSRSample = opaqueSurfaceMaterialInteractionCalcPSRTransmissionSample(
    opaqueSurfaceMaterialInteraction, viewDirection, insideMedium, penetrateSurface);

  // Determine which PSR surface should be selected for integration and the probability of this choice

  // Note: Reflection PSR is the only thing that can realistically happen in the opaque material currently.
  if (surfaceMaterialInteractionReflectionPSRSample.performPSR)
  {
    reflectionSelectedIntegrationSurface = true;
    selectedIntegrationSurfacePdf = 1.0f;
  }
  else // Implicitly: if (surfaceMaterialInteractionTransmissionPSRSample.performPSR)
  {
    reflectionSelectedIntegrationSurface = false;
    selectedIntegrationSurfacePdf = 1.0f;
  }
}

[ForceInline]
public float opaqueSurfaceMaterialInteractionCalcDiffuseReflectionSolidAnglePdf(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  vec3 inputDirection)
{
  const float cosTheta = dot(inputDirection, opaqueSurfaceMaterialInteraction.shadingNormal);
  const float solidAnglePdf = getCosineHemisphereSolidAnglePdf(saturate(cosTheta));

  return solidAnglePdf;
}

// The tangent space view and input directions and the microfacet normal of one input direction.
// Callers that need both the specular PDF and the projected weight of a direction compute it once.
public struct OpaqueTangentFrame
{
  public vec3 outputDirection;
  public vec3 inputDirection;
  public vec3 microfacetNormal;
};

[ForceInline]
public OpaqueTangentFrame opaqueSurfaceMaterialInteractionCalcTangentFrame(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  vec3 worldInputDirection)
{
  const vec4 tangentToWorldSpaceQuaternion =
    quaternionCreateOrientation(materialTangentSpaceNormal, opaqueSurfaceMaterialInteraction.shadingNormal);
  const vec4 worldToTangentSpaceQuaternion = quaternionInverse(tangentToWorldSpaceQuaternion);

  OpaqueTangentFrame tangentFrame;
  tangentFrame.outputDirection = quaternionTransformVector(worldToTangentSpaceQuaternion, viewDirection);
  tangentFrame.inputDirection = quaternionTransformVector(worldToTangentSpaceQuaternion, worldInputDirection);
  tangentFrame.microfacetNormal = normalize(tangentFrame.inputDirection + tangentFrame.outputDirection);

  return tangentFrame;
}

[ForceInline]
public float opaqueSurfaceMaterialInteractionCalcSpecularReflectionSolidAnglePdf(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  vec3 worldInputDirection)
{
  return opaqueSurfaceMaterialInteractionCalcSpecularReflectionSolidAnglePdf(
    opaqueSurfaceMaterialInteraction,
    opaqueSurfaceMaterialInteractionCalcTangentFrame(opaqueSurfaceMaterialInteraction, viewDirection, worldInputDirection));
}

[ForceInline]
public float opaqueSurfaceMaterialInteractionCalcSpecularReflectionSolidAnglePdf(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  OpaqueTangentFrame tangentFrame)
{
  const vec3 outputDirection = tangentFrame.outputDirection;
  const vec3 inputDirection = tangentFrame.inputDirection;
  const vec3 microfacetNormal = tangentFrame.microfacetNormal;

  // Calculate dot products used for evaluation
  // Note: Dot products against tangent space basis vectors are single components of the other vector

  const float /* v.h */ outputDirectionDotMicrofacetNormal = dot(outputDirection, microfacetNormal);
  const float /* t.v */ tangentDotOutputDirection = outputDirection.x;
  const float /* b.v */ bitangentDotOutputDirection = outputDirection.y;
  const float /* n.v */ normalDotOutputDirection = outputDirection.z;
  const float /* t.l */ tangentDotInputDirection = inputDirection.x;
  const float /* b.l */ bitangentDotInputDirection = inputDirection.y;
  const float /* n.l */ normalDotInputDirection = inputDirection.z;
  const float /* t.h */ tangentDotMicrofacetNormal = microfacetNormal.x;
  const float /* b.h */ bitangentDotMicrofacetNormal = microfacetNormal.y;
  const float /* n.h */ normalDotMicrofacetNormal = microfacetNormal.z;

  // Todo: Sanity check if inputDirection is facing the wrong direction (Into the surface), currently this does not have much
  // effect and getting the triangle normal here requires a fairly high memory bandwidth cost, so for now it is not done

  if (normalDotOutputDirection < 0.0f || normalDotInputDirection < 0.0f)
  {
    return 0.0f;
  }

  // Calculate the solid angle PDF of the sample

  const float solidAnglePdf = evalGGXBoundedVisibleNormalDistributionSamplePdf(
    opaqueSurfaceMaterialInteraction.anisotropicRoughness,
    tangentDotOutputDirection, bitangentDotOutputDirection, normalDotOutputDirection,
    tangentDotMicrofacetNormal, bitangentDotMicrofacetNormal, normalDotMicrofacetNormal,
    outputDirectionDotMicrofacetNormal);

  return solidAnglePdf;
}

[ForceInline]
public float opaqueSurfaceMaterialInteractionCalcDiffuseTransmissionSolidAnglePdf(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  vec3 inputDirection)
{
  const float cosTheta = dot(-inputDirection, opaqueSurfaceMaterialInteraction.shadingNormal);
  const float solidAnglePdf = getCosineHemisphereSolidAnglePdf(saturate(cosTheta));

  return solidAnglePdf;
}

[ForceInline]
public float opaqueSurfaceMaterialInteractionCalcSolidAnglePdf(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  OpaqueLobeSampling lobeSampling,
  vec3 viewDirection,
  vec3 inputDirection)
{
  // Return the average of all the lobe PDFs
  // Note: Only lobes which are possible and specifically involved in reflections are included

  float diffuseReflectionProbability = 0.0f;
  float specularReflectionProbability = 0.0f;
  float opacityTransmissionProbability = 0.0f;
  float diffuseTransmissionProbability = 0.0f;
  opaqueSurfaceMaterialInteractionCalcLobeProbability(
    opaqueSurfaceMaterialInteraction, viewDirection, lobeSampling,
    diffuseReflectionProbability, specularReflectionProbability, opacityTransmissionProbability, diffuseTransmissionProbability);

  const float diffuseSolidAnglePdf = opaqueSurfaceMaterialInteractionCalcDiffuseReflectionSolidAnglePdf(opaqueSurfaceMaterialInteraction, viewDirection, inputDirection);
  const float specularSolidAnglePdf = opaqueSurfaceMaterialInteractionCalcSpecularReflectionSolidAnglePdf(opaqueSurfaceMaterialInteraction, viewDirection, inputDirection);
  const float diffuseTransmissionSolidAnglePdf = opaqueSurfaceMaterialInteractionCalcDiffuseTransmissionSolidAnglePdf(opaqueSurfaceMaterialInteraction, viewDirection, inputDirection);

  return diffuseSolidAnglePdf * diffuseReflectionProbability +
         specularSolidAnglePdf * specularReflectionProbability +
         diffuseTransmissionSolidAnglePdf * diffuseTransmissionProbability;
}


[ForceInline]
public SurfaceMaterialInteractionSplitWeight opaqueSurfaceMaterialInteractionCalcApproxProjectedWeight(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  vec3 inputDirection)
{
  const vec3 microfacetNormal = normalize(inputDirection + viewDirection);
  const float /* n.h */ normalDotMicrofacetNormal = dot(opaqueSurfaceMaterialInteraction.shadingNormal, microfacetNormal);
  const float /* v.h */ outputDirectionDotMicrofacetNormal = dot(viewDirection, microfacetNormal);
  const float /* n.l */ normalDotInputDirection = dot(inputDirection, opaqueSurfaceMaterialInteraction.shadingNormal);
  const float /* n.v */ normalDotOutputDirection = dot(viewDirection, opaqueSurfaceMaterialInteraction.shadingNormal);

  const bool isThinOpaqueSubsurface = isThinOpaqueSubsurfaceMaterial(opaqueSurfaceMaterialInteraction);
  const bool isSss = isSubsurfaceDiffusionProfileMaterial(opaqueSurfaceMaterialInteraction);

  vec3 diffuseTransmissionWeight = vec3(0.0, 0.0, 0.0);
  if (isThinOpaqueSubsurface || isSss)
  {
    // Load Subsurface Material
    const SubsurfaceMaterial subsurfaceMaterial = decodeSubsurfaceMaterial(opaqueSurfaceMaterialInteraction.subsurfaceMaterialInteraction);

    diffuseTransmissionWeight = subsurfaceMaterial.singleScatteringAlbedo;
  }

  if (normalDotOutputDirection < 0.0f || normalDotInputDirection < 0.0f)
  {
    SurfaceMaterialInteractionSplitWeight splitWeight;

    splitWeight.diffuseReflectionWeight = vec3(0.0, 0.0, 0.0);
    splitWeight.specularReflectionWeight = vec3(0.0, 0.0, 0.0);

    if (normalDotOutputDirection < 0.0f)
    {
      if (!isSss)
      {
        splitWeight.diffuseTransmissionWeight = vec3(0.0, 0.0, 0.0);
      }
      else
      {
        // SSS Transmission could cause the angle between view direction (output) and backface normal larger than 90 degrees.
        // In such case we use the inversed dot product result to estimate the transmission weight.
        splitWeight.diffuseTransmissionWeight = diffuseTransmissionWeight * (-normalDotOutputDirection);
      }
    }
    else
    {
      splitWeight.diffuseTransmissionWeight = diffuseTransmissionWeight * (-normalDotInputDirection);
    }

    return splitWeight;
  }

  // Calculate the weight
  const vec3 fresnel = evalOpaqueSchlickFresnel(opaqueSurfaceMaterialInteraction.baseReflectivity, outputDirectionDotMicrofacetNormal);
  const float normalDistributionFunction = evalGGXNormalDistributionIsotropic(opaqueSurfaceMaterialInteraction.isotropicRoughness, normalDotMicrofacetNormal);
  // Note: Actual shadowing/masking (G) function omitted from visibility calculation for a cheaper approximation.
  const float visibility = safePositiveDivide(float(1), float(4) * normalDotInputDirection * normalDotOutputDirection, materialEpsilon);
  const vec3 diffuseReflectionWeight = opaqueSurfaceMaterialInteraction.albedo;
  const vec3 specularReflectionWeight = min(normalDistributionFunction * visibility * fresnel, vec3(float16Max));

  // Create a split weight
  SurfaceMaterialInteractionSplitWeight splitWeight;
  splitWeight.diffuseReflectionWeight = diffuseReflectionWeight * normalDotInputDirection;
  splitWeight.specularReflectionWeight = specularReflectionWeight * normalDotInputDirection;
  splitWeight.diffuseTransmissionWeight = vec3(0.0, 0.0, 0.0); // Will always be 0 when normalDotInputDirection >= 0

  return splitWeight;
}

[ForceInline]
public SurfaceMaterialInteractionSplitWeight opaqueProjectedWeight<let ThinFilm : bool>(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  vec3 viewDirection,
  vec3 inputDirection)
{
  return opaqueProjectedWeight<ThinFilm>(
    opaqueSurfaceMaterialInteraction,
    opaqueSurfaceMaterialInteractionCalcTangentFrame(opaqueSurfaceMaterialInteraction, viewDirection, inputDirection));
}

[ForceInline]
public SurfaceMaterialInteractionSplitWeight opaqueProjectedWeight<let ThinFilm : bool>(
  OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction,
  OpaqueTangentFrame tangentFrame)
{
  const vec3 tangentOutputDirection = tangentFrame.outputDirection;
  const vec3 tangentInputDirection = tangentFrame.inputDirection;
  const vec3 microfacetNormal = tangentFrame.microfacetNormal;

  // Calculate dot products used for evaluation

  const float /* l.v */ inputDirectionDotOutputDirection = dot(tangentInputDirection, tangentOutputDirection);
  const float /* v.h */ outputDirectionDotMicrofacetNormal = dot(tangentOutputDirection, microfacetNormal);
  const float /* t.v */ tangentDotOutputDirection = tangentOutputDirection.x;
  const float /* b.v */ bitangentDotOutputDirection = tangentOutputDirection.y;
  const float /* n.v */ normalDotOutputDirection = tangentOutputDirection.z;
  const float /* t.l */ tangentDotInputDirection = tangentInputDirection.x;
  const float /* b.l */ bitangentDotInputDirection = tangentInputDirection.y;
  const float /* n.l */ normalDotInputDirection = tangentInputDirection.z;
  const float /* t.h */ tangentDotMicrofacetNormal = microfacetNormal.x;
  const float /* b.h */ bitangentDotMicrofacetNormal = microfacetNormal.y;
  const float /* n.h */ normalDotMicrofacetNormal = microfacetNormal.z;
  const float /*-n.l */ transmissionNormalDotInputDirection = -tangentInputDirection.z;

  const bool isThinOpaqueSubsurface = isThinOpaqueSubsurfaceMaterial(opaqueSurfaceMaterialInteraction);

  vec3 diffuseTransmissionWeight = vec3(0.0f, 0.0f, 0.0f);
  if (isThinOpaqueSubsurface && normalDotOutputDirection > 0.0f) {
    // Load Subsurface Material
    const SubsurfaceMaterial subsurfaceMaterial = decodeSubsurfaceMaterial(opaqueSurfaceMaterialInteraction.subsurfaceMaterialInteraction);

    diffuseTransmissionWeight = evalHanrahanSingleScatteringDiffuseTransmission(
      opaqueSurfaceMaterialInteraction.baseReflectivity,
      subsurfaceMaterial.volumetricAttenuationCoefficient, subsurfaceMaterial.measurementDistance,
      subsurfaceMaterial.singleScatteringAlbedo, subsurfaceMaterial.volumetricAnisotropy,
      normalDotOutputDirection,
      transmissionNormalDotInputDirection,
      -inputDirectionDotOutputDirection);
  }

  if (normalDotOutputDirection < 0.0f || normalDotInputDirection < 0.0f)
  {
    SurfaceMaterialInteractionSplitWeight splitWeight;

    splitWeight.diffuseReflectionWeight = vec3(0.0, 0.0, 0.0);
    splitWeight.specularReflectionWeight = vec3(0.0, 0.0, 0.0);
    splitWeight.diffuseTransmissionWeight = diffuseTransmissionWeight * transmissionNormalDotInputDirection;

    return splitWeight;
  }

  // Calculate the weight

  const vec3 finalFresnel = opaqueFresnel<ThinFilm>(opaqueSurfaceMaterialInteraction, outputDirectionDotMicrofacetNormal);

  const float normalDistributionFunction = evalGGXNormalDistribution(
    opaqueSurfaceMaterialInteraction.anisotropicRoughness,
    tangentDotMicrofacetNormal, bitangentDotMicrofacetNormal, normalDotMicrofacetNormal);
  const float visibility = evalHeightCorrelatedGGXVisibility(
    opaqueSurfaceMaterialInteraction.anisotropicRoughness,
    tangentDotInputDirection, tangentDotOutputDirection, bitangentDotInputDirection,
    bitangentDotOutputDirection, normalDotInputDirection, normalDotOutputDirection);

  // Note: Thin film Fresnel not taken into account properly here. If we were using a simpler diffuse model where the transmission Fresnel was simply multiplied in somewhere
  // it'd be doable to incorporate like we do with specular, but currently the Hammon Diffuse model does Fresnel calculations internally based on various assumptions in its derivation
  // which makes it non-trivial to modify. This should be fine though as the thin film will only add a small bit of extra reflection in some cases (due to having two Fresnel layers),
  // or attenuate the reflected signal due to destructive interference, nothing major enough that'd majorly change the amount of transmitted light to make the diffuse contribution look very
  // wrong, though it may be slightly the wrong color in some instances, assuming that transmitted light interferes in the same way.
  const vec3 diffuseReflectionWeight = evalHammonDiffuse(
    opaqueSurfaceMaterialInteraction.albedo, opaqueSurfaceMaterialInteraction.isotropicRoughness,
    inputDirectionDotOutputDirection, normalDotInputDirection,
    normalDotOutputDirection, normalDotMicrofacetNormal);
  const vec3 specularReflectionWeight = min(finalFresnel * normalDistributionFunction * visibility, vec3(float16Max));

  // Create a split weight

  SurfaceMaterialInteractionSplitWeight splitWeight;

  splitWeight.diffuseReflectionWeight = diffuseReflectionWeight * normalDotInputDirection;
  splitWeight.specularReflectionWeight = specularReflectionWeight * normalDotInputDirection;
  splitWeight.diffuseTransmissionWeight = diffuseTransmissionWeight * normalDotInputDirection;

  return splitWeight;
}
