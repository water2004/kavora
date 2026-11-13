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
module translucent_psr;

import "rtx/concept/surface_material/material_scattering.slang";
import "rtx/concept/surface_material/surface_material_types.slang";
import "rtx/concept/surface_material/translucent_interface_optics.slang";
import "rtx/concept/surface_material/translucent_bsdf.slang";
import "rtx/utility/material_optics.slang";
import "rtx/utility/quaternion.slang";

#include "rtx/utility/shared_constants.h"

public struct TranslucentPsrHistory
{
  public bool useRayReconstruction;
  public bool invalidateAnimatedWater;
  public float reflectionNormalDetailThreshold;
  public float transmissionNormalDetailThreshold;
};

[ForceInline]
bool alternateDisocclusionThreshold(TranslucentSurfaceMaterialInteraction material, TranslucentPsrHistory history,
  float normalDetailThreshold)
{
  return history.useRayReconstruction
    ? (material.isAnimatedWater && history.invalidateAnimatedWater)
    : (material.normalDetail > normalDetailThreshold);
}

// Chooses separate continuation rays and their integration guides. The
// transmitted continuation owns the updated medium and penetration state.
// Note: Most this code duplicated from translucentSurfaceMaterialInteractionCalcSpecularReflectionSample,
// but done so if roughness is ever added to glass this path will exist as a dirac on its own.
SurfaceMaterialInteractionPSRSample translucentSurfaceMaterialInteractionCalcPSRReflectionSample(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction,
  float3 viewDirection,
  inout float3 diffuseLayerWeight,
  TranslucentInterface boundary, TranslucentPsrHistory history)
{
  // Determine if Reflection PSR should happen based on the lobe information

  const SurfaceMaterialInteractionLobeInformation lobeInformation =
    translucentSurfaceMaterialInteractionGetLobeInformation(translucentSurfaceMaterialInteraction);

  // Note: Indicate reflection PSR should occur only if no diffuse lobe is present (to prevent
  // the need to split paths) and if a specular dirac lobe is present.
  if (
    lobeInformation.diffuseReflectionPresent ||
    !lobeInformation.specularReflectionPresent ||
    !lobeInformation.specularReflectionDirac
  )
  {
    SurfaceMaterialInteractionPSRSample materialPSRSample;

    materialPSRSample.performPSR = false;

    return materialPSRSample;
  }

  const float outsideRelativeRefractiveIndex = boundary.eta;
  const float insideRelativeRefractiveIndex = rcp(boundary.eta);

  // Set up relevant input vectors

  const float3 normal = normalize(float3(translucentSurfaceMaterialInteraction.shadingNormal));
  const float3 outputDirection = normalize(float3(viewDirection));

  // Calculate the microfacet normal
  // Note: Typically the microfacet normal would be sampled from so this would be known upfront in a sampling
  // method, due to being a dirac distribution though we always know it'll be the same as the normal.

  const float3 microfacetNormal = normal;

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
  const float /* n.v */ normalDotOutputDirection = outputDirectionDotMicrofacetNormal;
  const float /* n.l */ normalDotInputDirection = dot(inputDirection, normal);
  // Note: Negative as refracted direction always points into surface
  const float /* r.h */ refractedDirectionDotMicrofacetNormal = dot(-refractedDirection, microfacetNormal);

  if (normalDotOutputDirection <= 0.f || normalDotInputDirection <= 0.f)
  {
    SurfaceMaterialInteractionPSRSample materialPSRSample;

    materialPSRSample.performPSR = false;

    return materialPSRSample;
  }

  // Calculate the fresnel of the sample

  const float outsideFresnel = evalTranslucentSchlickFresnelTIR(
    boundary.baseReflectivity, outsideRelativeRefractiveIndex, abs(outputDirectionDotMicrofacetNormal));
  const float insideFresnel = evalTranslucentSchlickFresnelTIR(
    boundary.baseReflectivity, insideRelativeRefractiveIndex, refractedDirectionDotMicrofacetNormal);

  // Calculate the attenuation of the sample

  float3 attenuation;

  const bool isThinWalled = boundary.thin;

  if (!isThinWalled)
  {
    attenuation = outsideFresnel.xxx;
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
      attenuation = outsideFresnel.xxx;
    }
    else
    {
      const float3 volumeAttenuation = thinWallTransmittance(
        translucentSurfaceMaterialInteraction.transmittanceColor.rgb,
        translucentSurfaceMaterialInteractionGetThinWallThickness(translucentSurfaceMaterialInteraction),
        microfacetNormal, refractedDirection);

      attenuation = evalThinReflectionGeometricSeriesThroughput(outsideFresnel, insideFresnel, volumeAttenuation);
    }
  }

  // Calculate diffuse layer weight and attenuation

  const bool useDiffuseLayer = translucentSurfaceMaterialInteraction.flags & TRANSLUCENT_SURFACE_MATERIAL_FLAG_USE_DIFFUSE_LAYER;

  // Note: Only handle the diffuse layer for reflections when the incident ray is outside the surface. This is because TIR will never need to
  // interact with a diffuse layer.
  if (useDiffuseLayer && !boundary.exiting)
  {
    const float3 diffuseAttenuation = lerp(float3(1.0f), translucentSurfaceMaterialInteraction.diffuseColor, translucentSurfaceMaterialInteraction.diffuseOpacity);
    const float3 diffuseInscattering = translucentSurfaceMaterialInteraction.diffuseColor * translucentSurfaceMaterialInteraction.diffuseOpacity;

    // Note: Factor attenuation from the entry through the diffuse layer and the attenuation (fresnel) contribution from the surface for a secondary diffuse layer
    // contribution to be accounted for. Assumes the attenuation up to this point is only the reflection contribution off the surface.
    diffuseLayerWeight += diffuseAttenuation * attenuation * diffuseInscattering;
    // Note: Account for the attenuation from the entry and exit through the diffuse layer.
    attenuation *= diffuseAttenuation * diffuseAttenuation;
  }

  // Return the material sample

  SurfaceMaterialInteractionPSRSample materialPSRSample;

  materialPSRSample.performPSR = true;
  materialPSRSample.useAlternateDisocclusionThreshold =
    alternateDisocclusionThreshold(translucentSurfaceMaterialInteraction, history, history.reflectionNormalDetailThreshold);
  materialPSRSample.inputDirection = inputDirection;
  materialPSRSample.attenuation = attenuation;
  materialPSRSample.vectorTransform = getReflectionQuaternion(microfacetNormal);

  return materialPSRSample;
}

