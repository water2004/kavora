/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/pass/common_binding_indices.h"

#define CLOUD_SKY_BINDING_PROBE 40
#define CLOUD_SKY_BINDING_SAMPLER 41
#define CLOUD_SKY_BINDING_OUTPUT 42

#if CLOUD_SKY_BINDING_PROBE <= COMMON_MAX_BINDING
#error "Cloud sky bindings overlap common bindings."
#endif
