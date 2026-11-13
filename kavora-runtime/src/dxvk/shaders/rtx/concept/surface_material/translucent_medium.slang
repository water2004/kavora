/*
* Copyright (c) 2024, NVIDIA CORPORATION. All rights reserved.
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
module translucent_medium;

import "rtx/concept/surface_material/surface_material_types.slang";
import "rtx/concept/surface_material/translucent_surface_material.slang";
import "rtx/concept/surface_material/translucent_medium_state.slang";
import "rtx/utility/material_optics.slang";

#include "rtx/pass/common_binding_indices.h"
#include "rtx/utility/shared_constants.h"

[ForceInline]
public uint translucentMediumEnter(TranslucentSurfaceMaterialInteraction interaction)
{
  if (interaction.flags & TRANSLUCENT_SURFACE_MATERIAL_FLAG_TEXTURED_TRANSMITTANCE)
  {
    return packMediumExtinction(transmittanceToAttenuationCoefficient(
      max(interaction.transmittanceColor, float3(1e-30f)), interaction.thicknessOrMeasurementDistance));
  }
  return interaction.sourceSurfaceMaterialIndex;
}

// Returns linear RGB extinction per world unit. Ray stages apply distance separately.
[ForceInline]
public float3 translucentMediumExtinction(uint mediumState, StructuredBuffer<MemoryPolymorphicSurfaceMaterial> surfaceMaterials)
{
  if (mediumState == SURFACE_INDEX_INVALID)
  {
    return float3(0);
  }
  if (mediumState & mediumStateSampledBit)
  {
    return unpackMediumExtinction(mediumState);
  }
  const TranslucentSurfaceMaterial medium = translucentSurfaceMaterialCreate(surfaceMaterials[mediumState]);
  return transmittanceToAttenuationCoefficient(max(medium.transmittanceColor, float3(1e-30f)),
    medium.thicknessOrMeasurementDistance);
}
