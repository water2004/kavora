/*
* Copyright (c) 2023-2024, NVIDIA CORPORATION. All rights reserved.
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
module quaternion;

import "rtx/utility/math_constants.slang";

[ForceInline]
public bool isQuatRightHanded(float q)
{
  return asuint(q) & 0x80000000;
}

[ForceInline]
public float3 quaternionTransformVector(float4 q, float3 v, bool signBitIsParity = false)
{
  float3 newVector = v + 2.0f * cross(q.xyz, cross(q.xyz, v) + q.w * v);

  // In cases where we encode the sign bit of the quaternion representing an improper rotation...
  if (signBitIsParity)
  {
    newVector = (isQuatRightHanded(q.w) ? -1.0f : 1.0f) * newVector;
  }

  return newVector;
}

[ForceInline]
public float4 quaternionInverse(float4 q)
{
  return float4(-q.xyz, q.w);
}

[ForceInline]
public float4 getReflectionQuaternion(float3 normal)
{
  // Half-angle for quaternion calculation
  float halfAngle = pi * 0.5f;

  // Calculate the quaternion components
  float sinHalfAngle = sin(halfAngle);

  float4 q;
  q.x = normal.x * sinHalfAngle;
  q.y = normal.y * sinHalfAngle;
  q.z = normal.z * sinHalfAngle;
  q.w = cos(halfAngle);

  // Normalize the quaternion to ensure it's a unit quaternion, also negate the result
  // Reflection quaternions have a determinant = -1, we'll utilize the q = -q property
  // of quaternions to encode the accumulated parity of reflections.
  return -normalize(q);
}

[ForceInline]
public float4 getIdentityQuaternion()
{
  return float4(0.0f, 0.0f, 0.0f, 1.0f);
}
