// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
#pragma once

// One 2x2 far tile beside two 1x1 tiles for the near and middle fields.
#define CLOUD_NEURAL_TILE_SIZE 10
#define CLOUD_NEURAL_ATLAS_WIDTH (3 * CLOUD_NEURAL_TILE_SIZE)
#define CLOUD_NEURAL_ATLAS_DEPTH (2 * CLOUD_NEURAL_TILE_SIZE)
#define CLOUD_NEURAL_HEIGHT 12
#define CLOUD_NEURAL_HIGH_HEIGHT 4
#define CLOUD_NEURAL_COLUMN_HEIGHT (CLOUD_NEURAL_HEIGHT + CLOUD_NEURAL_HIGH_HEIGHT)
#define CLOUD_NEURAL_BANDS 3
#define CLOUD_NEURAL_SKY_CHANNEL 2
#define CLOUD_NEURAL_SKY_BAND (2 * CLOUD_NEURAL_BANDS)
#define CLOUD_NEURAL_TOTAL_BANDS (CLOUD_NEURAL_SKY_BAND + 2)
// Each RGBA band holds the lower/upper view elevations of two polar knots.
#define CLOUD_NEURAL_POLAR_KNOTS (2 * CLOUD_NEURAL_BANDS)
// At either pole the two elevations coincide.
#define CLOUD_NEURAL_DIRECTIONS (2 * CLOUD_NEURAL_POLAR_KNOTS - 2)
#define CLOUD_NEURAL_INPUTS 15
#define CLOUD_NEURAL_WIDTH 96
#define CLOUD_NEURAL_HIDDEN_LAYERS 4
#define CLOUD_NEURAL_OUTPUT_BINDING 40
#define CLOUD_NEURAL_WEIGHTS_BINDING 41
