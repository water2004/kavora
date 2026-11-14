/* SPDX-License-Identifier: MIT */
module surface_glint_effect;

import "rtx/concept/surface_material/surface_glint.slang";
import "rtx/concept/surface_material/material_effects.slang";
import "rtx/concept/surface_material/opaque_emission_inputs.slang";
#include "rtx/utility/shared_constants.h"

public struct EnchantmentGlint<Resources : IOpaqueEmissionResources> : IMaterialEffect<OpaqueEffectInputs, float3> {
  [ForceInline]
  public static void apply(OpaqueEffectInputs input, inout float3 radiance) {
    const float scale = input.mode == OPAQUE_SURFACE_MATERIAL_GLINT_MODE_ITEM ? 8.f :
      (input.mode == OPAQUE_SURFACE_MATERIAL_GLINT_MODE_ENTITY ? 0.5f : 0.16f);
    radiance += surfaceGlint(Resources.texture(uint(input.material.glintTextureIndex)),
      Resources.sampler(uint(input.material.samplerIndex)), input.uv, input.gradientX, input.gradientY,
      input.albedo, input.lodBias, input.glintStrength, input.time, input.glintSpeed, scale);
  }
};
