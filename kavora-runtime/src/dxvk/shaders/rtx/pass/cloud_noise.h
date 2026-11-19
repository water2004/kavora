/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/utility/shader_types.h"

// World periods are expressed in units of CloudArgs.shapeScale.
static const uint kCloudBasePeriod = 2;
static const uint kCloudBaseResolution = 64;
static const uint kCloudDetailResolution = 64;
static const uint kCloudWeatherResolution = 512;
static const uint kCloudHighWeatherResolution = 1024;
static const uint kCloudWeatherPeriod = 64;
static const uint kCloudHighWeatherPeriod = 64;
