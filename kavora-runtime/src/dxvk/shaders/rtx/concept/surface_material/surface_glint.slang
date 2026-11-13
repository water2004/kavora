/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
module surface_glint;

// Glint adds to the shaded surface while preserving its existing coverage and material.
public float3 surfaceGlint(Texture2D<float4> texture, SamplerState sampler,
  float2 coordinates, float2 gradientX, float2 gradientY,
  float3 albedo, float lodBias, float strength, float seconds, float speed, float scale)
{
  // Minecraft rotates the scrolling texture by ten degrees.
  const float2x2 rotation = float2x2(0.98480775f, -0.17364818f, 0.17364818f, 0.98480775f);
  const float time = seconds * speed * 8.f;
  const float2 offset = float2(-frac(time / 110.f), frac(time / 30.f));
  const float2 uv = frac(mul(rotation, coordinates) * scale + offset);
  const float gradientScale = scale * exp2(lodBias);
  const float3 glint = texture.SampleGrad(sampler, uv,
    mul(rotation, gradientX) * gradientScale, mul(rotation, gradientY) * gradientScale).rgb * strength;
  const float3 pattern = glint * glint;

  // The glint softly lights the material beneath its colored sheen.
  const float patternLuminance = dot(pattern, float3(0.2126f, 0.7152f, 0.0722f));
  const float diffuseGlow = 0.018f * strength + patternLuminance * 1.4f;
  return albedo * diffuseGlow + pattern * 0.2f;
}
