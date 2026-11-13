/*
* Copyright (c) 2023-2025, NVIDIA CORPORATION. All rights reserved.
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

#include "rtx_option.h"
#include "rtx_frame_resources.h"
#include "../util/rc/util_rc_ptr.h"

namespace dxvk {

  class DxvkDevice;

  class DxvkPathtracerGbuffer : public CommonDeviceObject {
  public:
    RTX_OPTION_FLAG("rtx.gbuffer", bool, skipSERReorder, false, RtxOptionFlags::NoSave,
      "Diagnostic only. Skips G-buffer HitObject reordering without changing the ray tracing pipeline.");
    RTX_OPTION_FLAG("rtx.gbuffer", bool, logDispatch, false, RtxOptionFlags::NoSave,
      "Logs selected G-buffer shaders, dispatch dimensions and ray settings when they change.");

    enum class RaytraceMode {
      RayQuery = 0,
      RayQueryRayGen,
      TraceRay,
      Count
    };

    explicit DxvkPathtracerGbuffer(DxvkDevice* device);
    ~DxvkPathtracerGbuffer() = default;

    void prewarmShaders(DxvkPipelineManager& pipelineManager) const;
    void dispatchPrimaryTraversal(class RtxContext* ctx, const VkExtent3D& rayDims, const VolumeArgs& volume);

    // Supplies the surface and reconstruction guides for lighting and temporal consumers.
    using DispatchResources = FrameResourceView<
      &RaytracingFrame::m_raytraceArgs,
      &RaytracingFrame::m_sparseRenderingCompactedPixelIndices,
      &RaytracingFrame::m_sparseRenderingTileActiveCounts,
      &RaytracingFrame::m_sharedFlags,
      &RaytracingFrame::m_sharedRadianceRG,
      &RaytracingFrame::m_sharedRadianceB,
      &RaytracingFrame::m_sharedIntegrationSurfacePdf,
      &RaytracingFrame::m_sharedMaterialData0,
      &RaytracingFrame::m_sharedMaterialData1,
      &RaytracingFrame::m_sharedMediumState,
      &RaytracingFrame::m_sharedBiasCurrentColorMask,
      &RaytracingFrame::m_sharedTextureCoord,
      &RaytracingFrame::m_sharedSurfaceIndex,
      &RaytracingFrame::m_sharedSubsurfaceData,
      &RaytracingFrame::m_sharedSubsurfaceDiffusionProfileData,
      &RaytracingFrame::m_primaryAttenuation,
      &RaytracingFrame::m_primaryWorldShadingNormal,
      &RaytracingFrame::m_primaryPerceptualRoughness,
      &RaytracingFrame::m_primaryLinearViewZ,
      &RaytracingFrame::m_primaryAlbedo,
      &RaytracingFrame::m_primaryBaseReflectivity,
      &RaytracingFrame::m_primaryVirtualMotionVector,
      &RaytracingFrame::m_primaryScreenSpaceMotionVector,
      &RaytracingFrame::m_fgForegroundGuide,
      &RaytracingFrame::m_primaryVirtualWorldShadingNormalPerceptualRoughness,
      &RaytracingFrame::m_primaryVirtualWorldShadingNormalPerceptualRoughnessDenoising,
      &RaytracingFrame::m_primaryHitDistance,
      &RaytracingFrame::m_primaryViewDirection,
      &RaytracingFrame::m_primaryConeRadius,
      &RaytracingFrame::m_primaryPositionError,
      &RaytracingFrame::getCurrentSharedTerminatorFix,
      &RaytracingFrame::m_primarySurfaceFlags,
      &RaytracingFrame::m_primaryDisocclusionThresholdMix,
      &RaytracingFrame::m_primaryDepth,
      &RaytracingFrame::m_primaryObjectPicking,
      &RaytracingFrame::m_primaryWorldPositionRaw,
      &RaytracingFrame::m_primaryTexcoordsRaw,
      &RaytracingFrame::m_primaryNormalF32,
      &RaytracingFrame::m_primaryCoverage,
      &RaytracingFrame::m_secondaryAttenuation,
      &RaytracingFrame::m_secondaryWorldShadingNormal,
      &RaytracingFrame::m_secondaryPerceptualRoughness,
      &RaytracingFrame::m_secondaryLinearViewZ,
      &RaytracingFrame::m_secondaryAlbedo,
      &RaytracingFrame::m_secondaryBaseReflectivity,
      &RaytracingFrame::m_secondaryVirtualMotionVector,
      &RaytracingFrame::m_secondaryVirtualWorldShadingNormalPerceptualRoughness,
      &RaytracingFrame::m_secondaryVirtualWorldShadingNormalPerceptualRoughnessDenoising,
      &RaytracingFrame::m_secondaryHitDistance,
      &RaytracingFrame::m_secondaryViewDirection,
      &RaytracingFrame::m_secondaryConeRadius,
      &RaytracingFrame::m_secondaryPositionError,
      &RaytracingFrame::m_secondaryWorldPositionWorldTriangleNormal,
      &RaytracingFrame::m_alphaBlendGBuffer,
      &RaytracingFrame::m_gbufferPSRData,
      &RaytracingFrame::getCurrentPrimaryWorldPositionWorldTriangleNormal,
      &RaytracingFrame::m_gbufferPSRData1Dense,
      &RaytracingFrame::m_gbufferPSRData2Dense,
      &RaytracingFrame::m_transmissionPSRData,
      &RaytracingFrame::m_primaryDepthDLSSRR,
      &RaytracingFrame::m_rayReconstructionSpecularMotion,
      &RaytracingFrame::m_primaryWorldShadingNormalDLSSRR,
      &RaytracingFrame::m_sharedFlagsDense,
      &RaytracingFrame::m_primaryAlbedoDLSSRR,
      &RaytracingFrame::m_primarySpecularAlbedoDLSSRR,
      &RaytracingFrame::m_primaryWorldPositionWorldTriangleNormalCompacted,
      &RaytracingFrame::m_primaryWorldShadingNormalDense,
      &RaytracingFrame::m_controlMask,
      &RaytracingFrame::m_compositeOutputExtent>;
    void dispatch(class RtxContext* ctx, DispatchResources frame);

    static const char* raytraceModeToString(RaytraceMode raytraceMode);

  private:
    void ensurePathBuffers(const VkExtent3D& rayDims, bool atmosphereCache,
      uint32_t atmosphereStride, uint32_t checkpointCapacity);

    Rc<DxvkBuffer> m_pathStorage;
    Rc<DxvkBuffer> m_psrQueues[2];
    size_t m_loggedDispatchHash[2] = {};

    static DxvkRaytracingPipelineShaders getPrimaryPipelineShaders(const uint32_t featureVariant, const bool useRayQuery, const bool serEnabled, const bool ommEnabled, const bool includePortals, const bool nrcEnabled, const bool sharcEnabled, const bool wboitEnabled);
  };
}
