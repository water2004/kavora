// SPDX-FileCopyrightText: Copyright (c) 2023-2026 NVIDIA CORPORATION. All rights reserved.
// SPDX-License-Identifier: MIT
module material_optics;

public static const float materialIoRVacuum = 1.0f;
public static const float3 materialTangentSpaceNormal = float3(0.0f, 0.0f, 1.0f);

// Calculates extinction from transmittance measured over a known distance.
[ForceInline]
public float3 transmittanceToAttenuationCoefficient(float3 transmittanceColor, float transmittanceMeasurementDistance)
{
  return float3(-log(transmittanceColor)) / float(transmittanceMeasurementDistance);
}

// Applies Beer-Lambert attenuation over the supplied distance.
[ForceInline]
public float3 evalBeerLambertAttenuation(float3 attenuationCoefficient, float distance)
{
  return exp(-attenuationCoefficient * distance);
}

// Converts sheet thickness to the traveled distance along the refracted ray.
[ForceInline]
public float evalThinAttenuationDistance(float thickness, float3 normal, float3 direction)
{
  return thickness / dot(-normal, direction);
}

// Returns the relative index of refraction for the current boundary direction.
[ForceInline]
public float translucentInterfaceEta(float ratio, bool exiting)
{
  return exiting ? rcp(ratio) : ratio;
}

// Adjusts normal-incidence reflectivity for a non-vacuum exterior.
[ForceInline]
public float translucentInterfaceReflectivity(float baseReflectivity, float ratio, float vacuumReflectivity)
{
  const float ratioReflectivity = (1.0f - ratio) / (1.0f + ratio);
  return baseReflectivity + ratioReflectivity * ratioReflectivity - vacuumReflectivity;
}

// Evaluates thin-sheet transmittance using color measured over one world unit.
[ForceInline]
public float3 thinWallTransmittance(float3 transmittanceColor, float thickness, float3 normal, float3 direction)
{
  const float distance = evalThinAttenuationDistance(thickness, normal, direction);
  const float3 extinction = transmittanceToAttenuationCoefficient(transmittanceColor, 1.0f);
  return evalBeerLambertAttenuation(extinction, distance);
}

float fresnelGrazingWeight(float cosine)
{
  const float x = clamp(1.0f - cosine, 0.0f, 1.0f);
  const float squared = x * x;
  return squared * squared * x;
}

[ForceInline]
public float3 evalOpaqueSchlickFresnel(float3 baseReflectivity, float cosine)
{
  return baseReflectivity + (float3(1.0f) - baseReflectivity) * fresnelGrazingWeight(cosine);
}

// Fresnel (F): Schlick Approximation
// [Schlick 1994, "An Inexpensive BRDF Model for Physically-Based Rendering"]
// Note: This is only intended to be used for dielectrics when coming from a less dense to a more dense medium (not the other way
// around as this function will not adjust for TIR's influence on the Fresnel response properly) during BRDF evaluation (hence the
// monochromatic fresnel and base reflectivity) as translucent conductors are impossible. This function therefore is generally not
// meant to be called when dealing with more general purpose material evaluations that can happen from either inside or outside of
// a denser medium and the more general purpose evalTranslucentSchlickFresnelTIR should be called instead in these cases, but it is
// fine to call if you know that this will always be called from the outside of a denser medium.
[ForceInline]
public float evalTranslucentSchlickFresnel(float baseReflectivity,
                                    float /* v.h */ outputDirectionDotMicrofacetNormal)
{
  return float(baseReflectivity) + float(float(1.0) - baseReflectivity) * fresnelGrazingWeight(outputDirectionDotMicrofacetNormal);
}

// Calculates the cosine of the refracted angle given the cosine of an incident angle relative to a normal.
[ForceInline]
public bool evalRefractionCosineAngle(float relativeRefractiveIndex,
                               float /* v.h */ outputDirectionDotMicrofacetNormal,
                               inout float refractionDirectionDotMicrofacetNormal)
{
  const float sinSquaredAngle = (relativeRefractiveIndex * relativeRefractiveIndex) *
    (1.f - outputDirectionDotMicrofacetNormal * outputDirectionDotMicrofacetNormal);
  const float cosAngle = sqrt(1.f - sinSquaredAngle);

  if (sinSquaredAngle > 1.f)
  {
    return false;
  }

  refractionDirectionDotMicrofacetNormal = cosAngle;

  return true;
}

// Fresnel (F): TIR-Enhanced Schlick Approximation
// https://seblagarde.wordpress.com/2013/04/29/memo-on-fresnel-equations/
// Note: This is only intended to be used for dielectrics during BRDF/BTDF evaluation (hence the monochromatic
// fresnel and base reflectivity) as translucent conductors are impossible.
[ForceInline]
public float evalTranslucentSchlickFresnelTIR(float baseReflectivity,
                                           float relativeRefractiveIndex,
                                           float /* v.h */ outputDirectionDotMicrofacetNormal)
{
  float modifiedOutputDirectionDotMicrofacetNormal = outputDirectionDotMicrofacetNormal;

  if (relativeRefractiveIndex > 1.f)
  {
    if (!evalRefractionCosineAngle(
      relativeRefractiveIndex,
      outputDirectionDotMicrofacetNormal,
      modifiedOutputDirectionDotMicrofacetNormal
    )) {
       return 1.f;
    }
  }

  return evalTranslucentSchlickFresnel(baseReflectivity, modifiedOutputDirectionDotMicrofacetNormal);
}

// Evaluates the geometric series 1 + x^2 + x^4 + ... where x < 1
float3 evalThinGeometricSeries(in const float3 x)
{
  return float(1.0) / (float(1.0) - x * x);
}

// Evaluate the throughput of a sum of an infinite series of internal reflections in a thin surface under
// the assumption that the majority of these events in a reflected direction happen very close to the point
// of initial reflection (and thus can be combined into it)
// [Kulla 2017, "Revisiting Physically Based Shading at Imageworks"]
[ForceInline]
public float3 evalThinReflectionGeometricSeriesThroughput(in const float outsideFresnel,
                                                    in const float insideFresnel,
                                                    in const float3 attenuation)
{
  return outsideFresnel +
         insideFresnel * (attenuation * attenuation) * (float(1.0) - outsideFresnel) * (float(1.0) - insideFresnel) *
         evalThinGeometricSeries(attenuation * insideFresnel);
}

// Evaluate the throughput of a sum of an infinite series of internal reflections in a thin surface under
// the assumption that the majority of these events in a transmission direction happen very close to the point
// of initial transmission (and thus can be combined into it)
// [Kulla 2017, "Revisiting Physically Based Shading at Imageworks"]
[ForceInline]
public float3 evalThinTransmissionGeometricSeriesThroughput(in const float outsideFresnel,
                                                      in const float insideFresnel,
                                                      in const float3 attenuation)
{
  return attenuation * (float(1.0f) - outsideFresnel) * (float(1.0f) - insideFresnel) *
         evalThinGeometricSeries(attenuation * insideFresnel);
}
