/*
* Copyright (c) 2023-2024, NVIDIA CORPORATION. All rights reserved.
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

#include "rtx_frame_resources.h"
#include "rtx/pass/raytrace_args.h"

namespace dxvk {

  using IndirectIntegrationResources = FrameResourceView<
    &RaytracingFrame::m_sharedFlags,
    &RaytracingFrame::m_sharedMediumState,
    &RaytracingFrame::m_sharedTextureCoord,
    &RaytracingFrame::m_sharedSurfaceIndex,
    &RaytracingFrame::m_sharedSubsurfaceData,
    &RaytracingFrame::m_sharedIntegrationSurfacePdf,
    &RaytracingFrame::m_primaryConeRadius,
    &RaytracingFrame::m_secondaryConeRadius,
    &RaytracingFrame::getCompactedPrimaryWorldPositionWorldTriangleNormal,
    &RaytracingFrame::m_rtxdiReservoirBuffer,
    &RaytracingFrame::getCurrentPrimaryWorldPositionWorldTriangleNormal,
    &RaytracingFrame::m_indirectRayOriginDirection,
    &RaytracingFrame::m_sparseRenderingActiveLocalPixelCoords,
    &RaytracingFrame::m_sparseRenderingCompactedPixelIndices,
    &RaytracingFrame::m_sparseRenderingTileActiveCounts,
    &RaytracingFrame::m_indirectFirstHitPerceptualRoughness,
    &RaytracingFrame::m_gbufferLast,
    &RaytracingFrame::getPreviousPrimaryWorldPositionWorldTriangleNormal,
    &RaytracingFrame::m_primaryHitDistance,
    &RaytracingFrame::m_secondaryHitDistance,
    &RaytracingFrame::m_raytraceArgs,
    &RaytracingFrame::m_indirectFirstSampledLobeData,
    &RaytracingFrame::m_rtxdiGradients,
    &RaytracingFrame::m_primaryDirectDiffuseRadiance,
    &RaytracingFrame::m_primaryDirectSpecularRadiance,
    &RaytracingFrame::m_secondaryCombinedDiffuseRadiance,
    &RaytracingFrame::m_secondaryCombinedSpecularRadiance,
    &RaytracingFrame::m_neeCache,
    &RaytracingFrame::m_neeCacheSample,
    &RaytracingFrame::m_neeCacheTask,
    &RaytracingFrame::m_neeCacheThreadTask,
    &RaytracingFrame::m_indirectThroughputConeRadius,
    &RaytracingFrame::m_indirectRadianceHitDistance,
    &RaytracingFrame::m_compositeOutputExtent>;

  using NeeIntegrationResources = FrameResourceView<
    &RaytracingFrame::m_compositeOutputExtent,
    &RaytracingFrame::m_sharedFlags,
    &RaytracingFrame::m_sharedMaterialData0,
    &RaytracingFrame::m_sharedMaterialData1,
    &RaytracingFrame::m_sharedTextureCoord,
    &RaytracingFrame::m_sharedSurfaceIndex,
    &RaytracingFrame::m_sharedSubsurfaceData,
    &RaytracingFrame::m_sharedSubsurfaceDiffusionProfileData,
    &RaytracingFrame::m_sparseRenderingActivePixelCoords,
    &RaytracingFrame::m_sparseRenderingActivePixelCount,
    &RaytracingFrame::m_primaryWorldShadingNormal,
    &RaytracingFrame::m_primaryPerceptualRoughness,
    &RaytracingFrame::m_primaryHitDistance,
    &RaytracingFrame::m_primaryAlbedo,
    &RaytracingFrame::m_primaryViewDirection,
    &RaytracingFrame::m_primaryConeRadius,
    &RaytracingFrame::getCompactedPrimaryWorldPositionWorldTriangleNormal,
    &RaytracingFrame::m_primaryPositionError,
    &RaytracingFrame::m_indirectRadianceHitDistance,
    &RaytracingFrame::m_primaryBaseReflectivity,
    &RaytracingFrame::m_primaryIndirectDiffuseRadiance,
    &RaytracingFrame::m_primaryIndirectSpecularRadiance,
    &RaytracingFrame::m_neeCache,
    &RaytracingFrame::m_neeCacheTask,
    &RaytracingFrame::m_neeCacheSample,
    &RaytracingFrame::m_raytraceArgs,
    &RaytracingFrame::m_sparseRenderingDispatchIndirectArgs,
    &RaytracingFrame::m_finalOutputExtent>;

  class DxvkDevice;
  enum class IntegrateIndirectMode;

  class DxvkPathtracerIntegrateIndirect : public CommonDeviceObject {
  public:
    enum class RaytraceMode {
      RayQuery = 0,
      RayQueryRayGen,
      TraceRay,
      Count
    };

    explicit DxvkPathtracerIntegrateIndirect(DxvkDevice* device);
    ~DxvkPathtracerIntegrateIndirect() = default;

    void prewarmShaders(DxvkPipelineManager& pipelineManager) const;

    void dispatch(class RtxContext* ctx, IndirectIntegrationResources resources, const bool doSharcUpdate = false);

    void dispatchNEE(RtxContext* ctx, NeeIntegrationResources resources);

    static const char* raytraceModeToString(RaytraceMode raytraceMode);

  private:
    static DxvkRaytracingPipelineShaders getPipelineShaders(const bool useRayQuery, const bool serEnabled, const bool ommEnabled, const bool useNeeCache, const bool includePortals, const bool pomEnabled, const bool nrcEnabled, const bool sharcUpdateEnabled, const bool sharcQueryEnabled, const bool wboitEnabled);
    Rc<DxvkShader> getComputeShader(const bool useNeeCache, const bool nrcEnabled, const bool sharcUpdateEnabled, const bool sharcQueryEnabled, const bool wboitEnabled) const;
    void logIntegrateIndirectMode();
    
    IntegrateIndirectMode m_integrateIndirectMode;
  };
}
