/* SPDX-License-Identifier: MIT */
#pragma once

#define GBUFFER_WATER_IMAGES(X) \
  X(GBUFFER_BINDING_WATER_SIMULATION, TEXTURE2D, Texture2D<float4>, WaterSimulation, view) \
  X(GBUFFER_BINDING_WATER_WIND, TEXTURE2DARRAY, Texture2DArray<float4>, WaterWind, windView) \
  X(GBUFFER_BINDING_WATER_RINGS, TEXTURE2D, Texture2D<float2>, WaterRings, ringView) \
  X(GBUFFER_BINDING_WATER_IMPACTS, TEXTURE2D, Texture2D<float2>, WaterImpacts, impactView)
