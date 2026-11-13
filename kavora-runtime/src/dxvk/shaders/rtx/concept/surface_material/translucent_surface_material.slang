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
module translucent_surface_material;

import "rtx/concept/surface_material/surface_material_types.slang";
#include "rtx/pass/common_binding_indices.h"

// Translucent Surface Material Functions

[ForceInline]
public TranslucentSurfaceMaterial translucentSurfaceMaterialCreate(MemoryPolymorphicSurfaceMaterial memoryPolymorphicSurfaceMaterial, const bool visibilityCheckOnly = false)
{
  if (visibilityCheckOnly)
  {
    TranslucentSurfaceMaterial translucentSurfaceMaterial = reinterpret<TranslucentSurfaceMaterial>(memoryPolymorphicSurfaceMaterial);
    translucentSurfaceMaterial.normalTextureIndex = BINDING_INDEX_INVALID;
    translucentSurfaceMaterial.emissiveColorTextureIndex = BINDING_INDEX_INVALID;
    translucentSurfaceMaterial.emissiveIntensity = 0.f;
    translucentSurfaceMaterial.refractiveIndex = 1.f;
    translucentSurfaceMaterial.emissiveColorConstant = 0.f;
    return translucentSurfaceMaterial;
  }
  else
  {
    return reinterpret<TranslucentSurfaceMaterial>(memoryPolymorphicSurfaceMaterial);
  }
}
