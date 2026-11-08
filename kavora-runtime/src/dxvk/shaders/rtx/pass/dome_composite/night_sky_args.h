/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/utility/shader_types.h"

struct NightSkyArgs {
  vec4 rotation;
  vec4 intensity;
  vec4 endParameters;
  vec4 meteorHead[2];
  vec4 meteorTail[2];
};

#ifdef __cplusplus
static_assert(sizeof(NightSkyArgs) == 112);
#endif
