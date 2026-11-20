// SPDX-FileCopyrightText: Copyright (c) 2023-2024 NVIDIA CORPORATION
// SPDX-License-Identifier: MIT
module orthonormal_basis;

// Duff et al., Building an Orthonormal Basis, Revisited.
public void orthonormalBasis(float3 normal, out float3 tangent, out float3 bitangent)
{
  const float sign = normal.z < 0.0f ? -1.0f : 1.0f;
  const float a = -1.0f / (sign + normal.z);
  const float b = normal.x * normal.y * a;

  tangent = float3(1.0f + sign * normal.x * normal.x * a, sign * b, -sign * normal.x);
  bitangent = float3(b, sign + normal.y * normal.y * a, -normal.y);
}
