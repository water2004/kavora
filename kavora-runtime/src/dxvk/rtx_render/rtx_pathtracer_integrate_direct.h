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
#pragma once

#include "rtx_frame_resources.h"

namespace dxvk {

  using DirectIntegrationResources = FrameResourceView<
    &RaytracingFrame::m_sharedIntegrationSurfacePdf,
    &RaytracingFrame::getCurrentSharedTerminatorFix,
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
    &RaytracingFrame::m_primaryAlbedo,
    &RaytracingFrame::m_primaryViewDirection,
    &RaytracingFrame::m_primaryConeRadius,
    &RaytracingFrame::getCompactedPrimaryWorldPositionWorldTriangleNormal,
    &RaytracingFrame::m_primaryPositionError,
    &RaytracingFrame::m_rtxdiReservoirBuffer,
    &RaytracingFrame::m_secondaryWorldShadingNormal,
    &RaytracingFrame::m_secondaryPerceptualRoughness,
    &RaytracingFrame::m_secondaryAlbedo,
    &RaytracingFrame::m_secondaryViewDirection,
    &RaytracingFrame::m_secondaryConeRadius,
    &RaytracingFrame::m_secondaryWorldPositionWorldTriangleNormal,
    &RaytracingFrame::m_sharedFlags,
    &RaytracingFrame::m_sharedMediumState,
    &RaytracingFrame::m_primaryBaseReflectivity,
    &RaytracingFrame::m_secondaryBaseReflectivity,
    &RaytracingFrame::m_primaryDirectDiffuseRadiance,
    &RaytracingFrame::m_primaryDirectSpecularRadiance,
    &RaytracingFrame::m_secondaryCombinedDiffuseRadiance,
    &RaytracingFrame::m_secondaryCombinedSpecularRadiance,
    &RaytracingFrame::getCurrentRtxdiIlluminance,
    &RaytracingFrame::m_neeCache,
    &RaytracingFrame::m_neeCacheSample,
    &RaytracingFrame::m_neeCacheTask,
    &RaytracingFrame::m_neeCacheThreadTask,
    &RaytracingFrame::m_indirectRayOriginDirection,
    &RaytracingFrame::m_indirectThroughputConeRadius,
    &RaytracingFrame::m_indirectFirstHitPerceptualRoughness,
    &RaytracingFrame::m_secondaryPositionError,
    &RaytracingFrame::m_indirectFirstSampledLobeData,
    &RaytracingFrame::m_compositeOutputExtent,
    &RaytracingFrame::m_raytraceArgs,
    &RaytracingFrame::m_sparseRenderingDispatchIndirectArgs,
    &RaytracingFrame::m_sparseRenderingTraceRaysIndirectArgs>;

  class DxvkDevice;

  class DxvkPathtracerIntegrateDirect : public CommonDeviceObject {
  public:
    enum class RaytraceMode {
      RayQuery = 0,
      RayQueryRayGen,
      Count
    };

    explicit DxvkPathtracerIntegrateDirect(DxvkDevice* device);
    ~DxvkPathtracerIntegrateDirect() = default;

    void prewarmShaders(DxvkPipelineManager& pipelineManager) const;

    void dispatch(class RtxContext* ctx, DirectIntegrationResources resources);

    static const char* raytraceModeToString(RaytraceMode raytraceMode);

  private:
    static DxvkRaytracingPipelineShaders getPipelineShaders(const bool useRayQuery, const bool ommEnabled);
    Rc<DxvkShader> getComputeShader() const;
  };
}
