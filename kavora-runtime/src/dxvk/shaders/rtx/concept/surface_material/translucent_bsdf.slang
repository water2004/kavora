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
module translucent_bsdf;

import "rtx/concept/surface_material/material_scattering.slang";
import "rtx/concept/surface_material/surface_material_types.slang";
import "rtx/concept/surface_material/translucent_interface_optics.slang";
import "rtx/utility/material_optics.slang";
import "rtx/utility/quaternion.slang";

public struct TranslucentLobeSampling
{
  public LobeSamplingRange reflection;
  public LobeSamplingRange transmission;
};

// Samples the ordinary translucent BSDF in the current ray medium.
[ForceInline]
public SurfaceMaterialInteractionLobeInformation translucentSurfaceMaterialInteractionGetLobeInformation(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction)
{
  SurfaceMaterialInteractionLobeInformation surfaceMaterialInteractionLobeInformation;

  surfaceMaterialInteractionLobeInformation.diffuseReflectionPresent = false;
  surfaceMaterialInteractionLobeInformation.specularReflectionPresent = true;
  surfaceMaterialInteractionLobeInformation.diffuseTransmissionPresent = false;
  surfaceMaterialInteractionLobeInformation.specularTransmissionPresent = true;

  // Todo: Change these flags conditionally if roughness is ever added to the translucent material.
  surfaceMaterialInteractionLobeInformation.specularReflectionDirac = true;
  surfaceMaterialInteractionLobeInformation.specularTransmissionDirac = true;

  return surfaceMaterialInteractionLobeInformation;
}

[ForceInline]
public SurfaceMaterialInteractionLobeSample translucentSurfaceMaterialInteractionCalcLobeSample(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction,
  float random,
  float3 viewDirection,
  TranslucentInterface boundary, TranslucentLobeSampling sampling)
{
  SurfaceMaterialInteractionLobeSample surfaceMaterialInteractionLobeSample;

  // Calculate dot products used for evaluation

  // Note: Normal dot output direction used as a "best" approximation in this case, usually aligned with the actual sampled
  // microfacet normal on surfaces with low roughness (identical at 0), but will become more and more potentially inaccurate
  // as roughness increases.
  const float /* n.v */ normalDotOutputDirection =
    dot(translucentSurfaceMaterialInteraction.shadingNormal, viewDirection);

  // Compute material-related quantities

  const float fresnel = evalTranslucentSchlickFresnelTIR(boundary.baseReflectivity, boundary.eta, normalDotOutputDirection);

  // Calculate lobe probabilities

  float specularReflectionProbability = fresnel;
  float specularTransmissionProbability = float(1.0f) - specularReflectionProbability;

  adjustProbabilityValue(specularReflectionProbability, sampling.reflection);
  adjustProbabilityValue(specularTransmissionProbability, sampling.transmission);

  const float lobeProbabilitySum = specularTransmissionProbability + specularReflectionProbability;
  float lobeProbabilityNormalizationFactor;

  if (lobeProbabilitySum != float(0.0f))
  {
    lobeProbabilityNormalizationFactor = float(1.0f) / lobeProbabilitySum;
  }
  else
  {
    // Note: Default to sampling transmission lobe when no lobe desires to be sampled from.
    surfaceMaterialInteractionLobeSample.lobe = translucentLobeTypeSpecularTransmission;
    surfaceMaterialInteractionLobeSample.pdf = float(1.0f);

    return surfaceMaterialInteractionLobeSample;
  }

  // Sample a lobe

  // Note: Adjust the raw lobe sample to the total probability range based on the sum.
  const float lobeSample = random * lobeProbabilitySum;

  // Note: < used for conditions to ensure lobes never have a chance to be sampled from if their
  // probability is 0 (and to prevent NaNs from appearing due to 0 PDF).
  if (lobeSample < specularReflectionProbability)
  {
    surfaceMaterialInteractionLobeSample.lobe = translucentLobeTypeSpecularReflection;
    surfaceMaterialInteractionLobeSample.pdf = specularReflectionProbability * lobeProbabilityNormalizationFactor;
  }
  else
  {
    surfaceMaterialInteractionLobeSample.lobe = translucentLobeTypeSpecularTransmission;
    surfaceMaterialInteractionLobeSample.pdf = specularTransmissionProbability * lobeProbabilityNormalizationFactor;
  }

  return surfaceMaterialInteractionLobeSample;
}