// Note: Most this code duplicated from translucentSurfaceMaterialInteractionCalcSpecularTransmissionSample,
// but done so if roughness is ever added to glass this path will exist as a dirac on its own.
SurfaceMaterialInteractionPSRSample translucentSurfaceMaterialInteractionCalcPSRTransmissionSample(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction,
  float3 viewDirection,
  inout float3 diffuseLayerWeight,
  TranslucentInterface boundary, uint waterMediumMaterialIndex, TranslucentPsrHistory history,
  inout uint medium, inout bool penetrateSurface)
{
  // Determine if Transmission PSR should happen based on the lobe information

  const SurfaceMaterialInteractionLobeInformation lobeInformation =
    translucentSurfaceMaterialInteractionGetLobeInformation(translucentSurfaceMaterialInteraction);

  // Note: Indicate transmission PSR should occur only if a specular transmission dirac lobe is present.
  if (
    !lobeInformation.specularTransmissionPresent ||
    !lobeInformation.specularTransmissionDirac
  )
  {
    SurfaceMaterialInteractionPSRSample materialPSRSample;

    materialPSRSample.performPSR = false;

    return materialPSRSample;
  }

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
    SurfaceMaterialInteractionPSRSample materialPSRSample;

    materialPSRSample.performPSR = false;

    return materialPSRSample;
  }

  // Calculate the fresnel of the sample
  // Note: This is done in advance to determine TIR before BRDF/BTDF evaluation

  const float outsideFresnel = evalTranslucentSchlickFresnelTIR(
    boundary.baseReflectivity, outsideRelativeRefractiveIndex, outputDirectionDotMicrofacetNormal);
  const float insideFresnel = evalTranslucentSchlickFresnelTIR(
    boundary.baseReflectivity, insideRelativeRefractiveIndex, refractedDirectionDotMicrofacetNormal);

  // Calculate needed quantities

  float3 inputDirection;
  float3 attenuation;
  const bool isThinWalled = boundary.thin;

  if (!isThinWalled)
  {
    const bool totalInternalReflection = (outsideFresnel == 1.0f);

    // Note: Check for TIR as in this case we should indicate that no transmission PSR should happen, allowing the reflection PSR
    // to dominate and implicitly become the primary surface (to avoid it being demoted to a lower quality secondary surface).
    if (totalInternalReflection)
    {
      SurfaceMaterialInteractionPSRSample materialPSRSample;

      materialPSRSample.performPSR = false;

      return materialPSRSample;
    }
    else
    {
      // Calculate the input direction by refracting the output direction

      inputDirection = refractedDirection;

      // Calculate dot products used for evaluation

      const float /* n.l */ normalDotInputDirection = dot(inputDirection, -normal);

      if (normalDotInputDirection <= 0.0f)
      {
        SurfaceMaterialInteractionPSRSample materialPSRSample;

        materialPSRSample.performPSR = false;

        return materialPSRSample;
      }

      // Calculate the attenuation of the sample

      attenuation = float(1.0f - outsideFresnel).xxx;

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
        SurfaceMaterialInteractionPSRSample materialPSRSample;

        materialPSRSample.performPSR = false;

        return materialPSRSample;
    }

    // Calculate the throughput of the sample

    const float3 volumeAttenuation = thinWallTransmittance(
      translucentSurfaceMaterialInteraction.transmittanceColor.rgb,
      translucentSurfaceMaterialInteractionGetThinWallThickness(translucentSurfaceMaterialInteraction),
      microfacetNormal, refractedDirection);

    attenuation = evalThinTransmissionGeometricSeriesThroughput(outsideFresnel, insideFresnel, volumeAttenuation);

    // Thin-walled transmission preserves the incident direction.
    inputDirection = -outputDirection;
    penetrateSurface = true;
  }

  // Calculate diffuse layer weight and attenuation

  const bool useDiffuseLayer = translucentSurfaceMaterialInteraction.flags & TRANSLUCENT_SURFACE_MATERIAL_FLAG_USE_DIFFUSE_LAYER;

  // Note: Only handle the diffuse layer for reflections when the incident ray is outside the surface. This is because TIR will never need to
  // interact with a diffuse layer.
  if (useDiffuseLayer)
  {
    const float3 diffuseAttenuation = lerp(float3(1.0f), translucentSurfaceMaterialInteraction.diffuseColor, translucentSurfaceMaterialInteraction.diffuseOpacity);
    const float3 diffuseInscattering = translucentSurfaceMaterialInteraction.diffuseColor * translucentSurfaceMaterialInteraction.diffuseOpacity;

    // Note: Using old inside medium flag to check its state before the translucent sample. Only apply an extra diffuse layer contribution when exiting
    // the surface as this is the only time transmission will need to accumulate lighting from the diffuse layer.
    if (boundary.exiting)
    {
      // Note: Factor attenuation (fresnel) contribution from the surface for a secondary diffuse layer contribution to be accounted for.
      // Assumes the attenuation up to this point is only the transmission contribution off the surface.
      diffuseLayerWeight += attenuation * diffuseInscattering;
    }

    if (isThinWalled)
    {
      // Note: Account for the attenuation from the entry and exit through the diffuse layer as on a thin surface transmission goes through both hypothetical
      // diffuse layers (unlike reflection which only ever interacts with the layer on the incident side of the surface).
      attenuation *= diffuseAttenuation * diffuseAttenuation;
    }
    else
    {
      // Note: Account for the attenuation from either the entry or exit through the diffuse layer (depending on if the translucent interaction originates
      // from the inside or outside of the medium).
      attenuation *= diffuseAttenuation;
    }
  }

  // Return the material sample

  SurfaceMaterialInteractionPSRSample materialPSRSample;

  materialPSRSample.performPSR = true;
  materialPSRSample.useAlternateDisocclusionThreshold =
    alternateDisocclusionThreshold(translucentSurfaceMaterialInteraction, history, history.transmissionNormalDetailThreshold);
  materialPSRSample.inputDirection = inputDirection;
  materialPSRSample.attenuation = attenuation;
  materialPSRSample.vectorTransform = getIdentityQuaternion();
  materialPSRSample.relativeRefractiveIndex = isThinWalled ? 1.0f : outsideRelativeRefractiveIndex;

  return materialPSRSample;
}

