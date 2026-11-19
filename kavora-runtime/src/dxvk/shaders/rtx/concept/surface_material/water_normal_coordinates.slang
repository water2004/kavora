// SPDX-FileCopyrightText: Copyright (c) 2023-2026 NVIDIA CORPORATION. All rights reserved.
// SPDX-License-Identifier: MIT
module water_normal_coordinates;

// Each column describes a rotated wave lattice in material UV space. Integer
// multiples of 1/128 preserve phase when world mesh origins wrap by 128 tiles.
public static const float2 swellLattice = float2(55, 21) / 128.f;
public static const float2 rippleLattice = float2(-91, 169) / 128.f;

[ForceInline]
public float2 waveCoordinates(float2 uv, float2 lattice)
{
  return float2(dot(uv, lattice), dot(uv, float2(-lattice.y, lattice.x)));
}

// The transpose of the coordinate transform returns a height gradient to the
// original tangent frame. The two heights add before constructing the normal.
[ForceInline]
public float3 oceanWaveNormal(float3 swell, float3 ripple)
{
  float2 longSlope = waveCoordinates(swell.xy, swellLattice * float2(1, -1));
  float2 shortSlope = waveCoordinates(ripple.xy, rippleLattice * float2(1, -1));
  return float3(longSlope * ripple.z + 0.18f * shortSlope * swell.z, swell.z * ripple.z);
}

// The material pass retains sampler choice and LOD. These transforms return
// coordinates for its existing normal-map layers.
[ForceInline]
public float2 waterSpritesheetNormalUV(float2 localUV, float2 bias, float2 size, float time, float2 velocity)
{
  return bias + size * frac(localUV + time * velocity);
}

[ForceInline]
public float2 waterLayeredNormalUV(float2 uv, float2x4 transform, float2 motionScale)
{
  transform[0][2] *= motionScale.x;
  transform[1][2] *= motionScale.y;
  return mul(transform, float4(uv, 0.f, 1.f)).xy;
}
