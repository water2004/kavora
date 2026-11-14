/* SPDX-License-Identifier: MIT */
module appearance_lighting;

// Scene-linear diffuse fill for the visible surface. Kept out of light transport and cache training.
[ForceInline]
public float3 evaluateAppearanceEmission(float3 diffuseAlbedo)
{
  return diffuseAlbedo * 0.18f;
}
