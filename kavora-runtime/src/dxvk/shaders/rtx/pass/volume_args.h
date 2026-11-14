/*
* Copyright (c) 2022-2025, NVIDIA CORPORATION. All rights reserved.
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

#if defined(__cplusplus) || defined(KAVORA_DECLARE_VOLUME_PARAMETERS)
#include "rtx/utility/shader_types.h"
#ifdef __cplusplus
#include "rtx/pass/cloud_args.h"
#include "rtx/concept/camera/volume_camera.h"
#define VOLUME_PARAMETER
#else
import "rtx/pass/cloud_parameters.slang";
import "rtx/concept/camera/volume_camera.slang";
#define VOLUME_PARAMETER public
#endif

VOLUME_PARAMETER static const uint froxelVolumeMain = 0;
VOLUME_PARAMETER static const uint froxelVolumePortal0 = 1;
VOLUME_PARAMETER static const uint froxelVolumePortal1 = 2;
VOLUME_PARAMETER static const uint froxelVolumeCount = 3;

// Note: Ensure 16B alignment
VOLUME_PARAMETER struct VolumeArgs {
  VOLUME_PARAMETER CloudArgs clouds;
  VOLUME_PARAMETER VolumeDefinitionCamera cameras[froxelVolumeCount];
  VOLUME_PARAMETER VolumeDefinitionCamera restirCameras[froxelVolumeCount];

  VOLUME_PARAMETER uint2 froxelGridDimensions;
  VOLUME_PARAMETER vec2 inverseFroxelGridDimensions;

  VOLUME_PARAMETER uint2 restirFroxelGridDimensions;
  VOLUME_PARAMETER vec2 restirInverseFroxelGridDimensions;

  VOLUME_PARAMETER uint numFroxelVolumes;       // 1 if there is just the main camera volume in the texture, 3 if there are also per-portal volumes
  VOLUME_PARAMETER uint numActiveFroxelVolumes; // Same logic as numFroxelVolumes but only counting active volumes
  VOLUME_PARAMETER uint froxelDepthSlices;
  VOLUME_PARAMETER uint maxAccumulationFrames; // Note: Only an 8 bit value.

  VOLUME_PARAMETER float froxelDepthSliceDistributionExponent;
  VOLUME_PARAMETER float froxelMaxDistance;
  VOLUME_PARAMETER float froxelFireflyFilteringLuminanceThreshold;
  VOLUME_PARAMETER uint16_t enableVolumeRISInitialVisibility;
  VOLUME_PARAMETER uint16_t enableVolumeTemporalResampling;
  VOLUME_PARAMETER vec3 attenuationCoefficient;
  VOLUME_PARAMETER uint16_t enable;
  VOLUME_PARAMETER uint16_t enablevisibilityReuse;

  VOLUME_PARAMETER vec3 scatteringCoefficient;
  VOLUME_PARAMETER uint16_t enableVolumeSpatialResampling;
  VOLUME_PARAMETER uint16_t enableReferenceMode;

  // Note: Min/max filtered radiance U coordinate used to simulate clamp to edge behavior without artifacts
  // by clamping to the center of the first/last froxel on the U axis when dealing with the multiple side by
  // side froxel grids in a single 3D texture.
  VOLUME_PARAMETER float minFilteredRadianceU;
  VOLUME_PARAMETER float maxFilteredRadianceU;
  VOLUME_PARAMETER float inverseNumFroxelVolumes;
  VOLUME_PARAMETER uint numSpatialSamples;

  // Note: This value is already converted into linear space so it is fine to directly add in as a contribution to the volumetrics.
  VOLUME_PARAMETER vec3 multiScatteringEstimate;
  VOLUME_PARAMETER float spatialSamplingRadius;

  VOLUME_PARAMETER uint restirFroxelDepthSlices;
  VOLUME_PARAMETER float volumetricFogAnisotropy;
  VOLUME_PARAMETER uint16_t enableNoiseFieldDensity;
  VOLUME_PARAMETER uint16_t enableAtmosphere;
  VOLUME_PARAMETER float depthOffset;

  VOLUME_PARAMETER float noiseFieldSubStepSize;
  VOLUME_PARAMETER uint noiseFieldOctaves;
  // Note: When set to 0 this indicates that no time modulation of the noise field should be used.
  // Otherwise, scales the time modulation in noise coordinates per second.
  VOLUME_PARAMETER float noiseFieldTimeScale;
  VOLUME_PARAMETER float noiseFieldDensityScale;

  VOLUME_PARAMETER float noiseFieldDensityExponent;
  VOLUME_PARAMETER float noiseFieldInitialFrequency;
  VOLUME_PARAMETER float noiseFieldLacunarity;
  VOLUME_PARAMETER float noiseFieldGain;

  VOLUME_PARAMETER vec3 sceneUpDirection;
  VOLUME_PARAMETER vec3 planetCenter;
  // Note: Squared radius used as most functions involving atmospheric intersection use a squared radius
  // in their math, simplifying the work that needs to be done on the GPU.
  VOLUME_PARAMETER float atmosphereRadiusSquared;

  VOLUME_PARAMETER float maxAttenuationDistanceForNoAtmosphere;
  VOLUME_PARAMETER uint resetHistory;
  VOLUME_PARAMETER uint16_t enableTranslucentShadows;
  VOLUME_PARAMETER uint16_t heightFogLowerFalloff; // Half-float scale in world units.
  VOLUME_PARAMETER uint fogIntegrationMode;
  VOLUME_PARAMETER float heightFogBaseHeight;
  VOLUME_PARAMETER float heightFogFalloff;
  VOLUME_PARAMETER float densityTimeMS;
  VOLUME_PARAMETER uint fogIntegrationPixelStride;

  VOLUME_PARAMETER float groundMistExtinction;
  VOLUME_PARAMETER float groundMistBaseHeight;
  VOLUME_PARAMETER float groundMistFalloff;
  VOLUME_PARAMETER float groundMistRegionSize;
  VOLUME_PARAMETER uint volumePadding;
};

#undef VOLUME_PARAMETER

#ifdef __cplusplus
// We're packing these into a constant buffer (see: raytrace_args.h), so need to remain aligned
static_assert((sizeof(VolumeArgs) & 15) == 0);
#endif
#else
import "rtx/pass/volume_parameters.slang";
#endif
