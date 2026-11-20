// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
module water_slope_variance;

// Each band approximates the squared box response of the default ocean spectrum.
// The rationalized expression retains precision for sub-texel footprints.
float unresolvedBand(float trace, float determinant, float scale, float energy)
{
  const float x = scale * trace + scale * scale * determinant;
  const float root = sqrt(1.0f + x);
  return energy * x / (root * (root + 1.0f));
}

public float waterSlopeVariance(float2 gradientX, float2 gradientY)
{
  const float a = dot(gradientX, gradientX);
  const float c = dot(gradientY, gradientY);
  const float trace = a + c;
  const float area = gradientX.x * gradientY.y - gradientX.y * gradientY.x;
  const float determinant = area * area;
  const float energy = 0.0021125f;
  return energy * (
    unresolvedBand(trace, determinant, 2.17631448e-7f, 0.01164756f) +
    unresolvedBand(trace, determinant, 2.10271264e-5f, 0.40454206f) +
    unresolvedBand(trace, determinant, 2.92164538e-4f, 0.21706755f) +
    unresolvedBand(trace, determinant, 1.70971373e-3f, 0.21346971f) +
    unresolvedBand(trace, determinant, 1.34330695e-2f, 0.15327312f));
}
