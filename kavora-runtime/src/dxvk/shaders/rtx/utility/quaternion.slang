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

[ForceInline]
public bool isQuatRightHanded(float16_t q)
{
  return reinterpret<uint16_t>(q) & 0x8000;
}

[ForceInline]
public float16_t3 quaternionTransformVector(float4 q, float16_t3 v, bool signBitIsParity = false)
{
  return float16_t3(quaternionTransformVector(q, float3(v), signBitIsParity));
}

// Calculates the quaternion which transforms the normalized vector src to normalized vector dst
// Note: returns a zero quat if src == -dst, case when dot(src, dst) == -1 needs to be handled if this is expected.
[ForceInline]
public float4 quaternionCreateOrientation(float3 src, float3 dst)
{
  // https://stackoverflow.com/questions/1171849/finding-quaternion-representing-the-rotation-from-one-vector-to-another
  float4 q;
  q.xyz = cross(src, dst);
  q.w = 1.0 + dot(src, dst);

  // Normalize the quaternion
  float len = length(q);
  q = (len > 0.0f) ? q / len : float4(1.0, 0.0, 0.0, 0.0);

  // Quaternions returned here are always positive
  return (q.w < 0.0f) ? -q : q;
}

[ForceInline]
public float4 quaternionMultiply(float4 q1, float4 q2, bool signBitIsParity = false)
{
  float4 q;

  // q1 * q2
  q.w = q1.w * q2.w - dot(q1.xyz, q2.xyz);
  q.xyz = q1.w * q2.xyz + q2.w * q1.xyz + cross(q1.xyz, q2.xyz);

  // Quaternions returned here are always positive
  q = isQuatRightHanded(q.w) ? -q : q;

  // In cases where we encode the sign bit of the quaternion representing an improper rotation...
  if (signBitIsParity)
  {
    return (isQuatRightHanded(q1) ? -1.0f : 1.0f) * (isQuatRightHanded(q2.w) ? -1.0f : 1.0f) * q;
  }
  else
  {
    return q;
  }
}

[ForceInline]
public float3 quaternionToTangent(float4 q)
{
  return quaternionTransformVector(q, float3(1, 0, 0));
}

[ForceInline]
public float3 quaternionToBitangent(float4 q)
{
  if (isQuatRightHanded(q)) {
    return quaternionTransformVector(q, float3(0, 1, 0));
  } else {
    return quaternionTransformVector(q, float3(0, -1, 0));
  }
}

[ForceInline]
public float3 quaternionToNormal(float4 q)
{
  return quaternionTransformVector(q, float3(0, 0, 1));
}

// Because quaternion == -quaternion, we can use the sign bit of the w
// component of the quaternion to encode the handedness of the original TBN matrix.
[ForceInline]
public bool isQuatRightHanded(float16_t4 q)
{
  return isQuatRightHanded(q.w);
}

[ForceInline]
public bool isQuatRightHanded(float4 q)
{
  return isQuatRightHanded(q.w);
}

// This produces a quaternion with a positive w, then inverts the quaternion if the input
// TBN was right handed.  As q == -q for rotation purposes, this is a safe way to store
// an extra flag along with the quaternion.
// `isQuatRightHanded` can be used to easily check if the input TBN was right handed.
[ForceInline]
public float4 tbnFrameToQuaternion(float3 tangent, float3 biTangent, float3 normal)
{
  const bool rightHanded = (dot(cross(tangent,biTangent), normal) >= 0.0);
  if (!rightHanded)
  {
    biTangent = -biTangent;
  }
  float3x3 rotationMatrix = { tangent, biTangent, normal };
  rotationMatrix = transpose(rotationMatrix);

  const float tr = rotationMatrix[0][0] + rotationMatrix[1][1] + rotationMatrix[2][2];
  float4 quaternion;
  if (tr > 0)
  {
    float s = sqrt(tr + 1.0) * 2; // s=4*qw
    quaternion.w = 0.25 * s;
    quaternion.x = (rotationMatrix[2][1] - rotationMatrix[1][2]) / s;
    quaternion.y = (rotationMatrix[0][2] - rotationMatrix[2][0]) / s;
    quaternion.z = (rotationMatrix[1][0] - rotationMatrix[0][1]) / s;
  }
  else if ((rotationMatrix[0][0] > rotationMatrix[1][1]) && (rotationMatrix[0][0] > rotationMatrix[2][2]))
  {
    float s = sqrt(1.0 + rotationMatrix[0][0] - rotationMatrix[1][1] - rotationMatrix[2][2]) * 2; // s=4*qx
    quaternion.w = (rotationMatrix[2][1] - rotationMatrix[1][2]) / s;
    quaternion.x = 0.25 * s;
    quaternion.y = (rotationMatrix[0][1] + rotationMatrix[1][0]) / s;
    quaternion.z = (rotationMatrix[0][2] + rotationMatrix[2][0]) / s;
  }
  else if (rotationMatrix[1][1] > rotationMatrix[2][2])
  {
    float s = sqrt(1.0 + rotationMatrix[1][1] - rotationMatrix[0][0] - rotationMatrix[2][2]) * 2; // s=4*qy
    quaternion.w = (rotationMatrix[0][2] - rotationMatrix[2][0]) / s;
    quaternion.x = (rotationMatrix[0][1] + rotationMatrix[1][0]) / s;
    quaternion.y = 0.25 * s;
    quaternion.z = (rotationMatrix[1][2] + rotationMatrix[2][1]) / s;
  }
  else
  {
    float s = sqrt(1.0 + rotationMatrix[2][2] - rotationMatrix[0][0] - rotationMatrix[1][1]) * 2; // s=4*qz
    quaternion.w = (rotationMatrix[1][0] - rotationMatrix[0][1]) / s;
    quaternion.x = (rotationMatrix[0][2] + rotationMatrix[2][0]) / s;
    quaternion.y = (rotationMatrix[1][2] + rotationMatrix[2][1]) / s;
    quaternion.z = 0.25 * s;
  }

  // If sign bit of w is negative but the original TBN was right handed
  // (or vice versa) flip the quaternion.
  if (isQuatRightHanded(quaternion) != rightHanded)
  {
    quaternion = -quaternion;
  }

  return quaternion;
}
