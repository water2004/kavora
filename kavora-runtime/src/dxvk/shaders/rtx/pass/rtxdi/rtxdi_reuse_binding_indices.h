/*
* Copyright (c) 2022-2026, NVIDIA CORPORATION. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a
* copy of this software and associated documentation files (the "Software"),
* to deal in the Software without restriction, including without limitation
* the rights to use, copy, modify, merge, publish, distribute, sublicense,
* and/or sell copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
* THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
* DEALINGS IN THE SOFTWARE.
*/
#pragma once

#include "rtx/pass/common_binding_indices.h"

// Inputs

#define RTXDI_REUSE_BINDING_WORLD_SHADING_NORMAL_INPUT      44
#define RTXDI_REUSE_BINDING_PERCEPTUAL_ROUGHNESS_INPUT      32
#define RTXDI_REUSE_BINDING_HIT_DISTANCE_INPUT              33
#define RTXDI_REUSE_BINDING_ALBEDO_INPUT                    34
#define RTXDI_REUSE_BINDING_BASE_REFLECTIVITY_INPUT         35
#define RTXDI_REUSE_BINDING_WORLD_POSITION_INPUT            36
#define RTXDI_REUSE_BINDING_PREV_WORLD_POSITION_INPUT       37
#define RTXDI_REUSE_BINDING_VIEW_DIRECTION_INPUT            38
#define RTXDI_REUSE_BINDING_CONE_RADIUS_INPUT               39
#define RTXDI_REUSE_BINDING_WS_MVEC_INPUT                   40
#define RTXDI_REUSE_BINDING_SS_MVEC_INPUT                   41
#define RTXDI_REUSE_BINDING_POSITION_ERROR_INPUT            42
#define RTXDI_REUSE_BINDING_SHARED_FLAGS_INPUT              43
#define RTXDI_REUSE_BINDING_BEST_LIGHTS_INPUT               48
#define RTXDI_REUSE_BINDING_SHARED_SURFACE_INDEX_INPUT      49
#define RTXDI_REUSE_BINDING_SUBSURFACE_DATA_INPUT           50
#define RTXDI_REUSE_BINDING_SUBSURFACE_DIFFUSION_PROFILE_DATA_INPUT 51
#define RTXDI_REUSE_BINDING_SHARED_TERMINATOR_FIX_INPUT             52
#define RTXDI_REUSE_BINDING_SHARED_TERMINATOR_FIX_PREVIOUS_INPUT    53

#define RTXDI_REUSE_BINDING_COMPACTED_PIXEL_INDICES_INPUT           59
#define RTXDI_REUSE_BINDING_TILE_ACTIVE_COUNTS_INPUT                60
#define RTXDI_REUSE_BINDING_ACTIVE_PIXEL_COORDS_INPUT               61
#define RTXDI_REUSE_BINDING_ACTIVE_PIXEL_COUNT_INPUT                62
#define RTXDI_REUSE_BINDING_WORLD_POSITION_DENSE_INPUT              63
#define RTXDI_REUSE_BINDING_SHARED_FLAGS_DENSE_INPUT                64
#define RTXDI_REUSE_BINDING_LINEAR_VIEW_Z_INPUT                     65
#define RTXDI_REUSE_BINDING_WORLD_SHADING_NORMAL_DENSE_INPUT        66
#define RTXDI_REUSE_BINDING_ALBEDO_DENSE_INPUT                      67

// Inputs / Outputs

#define RTXDI_REUSE_BINDING_RTXDI_RESERVOIR_INPUT_OUTPUT    68
#define RTXDI_REUSE_BINDING_LAST_GBUFFER_INPUT_OUTPUT       69

// Outputs

#define RTXDI_REUSE_BINDING_REPROJECTION_CONFIDENCE_OUTPUT  93
#define RTXDI_REUSE_BINDING_BSDF_FACTOR_OUTPUT              94
#define RTXDI_REUSE_BINDING_TEMPORAL_POSITION_OUTPUT        95

#define RTXDI_REUSE_MIN_BINDING                           RTXDI_REUSE_BINDING_PERCEPTUAL_ROUGHNESS_INPUT

#if RTXDI_REUSE_MIN_BINDING <= COMMON_MAX_BINDING
#error "Increase the base index of RTXDI bindings to avoid overlap with common bindings!"
#endif
