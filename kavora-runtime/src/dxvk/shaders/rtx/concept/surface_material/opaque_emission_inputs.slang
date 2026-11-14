/* SPDX-License-Identifier: MIT */
module opaque_emission_inputs;

import "rtx/concept/surface_material/surface_material_types.slang";

public interface IOpaqueEmissionResources {
  static Texture2D<float4> texture(uint index);
  static SamplerState sampler(uint index);
};

public struct OpaqueEffectInputs {
  public OpaqueSurfaceMaterial material;
  public float2 uv;
  public float2 gradientX;
  public float2 gradientY;
  public float3 albedo;
  public float lodBias;
  public uint mode;
  public float time;
  public float glintStrength;
  public float glintSpeed;
};

public struct ProjectedEmissionInputs
{
  public OpaqueSurfaceMaterial material;
  public float4x4 projection;
  public uint2 resolution;
  public float time;
  public float3 viewDirection;
};
