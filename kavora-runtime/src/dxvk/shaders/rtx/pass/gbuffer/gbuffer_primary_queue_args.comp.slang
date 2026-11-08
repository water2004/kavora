/* SPDX-License-Identifier: MIT */
#include "rtx/utility/shader_types.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"

layout(binding = GBUFFER_BINDING_PSR_QUEUE_OUTPUT) RWStructuredBuffer<uint> PrimaryQueue;

[shader("compute")]
[numthreads(1, 1, 1)]
void main()
{
  const uint count = PrimaryQueue[0];
  PrimaryQueue[1] = (min(count, GBUFFER_PSR_QUEUE_ROW_WIDTH) + 127) / 128;
  PrimaryQueue[2] = (count + GBUFFER_PSR_QUEUE_ROW_WIDTH - 1) / GBUFFER_PSR_QUEUE_ROW_WIDTH;
  PrimaryQueue[3] = 1;
}
