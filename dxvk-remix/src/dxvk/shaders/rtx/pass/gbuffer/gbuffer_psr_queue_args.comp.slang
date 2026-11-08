/*
* Copyright (c) 2026, NVIDIA CORPORATION. All rights reserved.
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
#include "rtx/utility/shader_types.slangh"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
layout(binding = GBUFFER_BINDING_PSR_QUEUE_INPUT) RWStructuredBuffer<uint> PSRQueueInput;
layout(binding = GBUFFER_BINDING_PSR_QUEUE_OUTPUT) RWStructuredBuffer<uint> PSRQueueOutput;

[shader("compute")]
[numthreads(1, 1, 1)]
void main()
{
  const uint count = PSRQueueInput[0];
  PSRQueueInput[1] = min(count, GBUFFER_PSR_QUEUE_ROW_WIDTH);
  PSRQueueInput[2] = (count + GBUFFER_PSR_QUEUE_ROW_WIDTH - 1) / GBUFFER_PSR_QUEUE_ROW_WIDTH;
  PSRQueueInput[3] = 1;
  PSRQueueOutput[0] = 0;
}
