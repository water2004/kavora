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
module translucent_interface_optics;

import "rtx/concept/surface_material/surface_material_types.slang";
import "rtx/concept/surface_material/translucent_surface_material.slang";
import "rtx/concept/surface_material/translucent_medium.slang";
import "rtx/utility/material_optics.slang";

#include "rtx/pass/common_binding_indices.h"
#include "rtx/utility/shared_constants.h"

// Interface optics shared by BSDF sampling and primary surface replacement.
bool translucentSurfaceMaterialInteractionIsThinWalled(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction)
{
  return translucentSurfaceMaterialInteraction.thicknessOrMeasurementDistance < 0.0f;
}

[ForceInline]
public float translucentSurfaceMaterialInteractionGetThinWallThickness(
  TranslucentSurfaceMaterialInteraction translucentSurfaceMaterialInteraction)
{
  return -translucentSurfaceMaterialInteraction.thicknessOrMeasurementDistance;
}

public struct TranslucentInterface
{
  public uint exteriorMedium;
  public bool exiting;
  public bool thin;
  public float eta;
  public float baseReflectivity;
};

// Thick faces delimit their material and their declared exterior. Thin sheets keep the incident medium.
[ForceInline]
public TranslucentInterface translucentInterface(TranslucentSurfaceMaterialInteraction material, uint medium,
  uint waterMediumMaterialIndex, StructuredBuffer<MemoryPolymorphicSurfaceMaterial> surfaceMaterials)
{
  TranslucentInterface boundary;
  boundary.thin = translucentSurfaceMaterialInteractionIsThinWalled(material);
  boundary.exteriorMedium = (material.flags & TRANSLUCENT_SURFACE_MATERIAL_FLAG_WATER_CONTACT)
    ? waterMediumMaterialIndex : SURFACE_INDEX_INVALID;
  boundary.exiting = !boundary.thin && medium != SURFACE_INDEX_INVALID && medium != boundary.exteriorMedium;

  const uint exteriorMedium = boundary.thin ? medium : boundary.exteriorMedium;
  const float exteriorIor = exteriorMedium == waterMediumMaterialIndex && exteriorMedium != SURFACE_INDEX_INVALID
    ? float(translucentSurfaceMaterialCreate(surfaceMaterials[exteriorMedium]).refractiveIndex) : materialIoRVacuum;
  const float ratio = exteriorIor / material.refractiveIndex;
  boundary.eta = translucentInterfaceEta(ratio, boundary.exiting);
  boundary.baseReflectivity = material.baseReflectivity;
  if (exteriorIor != materialIoRVacuum) {
    const float vacuumRatio = (material.refractiveIndex - 1.0f) / (material.refractiveIndex + 1.0f);
    const float vacuumReflectivity = vacuumRatio * vacuumRatio;
    boundary.baseReflectivity = clamp(translucentInterfaceReflectivity(
      boundary.baseReflectivity, ratio, vacuumReflectivity), 0.0f, 1.0f);
  }
  return boundary;
}

[ForceInline]
public uint translucentTransmittedMedium(TranslucentSurfaceMaterialInteraction material, TranslucentInterface boundary,
  uint waterMediumMaterialIndex)
{
  if (boundary.exiting) return boundary.exteriorMedium;
  if ((material.flags & TRANSLUCENT_SURFACE_MATERIAL_FLAG_SIMULATED_WATER) &&
      waterMediumMaterialIndex != SURFACE_INDEX_INVALID) return waterMediumMaterialIndex;
  return translucentMediumEnter(material);
}
