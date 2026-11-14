/* SPDX-License-Identifier: MIT */
module material_parameter_adjustment;

import "rtx/concept/surface_material/material_effects.slang";

public struct OpaqueShadingParameters {
  public float roughness;
  public float metallic;
};

public struct OpaqueParameterSettings {
  public float roughnessScale;
  public float roughnessBias;
  public float metallicScale;
  public float metallicBias;
};

public struct MaterialParameterAdjustment : IMaterialEffect<OpaqueParameterSettings, OpaqueShadingParameters> {
  [ForceInline]
  public static void apply(OpaqueParameterSettings settings, inout OpaqueShadingParameters material)
  {
    material.roughness = saturate(material.roughness * settings.roughnessScale + settings.roughnessBias);
    material.metallic = saturate(material.metallic * settings.metallicScale + settings.metallicBias);
  }
};
