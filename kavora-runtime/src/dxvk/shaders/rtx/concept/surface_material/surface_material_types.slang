/*
* Copyright (c) 2022-2026, NVIDIA CORPORATION. All rights reserved.
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
module surface_material_types;

#include "rtx/utility/shader_types.h"

#include "../../utility/shared_constants.h"

public static const uint8_t opaqueLobeTypeDiffuseReflection = uint8_t(0u);
public static const uint8_t opaqueLobeTypeSpecularReflection = uint8_t(1u);
public static const uint8_t opaqueLobeTypeOpacityTransmission = uint8_t(2u);
public static const uint8_t opaqueLobeTypeDiffuseTransmission = uint8_t(3u);

public static const uint8_t translucentLobeTypeSpecularReflection = uint8_t(0u);
public static const uint8_t translucentLobeTypeSpecularTransmission = uint8_t(1u);

public struct MemoryPolymorphicSurfaceMaterial
{
  // Note: Currently aligned nicely to 64 bytes, avoid changing the size of this structure. Note however since this is smaller
  // than a L1 cacheline the actual size doesn't matter as much, so it is not heavily packed as the cache hitrate will be low
  // and the random access nature does not facilitate much memory coalescing. Since this structure can fit in 64 bytes however
  // it is best not to be too wasteful as this will align to L2's 32 byte cachelines better.

  // Note: Keeping these as uint4 ensures 16 byte memory alignment, which is important for aligned vector loading.
  public uvec4 data[4];

  public bool isOpaque()
  {
    // Note: First two bits of data are reserved for common polymorphic type
    return (data[0].x & surfaceMaterialTypeMask) == surfaceMaterialTypeOpaque;
  }

  public bool hasValidDisplacement() {
    return isOpaque() && (data[0].x & OPAQUE_SURFACE_MATERIAL_FLAG_HAS_DISPLACEMENT);
  }
};

public struct OpaqueSurfaceMaterial
{
  // 0 - 3
  // bitmask of OPAQUE_SURFACE_MATERIAL_FLAG_* bits
  public uint16_t flags;
  public uint16_t samplerIndex;
  public uint16_t albedoOpacityTextureIndex;
  public uint16_t secondaryTextureIndex;

  // 4-7
  public f16vec4 albedoOpacityConstant;

  // 8-11
  public float16_t displaceIn;
  public float16_t displaceOut;
  public uint16_t heightTextureIndex;
  public uint16_t reliefSamplerIndex;

  // For performance, we want to keep fields used in the visibility check in the first 32 bytes.
  // The fields below here are overridden to constant values in that code, so should be left at the end
  // If we add a new field that is used for visibility, it should go above this.
  // If it isn't used for visibility, it should go below and be overridden in opaqueSurfaceMaterialCreate().

  // 12-15
  public uint16_t emissiveColorTextureIndex;
  public uint16_t roughnessTextureIndex;
  public uint16_t metallicTextureIndex;
  public uint16_t normalTextureIndex;

  // 16-19
  public f16vec3 emissiveColorConstant;
  public float16_t emissiveIntensity;

  // 20-23
  public float16_t roughnessConstant;
  public float16_t metallicConstant;
  public float16_t anisotropy;
  public uint16_t tangentTextureIndex;

  // 24-25
  public uint subsurfaceMaterialIndex;

  // 26
  public uint16_t samplerFeedbackStamp;
  // 27: normalized to [0, 1]
  public float16_t thinFilmThicknessConstant;

  // 28-29
  public uint dlssControlMask;

  // Todo: Fixed function blend state info here in the future (Actually this should go on a Legacy Material, or some sort of non-PBR Legacy Surface)

  // 30-31
  public uint16_t vertexColorMaskTextureIndex;
  public uint16_t glintTextureIndex;

  public bool hasValidDisplacement() {
    return flags & OPAQUE_SURFACE_MATERIAL_FLAG_HAS_DISPLACEMENT;
  }
};

public struct TranslucentSurfaceMaterial
{
  // bitmask of TRANSLUCENT_SURFACE_MATERIAL_FLAG_* bits
  public uint16_t flags;
  public float16_t baseReflectivity;
  public f16vec3 transmittanceColor;
  public uint16_t samplerIndex;
  public uint16_t transmittanceOrDiffuseTextureIndex;
  // encodes either the thin-walled thickness or the transmittance measurement distance
  // thin-walled thickness is represented as a negative number
  public float16_t thicknessOrMeasurementDistance;
  public uint16_t normalTextureIndex;
  public uint16_t emissiveColorTextureIndex;
  public float16_t emissiveIntensity;
  public float16_t refractiveIndex;

  // 12-13: Source material-table index (21 bits).  Placed here (byte offset 24)
  // so the uint32_t is naturally 4-byte aligned — avoids Slang inserting padding
  // that would break reinterpret<> from the 64-byte MemoryPolymorphicSurfaceMaterial.
  public uint32_t sourceSurfaceMaterialIndex;

  // 14-16
  public f16vec3 emissiveColorConstant;

  // 17
  public uint16_t samplerFeedbackStamp;

  // 18
  public uint16_t reliefSamplerIndex;

  // padding (to keep size matching with MemoryPolymorphicSurfaceMaterial)
  public uint16_t data[13];
};

public struct RayPortalSurfaceMaterial
{
  public uint16_t flags;
  public uint16_t rayPortalIndex;

  public uint16_t maskTextureIndex;
  public uint16_t maskTextureIndex2;

  public float16_t rotationSpeed;
  public float16_t emissiveIntensity;

  public uint16_t samplerIndex;
  public uint16_t samplerIndex2;

  // padding (to keep size matching with MemoryPolymorphicSurfaceMaterial)
  public uint16_t data[24];

};

public struct SubsurfaceMaterial
{
  public uint16_t flags;

  public uint16_t subsurfaceTransmittanceTextureIndex;
  public uint16_t subsurfaceThicknessTextureIndex;
  public uint16_t subsurfaceSingleScatteringAlbedoTextureIndex;

  public float16_t volumetricAnisotropy;
  public f16vec3 volumetricAttenuationCoefficient;
  public float16_t measurementDistance;
  public f16vec3 singleScatteringAlbedo;

  public float16_t maxSampleRadius;

  // padding (to keep size matching with MemoryPolymorphicSurfaceMaterial)
  public uint16_t data[19];
};

public struct SubsurfaceMaterialInteraction
{
  public uint32_t packedTransmittanceColor;
  public float16_t measurementDistance;
  public uint32_t packedSingleScatteringAlbedo;
  public uint8_t volumetricAnisotropy;
  public uint8_t maxSampleRadius;
};

public struct OpaqueSurfaceMaterialInteraction
{
  public vec3 shadingNormal;
  public float opacity;
  public vec3 albedo;
  public float normalDetail; // 1.0 - dot(shadingNormal, interpolatedNormal)
  public vec3 baseReflectivity;
  // Note: These roughness values are non-perceptual roughness.
  public float isotropicRoughness;
  public vec2 anisotropicRoughness;
  // Note: fp16 may not be sufficient here for high radiance values, potentially change if clamping
  public vec3 emissiveRadiance;
  public SubsurfaceMaterialInteraction subsurfaceMaterialInteraction;
  // Note: A value of 0 in the thin film thickness indicates the thin film is disabled.
  public float thinFilmThickness;
  public uint32_t dlssControlMask;
  public uint8_t flags;
};

public struct DecalMaterialInteraction
{
  public vec3 shadingNormal;
  public vec3 albedo;
  public vec3 baseReflectivity;
  public vec3 emissiveRadiance;
  public float opacity;
  public float roughness;
  public float anisotropy;
};

public struct MemoryDecalMaterialInteraction
{
  public uint4 packed;
  public f16vec3 emissiveRadiance;
};

public struct TranslucentSurfaceMaterialInteraction
{
  public vec3 shadingNormal;
  public float normalDetail; // 1.0 - dot(shadingNormal, interpolatedNormal)

  public float baseReflectivity;
  public float refractiveIndex;
  public vec3 transmittanceColor;
  public vec3 emissiveRadiance;

  // diffuse layer parameters, only valid if TRANSLUCENT_SURFACE_MATERIAL_FLAG_USE_ALBEDO_LAYER is set in flags
  public vec3 diffuseColor;
  public float diffuseOpacity;

  // Note: Source values only used for serialization purposes.
  // Note: Used as much of a translucent material is constant and typically reading from the material even
  // if it requires an indirection should be better than reading/writing more data to per-pixel buffers. Additionally the
  // lack of the original values such as the transmittance measurement distance and color make it hard to send this compactly
  // without otherwise having to upload those to the Translucent Surface Material (could be done if needed though).
  public uint sourceSurfaceMaterialIndex;
  // Note: Raw (gamma encoded) emissive color packed in R5G6B5 needed for more tight packing, not ideal as this carries live state
  // across other code but this is an easy way to get the required info.
  public uint16_t sourcePackedGammaEmissiveColor;

  // encodes either the thin-walled thickness or the transmittance measurement distance
  // thin-walled thickness is represented as a negative number
  public float thicknessOrMeasurementDistance;

  public uint8_t flags;

  public bool isAnimatedWater;
};

public struct RayPortalSurfaceMaterialInteraction
{
  public vec4 mask;

  public uint8_t rayPortalIndex;
  public uint8_t isInsidePortal;
};

// Note: GBuffer-specific serialization data, not as tightly packed as it could be but done in this manner to share
// data with other passes (NRD, RTXDI, etc) and reuse that data for deserialization to not duplicate information. Some
// of these values may not be populated depending on the material and will instead be set to the desired special output
// value to indicate non-presence to subsequent passes. Additionally some of this data is assumed to be packed later
// by the gbuffer helper functions just to avoid code duplication (not ideal but we probably need a better way to do this).
public struct GBufferMemoryPolymorphicSurfaceMaterialInteraction
{
  public f16vec3 worldShadingNormal;
  public float16_t perceptualRoughness;
  public f16vec3 albedo;
  public f16vec3 baseReflectivity;

  public uint data0;
  public uint data1;
  public bool waterContact = false;
};

public struct PolymorphicSurfaceMaterialInteraction
{
  public vec3 shadingNormal;
  public vec3 emissiveRadiance;
  public vec3 vdata0;
  public vec3 vdata1;

  public float fdata0;
  public float fdata1;
  public float fdata2;
  public float fdata3;
  public float fdata4;
  public float fdata5;

  public uint idata0;
  public uint16_t idata1;

  public uint32_t i32data0;
  public uint32_t i32data1;
  public uint32_t dlssControlMask;

  public uint8_t bdata0;
  public uint8_t bdata1;
  public uint8_t bdata2;

  public uint8_t type;

  public uint8_t isAnimatedWater;
};
