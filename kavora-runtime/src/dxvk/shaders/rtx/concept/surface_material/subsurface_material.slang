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
module subsurface_material;

import "rtx/concept/surface_material/surface_material_types.slang";
#include "rtx/utility/shader_types.h"
#include "rtx/utility/brdf.slangh"
#include "rtx/utility/packing.slangh"

[ForceInline]
public bool isSubsurfaceDiffusionProfileMaterial(OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction)
{
  return opaqueSurfaceMaterialInteraction.subsurfaceMaterialInteraction.maxSampleRadius > 0.0h;
}

[ForceInline]
public bool isThinOpaqueSubsurfaceMaterial(OpaqueSurfaceMaterialInteraction opaqueSurfaceMaterialInteraction)
{
  return opaqueSurfaceMaterialInteraction.subsurfaceMaterialInteraction.measurementDistance > 0.0h && !isSubsurfaceDiffusionProfileMaterial(opaqueSurfaceMaterialInteraction);
}

[ForceInline]
public SubsurfaceMaterial decodeSubsurfaceMaterial(const SubsurfaceMaterialInteraction subsurfaceMaterialInteraction)
{
  SubsurfaceMaterial subsurfaceMaterial;

  const f16vec3 transmittanceColor = r11g11b10ToColor(subsurfaceMaterialInteraction.packedTransmittanceColor);
  subsurfaceMaterial.volumetricAttenuationCoefficient = subsurfaceMaterialInteraction.maxSampleRadius == 0.0f ?
    transmittanceToAttenuationCoefficient(max(transmittanceColor, materialEpsilon), subsurfaceMaterialInteraction.measurementDistance) :
    transmittanceColor;
  subsurfaceMaterial.measurementDistance = subsurfaceMaterialInteraction.measurementDistance;
  subsurfaceMaterial.singleScatteringAlbedo = r11g11b10ToColor(subsurfaceMaterialInteraction.packedSingleScatteringAlbedo);
  subsurfaceMaterial.volumetricAnisotropy = snorm8ToF16(subsurfaceMaterialInteraction.volumetricAnisotropy);
  subsurfaceMaterial.maxSampleRadius = unorm8ToF16(subsurfaceMaterialInteraction.maxSampleRadius);

  return subsurfaceMaterial;
}