// Note: diffuseLayerWeight is used to weight the radiance from the radiance cache which when summed into the path radiance introduces an approximate diffuse
// layer on top of the surface for PSR specifically (currently it is not implemented in the non-PSR path). For performance the radiance cache should only be
// accessed if this value is non-zero. Note the reflection/transmission PSR sample attenuations will have this contribution factored in already for how they transmit
// through this one-sided layer.
[ForceInline]
public void translucentSurfaceMaterialInteractionCalcPSRSample(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction,
  float random,
  float3 viewDirection,
  TranslucentInterface boundary, uint waterMediumMaterialIndex, TranslucentLobeSampling sampling, TranslucentPsrHistory history,
  inout SurfaceMaterialInteractionPSRSample surfaceMaterialInteractionReflectionPSRSample,
  inout SurfaceMaterialInteractionPSRSample surfaceMaterialInteractionTransmissionPSRSample,
  inout float3 diffuseLayerWeight,
  inout bool reflectionSelectedIntegrationSurface,
  inout float selectedIntegrationSurfacePdf,
  inout uint medium, inout bool penetrateSurface)
{

  // Calculate shared diffuse layer weight contribution

  if (!boundary.exiting)
  {
    const float3 diffuseInscattering = translucentSurfaceMaterialInteraction.diffuseColor * translucentSurfaceMaterialInteraction.diffuseOpacity;

    // Note: When interacting with the medium from the outside (the side the diffuse layer is on), incorperate it
    // by convolving a spherical isotropic BRDF with the volumetric radiance contribution. Note this in the future should
    // be adjusted so an actual directional contribution can be done when the radiance is stored in the cache with spherical
    // harmonics, but for now this is fine. Also note that the volumetric radiance cache already divides by the integrated spherical "BRDF"
    // factor of 1/4pi so that does not have to be done here.
    diffuseLayerWeight = diffuseInscattering;
  }
  else
  {
    diffuseLayerWeight = float3(0.0f);
  }

  // Sample Reflection PSR

  surfaceMaterialInteractionReflectionPSRSample = translucentSurfaceMaterialInteractionCalcPSRReflectionSample(
    translucentSurfaceMaterialInteraction, viewDirection, diffuseLayerWeight, boundary, history);

  // Sample Transmission PSR

  surfaceMaterialInteractionTransmissionPSRSample = translucentSurfaceMaterialInteractionCalcPSRTransmissionSample(
    translucentSurfaceMaterialInteraction, viewDirection, diffuseLayerWeight, boundary, waterMediumMaterialIndex, history, medium, penetrateSurface);

  // Determine which PSR surface should be selected for integration and the probability of this choice
  // Note: TIR is handled here in a good way as reflection PSR should be requested and transmission PSR should not be, meaning
  // we do not have to do any special logic in the lobe sampling portion as it'll never be hit.

  if (
    surfaceMaterialInteractionReflectionPSRSample.performPSR &&
    surfaceMaterialInteractionTransmissionPSRSample.performPSR
  )
  {
    const SurfaceMaterialInteractionLobeSample lobeSample = translucentSurfaceMaterialInteractionCalcLobeSample(
      translucentSurfaceMaterialInteraction,
      random,
      viewDirection,
      boundary, sampling);

    reflectionSelectedIntegrationSurface = (lobeSample.lobe == translucentLobeTypeSpecularReflection);
    selectedIntegrationSurfacePdf = lobeSample.pdf;
  }
  else if (surfaceMaterialInteractionReflectionPSRSample.performPSR)
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
