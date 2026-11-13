/*
* Copyright (c) 2023-2025, NVIDIA CORPORATION. All rights reserved.
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
module material_scattering;

public struct LobeSamplingRange
{
  public float zeroThreshold;
  public float minimum;
};

public struct SurfaceMaterialInteractionLobeInformation
{
  public bool diffuseReflectionPresent;
  public bool specularReflectionPresent;
  public bool diffuseTransmissionPresent;
  public bool specularTransmissionPresent;

  public bool specularReflectionDirac;
  public bool specularTransmissionDirac;
};

public struct SurfaceMaterialInteractionLobeSample
{
  public uint8_t lobe;
  public float pdf;
};

public struct SurfaceMaterialInteractionSplitWeight
{
  // Note: Only Diffuse and Specular reflection weights needed as weights are never evaluated separately on transmission lobes
  public float3 diffuseReflectionWeight;
  public float3 specularReflectionWeight;
  public float3 diffuseTransmissionWeight;
};

public struct SurfaceMaterialInteractionSample
{
  public float3 inputDirection;
  // Note: Throughput represents (weight / solidAnglePdf) * (n.l), otherwise known as projectedWeight / solidAnglePdf to allow for
  // algebraic cancelling. Weight never directly needed from sampling operations, only when evaluating the BSDF weight directly (which
  // there is a function for).
  public float3 throughput;
  public float solidAnglePdf;
};

public struct SurfaceMaterialInteractionPSRSample
{
  public bool performPSR;
  public bool useAlternateDisocclusionThreshold;
  public float3 inputDirection;
  public float3 attenuation;
  public float4 vectorTransform;
  public float relativeRefractiveIndex;
};

[ForceInline]
public void adjustProbabilityValue(inout float value, float zeroThreshold, float minProbability)
{
  // Clamp the probability to zero if it is below the threshold

  if (value <= zeroThreshold)
  {
    value = 0.0f;

    return;
  }

  // Clamp any probabilities to the minimum if they are below it

  value = max(value, minProbability);
}

[ForceInline]
public void adjustProbabilityValue(inout float value, LobeSamplingRange range)
{
  adjustProbabilityValue(value, range.zeroThreshold, range.minimum);
}
