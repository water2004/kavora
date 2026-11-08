/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/utility/shader_types.h"

static const uint kCloudBaseResolution = 128;
static const uint kCloudDetailResolution = 32;
static const uint kCloudWeatherResolution = 512;

struct CloudNoiseGenerationArgs {
  uint detail;
  uint padding0;
  uint padding1;
  uint padding2;
};
