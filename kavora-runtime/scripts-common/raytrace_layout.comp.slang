// SPDX-License-Identifier: MIT

#include "rtx/pass/raytrace_args.h"

// This entry point is compiled for reflection during the build only.
[[vk::binding(0, 0)]] ConstantBuffer<RaytraceArgs> args;
[[vk::binding(1, 0)]] RWStructuredBuffer<uint> probe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
  probe[0] = args.frameIdx;
}
