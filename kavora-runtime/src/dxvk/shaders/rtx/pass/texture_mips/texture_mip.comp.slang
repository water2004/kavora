/* SPDX-License-Identifier: MIT */
#include "texture_mip_args.h"

layout(binding = TEXTURE_MIP_SOURCE) Texture2D<float4> sourceMip;
layout(binding = TEXTURE_MIP_OUTPUT) RWTexture2D<float4> targetMip;
layout(binding = TEXTURE_MIP_TILES) StructuredBuffer<uint2> tiles;
layout(push_constant) ConstantBuffer<TextureMipArgs> args;

float linearize(float v) {
  return v <= 0.04045f ? v / 12.92f : pow((v + 0.055f) / 1.055f, 2.4f);
}

float encodeSrgb(float v) {
  return v <= 0.0031308f ? v * 12.92f : 1.055f * pow(v, 1.f / 2.4f) - 0.055f;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 group : SV_GroupID, uint3 local : SV_GroupThreadID) {
  uint tile = group.x + group.y * args.groupsX;
  if (tile >= args.tileCount) {
    return;
  }
  uint2 pixel = tiles[args.firstTile + tile] + local.xy;
  if (pixel.x >= args.targetWidth || pixel.y >= args.targetHeight) {
    return;
  }
  uint2 sourceSize = uint2(args.sourceWidth, args.sourceHeight);
  uint2 targetSize = uint2(args.targetWidth, args.targetHeight);
  uint2 first = pixel * sourceSize / targetSize;
  uint2 last = (pixel + 1) * sourceSize / targetSize;
  bool srgb = (args.format == 1 || args.format == 3) && args.purpose == 0;
  bool normal = args.purpose >= 2 && args.purpose <= 4;
  bool peak = args.purpose == 5;
  precise float4 sum = peak ? -asfloat(0x7f800000u) : 0.f;
  uint count = 0;
  for (uint y = first.y; y < last.y; y++) {
    for (uint x = first.x; x < last.x; x++) {
      float4 value = sourceMip.Load(int3(x, y, 0));
      if (srgb) {
        value.r = linearize(value.r);
        value.g = linearize(value.g);
        value.b = linearize(value.b);
      }
      if (normal) {
        float2 n = value.xy * 2.f - 1.f;
        float2 t = float2(n.x + n.y, n.x - n.y) * 0.5f;
        value.xyz = normalize(float3(t, max(0.f, 1.f - abs(t.x) - abs(t.y))));
      }
      sum = peak ? max(sum, value) : sum + value;
      count++;
    }
  }
  float4 result = peak ? sum : sum / count;
  if (normal) {
    float length = abs(sum.x) + abs(sum.y) + max(0.f, sum.z);
    result.xy = length > 1.e-20f ? float2(sum.x + sum.y, sum.x - sum.y) / length * 0.5f + 0.5f : 128.f / 255.f;
  } else if (srgb) {
    result.r = encodeSrgb(result.r);
    result.g = encodeSrgb(result.g);
    result.b = encodeSrgb(result.b);
  }
  // Preserve the API's nearest-UNORM conversion, including half boundaries.
  if (args.format <= 5) {
    result = floor(saturate(result) * 255.f + 0.5f) / 255.f;
  }
  if (args.format == 6) {
    result = f16tof32(f32tof16(result));
  }
  targetMip[pixel] = result;
}
