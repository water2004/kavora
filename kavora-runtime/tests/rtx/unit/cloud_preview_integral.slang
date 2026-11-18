// SPDX-License-Identifier: MIT
module cloud_preview_integral;

// A cloud step adds linear radiance and multiplies the remaining transmittance.
// The caller owns the sample grid, density lookup and termination policy.
[ForceInline]
public void accumulateCloudPreviewStep(float extinction, float length, float albedo, float3 incidentRadiance,
  inout float3 radiance, inout float transmittance)
{
  const float stepTransmittance = exp(-extinction * length);
  const float opacity = transmittance * (1.0f - stepTransmittance);
  radiance += opacity * albedo * incidentRadiance;
  transmittance *= stepTransmittance;
}
