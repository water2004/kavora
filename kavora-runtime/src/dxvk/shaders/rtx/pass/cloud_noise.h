/* SPDX-License-Identifier: MIT */
#pragma once

#include "rtx/utility/shader_types.h"

// Revelation's base volume and high cirrus LUT cover 2 km and 20 km.
// Lengths here are expressed in units of CloudArgs.shapeScale (1 km by default).
static const uint kCloudBasePeriod = 2;
static const uint kCloudBaseResolution = 128;
static const uint kCloudDetailResolution = 64;
static const uint kCloudWeatherResolution = 512;
static const uint kCloudCirrusResolution = 1024;
static const uint kCloudCirrusPeriod = 20;
