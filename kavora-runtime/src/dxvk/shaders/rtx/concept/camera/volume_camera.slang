/* SPDX-License-Identifier: MIT */
module volume_camera;

#include "rtx/concept/camera/volume_camera.h"

[ForceInline]
public float3 worldToTranslatedWorld(VolumeDefinitionCamera camera, float3 worldPosition)
{
  return worldPosition - camera.translatedWorldOffset;
}

[ForceInline]
public float3 translatedWorldToWorld(VolumeDefinitionCamera camera, float3 translatedWorldPosition)
{
  return translatedWorldPosition + camera.translatedWorldOffset;
}
