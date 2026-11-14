/* SPDX-License-Identifier: MIT */
module end_portal_emission_effect;

import "rtx/concept/surface_material/end_portal_material.slang";
import "rtx/concept/surface_material/material_effects.slang";
import "rtx/concept/surface_material/opaque_emission_inputs.slang";
import "rtx/concept/surface_material/surface_material_types.slang";
#include "rtx/pass/common_binding_indices.h"
#include "rtx/utility/shared_constants.h"

public struct EndPortalEmission<Resources : IOpaqueEmissionResources> : IMaterialEffect<ProjectedEmissionInputs, float3> {
  [ForceInline]
  public static bool replaces(OpaqueSurfaceMaterial material)
  {
    return (material.flags & OPAQUE_SURFACE_MATERIAL_FLAG_IS_END_PORTAL) != 0;
  }

  [ForceInline]
  public static bool hasTextures(OpaqueSurfaceMaterial material)
  {
    return material.secondaryTextureIndex != BINDING_INDEX_INVALID && material.emissiveColorTextureIndex != BINDING_INDEX_INVALID;
  }

  [ForceInline]
  public static void apply(ProjectedEmissionInputs inputs, inout float3 radiance)
  {
    const OpaqueSurfaceMaterial material = inputs.material;
    const uint layerCount = (material.flags & OPAQUE_SURFACE_MATERIAL_FLAG_IS_END_GATEWAY) ? 16u : 15u;
    radiance = endPortalEmission(
      Resources.texture(uint(material.secondaryTextureIndex)),
      Resources.texture(uint(material.emissiveColorTextureIndex)),
      Resources.sampler(uint(material.samplerIndex)),
      inputs.projection, inputs.resolution, inputs.time, inputs.viewDirection, layerCount, material.emissiveIntensity);
  }
};
