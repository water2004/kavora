/*
* Copyright (c) 2023-2026, NVIDIA CORPORATION. All rights reserved.
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
#include "rtx_voxel_coverage_shader.h"
#include "rtx_pathtracer_gbuffer.h"
#include "dxvk_device.h"
#include "rtx_shader_manager.h"
#include "rtx_options.h"
#include "rtx_composite.h"
#include "rtx_debug_view.h"
#include "rtx_neural_radiance_cache.h"
#include "rtx_spatially_hashed_radiance_cache.h"

#include "rtx/pass/common_binding_indices.h"
#include "rtx/pass/gbuffer/gbuffer_binding_indices.h"
#include "rtx/pass/gbuffer/primary_atmosphere_cache.h"
#include "rtx/concept/surface_material/surface_material_hitgroup.h"

#include <rtx_shaders/gbuffer_variants.h>
#include <rtx_shaders/gbuffer_rayquery_variants.h>
#include <rtx_shaders/gbuffer_psr_prepare.h>
#include <rtx_shaders/gbuffer_primary_trace.h>
#include <rtx_shaders/gbuffer_primary_atmosphere.h>
#include <rtx_shaders/gbuffer_atmosphere_columns.h>
#include <rtx_shaders/gbuffer_primary_queue_args.h>
#include <rtx_shaders/gbuffer_atmosphere.h>
#include <rtx_shaders/gbuffer_psr_queue_args.h>
#include <rtx_shaders/gbuffer_psr_queue_shade_variants.h>
#include <rtx_shaders/gbuffer_decal_prepare_variants.h>

#include <rtx_shaders/gbuffer_miss.h>
#include <rtx_shaders/gbuffer_traversal_miss.h>
#include <rtx_shaders/gbuffer_nrc_miss.h>
#include <rtx_shaders/gbuffer_sharc_miss.h>

#include <rtx_shaders/gbuffer_material_opaque_translucent_closesthit.h>
#include <rtx_shaders/gbuffer_material_rayPortal_closesthit.h>
#include <rtx_shaders/gbuffer_nrc_material_opaque_translucent_closesthit.h>
#include <rtx_shaders/gbuffer_nrc_material_rayPortal_closesthit.h>
#include <rtx_shaders/gbuffer_sharc_material_opaque_translucent_closesthit.h>
#include <rtx_shaders/gbuffer_sharc_material_rayPortal_closesthit.h>

#include <rtx_shaders/gbuffer_miss_wboit.h>
#include <rtx_shaders/gbuffer_nrc_miss_wboit.h>
#include <rtx_shaders/gbuffer_sharc_miss_wboit.h>

#include <rtx_shaders/gbuffer_material_opaque_translucent_closesthit_wboit.h>
#include <rtx_shaders/gbuffer_material_rayPortal_closesthit_wboit.h>
#include <rtx_shaders/gbuffer_nrc_material_opaque_translucent_closesthit_wboit.h>
#include <rtx_shaders/gbuffer_nrc_material_rayPortal_closesthit_wboit.h>
#include <rtx_shaders/gbuffer_sharc_material_opaque_translucent_closesthit_wboit.h>
#include <rtx_shaders/gbuffer_sharc_material_rayPortal_closesthit_wboit.h>

#include "dxvk_scoped_annotation.h"
#include "rtx_context.h"
#include "rtx_opacity_micromap_manager.h"
#include "rtx_sparse_rendering.h"

namespace dxvk {

  // Defined within an unnamed namespace to ensure unique definition across binary
  namespace {
    class GbufferRayGenShader : public ManagedShader {
    public:
      BINDLESS_ENABLED()

      PUSH_CONSTANTS(GbufferPushConstants)

      BEGIN_PARAMETER()
        COMMON_RAYTRACING_BINDINGS

        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_PATH_STORAGE)
        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_PSR_QUEUE_INPUT)
        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_PSR_QUEUE_OUTPUT)
        { GBUFFER_BINDING_FG_FOREGROUND_GUIDE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_IMAGE_VIEW_TYPE_2D,
          VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT },
        SAMPLER(GBUFFER_BINDING_LINEAR_WRAP_SAMPLER)
        TEXTURE2D(GBUFFER_BINDING_WATER_SIMULATION)
        TEXTURE2DARRAY(GBUFFER_BINDING_WATER_WIND)
        TEXTURE2D(GBUFFER_BINDING_WATER_RINGS)
        TEXTURE2D(GBUFFER_BINDING_WATER_IMPACTS)
        SAMPLER(GBUFFER_BINDING_WATER_TILE_SAMPLER)

        SAMPLER3D(GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_Y_INPUT)
        SAMPLER3D(GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_CO_CG_INPUT)

        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_BINDING_SHARC_HASH_ENTRIES_INPUT_OUTPUT)
        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_BINDING_SHARC_ACCUMULATION_INPUT_OUTPUT)
        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_BINDING_SHARC_RESOLVED_INPUT_OUTPUT)
        TEXTURE2D(GBUFFER_BINDING_COMPACTED_PIXEL_INDICES_INPUT)
        TEXTURE2D(GBUFFER_BINDING_RADIANCE_CACHE_UPDATE_QUERY_RESERVOIR_INPUT)
        TEXTURE2D(GBUFFER_BINDING_TILE_ACTIVE_COUNTS_INPUT)

        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_FLAGS_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_RADIANCE_RG_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_RADIANCE_B_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_INTEGRATION_SURFACE_PDF_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_MATERIAL_DATA0_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_MATERIAL_DATA1_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_MEDIUM_MATERIAL_INDEX_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_TEXTURE_COORD_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_SURFACE_INDEX_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_SUBSURFACE_DATA_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_SUBSURFACE_DIFFUSION_PROFILE_DATA_OUTPUT)

        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_ATTENUATION_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_WORLD_SHADING_NORMAL_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_PERCEPTUAL_ROUGHNESS_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_LINEAR_VIEW_Z_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_ALBEDO_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_BASE_REFLECTIVITY_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_VIRTUAL_MVEC_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_SCREEN_SPACE_MOTION_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_VIRTUAL_WORLD_SHADING_NORMAL_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_VIRTUAL_WORLD_SHADING_NORMAL_DENOISING_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_HIT_DISTANCE_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_VIEW_DIRECTION_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_CONE_RADIUS_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_POSITION_ERROR_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_SHADOW_TERMINATOR_FIX_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_OBJECT_PICKING_OUTPUT)

        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_WORLD_POSITION_RAW_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_TEXCOORDS_RAW_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_NORMAL_F32_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_COVERAGE_OUTPUT)

        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_ATTENUATION_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_WORLD_SHADING_NORMAL_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_PERCEPTUAL_ROUGHNESS_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_LINEAR_VIEW_Z_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_ALBEDO_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_BASE_REFLECTIVITY_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_VIRTUAL_MVEC_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_VIRTUAL_WORLD_SHADING_NORMAL_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_VIRTUAL_WORLD_SHADING_NORMAL_DENOISING_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_HIT_DISTANCE_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_VIEW_DIRECTION_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_CONE_RADIUS_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_WORLD_POSITION_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SECONDARY_POSITION_ERROR_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_SURFACE_FLAGS_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_DISOCCLUSION_THRESHOLD_MIX_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_DEPTH_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_BIAS_CURRENT_COLOR_MASK_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_ALPHA_BLEND_GBUFFER_OUTPUT)
        SAMPLER2D(GBUFFER_BINDING_SKYMATTE)
        SAMPLERCUBE(GBUFFER_BINDING_SKYPROBE)

        RW_TEXTURE2D(GBUFFER_BINDING_REFLECTION_PSR_DATA_STORAGE_0)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_WORLD_POSITION_OUTPUT)

        RW_TEXTURE2D(GBUFFER_BINDING_REFLECTION_PSR_DATA_STORAGE_1)

        RW_TEXTURE2D(GBUFFER_BINDING_REFLECTION_PSR_DATA_STORAGE_2)
        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_TRANSMISSION_PSR_DATA_STORAGE)

        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_DEPTH_DLSSRR_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_NORMAL_DLSSRR_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_SCREEN_SPACE_MOTION_DLSSRR_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_DLSS_NR_CONTROL_MASK_OUTPUT)

        RW_TEXTURE2D(GBUFFER_BINDING_SHARED_FLAGS_DENSE_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_ALBEDO_DLSSRR_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_SPECULAR_ALBEDO_DLSSRR_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_WORLD_POSITION_COMPACTED_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_PRIMARY_WORLD_SHADING_NORMAL_DENSE_OUTPUT)

        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_NRC_QUERY_PATH_INFO_OUTPUT)
        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_NRC_TRAINING_PATH_INFO_OUTPUT)
        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_NRC_TRAINING_PATH_VERTICES_OUTPUT)
        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_NRC_QUERY_RADIANCE_PARAMS_OUTPUT)
        RW_STRUCTURED_BUFFER(GBUFFER_BINDING_NRC_COUNTERS_OUTPUT)

        RW_TEXTURE2D(GBUFFER_BINDING_NRC_QUERY_PATH_DATA0_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_NRC_QUERY_PATH_DATA1_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_NRC_TRAINING_PATH_DATA1_OUTPUT)

        RW_TEXTURE2D(GBUFFER_BINDING_RADIANCE_CACHE_UPDATE_GBUFFER_SURFACE_RADIANCE_RG_OUTPUT)
        RW_TEXTURE2D(GBUFFER_BINDING_RADIANCE_CACHE_UPDATE_GBUFFER_SURFACE_RADIANCE_B_OUTPUT)

      END_PARAMETER()
    };

    class GbufferClosestHitShader : public ManagedShader {

      BEGIN_PARAMETER()
      END_PARAMETER()
    };

    class GbufferMissShader : public ManagedShader {
      
      BEGIN_PARAMETER()
      END_PARAMETER()
    };

    class GbufferTraversalMissShader : public ManagedShader {
      SHADER_SOURCE(GbufferTraversalMissShader, VK_SHADER_STAGE_MISS_BIT_KHR, gbuffer_traversal_miss)

      BEGIN_PARAMETER()
      END_PARAMETER()
    };

    class GbufferAtmosphereShader : public ManagedShader {
      SHADER_SOURCE(GbufferAtmosphereShader, VK_SHADER_STAGE_CALLABLE_BIT_KHR, gbuffer_atmosphere)

      BEGIN_PARAMETER()
      END_PARAMETER()
    };

    constexpr uint32_t kGbufferVariantWBOIT = 1u << 1;
    constexpr uint32_t kGbufferVariantSER = 1u << 2;
    constexpr uint32_t kGbufferVariantPortals = 1u << 3;
    constexpr uint32_t kGbufferVariantLeanPSRFeatures = 1u << 4;
    constexpr uint32_t kGbufferVariantLeanNoPSRFeatures = 1u << 5;
    constexpr uint32_t kGbufferVariantDebugFeatures = 1u << 6;
    constexpr uint32_t kGbufferVariantNRC = 1u << 7;
    constexpr uint32_t kGbufferVariantSHARC = 1u << 8;
    constexpr uint32_t kGbufferVariantRRPSRFeatures = 1u << 9;
    constexpr uint32_t kGbufferVariantRRNoPSRFeatures = 1u << 10;
    constexpr uint32_t kGbufferVariantPSRStart = 1u << 11;

    constexpr uint32_t kGbufferDecalPrepareVariantDebug = 1u << 0;

    constexpr uint32_t kGbufferRayQueryFeatureRaytracedRenderTarget = 1u << 0;
    constexpr uint32_t kGbufferRayQueryFeatureStochasticAlpha = 1u << 1;
    constexpr uint32_t kGbufferRayQueryFeatureVariantWBOIT = 1u << 9;
    constexpr uint32_t kGbufferRayQueryFeatureVariantNRC = 1u << 10;
    constexpr uint32_t kGbufferRayQueryFeatureVariantSHARC = 1u << 11;
    constexpr uint32_t kGbufferRayQueryContinuation = 1u << 12;
    constexpr uint32_t kGbufferRayQueryFeatureVariantCount =
      RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_DEBUG + 1u;

    static_assert(RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_NO_PSR == 0u);
    static_assert(RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_PSR_INLINE == 4u);
    static_assert(RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_PSR_PREPARE == 8u);
    static_assert(RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_DEBUG == 10u);

    constexpr uint32_t getGbufferFeatureVariantKey(
      const uint32_t features) {
      switch (features) {
      case RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_DEBUG:
        return kGbufferVariantDebugFeatures;
      case RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_LEAN_PSR:
        return kGbufferVariantLeanPSRFeatures;
      case RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_LEAN_NO_PSR:
        return kGbufferVariantLeanNoPSRFeatures;
      case RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_RR_PSR:
        return kGbufferVariantRRPSRFeatures;
      case RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_RR_NO_PSR:
        return kGbufferVariantRRNoPSRFeatures;
      default:
        return 0u;
      }
    }

    constexpr uint32_t getGbufferMatrixVariantKey(
      const uint32_t features,
      const uint32_t radianceCache,
      const uint32_t wboit) {
      return
        getGbufferFeatureVariantKey(features) |
        (radianceCache == RTX_SHADER_VARIANT_MATRIX_GBUFFER_RADIANCE_CACHE_NRC ? kGbufferVariantNRC : 0u) |
        (radianceCache == RTX_SHADER_VARIANT_MATRIX_GBUFFER_RADIANCE_CACHE_SHARC ? kGbufferVariantSHARC : 0u) |
        (wboit == RTX_SHADER_VARIANT_MATRIX_GBUFFER_WBOIT_WBOIT ? kGbufferVariantWBOIT : 0u);
    }

    constexpr uint32_t getGbufferVariantKey(
      const uint32_t features,
      const bool nrcEnabled,
      const bool sharcEnabled,
      const bool wboitEnabled) {
      assert(!(nrcEnabled && sharcEnabled));
      return
        getGbufferFeatureVariantKey(features) |
        (nrcEnabled ? kGbufferVariantNRC : 0u) |
        (sharcEnabled ? kGbufferVariantSHARC : 0u) |
        (wboitEnabled ? kGbufferVariantWBOIT : 0u);
    }

    constexpr uint32_t getGbufferMaterialVariantKey(
      const bool nrcEnabled,
      const bool sharcEnabled,
      const bool wboitEnabled) {
      return getGbufferVariantKey(
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_NONE,
        nrcEnabled,
        sharcEnabled,
        wboitEnabled);
    }

    constexpr uint32_t selectGbufferFeatureVariant(
      const bool debugViewEnabled,
      const bool objectPickingEnabled,
      const bool psrEnabled,
      const bool rayReconstructionEnabled,
      const bool useDefaultFeatureVariant) {
      if (debugViewEnabled || objectPickingEnabled) {
        return RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_DEBUG;
      }

      if (useDefaultFeatureVariant) {
        return RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_NONE;
      }

      if (rayReconstructionEnabled) {
        return psrEnabled
          ? RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_RR_PSR
          : RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_RR_NO_PSR;
      }

      return psrEnabled
        ? RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_LEAN_PSR
        : RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_LEAN_NO_PSR;
    }

    constexpr uint32_t getGbufferRayQueryFeatureMask(
      const bool raytracedRenderTargetEnabled,
      const bool stochasticAlphaEnabled) {
      return
        (raytracedRenderTargetEnabled ? kGbufferRayQueryFeatureRaytracedRenderTarget : 0u) |
        (stochasticAlphaEnabled ? kGbufferRayQueryFeatureStochasticAlpha : 0u);
    }

    constexpr uint32_t getGbufferRayQueryPSRPrepareFeatureMask(
      const uint32_t featureMask) {
      return (featureMask & kGbufferRayQueryFeatureStochasticAlpha) >> 1u;
    }

    constexpr uint32_t selectGbufferRayQueryFeatureVariant(
      const bool debugViewEnabled,
      const bool objectPickingEnabled,
      const bool psrEnabled,
      const bool usePSRPrepare,
      const bool raytracedRenderTargetEnabled,
      const bool stochasticAlphaEnabled) {
      if (debugViewEnabled || objectPickingEnabled) {
        return RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_DEBUG;
      }

      const uint32_t featureMask = getGbufferRayQueryFeatureMask(
        raytracedRenderTargetEnabled,
        stochasticAlphaEnabled);

      if (!psrEnabled) {
        return RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_NO_PSR + featureMask;
      }

      if (usePSRPrepare) {
        return RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_PSR_PREPARE +
          getGbufferRayQueryPSRPrepareFeatureMask(featureMask);
      }

      return RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_PSR_INLINE + featureMask;
    }

    constexpr uint32_t getGbufferTraceRayGenVariantKey(
      const uint32_t features,
      const uint32_t pipeline,
      const uint32_t radianceCache,
      const uint32_t wboit) {
      return getGbufferMatrixVariantKey(features, radianceCache, wboit) |
        (pipeline == RTX_SHADER_VARIANT_MATRIX_GBUFFER_PIPELINE_RAYGEN_SER ? kGbufferVariantSER : 0u);
    }

    constexpr uint32_t getGbufferRayQueryMatrixVariantKey(
      const uint32_t features,
      const uint32_t radianceCache,
      const uint32_t wboit) {
      return
        features |
        (radianceCache == RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_RADIANCE_CACHE_NRC ? kGbufferRayQueryFeatureVariantNRC : 0u) |
        (radianceCache == RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_RADIANCE_CACHE_SHARC ? kGbufferRayQueryFeatureVariantSHARC : 0u) |
        (wboit == RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_WBOIT_WBOIT ? kGbufferRayQueryFeatureVariantWBOIT : 0u);
    }

    constexpr uint32_t getGbufferRayQueryVariantKey(
      const uint32_t features,
      const bool nrcEnabled,
      const bool sharcEnabled,
      const bool wboitEnabled) {
      assert(!(nrcEnabled && sharcEnabled));
      return
        features |
        (nrcEnabled ? kGbufferRayQueryFeatureVariantNRC : 0u) |
        (sharcEnabled ? kGbufferRayQueryFeatureVariantSHARC : 0u) |
        (wboitEnabled ? kGbufferRayQueryFeatureVariantWBOIT : 0u);
    }

    constexpr uint32_t getGbufferDecalPrepareMatrixVariantKey(
      const uint32_t debug) {
      return
        (debug == RTX_SHADER_VARIANT_MATRIX_GBUFFER_DECAL_PREPARE_DEBUG_DEBUG ? kGbufferDecalPrepareVariantDebug : 0u);
    }

    constexpr uint32_t getGbufferDecalPrepareVariantKey(
      const bool debugViewEnabled,
      const bool objectPickingEnabled) {
      return
        (debugViewEnabled || objectPickingEnabled ? kGbufferDecalPrepareVariantDebug : 0u);
    }

    Rc<DxvkShader> getGbufferRayQueryRayGenShader(const uint32_t key) {
      switch (key) {
#define GBUFFER_RAYQUERY_RAYGEN_CASE(features, pipeline, radianceCache, wboit, code) \
        case getGbufferMatrixVariantKey(features, radianceCache, wboit): \
          return GET_SHADER_VARIANT(VK_SHADER_STAGE_RAYGEN_BIT_KHR, GbufferRayGenShader, code);
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_PIPELINE_RAYQUERY_RAYGEN_RGEN(GBUFFER_RAYQUERY_RAYGEN_CASE)
#undef GBUFFER_RAYQUERY_RAYGEN_CASE
        default:
          assert(false && "Invalid GBuffer RayQuery raygen shader variant");
          return nullptr;
      }
    }

    Rc<DxvkShader> getGbufferTraceRayGenShader(const uint32_t key) {
      switch (key) {
#define GBUFFER_TRACE_RAYGEN_CASE(features, pipeline, radianceCache, wboit, code) \
        case getGbufferTraceRayGenVariantKey(features, pipeline, radianceCache, wboit): \
          return GET_SHADER_VARIANT(VK_SHADER_STAGE_RAYGEN_BIT_KHR, GbufferRayGenShader, code);
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_PIPELINE_RAYGEN_RGEN(GBUFFER_TRACE_RAYGEN_CASE)
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_PIPELINE_RAYGEN_SER_RGEN(GBUFFER_TRACE_RAYGEN_CASE)
#undef GBUFFER_TRACE_RAYGEN_CASE
        default:
          assert(false && "Invalid GBuffer TraceRay raygen shader variant");
          return nullptr;
      }
    }

    Rc<DxvkShader> getGbufferRayQueryComputeShader(const uint32_t key, bool continuation = false) {
      switch (key | (continuation ? kGbufferRayQueryContinuation : 0u)) {
#define GBUFFER_RAYQUERY_COMPUTE_CASE(features, phase, radianceCache, wboit, code) \
        case getGbufferRayQueryMatrixVariantKey(features, radianceCache, wboit) \
          | (phase == RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_PHASE_CONTINUE ? kGbufferRayQueryContinuation : 0u): \
          return GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, code);
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_RADIANCE_CACHE_NONE_COMP(GBUFFER_RAYQUERY_COMPUTE_CASE)
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_RADIANCE_CACHE_NRC_COMP(GBUFFER_RAYQUERY_COMPUTE_CASE)
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_RADIANCE_CACHE_SHARC_COMP(GBUFFER_RAYQUERY_COMPUTE_CASE)
#undef GBUFFER_RAYQUERY_COMPUTE_CASE
        default:
          assert(false && "Invalid GBuffer RayQuery compute shader variant");
          return nullptr;
      }
    }

    Rc<DxvkShader> getGbufferPrimaryTraceComputeShader() {
      return GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, gbuffer_primary_trace);
    }

    Rc<DxvkShader> getGbufferAtmosphereColumnsComputeShader() {
      return GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, gbuffer_atmosphere_columns);
    }

    Rc<DxvkShader> getGbufferPrimaryAtmosphereComputeShader() {
      return GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, gbuffer_primary_atmosphere);
    }

    Rc<DxvkShader> getGbufferPSRQueueShadeShader(const uint32_t features,
      const bool nrcEnabled, const bool sharcEnabled, const bool wboitEnabled, const bool serEnabled, const bool firstBounce) {
      const uint32_t key = getGbufferVariantKey(features, nrcEnabled, sharcEnabled, wboitEnabled)
        | (serEnabled ? kGbufferVariantSER : 0u)
        | (firstBounce ? kGbufferVariantPSRStart : 0u);
      switch (key) {
#define GBUFFER_PSR_QUEUE_SHADE_CASE(feature, phase, pipeline, cache, wboit, code) \
        case getGbufferVariantKey(feature, \
          cache == RTX_SHADER_VARIANT_MATRIX_GBUFFER_PSR_QUEUE_SHADE_RADIANCE_CACHE_NRC, \
          cache == RTX_SHADER_VARIANT_MATRIX_GBUFFER_PSR_QUEUE_SHADE_RADIANCE_CACHE_SHARC, \
          wboit == RTX_SHADER_VARIANT_MATRIX_GBUFFER_PSR_QUEUE_SHADE_WBOIT_WBOIT) \
          | (pipeline == RTX_SHADER_VARIANT_MATRIX_GBUFFER_PSR_QUEUE_SHADE_PIPELINE_RAYGEN_SER ? kGbufferVariantSER : 0u) \
          | (phase == RTX_SHADER_VARIANT_MATRIX_GBUFFER_PSR_QUEUE_SHADE_PHASE_START ? kGbufferVariantPSRStart : 0u): \
          return GET_SHADER_VARIANT(VK_SHADER_STAGE_RAYGEN_BIT_KHR, GbufferRayGenShader, code);
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_PSR_QUEUE_SHADE_PIPELINE_RAYGEN_RGEN(GBUFFER_PSR_QUEUE_SHADE_CASE)
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_PSR_QUEUE_SHADE_PIPELINE_RAYGEN_SER_RGEN(GBUFFER_PSR_QUEUE_SHADE_CASE)
#undef GBUFFER_PSR_QUEUE_SHADE_CASE
        default:
          assert(false && "Invalid PSR queue shader variant");
          return nullptr;
      }
    }

    DxvkRaytracingPipelineShaders getGbufferPSRQueuePipeline(
      const uint32_t features, const bool nrcEnabled, const bool sharcEnabled,
      const bool wboitEnabled, const bool serEnabled, const bool firstBounce) {
      DxvkRaytracingPipelineShaders pipeline;
      pipeline.addGeneralShader(getGbufferPSRQueueShadeShader(
        features, nrcEnabled, sharcEnabled, wboitEnabled, serEnabled, firstBounce));
      pipeline.addGeneralShader(GbufferAtmosphereShader::getShader());
      if (serEnabled) {
        pipeline.addGeneralShader(GbufferTraversalMissShader::getShader());
        pipeline.addHitGroup(nullptr, VoxelCoverageAnyHitShader::getShader(), nullptr);
      }
      pipeline.debugName = firstBounce ? "PSR first bounce" : "PSR queued shading";
      return pipeline;
    }

    Rc<DxvkShader> getGbufferPSRPrepareComputeShader() {
      return GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, gbuffer_psr_prepare);
    }

    Rc<DxvkShader> getGbufferDecalPrepareComputeShader(const uint32_t key) {
      switch (key) {
#define GBUFFER_DECAL_PREPARE_CASE(debug, code) \
        case getGbufferDecalPrepareMatrixVariantKey(debug): \
          return GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, code);
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_DECAL_PREPARE_DEBUG_NONE_COMP(GBUFFER_DECAL_PREPARE_CASE)
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_DECAL_PREPARE_DEBUG_DEBUG_COMP(GBUFFER_DECAL_PREPARE_CASE)
#undef GBUFFER_DECAL_PREPARE_CASE
        default:
          assert(false && "Invalid GBuffer decal prepare shader variant");
          return nullptr;
      }
    }

    void prewarmGbufferDecalPrepareComputeShaders() {
      for (uint32_t key = 0; key < (1u << 1); key++) {
        getGbufferDecalPrepareComputeShader(key);
      }
    }

    void prewarmGbufferRayQueryComputeShaders(
      const bool allVariants,
      const bool nrcEnabled,
      const bool sharcEnabled,
      const bool wboitEnabled) {
      if (allVariants) {
        for (uint32_t featureVariant = 0; featureVariant < kGbufferRayQueryFeatureVariantCount; featureVariant++) {
          const int32_t radianceCacheVariantCount = nrcEnabled ? 3 : 2;
          for (int32_t radianceCacheVariant = 0; radianceCacheVariant < radianceCacheVariantCount; radianceCacheVariant++) {
            const bool useNRC = radianceCacheVariant == 2;
            const bool useSHARC = radianceCacheVariant == 1;
            if (useSHARC && !sharcEnabled) {
              continue;
            }
            for (int32_t useWBOIT = 1; useWBOIT >= 0; useWBOIT--) {
              getGbufferRayQueryComputeShader(getGbufferRayQueryVariantKey(featureVariant, useNRC, useSHARC, useWBOIT));
              getGbufferRayQueryComputeShader(getGbufferRayQueryVariantKey(featureVariant, useNRC, useSHARC, useWBOIT), true);
            }
          }
        }

        return;
      }

      const uint32_t featureVariants[] = {
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_NO_PSR,
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_NO_PSR + 3u,
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_PSR_INLINE,
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_PSR_INLINE + 3u,
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_PSR_PREPARE,
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_PSR_PREPARE + 1u,
        RTX_SHADER_VARIANT_MATRIX_GBUFFER_RAYQUERY_FEATURES_DEBUG,
      };

      for (const uint32_t featureVariant : featureVariants) {
        getGbufferRayQueryComputeShader(getGbufferRayQueryVariantKey(featureVariant, nrcEnabled, sharcEnabled, wboitEnabled));
        getGbufferRayQueryComputeShader(getGbufferRayQueryVariantKey(featureVariant, nrcEnabled, sharcEnabled, wboitEnabled), true);
      }
    }

    Rc<DxvkShader> getGbufferMissShader(const uint32_t key) {
      switch (key) {
      case 0:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_MISS_BIT_KHR, GbufferMissShader, gbuffer_miss);
      case kGbufferVariantNRC:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_MISS_BIT_KHR, GbufferMissShader, gbuffer_nrc_miss);
      case kGbufferVariantSHARC:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_MISS_BIT_KHR, GbufferMissShader, gbuffer_sharc_miss);
      case kGbufferVariantWBOIT:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_MISS_BIT_KHR, GbufferMissShader, gbuffer_miss_wboit);
      case kGbufferVariantNRC | kGbufferVariantWBOIT:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_MISS_BIT_KHR, GbufferMissShader, gbuffer_nrc_miss_wboit);
      case kGbufferVariantSHARC | kGbufferVariantWBOIT:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_MISS_BIT_KHR, GbufferMissShader, gbuffer_sharc_miss_wboit);
      default:
        assert(false && "Invalid GBuffer miss shader variant");
        return nullptr;
      }
    }

    Rc<DxvkShader> getGbufferClosestHitShader(const uint32_t key) {
      switch (key) {
      case 0:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_material_opaque_translucent_closestHit);
      case kGbufferVariantPortals:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_material_rayportal_closestHit);
      case kGbufferVariantNRC:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_nrc_material_opaque_translucent_closestHit);
      case kGbufferVariantNRC | kGbufferVariantPortals:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_nrc_material_rayportal_closestHit);
      case kGbufferVariantSHARC:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_sharc_material_opaque_translucent_closestHit);
      case kGbufferVariantSHARC | kGbufferVariantPortals:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_sharc_material_rayportal_closestHit);
      case kGbufferVariantWBOIT:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_material_opaque_translucent_closestHit_wboit);
      case kGbufferVariantWBOIT | kGbufferVariantPortals:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_material_rayportal_closestHit_wboit);
      case kGbufferVariantNRC | kGbufferVariantWBOIT:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_nrc_material_opaque_translucent_closestHit_wboit);
      case kGbufferVariantNRC | kGbufferVariantWBOIT | kGbufferVariantPortals:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_nrc_material_rayportal_closestHit_wboit);
      case kGbufferVariantSHARC | kGbufferVariantWBOIT:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_sharc_material_opaque_translucent_closestHit_wboit);
      case kGbufferVariantSHARC | kGbufferVariantWBOIT | kGbufferVariantPortals:
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR, GbufferClosestHitShader, gbuffer_sharc_material_rayportal_closestHit_wboit);
      default:
        assert(false && "Invalid GBuffer closest hit shader variant");
        return nullptr;
      }
    }
  }

  DxvkPathtracerGbuffer::DxvkPathtracerGbuffer(DxvkDevice* device) : CommonDeviceObject(device) {
  }

  void DxvkPathtracerGbuffer::prewarmShaders(DxvkPipelineManager& pipelineManager) const {
    ScopedCpuProfileZoneN("Gbuffer Shader Prewarming");

    const bool isNrcSupported = NeuralRadianceCache::checkIsSupported(device());
    const bool isSharcSupported = SpatiallyHashedRadianceCache::checkIsSupported(device());
    const bool portalsEnabled = !RtxOptions::rayPortalModelTextureHashes().empty();
    const bool serEnabled = RtxOptions::isShaderExecutionReorderingInPathtracerGbufferEnabled();
    const bool ommEnabled = RtxOptions::getEnableOpacityMicromap();
    const uint32_t featureVariants[] = {
      RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_NONE,
      RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_DEBUG,
      RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_LEAN_PSR,
      RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_LEAN_NO_PSR,
      RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_RR_PSR,
      RTX_SHADER_VARIANT_MATRIX_GBUFFER_FEATURES_RR_NO_PSR,
    };

    const auto prewarmPSR = [&](uint32_t features, bool useNRC, bool useSHARC, bool useWBOIT, bool useSER) {
      for (const bool firstBounce : { true, false }) {
        pipelineManager.registerRaytracingShaders(getGbufferPSRQueuePipeline(
          features, useNRC, useSHARC, useWBOIT, useSER, firstBounce));
      }
    };

    if (RtxOptions::Shader::prewarmAllVariants()) {
      const bool isOpacityMicromapSupported = OpacityMicromapManager::checkIsOpacityMicromapSupported(*m_device);
      const bool isShaderExecutionReorderingSupported =
        RtxContext::checkIsShaderExecutionReorderingSupported(*m_device) && serEnabled;
      const int32_t cacheVariants = isNrcSupported ? 3 : 2;
      for (const uint32_t features : featureVariants) {
        for (int32_t cache = 0; cache < cacheVariants; ++cache) {
          const bool useNRC = cache == 2;
          const bool useSHARC = cache == 1;
          if (useSHARC && !isSharcSupported) {
            continue;
          }
          for (const bool useWBOIT : { true, false }) {
            for (int32_t useSER = isShaderExecutionReorderingSupported; useSER >= 0; --useSER) {
              prewarmPSR(features, useNRC, useSHARC, useWBOIT, useSER);
              for (int32_t includePortals = portalsEnabled; includePortals >= 0; --includePortals) {
                for (const bool useRayQuery : { true, false }) {
                  for (int32_t useOMM = isOpacityMicromapSupported; useOMM >= 0; --useOMM) {
                    pipelineManager.registerRaytracingShaders(getPrimaryPipelineShaders(
                      features, useRayQuery, useSER, useOMM, includePortals, useNRC, useSHARC, useWBOIT));
                  }
                }
              }
            }
          }
        }
      }
      prewarmGbufferRayQueryComputeShaders(true, isNrcSupported, isSharcSupported, false);
    } else {
      const bool nrcEnabled = RtxOptions::integrateIndirectMode() == IntegrateIndirectMode::NeuralRadianceCache;
      const bool sharcEnabled = isSharcSupported && RtxOptions::integrateIndirectMode() == IntegrateIndirectMode::SHaRC;
      const bool wboitEnabled = RtxOptions::wboitEnabled();
      const bool useDefaultFeatures = RtxOptions::RaytracedRenderTarget::enable() ||
        CompositePass::enableStochasticAlphaBlend();
      prewarmPSR(selectGbufferFeatureVariant(
        DebugView::debugViewIdx() != 0, false,
        RtxOptions::enablePSRR() || RtxOptions::enablePSTR(),
        RtxOptions::isRayReconstructionEnabled(), useDefaultFeatures),
        nrcEnabled, sharcEnabled, wboitEnabled, serEnabled);

      const RaytraceMode raytraceMode = RtxOptions::renderPassGBufferRaytraceMode();
      switch (raytraceMode) {
      case RaytraceMode::RayQuery:
        prewarmGbufferRayQueryComputeShaders(false, nrcEnabled, sharcEnabled, wboitEnabled);
        break;
      case RaytraceMode::RayQueryRayGen:
      case RaytraceMode::TraceRay:
        for (const uint32_t features : featureVariants) {
          for (int32_t includePortals = portalsEnabled; includePortals >= 0; --includePortals) {
            pipelineManager.registerRaytracingShaders(getPrimaryPipelineShaders(
              features, raytraceMode == RaytraceMode::RayQueryRayGen, serEnabled, ommEnabled,
              includePortals, nrcEnabled, sharcEnabled, wboitEnabled));
          }
        }
        break;
      case RaytraceMode::Count:
        assert(false && "Invalid RaytraceMode in DxvkPathtracerGbuffer::prewarmShaders");
        break;
      }
    }

    getGbufferPrimaryTraceComputeShader();
    getGbufferAtmosphereColumnsComputeShader();
    getGbufferPrimaryAtmosphereComputeShader();
    GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, gbuffer_primary_queue_args);
    getGbufferPSRPrepareComputeShader();
    GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, gbuffer_psr_queue_args);
    prewarmGbufferDecalPrepareComputeShaders();
  }

  void DxvkPathtracerGbuffer::dispatchPrimaryTraversal(
    RtxContext* ctx, const Resources::RaytracingOutput& rtOutput) {
    const RaytraceMode raytraceMode = RtxOptions::renderPassGBufferRaytraceMode();
    if (raytraceMode >= RaytraceMode::Count) {
      assert(false && "Invalid RaytraceMode in DxvkPathtracerGbuffer::dispatchPrimaryTraversal");
      return;
    }
    if (raytraceMode == RaytraceMode::TraceRay) {
      return;
    }
    ctx->setFramePassStage(RtxFramePassStage::GBufferPrimaryRays);
    ctx->bindCommonRayTracingResources();
    const VkExtent3D& rayDims = rtOutput.m_compositeOutputExtent;
    const VkExtent3D workgroups = util::computeBlockCount(rayDims, VkExtent3D { 16, 8, 1 });
    const auto& volume = rtOutput.m_raytraceArgs.volumeArgs;
    ensurePathBuffers(rayDims, volume.enable || volume.clouds.enable,
      volume.fogIntegrationMode == 1 ? volume.fogIntegrationPixelStride : GBUFFER_ATMOSPHERE_COLUMN_STRIDE,
      volume.fogIntegrationMode == 1 ? GBUFFER_ATMOSPHERE_PREINTEGRATED_CAPACITY : GBUFFER_ATMOSPHERE_CHECKPOINT_CAPACITY);
    ctx->bindResourceBuffer(GBUFFER_BINDING_PATH_STORAGE,
      DxvkBufferSlice(m_pathStorage, 0, m_pathStorage->info().size));
    ScopedGpuProfileZone(ctx, "Primary Traversal");
    ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, getGbufferPrimaryTraceComputeShader());
    ctx->dispatch(workgroups.width, workgroups.height, workgroups.depth);
  }

  void DxvkPathtracerGbuffer::ensurePathBuffers(const VkExtent3D& rayDims, bool atmosphereCache,
    uint32_t atmosphereStride, uint32_t checkpointCapacity) {
    static_assert(GBUFFER_PRIMARY_INTERSECTION_WORDS == 5);
    static_assert(GBUFFER_PRIMARY_ATMOSPHERE_WORDS == 6);
    static_assert(GBUFFER_PRIMARY_TAIL_WORDS == 7);
    static_assert(GBUFFER_PSR_STATE_WORDS == 18);
    static_assert(GBUFFER_PSR_QUEUE_ROW_WIDTH % 128 == 0);
    const VkDeviceSize pixels = VkDeviceSize(rayDims.width) * rayDims.height;
    const auto& limits = device()->properties().core.properties.limits;
    if (!device()->features().khrDeviceRayTracingPipelineFeatures.rayTracingPipelineTraceRaysIndirect) {
      throw DxvkError("PSR work queues require indirect ray dispatch support");
    }
    const VkDeviceSize queueRows = util::ceilDivide(pixels, VkDeviceSize(GBUFFER_PSR_QUEUE_ROW_WIDTH));
    const VkDeviceSize queueWidth = std::min(pixels, VkDeviceSize(GBUFFER_PSR_QUEUE_ROW_WIDTH));
    if (queueRows > limits.maxComputeWorkGroupCount[1]
        || queueWidth > VkDeviceSize(limits.maxComputeWorkGroupCount[0]) * limits.maxComputeWorkGroupSize[0]
        || queueWidth * queueRows > device()->properties().khrDeviceRayTracingPipelineProperties.maxRayDispatchInvocationCount) {
      throw DxvkError("PSR queue dimensions exceed the device dispatch limit");
    }
    if (util::ceilDivide(queueWidth, VkDeviceSize(128)) > limits.maxComputeWorkGroupCount[0]) {
      throw DxvkError("Primary queue dimensions exceed the device dispatch limit");
    }
    auto ensureBuffer = [&](Rc<DxvkBuffer>& buffer, VkDeviceSize bytes, const char* name, bool indirect) {
      if (bytes > limits.maxStorageBufferRange) {
        throw DxvkError(str::format(name, " exceeds the device storage-buffer range: ", bytes));
      }
      if (buffer != nullptr && buffer->info().size == bytes) {
        return;
      }
      DxvkBufferCreateInfo info = {};
      info.size = bytes;
      info.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
      info.stages = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_RAY_TRACING_SHADER_BIT_KHR | VK_PIPELINE_STAGE_TRANSFER_BIT;
      info.access = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
      if (indirect) {
        info.usage |= VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        info.stages |= VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT;
        info.access |= VK_ACCESS_INDIRECT_COMMAND_READ_BIT;
      }
      buffer = device()->createBuffer(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        DxvkMemoryStats::Category::RTXBuffer, name);
    };
    const VkDeviceSize columns = (VkDeviceSize(rayDims.width) / atmosphereStride + 1)
      * (VkDeviceSize(rayDims.height) / atmosphereStride + 1);
    const VkDeviceSize prefixWords = atmosphereCache
      ? columns * checkpointCapacity * GBUFFER_ATMOSPHERE_CHECKPOINT_WORDS : 0;
    const VkDeviceSize primaryWords = pixels * (GBUFFER_PRIMARY_INTERSECTION_WORDS + GBUFFER_PRIMARY_ATMOSPHERE_WORDS)
      + std::max(pixels * GBUFFER_PRIMARY_TAIL_WORDS, prefixWords);
    ensureBuffer(m_pathStorage, std::max(pixels * GBUFFER_PSR_STATE_WORDS, primaryWords) * sizeof(uint32_t),
      "GBuffer path storage", false);
    const VkDeviceSize queueBytes = (pixels + GBUFFER_PSR_QUEUE_HEADER_WORDS) * sizeof(uint32_t);
    ensureBuffer(m_psrQueues[0], queueBytes, "PSR queue 0", true);
    ensureBuffer(m_psrQueues[1], queueBytes, "PSR queue 1", true);
  }

  void DxvkPathtracerGbuffer::dispatch(
    RtxContext* ctx, 
    const Resources::RaytracingOutput& rtOutput) {
    ScopedGpuProfileZone(ctx, "Gbuffer Raytracing");
    ctx->setFramePassStage(RtxFramePassStage::GBufferPrimaryRays);

    // With sparse rendering, inactive pixels have no compacted slot,
    // so the PSR payload and the outputs that inactive pixels write in place of a GBuffer are dense.
    const bool compactedGBuffer = rtOutput.m_raytraceArgs.sparseRenderingArgs.mode != SparseRenderingMode::Off;

    // Bind resources

    ctx->bindCommonRayTracingResources();
    // Note: Clamp to edge used to avoid interpolation to black on the edges of the view.
    Rc<DxvkSampler> linearClampSampler = ctx->getResourceManager().getSampler(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
    Rc<DxvkSampler> linearWrapSampler = ctx->getResourceManager().getSampler(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST,
      VK_SAMPLER_ADDRESS_MODE_REPEAT, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);

    ctx->bindResourceSampler(GBUFFER_BINDING_LINEAR_WRAP_SAMPLER, linearWrapSampler);
    const auto& water = ctx->getCommonObjects()->waterSimulation();
    ctx->bindResourceView(GBUFFER_BINDING_WATER_SIMULATION, water.view(), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_WATER_WIND, water.windView(), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_WATER_RINGS, water.ringView(), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_WATER_IMPACTS, water.impactView(), nullptr);
    if (water.effects() & (WATER_EFFECT_WIND | WATER_EFFECT_RINGS)) {
      ctx->bindResourceSampler(GBUFFER_BINDING_WATER_TILE_SAMPLER, ctx->getResourceManager().getSampler(
        VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_REPEAT));
    }

    // These resources give each pixel's slot in the compacted GBuffer. They are null when sparse rendering is inactive,
    // in which case DXVK substitutes a null descriptor.
    ctx->bindResourceView(GBUFFER_BINDING_COMPACTED_PIXEL_INDICES_INPUT, rtOutput.m_sparseRenderingCompactedPixelIndices.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_TILE_ACTIVE_COUNTS_INPUT, rtOutput.m_sparseRenderingTileActiveCounts.view, nullptr);

    const RtxGlobalVolumetrics& globalVolumetrics = ctx->getCommonObjects()->metaGlobalVolumetrics();
    ctx->bindResourceView(GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_Y_INPUT, globalVolumetrics.getFilteredRadianceY().view, nullptr);
    ctx->bindResourceSampler(GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_Y_INPUT, linearClampSampler);
    ctx->bindResourceView(GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_CO_CG_INPUT, globalVolumetrics.getFilteredRadianceCoCg().view, nullptr);
    ctx->bindResourceSampler(GBUFFER_BINDING_VOLUME_FILTERED_RADIANCE_CO_CG_INPUT, linearClampSampler);

    ctx->bindResourceView(GBUFFER_BINDING_SKYMATTE, ctx->getResourceManager().getSkyMatte(ctx).view, nullptr);
    ctx->bindResourceSampler(GBUFFER_BINDING_SKYMATTE, linearClampSampler);

    // Requires the probe too for PSRR/T miss
    ctx->bindResourceView(GBUFFER_BINDING_SKYPROBE, ctx->getResourceManager().getSkyProbe(ctx).view, nullptr);
    ctx->bindResourceSampler(GBUFFER_BINDING_SKYPROBE, linearClampSampler);

    // Output resources

    ctx->bindResourceView(GBUFFER_BINDING_SHARED_FLAGS_OUTPUT, rtOutput.m_sharedFlags.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_RADIANCE_RG_OUTPUT, rtOutput.m_sharedRadianceRG.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_RADIANCE_B_OUTPUT, rtOutput.m_sharedRadianceB.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_INTEGRATION_SURFACE_PDF_OUTPUT, rtOutput.m_sharedIntegrationSurfacePdf.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_MATERIAL_DATA0_OUTPUT, rtOutput.m_sharedMaterialData0.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_MATERIAL_DATA1_OUTPUT, rtOutput.m_sharedMaterialData1.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_MEDIUM_MATERIAL_INDEX_OUTPUT, rtOutput.m_sharedMediumMaterialIndex.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_BIAS_CURRENT_COLOR_MASK_OUTPUT, rtOutput.m_sharedBiasCurrentColorMask.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_TEXTURE_COORD_OUTPUT, rtOutput.m_sharedTextureCoord.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_SURFACE_INDEX_OUTPUT, rtOutput.m_sharedSurfaceIndex.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_SUBSURFACE_DATA_OUTPUT, rtOutput.m_sharedSubsurfaceData.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_SUBSURFACE_DIFFUSION_PROFILE_DATA_OUTPUT, rtOutput.m_sharedSubsurfaceDiffusionProfileData.view, nullptr);

    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_ATTENUATION_OUTPUT, rtOutput.m_primaryAttenuation.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_WORLD_SHADING_NORMAL_OUTPUT, rtOutput.m_primaryWorldShadingNormal.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_PERCEPTUAL_ROUGHNESS_OUTPUT, rtOutput.m_primaryPerceptualRoughness.view, nullptr);
    // Temporarily holds the PSR continuation hit distance with the compacted GBuffer.
    // See geometryResolverStorePSRContinuation.
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_LINEAR_VIEW_Z_OUTPUT, rtOutput.m_primaryLinearViewZ.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_ALBEDO_OUTPUT, rtOutput.m_primaryAlbedo.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_BASE_REFLECTIVITY_OUTPUT, rtOutput.m_primaryBaseReflectivity.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_VIRTUAL_MVEC_OUTPUT, rtOutput.m_primaryVirtualMotionVector.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_SCREEN_SPACE_MOTION_OUTPUT, rtOutput.m_primaryScreenSpaceMotionVector.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_FG_FOREGROUND_GUIDE, rtOutput.m_fgForegroundGuide.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_VIRTUAL_WORLD_SHADING_NORMAL_OUTPUT, rtOutput.m_primaryVirtualWorldShadingNormalPerceptualRoughness.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_VIRTUAL_WORLD_SHADING_NORMAL_DENOISING_OUTPUT,
      rtOutput.m_primaryVirtualWorldShadingNormalPerceptualRoughnessDenoising.empty()
        ? nullptr
        : rtOutput.m_primaryVirtualWorldShadingNormalPerceptualRoughnessDenoising.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_HIT_DISTANCE_OUTPUT, rtOutput.m_primaryHitDistance.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_VIEW_DIRECTION_OUTPUT, rtOutput.m_primaryViewDirection.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_CONE_RADIUS_OUTPUT, rtOutput.m_primaryConeRadius.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_POSITION_ERROR_OUTPUT, rtOutput.m_primaryPositionError.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_SHADOW_TERMINATOR_FIX_OUTPUT, rtOutput.getCurrentSharedTerminatorFix().view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_SURFACE_FLAGS_OUTPUT, rtOutput.m_primarySurfaceFlags.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_DISOCCLUSION_THRESHOLD_MIX_OUTPUT, rtOutput.m_primaryDisocclusionThresholdMix.view, nullptr);
    // Temporarily holds the PSR continuation cone radius with the compacted GBuffer.
    // See geometryResolverStorePSRContinuation.
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_DEPTH_OUTPUT, rtOutput.m_primaryDepth.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_OBJECT_PICKING_OUTPUT, rtOutput.m_primaryObjectPicking.view, nullptr);

    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_WORLD_POSITION_RAW_OUTPUT, rtOutput.m_primaryWorldPositionRaw.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_TEXCOORDS_RAW_OUTPUT,     rtOutput.m_primaryTexcoordsRaw.view,     nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_NORMAL_F32_OUTPUT,        rtOutput.m_primaryNormalF32.view,        nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_COVERAGE_OUTPUT,          rtOutput.m_primaryCoverage.view,         nullptr);

    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_ATTENUATION_OUTPUT, rtOutput.m_secondaryAttenuation.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_WORLD_SHADING_NORMAL_OUTPUT, rtOutput.m_secondaryWorldShadingNormal.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_PERCEPTUAL_ROUGHNESS_OUTPUT, rtOutput.m_secondaryPerceptualRoughness.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_LINEAR_VIEW_Z_OUTPUT, rtOutput.m_secondaryLinearViewZ.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_ALBEDO_OUTPUT, rtOutput.m_secondaryAlbedo.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_BASE_REFLECTIVITY_OUTPUT, rtOutput.m_secondaryBaseReflectivity.view(Resources::AccessType::Write), nullptr);
    // The secondary motion vector is unbound when NRD will not read it,
    // because the compacted GBuffer then puts the dense copy of PSR slot 1 in its memory.
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_VIRTUAL_MVEC_OUTPUT,
      rtOutput.m_raytraceArgs.writeSecondaryDenoisingGuides
        ? rtOutput.m_secondaryVirtualMotionVector.view(Resources::AccessType::Write)
        : nullptr, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_VIRTUAL_WORLD_SHADING_NORMAL_OUTPUT, rtOutput.m_secondaryVirtualWorldShadingNormalPerceptualRoughness.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_VIRTUAL_WORLD_SHADING_NORMAL_DENOISING_OUTPUT, rtOutput.m_secondaryVirtualWorldShadingNormalPerceptualRoughnessDenoising.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_HIT_DISTANCE_OUTPUT, rtOutput.m_secondaryHitDistance.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_VIEW_DIRECTION_OUTPUT, rtOutput.m_secondaryViewDirection.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_CONE_RADIUS_OUTPUT, rtOutput.m_secondaryConeRadius.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_POSITION_ERROR_OUTPUT, rtOutput.m_secondaryPositionError.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_SECONDARY_WORLD_POSITION_OUTPUT, rtOutput.m_secondaryWorldPositionWorldTriangleNormal.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_ALPHA_BLEND_GBUFFER_OUTPUT, rtOutput.m_alphaBlendGBuffer.view, nullptr);

    ctx->bindResourceView(GBUFFER_BINDING_REFLECTION_PSR_DATA_STORAGE_0, rtOutput.m_gbufferPSRData[0].view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_WORLD_POSITION_OUTPUT, rtOutput.getCurrentPrimaryWorldPositionWorldTriangleNormal().view(Resources::AccessType::Write), nullptr);

    ctx->bindResourceView(GBUFFER_BINDING_REFLECTION_PSR_DATA_STORAGE_1,
      compactedGBuffer
        ? rtOutput.m_gbufferPSRData1Dense.view(Resources::AccessType::Write)
        : rtOutput.m_gbufferPSRData[1].view(Resources::AccessType::Write), nullptr);

    // Note: m_gbufferPSRData[1..2] are aliased with radiance textures that are used later as integrator outputs.
    ctx->bindResourceView(GBUFFER_BINDING_REFLECTION_PSR_DATA_STORAGE_2,
      compactedGBuffer
        ? rtOutput.m_gbufferPSRData2Dense.view(Resources::AccessType::Write)
        : rtOutput.m_gbufferPSRData[2].view(Resources::AccessType::Write), nullptr);
    // The RTXDI reservoir buffer's scratch page, which is idle until RTXDI initial sampling.
    ctx->bindResourceBuffer(GBUFFER_BINDING_TRANSMISSION_PSR_DATA_STORAGE, rtOutput.m_transmissionPSRData.slice(Resources::AccessType::Write));

    // Bind necessary buffers for DLSS-RR. 
    // Note: RR uses different PSR rules compared to other uses, and its resolves are resolved in an another shader.
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_DEPTH_DLSSRR_OUTPUT, rtOutput.m_primaryDepthDLSSRR.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_NORMAL_DLSSRR_OUTPUT, rtOutput.m_primaryWorldShadingNormalDLSSRR.view(Resources::AccessType::Write), nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_SCREEN_SPACE_MOTION_DLSSRR_OUTPUT, rtOutput.m_primaryScreenSpaceMotionVectorDLSSRR.view, nullptr);

    // With sparse rendering, the GBuffer also writes the flags densely for the passes that read them by pixel.
    ctx->bindResourceView(GBUFFER_BINDING_SHARED_FLAGS_DENSE_OUTPUT,
      compactedGBuffer
        ? rtOutput.m_sharedFlagsDense.view(Resources::AccessType::Write)
        : nullptr, nullptr);

    // With sparse rendering, the GBuffer writes these dense guides at every pixel instead of the RR prepare pass.
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_ALBEDO_DLSSRR_OUTPUT, rtOutput.m_primaryAlbedoDLSSRR.view, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_SPECULAR_ALBEDO_DLSSRR_OUTPUT, rtOutput.m_primarySpecularAlbedoDLSSRR.view, nullptr);

    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_WORLD_POSITION_COMPACTED_OUTPUT,
      compactedGBuffer
        ? rtOutput.m_primaryWorldPositionWorldTriangleNormalCompacted.view(Resources::AccessType::Write)
        : nullptr, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_PRIMARY_WORLD_SHADING_NORMAL_DENSE_OUTPUT,
      compactedGBuffer
        ? rtOutput.m_primaryWorldShadingNormalDense.view
        : nullptr, nullptr);
    ctx->bindResourceView(GBUFFER_BINDING_DLSS_NR_CONTROL_MASK_OUTPUT, rtOutput.m_controlMask.view, nullptr);

    // Bind necessary resources for Neural Radiance Cache
    NeuralRadianceCache& nrc = ctx->getCommonObjects()->metaNeuralRadianceCache();
    nrc.bindGBufferPathTracingResources(*ctx);

    // Bind necessary resources for Spatially Hashed Radiance Cache
    SpatiallyHashedRadianceCache& sharc = ctx->getCommonObjects()->metaSpatiallyHashedRadianceCache();
    sharc.bindGBufferPathTracingResources(*ctx);
  
    const VkExtent3D& rayDims = rtOutput.m_compositeOutputExtent;

    const bool nrcEnabled = nrc.isActive();
    const bool sharcEnabled = sharc.isActive();
    const bool serEnabled = RtxOptions::isShaderExecutionReorderingInPathtracerGbufferEnabled();
    const bool ommEnabled = RtxOptions::getEnableOpacityMicromap();
    const bool includePortals = RtxOptions::rayPortalModelTextureHashes().size() > 0 || rtOutput.m_raytraceArgs.numActiveRayPortals > 0;
    const bool wboitEnabled = RtxOptions::wboitEnabled();
    const bool psrEnabled = rtOutput.m_raytraceArgs.enablePSRR || rtOutput.m_raytraceArgs.enablePSTR;
    const bool debugViewEnabled = rtOutput.m_raytraceArgs.debugView != 0;
    const bool usePipelineDefaultFeatureVariant =
      rtOutput.m_raytraceArgs.enableRaytracedRenderTarget ||
      rtOutput.m_raytraceArgs.enableStochasticAlphaBlend;
    const uint32_t pipelineFeatureVariant = selectGbufferFeatureVariant(
      debugViewEnabled,
      rtOutput.m_raytraceArgs.enableObjectPicking,
      psrEnabled,
      rtOutput.m_raytraceArgs.enableDLSSRR,
      usePipelineDefaultFeatureVariant);
    const bool deferUnorderedDecals =
      rtOutput.m_raytraceArgs.enableSeparateUnorderedApproximations &&
      rtOutput.m_raytraceArgs.enableDecalMaterialBlending;
    const uint32_t decalPrepareVariantKey = getGbufferDecalPrepareVariantKey(
      debugViewEnabled,
      rtOutput.m_raytraceArgs.enableObjectPicking);
    // Keep radiance cache updates on the original inline PSR sampling path. They depend
    // on knowing whether the current GBuffer hit is the final integrated
    // surface, which PSR prepare defers to a later pass.
    // The compacted GBuffer samples inline too, because PSR prepare reads the material back from the GBuffer,
    // which inactive pixels do not store. Its first hits then take their decals before sampling.
    // See geometryResolverResolvesDecalBeforePSR.
    const bool usePSRPrepare =
      psrEnabled &&
      !nrcEnabled &&
      !sharcEnabled &&
      !rtOutput.m_raytraceArgs.enableRaytracedRenderTarget &&
      rtOutput.m_raytraceArgs.sparseRenderingArgs.mode == SparseRenderingMode::Off;
    const uint32_t rayQueryFeatureVariant = selectGbufferRayQueryFeatureVariant(
      debugViewEnabled,
      rtOutput.m_raytraceArgs.enableObjectPicking,
      psrEnabled,
      usePSRPrepare,
      rtOutput.m_raytraceArgs.enableRaytracedRenderTarget,
      rtOutput.m_raytraceArgs.enableStochasticAlphaBlend);
    const VkExtent3D workgroups = util::computeBlockCount(rayDims, VkExtent3D { 16, 8, 1 });
    const RaytraceMode raytraceMode = RtxOptions::renderPassGBufferRaytraceMode();

    auto logPass = [&](const bool isPSRPass, const size_t shaderHash) {
      const auto& args = rtOutput.m_raytraceArgs;
      DxvkHashState state;
      state.add(shaderHash);
      state.add(rayDims.width);
      state.add(rayDims.height);
      state.add(rayDims.depth);
      state.add(usePSRPrepare);
      state.add(skipSERReorder());
      state.add(uint32_t(args.pomMode));
      state.add(args.pomMaxIterations);
      state.add(args.pathMaxBounces);
      state.add(uint32_t(args.sparseRenderingArgs.mode));
      if (m_loggedDispatchHash[isPSRPass] == size_t(state)) {
        return false;
      }
      m_loggedDispatchHash[isPSRPass] = state;
      Logger::info(str::format("Gbuffer dispatch: pass=", isPSRPass ? "PSR" : "Primary",
        " mode=", isPSRPass ? "Wavefront [RGS]" : raytraceModeToString(raytraceMode),
        " extent=", rayDims.width, "x", rayDims.height, "x", rayDims.depth,
        " groups=", workgroups.width, "x", workgroups.height, "x", workgroups.depth,
        " features=", pipelineFeatureVariant, " queryFeatures=", rayQueryFeatureVariant,
        " SER=", serEnabled, " skipReorder=", skipSERReorder(), " OMM=", ommEnabled,
        " NRC=", nrcEnabled, " SHaRC=", sharcEnabled, " WBOIT=", wboitEnabled,
        " portals=", includePortals, " PSRPrepare=", usePSRPrepare,
        " PSRR=", args.enablePSRR, " PSTR=", args.enablePSTR,
        " POM=", uint32_t(args.pomMode), " POMIterations=", args.pomMaxIterations,
        " pathMaxBounces=", uint32_t(args.pathMaxBounces),
        " sparseMode=", uint32_t(args.sparseRenderingArgs.mode)));
      return true;
    };

    auto bindPrimary = [&]() {
      if (raytraceMode == RaytraceMode::RayQuery) {
        auto shader = getGbufferRayQueryComputeShader(getGbufferRayQueryVariantKey(
          rayQueryFeatureVariant, nrcEnabled, sharcEnabled, wboitEnabled));
        if (logDispatch() && logPass(false, shader->getHash())) {
          Logger::info(str::format("Gbuffer shader: ", shader->debugName(), " ", shader->getShaderKey().toString()));
        }
        ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, shader);
      } else {
        auto shaders = getPrimaryPipelineShaders(
          pipelineFeatureVariant, raytraceMode == RaytraceMode::RayQueryRayGen,
          serEnabled, ommEnabled, includePortals, nrcEnabled, sharcEnabled, wboitEnabled);
        if (logDispatch() && logPass(false, shaders.hash())) {
          Logger::info(str::format("Gbuffer pipeline flags: ", shaders.pipelineFlags));
          for (const auto& group : shaders.groups) {
            for (const auto& shader : { group.generalShader, group.closestHitShader, group.anyHitShader, group.intersectionShader }) {
              if (shader != nullptr) {
                Logger::info(str::format("Gbuffer shader: ", shader->debugName(), " ", shader->getShaderKey().toString()));
              }
            }
          }
        }
        ctx->bindRaytracingPipelineShaders(shaders);
      }
    };

    // Always launched over the screen grid,
    // because every pixel is resolved and the compacted GBuffer only decides where each pixel stores its outputs.
    auto dispatchPrimary = [&]() {
      if (raytraceMode == RaytraceMode::RayQuery) {
        ctx->dispatch(workgroups.width, workgroups.height, workgroups.depth);
      } else {
        ctx->traceRays(rayDims.width, rayDims.height, rayDims.depth);
      }
    };

    GbufferPushConstants pushArgs = {};
    pushArgs.usePSRPrepare = usePSRPrepare;
    pushArgs.skipSERReorder = skipSERReorder();
    pushArgs.writeFgForegroundGuide = rtOutput.m_fgForegroundGuide.isValid();
    ctx->setPushConstantBank(DxvkPushConstantBank::RTX);
    ctx->pushConstants(0, sizeof(pushArgs), &pushArgs);

    // The main GBuffer pass now leaves some uncommon work for small follow-up
    // passes. This keeps the hot Primary Rays shader variants from carrying
    // PSR sampling or unordered decal blending when those features are inactive
    // or rare.
    auto dispatchPSRPrepare = [&]() {
      if (!usePSRPrepare) {
        return;
      }

      // Classify PSR candidate pixels and serialize the PSR payloads used by
      // the Reflection/Transmission PSR passes. This avoids running the
      // material-heavy PSR sampling path for every primary hit.
      ScopedGpuProfileZone(ctx, "PSR Prepare");
      pushArgs.usePSRPrepare = 0;
      ctx->pushConstants(0, sizeof(pushArgs), &pushArgs);
      ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, getGbufferPSRPrepareComputeShader());
      ctx->dispatch(workgroups.width, workgroups.height, workgroups.depth);
    };

    auto dispatchDeferredDecals = [&]() {
      if (!deferUnorderedDecals) {
        return;
      }

      // Apply unordered decal material blending after the main GBuffer pass.
      // Decals are sparse, so keeping their sampling/blending out of Primary
      // Rays reduces register pressure in the common path.
      ScopedGpuProfileZone(ctx, "Unordered Decal Prepare");
      ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, getGbufferDecalPrepareComputeShader(decalPrepareVariantKey));
      ctx->dispatch(workgroups.width, workgroups.height, workgroups.depth);
    };

    if (raytraceMode >= RaytraceMode::Count) {
      assert(false && "Invalid RaytraceMode in DxvkPathtracerGbuffer::dispatch");
      return;
    }


    const auto& volume = rtOutput.m_raytraceArgs.volumeArgs;
    const uint32_t atmosphereStride = volume.fogIntegrationMode == 1
      ? volume.fogIntegrationPixelStride : GBUFFER_ATMOSPHERE_COLUMN_STRIDE;
    ensurePathBuffers(rayDims, raytraceMode != RaytraceMode::TraceRay && (volume.enable || volume.clouds.enable),
      atmosphereStride,
      volume.fogIntegrationMode == 1 ? GBUFFER_ATMOSPHERE_PREINTEGRATED_CAPACITY : GBUFFER_ATMOSPHERE_CHECKPOINT_CAPACITY);
    ctx->bindResourceBuffer(GBUFFER_BINDING_PATH_STORAGE, DxvkBufferSlice(m_pathStorage, 0, m_pathStorage->info().size));
    if (raytraceMode != RaytraceMode::TraceRay && (volume.enable || volume.clouds.enable)) {
      // Primary consumes these six-word segments before PSR initializes the same storage.
      ScopedGpuProfileZone(ctx, "Primary Atmosphere");
      {
        ScopedGpuProfileZone(ctx, "Column Prefix");
        const VkExtent3D columnExtent = { rayDims.width / atmosphereStride + 1,
          rayDims.height / atmosphereStride + 1, 1 };
        const auto groups = util::computeBlockCount(columnExtent, VkExtent3D { 8, 8, 1 });
        ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, getGbufferAtmosphereColumnsComputeShader());
        ctx->dispatch(groups.width, groups.height, groups.depth);
      }
      {
        ScopedGpuProfileZone(ctx, "Dense Endpoint");
        const auto groups = util::computeBlockCount(rayDims, VkExtent3D { 8, 8, 1 });
        ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, getGbufferPrimaryAtmosphereComputeShader());
        ctx->dispatch(groups.width, groups.height, groups.depth);
      }
    }

    if (raytraceMode == RaytraceMode::RayQuery) {
      ScopedGpuProfileZone(ctx, "Primary Queue Reset");
      ctx->clearBuffer(m_psrQueues[0], 0, sizeof(uint32_t), 0);
      ctx->bindResourceBuffer(GBUFFER_BINDING_PSR_QUEUE_OUTPUT,
        DxvkBufferSlice(m_psrQueues[0], 0, m_psrQueues[0]->info().size));
    }

    {
      ScopedGpuProfileZone(ctx, "Primary Rays");
      bindPrimary();
      dispatchPrimary();
    }

    if (raytraceMode == RaytraceMode::RayQuery) {
      ScopedGpuProfileZone(ctx, "Primary Continuations");
      {
        ScopedGpuProfileZone(ctx, "Schedule");
        ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT,
          GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, gbuffer_primary_queue_args));
        ctx->dispatch(1, 1, 1);
      }
      {
        ScopedGpuProfileZone(ctx, "Shade");
        ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, getGbufferRayQueryComputeShader(getGbufferRayQueryVariantKey(
          rayQueryFeatureVariant, nrcEnabled, sharcEnabled, wboitEnabled), true));
        ctx->bindDrawBuffers(DxvkBufferSlice(m_psrQueues[0], sizeof(uint32_t), sizeof(VkDispatchIndirectCommand)),
          DxvkBufferSlice());
        ctx->dispatchIndirect(0);
      }
    }

    dispatchDeferredDecals();
    dispatchPSRPrepare();

    {
      ScopedGpuProfileZone(ctx, "PSR");
      ctx->setFramePassStage(RtxFramePassStage::PSR);
      const VkDeviceSize pixels = VkDeviceSize(rayDims.width) * rayDims.height;
      const VkDeviceSize queueBytes = (pixels + GBUFFER_PSR_QUEUE_HEADER_WORDS) * sizeof(uint32_t);
      ctx->bindResourceBuffer(GBUFFER_BINDING_PATH_STORAGE, DxvkBufferSlice(m_pathStorage, 0, m_pathStorage->info().size));

      const auto argsShader = GET_SHADER_VARIANT(VK_SHADER_STAGE_COMPUTE_BIT, GbufferRayGenShader, gbuffer_psr_queue_args);
      const auto initialPipeline = getGbufferPSRQueuePipeline(
        pipelineFeatureVariant, nrcEnabled, sharcEnabled, wboitEnabled, serEnabled, true);
      const auto shadePipeline = getGbufferPSRQueuePipeline(
        pipelineFeatureVariant, nrcEnabled, sharcEnabled, wboitEnabled, serEnabled, false);
      const auto shadeShader = getGbufferPSRQueueShadeShader(pipelineFeatureVariant, nrcEnabled, sharcEnabled, wboitEnabled, serEnabled, false);
      if (logDispatch() && logPass(true, shadeShader->getHash())) {
        Logger::info(str::format("Gbuffer PSR wavefront: stateBytes=", m_pathStorage->info().size,
          " queueBytes=", 2 * queueBytes, " shader=", shadeShader->debugName(), " ", shadeShader->getShaderKey().toString()));
      }

      // Transmission starts with the radiance and random stream left by the complete reflection path.
      for (uint32_t lobe = 0; lobe < 2; ++lobe) {
        pushArgs.psrTransmission = lobe;
        ctx->pushConstants(0, sizeof(pushArgs), &pushArgs);
        uint32_t input = 0;
        ctx->clearBuffer(m_psrQueues[input], 0, GBUFFER_PSR_QUEUE_HEADER_WORDS * sizeof(uint32_t), 0);
        ctx->bindResourceBuffer(GBUFFER_BINDING_PSR_QUEUE_OUTPUT, DxvkBufferSlice(m_psrQueues[input], 0, queueBytes));
        ctx->bindResourceBuffer(GBUFFER_BINDING_PSR_QUEUE_INPUT, DxvkBufferSlice(m_psrQueues[1 - input], 0, queueBytes));
        {
          ScopedGpuProfileZone(ctx, "First Bounce");
          ctx->bindRaytracingPipelineShaders(initialPipeline);
          ctx->traceRays(rayDims.width, rayDims.height, rayDims.depth);
        }
        // Render-target redirection uses the reflection limit in either lobe.
        const uint32_t maxBounces = std::max(rtOutput.m_raytraceArgs.psrrMaxBounces, rtOutput.m_raytraceArgs.pstrMaxBounces);
        for (uint32_t bounce = 1; bounce <= maxBounces; ++bounce) {
          ctx->bindResourceBuffer(GBUFFER_BINDING_PSR_QUEUE_INPUT, DxvkBufferSlice(m_psrQueues[input], 0, queueBytes));
          ctx->bindResourceBuffer(GBUFFER_BINDING_PSR_QUEUE_OUTPUT, DxvkBufferSlice(m_psrQueues[1 - input], 0, queueBytes));
          {
            ScopedGpuProfileZone(ctx, "Schedule");
            ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, argsShader);
            ctx->dispatch(1, 1, 1);
          }
          {
            ScopedGpuProfileZone(ctx, "Shade");
            ctx->bindRaytracingPipelineShaders(shadePipeline);
            ctx->traceRaysIndirect(m_psrQueues[input], sizeof(uint32_t));
          }
          input = 1 - input;
        }
      }
    }
  }

  DxvkRaytracingPipelineShaders DxvkPathtracerGbuffer::getPrimaryPipelineShaders(
    const uint32_t featureVariant,
    const bool useRayQuery,
    const bool serEnabled,
    const bool ommEnabled,
    const bool includePortals,
    const bool nrcEnabled,
    const bool sharcEnabled,
    const bool wboitEnabled) {
    ScopedCpuProfileZone();

    const uint32_t rayGenBaseKey = getGbufferVariantKey(featureVariant, nrcEnabled, sharcEnabled, wboitEnabled);
    const uint32_t passKey = getGbufferMaterialVariantKey(nrcEnabled, sharcEnabled, wboitEnabled);
    DxvkRaytracingPipelineShaders shaders;

    if (useRayQuery) {
      shaders.addGeneralShader(getGbufferRayQueryRayGenShader(rayGenBaseKey));
      shaders.debugName = "GBuffer RayQuery (RGS)";
    } else {
      const uint32_t rayGenKey = rayGenBaseKey | (serEnabled ? kGbufferVariantSER : 0u);
      const uint32_t closestHitKey = passKey | (includePortals ? kGbufferVariantPortals : 0u);

      shaders.addGeneralShader(getGbufferTraceRayGenShader(rayGenKey));
      if (serEnabled) {
        shaders.addGeneralShader(GbufferTraversalMissShader::getShader());
        shaders.addHitGroup(nullptr, VoxelCoverageAnyHitShader::getShader(), nullptr);
      } else {
        shaders.addGeneralShader(getGbufferMissShader(passKey));
        shaders.addHitGroup(getGbufferClosestHitShader(closestHitKey), VoxelCoverageAnyHitShader::getShader(), nullptr);
      }
      shaders.debugName = "GBuffer TraceRay (RGS)";
    }

    if (ommEnabled) {
      shaders.pipelineFlags |= VK_PIPELINE_CREATE_RAY_TRACING_OPACITY_MICROMAP_BIT_EXT;
    }

    return shaders;
  }

  const char* DxvkPathtracerGbuffer::raytraceModeToString(RaytraceMode raytraceMode)
  {
    switch (raytraceMode)
    {
    case RaytraceMode::RayQuery:
      return "Ray Query [CS]";
    case RaytraceMode::RayQueryRayGen:
      return "Ray Query [RGS]";
    case RaytraceMode::TraceRay:
      return "Trace Ray [RGS]";
    default:
      return "Unknown";
    }
  }
}
