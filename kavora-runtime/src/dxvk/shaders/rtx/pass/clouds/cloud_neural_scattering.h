// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
#pragma once

// Sky bands pack one far tile beside near and middle tiles. Beam bands use
// the full plane for the light- and view-optical-depth axes.
#define CLOUD_NEURAL_TILE_SIZE 10
#define CLOUD_NEURAL_ATLAS_WIDTH (3 * CLOUD_NEURAL_TILE_SIZE)
#define CLOUD_NEURAL_ATLAS_DEPTH (2 * CLOUD_NEURAL_TILE_SIZE)
#define CLOUD_NEURAL_HEIGHT 12
#define CLOUD_NEURAL_HIGH_HEIGHT 4
#define CLOUD_NEURAL_COLUMN_HEIGHT (CLOUD_NEURAL_HEIGHT + CLOUD_NEURAL_HIGH_HEIGHT)
#define CLOUD_NEURAL_POLAR_KNOTS 6
#define CLOUD_NEURAL_SKY_CHANNEL 2
// A beam band stores radiance and tangent at the two elevations of one knot.
#define CLOUD_NEURAL_SKY_BAND (CLOUD_NEURAL_SKY_CHANNEL * CLOUD_NEURAL_POLAR_KNOTS)
#define CLOUD_NEURAL_TOTAL_BANDS (CLOUD_NEURAL_SKY_BAND + 2)
// At either pole the two elevations coincide.
#define CLOUD_NEURAL_DIRECTIONS (2 * CLOUD_NEURAL_POLAR_KNOTS - 2)
#define CLOUD_NEURAL_INPUTS 16
#define CLOUD_NEURAL_WIDTH 96
#define CLOUD_NEURAL_HIDDEN_LAYERS 4
#define CLOUD_NEURAL_OUTPUT_BINDING 40
#define CLOUD_NEURAL_WEIGHTS_BINDING 41