SurfaceMaterialInteractionSample translucentSurfaceMaterialInteractionCalcSpecularReflectionSample(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction,
  float4 tangentToWorldSpaceQuaternion,
  float3 viewDirection,
  TranslucentInterface boundary)
{
  const float outsideRelativeRefractiveIndex = boundary.eta;
  const float insideRelativeRefractiveIndex = rcp(boundary.eta);

  const float4 worldToTangentSpaceQuaternion = quaternionInverse(tangentToWorldSpaceQuaternion);

  // Set up relevant input vectors in tangent space

  const float3 outputDirection = quaternionTransformVector(worldToTangentSpaceQuaternion, viewDirection);

  // Sample a new microfacet normal based on the distribution of visible normals

  //const float2 u = random.xy;
  //const float3 microfacetNormal = calcGGXVisibleNormalDistributionSample(translucentSurfaceMaterialInteraction.anisotropicRoughness, outputDirection, u);
  const float3 microfacetNormal = materialTangentSpaceNormal;

  // Reflect the output direction across the microfacet normal to get the input direction

  const float3 inputDirection = reflect(-outputDirection, microfacetNormal);

  // Calculate the refracted direction into the thin surface for the inside Fresnel evaluation
  // Note: This should never result in a TIR case since it's always done from the outside of the surface

  const float3 refractedDirection = refract(-outputDirection, microfacetNormal, outsideRelativeRefractiveIndex);

  // Todo: Sanity check if inputDirection is facing the wrong direction (Into the surface), currently this does not have much
  // effect and getting the triangle normal here requires a fairly high memory bandwidth cost, so for now it is not done

  // Calculate dot products used for evaluation
  // Note: Dot products against tangent space basis vectors are single components of the other vector

  const float /* v.h */ outputDirectionDotMicrofacetNormal = dot(outputDirection, microfacetNormal);
  const float /* n.v */ normalDotOutputDirection = outputDirection.z;
  const float /* n.l */ normalDotInputDirection = inputDirection.z;
  // Note: Negative as refracted direction always points into surface
  const float /* r.h */ refractedDirectionDotMicrofacetNormal = dot(-refractedDirection, microfacetNormal);

  if (normalDotOutputDirection <= 0.f || normalDotInputDirection <= 0.f)
  {
    SurfaceMaterialInteractionSample materialSample;

    materialSample.inputDirection = float3(0.0, 0.0, 0.0);
    materialSample.throughput = float3(0.0, 0.0, 0.0);
    materialSample.solidAnglePdf = 0.0f;

    return materialSample;
  }

  // Calculate the fresnel of the sample

  const float outsideFresnel = evalTranslucentSchlickFresnelTIR(
    boundary.baseReflectivity, outsideRelativeRefractiveIndex, abs(outputDirectionDotMicrofacetNormal));
  const float insideFresnel = evalTranslucentSchlickFresnelTIR(
    boundary.baseReflectivity, insideRelativeRefractiveIndex, refractedDirectionDotMicrofacetNormal);

  // Calculate the throughput of the sample

  float3 throughput;

  const bool isThinWalled = boundary.thin;

  if (!isThinWalled)
  {
    throughput = outsideFresnel.xxx;
  }
  else
  {
    // Note: Check for "fiber optic" case where light never escapes from inside the thin surface.
    const bool thinWalledTIR = (insideFresnel == float(1.0f));

    if (refractedDirectionDotMicrofacetNormal <= 0.0f || thinWalledTIR)
    {
      // Note: In this case the material acts like a fiber optic cable and infiniately reflects inside,
      // or the vector is parallel to the plane which causes issues when trying to solve, so only the
      // direct reflected contribution should be considered.
      throughput = outsideFresnel.xxx;
    }
    else
    {
      const float3 volumeAttenuation = thinWallTransmittance(
        translucentSurfaceMaterialInteraction.transmittanceColor.rgb,
        translucentSurfaceMaterialInteractionGetThinWallThickness(translucentSurfaceMaterialInteraction),
        microfacetNormal, refractedDirection);

      // Note: Simplified version of (weight / solidAnglePdf) * (n.l)
      throughput = evalThinReflectionGeometricSeriesThroughput(outsideFresnel, insideFresnel, volumeAttenuation);
    }
  }

  // Return the material sample

  SurfaceMaterialInteractionSample materialSample;

  materialSample.inputDirection = quaternionTransformVector(tangentToWorldSpaceQuaternion, inputDirection);
  materialSample.throughput = throughput;
  materialSample.solidAnglePdf = 1.0f;

  return materialSample;
}

