// SPDX-FileCopyrightText: Copyright (c) 2026 HaringPro
// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: Apache-2.0
module cloud_phase;

import "rtx/utility/cloud_phase_constants.slang";
#include "rtx/pass/clouds/cloud_phase.h"

public uint cloudPhaseMaterial(uint layer)
{
  return layer == 0 ? 0u : 2u;
}

public float cloudMeanCosine(uint layer)
{
  return kCloudPhaseMeanCosine[cloudPhaseMaterial(layer)];
}

// Revelation stores log2(p) with more angular resolution near both poles.
float cloudPhaseU(float cosine)
{
  const float angle = acos(clamp(cosine, -1.0f, 1.0f)) * (2.0f / 3.14159265359f) - 1.0f;
  const float symmetric = 0.5f + 0.5f * angle / lerp(1.98f, 1.0f, abs(angle));
  const float u = symmetric / lerp(0.84f, 1.0f, symmetric);
  return (u * (CLOUD_PHASE_WIDTH - 1) + 0.5f) / CLOUD_PHASE_WIDTH;
}

float samplePhaseRow(Sampler2D<float> table, float u, uint row)
{
  return exp2(table.SampleLevel(float2(u, (float(row) + 0.5f) / CLOUD_PHASE_HEIGHT), 0));
}

public float cloudPhase(Sampler2D<float> table, float cosine, uint layer)
{
  const uint material = cloudPhaseMaterial(layer);
  return samplePhaseRow(table, cloudPhaseU(cosine), material) * kCloudPhaseNormalization[material];
}

public float2 cloudDiskPhase(Sampler2D<float> table, float cosine)
{
  const float u = cloudPhaseU(cosine);
  return float2(samplePhaseRow(table, u, CLOUD_PHASE_MATERIALS),
    samplePhaseRow(table, u, CLOUD_PHASE_MATERIALS + 2));
}
