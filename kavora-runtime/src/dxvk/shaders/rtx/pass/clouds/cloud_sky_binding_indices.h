/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/pass/common_binding_indices.h"

#define CLOUD_SKY_BINDING_OUTPUT 42
#define CLOUD_SKY_BINDING_PROBE 43
#define CLOUD_SKY_BINDING_AIR_SAMPLER 44
#define CLOUD_SKY_RESOLUTION 192
#define CLOUD_SKY_LAYER_COUNT 3

#if CLOUD_SKY_BINDING_OUTPUT <= COMMON_MAX_BINDING
#error "Cloud sky bindings overlap common bindings."
#endif