SurfaceMaterialInteractionSample translucentSurfaceMaterialInteractionCalcSpecularTransmissionSample(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction,
  float3 viewDirection,
  TranslucentInterface boundary, uint waterMediumMaterialIndex, inout uint medium, inout bool penetrateSurface)
{
  const float outsideRelativeRefractiveIndex = boundary.eta;
  const float insideRelativeRefractiveIndex = rcp(boundary.eta);

  // Set up relevant input vectors

  const float3 normal = normalize(float3(translucentSurfaceMaterialInteraction.shadingNormal));
  const float3 outputDirection = normalize(float3(viewDirection));

  // Calculate the microfacet normal
  // Note: Typically the microfacet normal would be sampled from so this would be known upfront in a sampling
  // method, due to being a dirac distribution though we always know it'll be the same as the normal.

  const float3 microfacetNormal = normal;

  // Calculate the refracted direction into the thin surface for the inside Fresnel evaluation
  // Note: This should never result in a TIR case since it's always done from the outside of the surface

  const float3 refractedDirection = refract(-outputDirection, microfacetNormal, outsideRelativeRefractiveIndex);

  // Calculate dot products used for evaluation

  const float /* v.h */ outputDirectionDotMicrofacetNormal = dot(outputDirection, microfacetNormal);
  const float /* n.v */ normalDotOutputDirection = dot(outputDirection, normal);
  // Note: Negative as refracted direction always points into surface
  const float /* r.h */ refractedDirectionDotMicrofacetNormal = dot(-refractedDirection, microfacetNormal);

  if (normalDotOutputDirection <= 0.0f)
  {
    SurfaceMaterialInteractionSample materialSample;

    materialSample.inputDirection = float3(0.0f, 0.0f, 0.0f);
    materialSample.throughput = float3(0.0f);
    materialSample.solidAnglePdf = 0.0f;

    return materialSample;
  }

  // Calculate the fresnel of the sample
  // Note: This is done in advance to determine TIR before BRDF/BTDF evaluation

  const float outsideFresnel = evalTranslucentSchlickFresnelTIR(
    boundary.baseReflectivity, outsideRelativeRefractiveIndex, outputDirectionDotMicrofacetNormal);
  const float insideFresnel = evalTranslucentSchlickFresnelTIR(
    boundary.baseReflectivity, insideRelativeRefractiveIndex, refractedDirectionDotMicrofacetNormal);

  // Switch between reflection and transmission

  float3 inputDirection;
  float3 throughput;

  const bool isThinWalled = boundary.thin;

  if (!isThinWalled)
  {
    const bool totalInternalReflection = (outsideFresnel == 1.0f);

    // Note: Check for TIR, typically this shouldn't ever be hit as the chance of imporance sampling a transmission event
    // should be 0.
    if (totalInternalReflection)
    {
      SurfaceMaterialInteractionSample materialSample;

      materialSample.inputDirection = float3(0.0f, 0.0f, 0.0f);
      materialSample.throughput = float3(0.0f);
      materialSample.solidAnglePdf = 0.0f;

      return materialSample;
    }
    else
    {
      // Calculate the input direction by refracting the output direction

      inputDirection = refractedDirection;

      // Calculate dot products used for evaluation

      const float /* n.l */ normalDotInputDirection = dot(inputDirection, -normal);

      if (normalDotInputDirection <= 0.0f)
      {
        SurfaceMaterialInteractionSample materialSample;

        materialSample.inputDirection = float3(0.0f, 0.0f, 0.0f);
        materialSample.throughput = float3(0.0f);
        materialSample.solidAnglePdf = 0.0f;

        return materialSample;
      }

      // Calculate the throughput of the sample

      // Note: Simplified version of (weight / solidAnglePdf) * (n.l)
      throughput = float(1.0f - outsideFresnel).xxx;

      medium = translucentTransmittedMedium(translucentSurfaceMaterialInteraction, boundary, waterMediumMaterialIndex);
      penetrateSurface = true;
    }
  }
  else
  {
    // for thin-walled materials, we pretend that the surface we hit is a solid with some thickness,
    // and figure out what happens to the light ray based on the fresnel coefficient of the refracted ray hitting the other
    // side of the "solid"

    // Note: Check for "fiber optic" case where light never escapes from inside the thin surface.
    const bool thinWalledTIR = (insideFresnel == float(1.0f));

    if (refractedDirectionDotMicrofacetNormal <= 0.0f || thinWalledTIR)
    {
      SurfaceMaterialInteractionSample materialSample;

      materialSample.inputDirection = float3(0.0f, 0.0f, 0.0f);
      materialSample.throughput = float3(0.0f);
      materialSample.solidAnglePdf = 0.0f;

      return materialSample;
    }

    // Calculate the throughput of the sample

    const float3 volumeAttenuation = thinWallTransmittance(
      translucentSurfaceMaterialInteraction.transmittanceColor.rgb,
      translucentSurfaceMaterialInteractionGetThinWallThickness(translucentSurfaceMaterialInteraction),
      microfacetNormal, refractedDirection);

    // Note: Simplified version of (weight / solidAnglePdf) * (n.l)
    throughput = evalThinTransmissionGeometricSeriesThroughput(outsideFresnel, insideFresnel, volumeAttenuation);

    // ray direction never changes for thin-walled materials
    inputDirection = -outputDirection;
    penetrateSurface = true;
  }

  // Return the material sample

  SurfaceMaterialInteractionSample materialSample;

  materialSample.inputDirection = inputDirection;
  materialSample.throughput = throughput;
  materialSample.solidAnglePdf = 1.0f;

  return materialSample;
}

