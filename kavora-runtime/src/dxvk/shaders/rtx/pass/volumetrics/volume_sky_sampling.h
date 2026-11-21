/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/pass/clouds/cloud_sky_binding_indices.h"

#define VOLUME_SKY_GROUP_SIZE 512
#define VOLUME_SKY_SAMPLE_COUNT 4096
#define VOLUME_SKY_CDF_BINDING 40
#define VOLUME_SKY_SAMPLES_BINDING 41
#define VOLUME_SKY_PROBE_BINDING 42
#define VOLUME_SKY_PROBE_SAMPLER_BINDING 43

#if VOLUME_SKY_CDF_BINDING <= COMMON_MAX_BINDING
#error "Volume sky sampling bindings overlap common bindings."
#endif
#if VOLUME_SKY_GROUP_SIZE < 2 * CLOUD_SKY_RESOLUTION || (VOLUME_SKY_SAMPLE_COUNT % VOLUME_SKY_GROUP_SIZE) != 0
#error "The volume sky scan must cover a complete row and the sample-bank dispatch must be exact."
#endif
