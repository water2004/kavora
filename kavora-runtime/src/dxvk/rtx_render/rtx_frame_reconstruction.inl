// SPDX-FileCopyrightText: Copyright (c) 2021-2026 NVIDIA CORPORATION. All rights reserved.
// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
// SPDX-License-Identifier: MIT

/*
* Copyright (c) 2021-2026, NVIDIA CORPORATION. All rights reserved.
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

#include "rtx_frame_pipeline.h"
#include "rtx_context.h"
#include "dxvk_device.h"
#include "dxvk_scoped_annotation.h"
#include "rtx_ray_reconstruction.h"
#include "rtx_scene_manager.h"
#include "rtx_xess.h"
#include "rtx/pass/raytrace_args.h"
#include "rtx/utility/debug_view_indices.h"
#include "../util/util_global_time.h"

namespace dxvk {
  __forceinline void RtxFramePipeline::reconstructSceneImage(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput, RtxFrameCapture capture) {
    // Upscaling if DLSS/NIS enabled, or the Composition Pass will do upscaling
    if (ctx.m_currentUpscaler == RtxContext::InternalUpscaler::DLSS) {
      // xxxnsubtil: the DLSS indicator reads our exposure texture even with DLSS autoexposure on
      // make sure it has been created, otherwise we run into trouble on the first frame
      ctx.m_common->metaAutoExposure().createResources(&ctx);
      dispatchDLSS(ctx, rtOutput);
    } else if (ctx.m_currentUpscaler == RtxContext::InternalUpscaler::DLSS_RR) {
      ctx.m_common->metaAutoExposure().createResources(&ctx);
      dispatchRayReconstruction(ctx, rtOutput);
    } else if (ctx.m_currentUpscaler == RtxContext::InternalUpscaler::XeSS) {
      ctx.m_common->metaAutoExposure().createResources(&ctx);
      dispatchXeSS(ctx, rtOutput);
    } else if (ctx.m_currentUpscaler == RtxContext::InternalUpscaler::NIS) {
      dispatchNIS(ctx, rtOutput);
    } else if (ctx.m_currentUpscaler == RtxContext::InternalUpscaler::TAAU){
      dispatchTemporalAA(ctx, rtOutput);
    } else {
      ctx.copyImage(
        rtOutput.m_finalOutput.resource(Resources::AccessType::Write).image,
        { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
        { 0, 0, 0 },
        rtOutput.m_compositeOutput.image(Resources::AccessType::Read),
        { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 },
        { 0, 0, 0 },
        rtOutput.m_compositeOutputExtent);
    }
    ctx.m_previousUpscaler = ctx.m_currentUpscaler;
    if (capture.capturesDebugImages()) {
      ctx.takeScreenshot("rtxImagePostUpscaling", rtOutput.m_finalOutput.resource(Resources::AccessType::Read).image);
      if (ctx.m_currentUpscaler == RtxContext::InternalUpscaler::DLSS_RR) {
        const bool compacted = rtOutput.m_raytraceArgs.sparseRenderingArgs.mode != SparseRenderingMode::Off;
        ctx.takeScreenshot("rrDiffuseGuide", compacted ? rtOutput.m_primaryAlbedoDLSSRR.image : rtOutput.m_primaryAlbedo.image);
        ctx.takeScreenshot("rrSpecularGuide", compacted ? rtOutput.m_primarySpecularAlbedoDLSSRR.image : rtOutput.m_primarySpecularAlbedo.image(Resources::AccessType::Read));
        ctx.takeScreenshot("rrNormalGuide", rtOutput.m_primaryWorldShadingNormalDLSSRR.image(Resources::AccessType::Read));
        ctx.takeScreenshot("rrDepthGuide", rtOutput.m_primaryDepthDLSSRR.image(Resources::AccessType::Read));
        ctx.takeScreenshot("rrMotionGuide", rtOutput.m_primaryScreenSpaceMotionVector.image);
        ctx.takeScreenshot("rrSpecularMotionGuide", rtOutput.m_rayReconstructionSpecularMotion.image);
        ctx.takeScreenshot("rrRoughnessGuide", rtOutput.m_primaryPerceptualRoughness.image);
        ctx.takeScreenshot("primaryResolvedDepth", rtOutput.m_primaryDepth.image);
        ctx.takeScreenshot("primaryResolvedMotion", rtOutput.m_primaryScreenSpaceMotionVector.image);
        if (compacted) {
          ctx.takeScreenshot("compactedPixelIndices", rtOutput.m_sparseRenderingCompactedPixelIndices.image);
        }
      }
    }
  }

  void RtxFramePipeline::dispatchDLSS(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    DxvkDLSS& dlss = ctx.m_common->metaDLSS();
    dlss.dispatch(&ctx, ctx.m_execBarriers, DxvkDLSS::DLSSDispatchResources(rtOutput), ctx.m_resetHistory);
  }

  void RtxFramePipeline::dispatchRayReconstruction(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    DxvkRayReconstruction& rayReconstruction = ctx.m_common->metaRayReconstruction();
    const auto& args = rtOutput.m_raytraceArgs;
    rayReconstruction.dispatch(&ctx, ctx.m_execBarriers, DxvkRayReconstruction::DispatchResources(rtOutput), {
      .compositeOutputExtent = rtOutput.m_compositeOutputExtent,
      .enableRaytracedRenderTarget = args.enableRaytracedRenderTarget,
      .debugView = args.debugView,
      .debugKnob = args.debugKnob,
      .frameIdx = args.frameIdx,
      .sparseRenderingArgs = args.sparseRenderingArgs,
    }, {
      .resetHistory = ctx.m_resetHistory,
      .frameTimeMilliseconds = GlobalTime::get().deltaTimeMs(),
    });
  }

  void RtxFramePipeline::dispatchNIS(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    ScopedGpuProfileZone(&ctx, "NIS");
    ctx.setFramePassStage(RtxFramePassStage::NIS);
    ctx.getCommonObjects()->metaNIS().dispatch(&ctx, rtOutput.m_compositeOutput, rtOutput.m_finalOutput);
  }

  void RtxFramePipeline::dispatchXeSS(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    ScopedGpuProfileZone(&ctx, "XeSS");
    ctx.setFramePassStage(RtxFramePassStage::XeSS);
    DxvkXeSS& xess = ctx.m_common->metaXeSS();
    xess.dispatch(&ctx, ctx.m_execBarriers, DxvkXeSS::XeSSDispatchResources(rtOutput), ctx.m_resetHistory);
  }

  void RtxFramePipeline::dispatchTemporalAA(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    ScopedGpuProfileZone(&ctx, "TAA");
    ctx.setFramePassStage(RtxFramePassStage::TAA);

    DxvkTemporalAA& taa = ctx.m_common->metaTAA();
    RtCamera& mainCamera = ctx.getSceneManager().getCamera();
    const bool isViewHistoryInvalidated = mainCamera.isViewHistoryInvalidated(ctx.m_device->getCurrentFrameId());

    if (taa.isActive() && !isViewHistoryInvalidated) {
      float jitterOffset[2];
      mainCamera.getJittering(jitterOffset);

      taa.dispatch(&ctx,
        ctx.getResourceManager().getSampler(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),
        mainCamera.getShaderConstants().resolution,
        jitterOffset,
        rtOutput.m_compositeOutput.resource(Resources::AccessType::Read),
        rtOutput.m_primaryScreenSpaceMotionVector,
        rtOutput.m_finalOutput.resource(Resources::AccessType::Write),
        true);
    }
  }
}