[ForceInline]
public void translucentSurfaceMaterialInteractionCalcSample(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction,
  float lobeRandom,
  float4 tangentToWorldSpaceQuaternion,
  float3 viewDirection,
  TranslucentInterface boundary, uint waterMediumMaterialIndex, TranslucentLobeSampling sampling,
  inout SurfaceMaterialInteractionSample surfaceMaterialInteractionSample,
  inout SurfaceMaterialInteractionLobeSample surfaceMaterialInteractionLobeSample,
  inout uint medium, inout bool penetrateSurface)
{
  surfaceMaterialInteractionLobeSample = translucentSurfaceMaterialInteractionCalcLobeSample(
    translucentSurfaceMaterialInteraction, lobeRandom, viewDirection, boundary, sampling);

  switch (uint(surfaceMaterialInteractionLobeSample.lobe))
  {
  default:
  case uint(translucentLobeTypeSpecularReflection):
    surfaceMaterialInteractionSample = translucentSurfaceMaterialInteractionCalcSpecularReflectionSample(
      translucentSurfaceMaterialInteraction, tangentToWorldSpaceQuaternion, viewDirection, boundary);

    break;
  case uint(translucentLobeTypeSpecularTransmission):
    surfaceMaterialInteractionSample = translucentSurfaceMaterialInteractionCalcSpecularTransmissionSample(
      translucentSurfaceMaterialInteraction, viewDirection, boundary, waterMediumMaterialIndex, medium, penetrateSurface);

    break;
  }
}
