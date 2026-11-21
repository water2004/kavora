// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT
module cloud_scattering;

static const float kCloudStreamCosine = 0.57735026919f;

// Conservative two-stream diffusion with prescribed upper and lower boundary radiances.
// Optical-depth components measure the column above and below the scattering point.
public float3 cloudDiffuseBoundaryRadiance(float3 upperRadiance, float3 lowerRadiance,
  float2 opticalDepth, float meanCosine)
{
  const float2 transportDepth = opticalDepth * (1.0f - meanCosine);
  const float denominator = 2.0f * kCloudStreamCosine + transportDepth.x + transportDepth.y;
  return ((kCloudStreamCosine + transportDepth.y) * upperRadiance
    + (kCloudStreamCosine + transportDepth.x) * lowerRadiance) / denominator;
}

// A Lambertian terrain boundary receives the transmitted sky and beam. Sum
// its repeated reflections with the conservative column, without adding that
// light a second time to the cloud source. Terrain reflectance is a local
// average here; the surface path tracer continues to use the actual materials.
public float3 cloudGroundBoundaryRadiance(float3 upperRadiance, float3 beamIrradianceOverPi,
  float totalOpticalDepth, float meanCosine, float groundAlbedo)
{
  const float transportDepth = totalOpticalDepth * (1.0f - meanCosine);
  const float transmission = 2.0f * kCloudStreamCosine / (2.0f * kCloudStreamCosine + transportDepth);
  const float reflectance = 1.0f - transmission;
  const float albedo = saturate(groundAlbedo);
  return albedo * (transmission * upperRadiance + beamIrradianceOverPi)
    / max(1.0f - albedo * reflectance, 1e-6f);
}

// Transport of a collimated light through a conservative slab. Delta scaling
// retains the forward peak in the beam. The remaining flux exits diffusely.
public struct CloudBeamTransfer
{
  public float beam;
  public float downwardFlux;
};

public CloudBeamTransfer cloudBeamTransfer(float opticalDepth, float meanCosine, float cosine)
{
  const float transport = opticalDepth * (1.0f - meanCosine);
  const float beam = exp(-transport * (1.0f + meanCosine) / max(cosine, 1e-20f));
  const float flux = cosine * (kCloudStreamCosine * (1.0f + beam) + cosine * (1.0f - beam))
    / (2.0f * kCloudStreamCosine + transport);
  return { beam, flux };
}
