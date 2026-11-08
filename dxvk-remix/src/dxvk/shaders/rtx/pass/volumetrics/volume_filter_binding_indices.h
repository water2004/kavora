/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/pass/common_binding_indices.h"

#define VOLUME_FILTER_INPUT_Y       40
#define VOLUME_FILTER_INPUT_CO_CG   41
#define VOLUME_FILTER_OUTPUT_Y      42
#define VOLUME_FILTER_OUTPUT_CO_CG  43

#if VOLUME_FILTER_INPUT_Y <= COMMON_MAX_BINDING
#error "Volume filtering bindings overlap common resources."
#endif
