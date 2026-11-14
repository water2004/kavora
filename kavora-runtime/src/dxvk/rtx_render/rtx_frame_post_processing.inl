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
#include "rtx_frame_schedule.h"
#include "rtx_context.h"
#include "dxvk_device.h"
#include "dxvk_scoped_annotation.h"
#include "rtx_debug_view.h"
#include "rtx_scene_manager.h"
#include "rtx/utility/debug_view_indices.h"
#include "../util/util_global_time.h"

namespace dxvk {
  __forceinline void RtxFramePipeline::recordDisplayEffects(RtxContext& ctx, Resources::RaytracingOutput& rtOutput, RtxFrameCapture capture) {
    DisplayFrame display { rtOutput, capture };
    // Bloom and motion blur consume HDR. Lens effects and final grading consume LDR.
    using Effects = frame::Schedule<recordDust, recordNeuralRendering, dispatchBloom, dispatchPostFxMotionBlur,
      dispatchToneMapping, dispatchPostFxLensEffects, recordDisplayConversion, captureDisplay>;
    static_assert(Effects::orders<dispatchBloom, dispatchToneMapping>);
    static_assert(Effects::orders<dispatchPostFxMotionBlur, dispatchToneMapping>);
    static_assert(Effects::orders<dispatchToneMapping, dispatchPostFxLensEffects>);
    static_assert(Effects::orders<dispatchPostFxLensEffects, recordDisplayConversion>);
    static_assert(Effects::orders<recordDisplayConversion, captureDisplay>);
    Effects::record(ctx, display);
  }

  __forceinline void RtxFramePipeline::recordDust(RtxContext& ctx, DisplayFrame& frame) {
    ctx.m_common->metaDustParticles().simulateAndDraw(&ctx, ctx.m_state, RtxDustParticles::DispatchResources(frame.resources));
  }

  __forceinline void RtxFramePipeline::recordNeuralRendering(RtxContext& ctx, DisplayFrame& frame) {
    frame.neuralRendering = dispatchDlssNR(ctx, frame.resources);
    if (frame.capture.capturesDebugImages()) {
      ctx.takeScreenshot("rtxImagePostNeuralRendering", frame.resources.m_finalOutput.resource(Resources::AccessType::Read).image);
    }
  }

  __forceinline void RtxFramePipeline::recordDisplayConversion(RtxContext& ctx, DisplayFrame& frame) {
    // Final output pass grades the linear post-tonemap LDR image, converts to sRGB and applies
    // dithering as the very last step. SRGB conversion is suppressed for screenshot
    // captures (WAR for TREX-553: NVTT implicitly applies sRGB during dds->png conversion
    // for 16bit float formats).
    ScopedCpuProfileZoneN("dxvk::RtxContext::dispatchSRGBDither");
    ctx.getCommonObjects()->metaSRGBDither().dispatch(
      &ctx, DxvkSRGBDither::DispatchResources(frame.resources), frame.capture.screenImage);
  }

  __forceinline void RtxFramePipeline::captureDisplay(RtxContext& ctx, DisplayFrame& frame) {
    const auto& capture = frame.capture;
    const auto& rtOutput = frame.resources;
    if (capture.screenImage) {
      if (ctx.m_common->metaDebugView().debugViewIdx() == DEBUG_VIEW_DISABLED) {
        ctx.takeScreenshot("rtxImagePostTonemapping", rtOutput.m_finalOutput.resource(Resources::AccessType::Read).image);
      }

      if (capture.debugImages) {
        ctx.takeScreenshot("albedo", rtOutput.m_primaryAlbedo.image);
        ctx.takeScreenshot("worldNormals", rtOutput.m_primaryWorldShadingNormal.image);
        ctx.takeScreenshot("worldMotion", rtOutput.m_primaryVirtualMotionVector.image(Resources::AccessType::Read));
        ctx.takeScreenshot("linearZ", rtOutput.m_primaryLinearViewZ.image);
      }
    }
  }

  bool RtxFramePipeline::dispatchDlssNR(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    ScopedCpuProfileZone();

    auto& dlssNr = ctx.m_common->metaDlssNeuralRendering();
    if (!dlssNr.useDlssNeuralRendering()) {
      return false;
    }

    DxvkToneMapping& toneMapper = ctx.m_common->metaToneMapping();
    DxvkAutoExposure& autoExposure = ctx.m_common->metaAutoExposure();
    autoExposure.dispatch(&ctx,
      ctx.getResourceManager().getSampler(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER),
      DxvkAutoExposure::DispatchResources(rtOutput), GlobalTime::get().deltaTimeMs());

    // DLSS-NR operates in SDR space; inverse tone mapping restores HDR for downstream post-processing.
    toneMapper.dispatchFastToneMapping(
      &ctx,
      autoExposure.getExposureTexture().view,
      DxvkToneMapping::DispatchResources(rtOutput),
      autoExposure.enabled());

    const bool useRayReconstructionGuides = ctx.m_currentUpscaler == RtxContext::InternalUpscaler::DLSS_RR;
    if (dlssNr.dispatch(&ctx, ctx.m_execBarriers, DlssNeuralRenderingFrame(rtOutput), ctx.m_resetHistory, useRayReconstructionGuides)) {
      toneMapper.dispatchInverseToneMapping(
        &ctx,
        autoExposure.getExposureTexture().view,
        DxvkToneMapping::DispatchResources(rtOutput),
        autoExposure.enabled());
    }

    return true;
  }

  void RtxFramePipeline::dispatchToneMapping(RtxContext& ctx, DisplayFrame& frame) {
    ScopedCpuProfileZone();

    if (ctx.m_common->metaDebugView().debugViewIdx() == DEBUG_VIEW_PRE_TONEMAP_OUTPUT) {
      return;
    }

    // TODO: I think these are unnecessary, and/or should be automatically done within DXVK
    ctx.spillRenderPass(false);
    ctx.unbindComputePipeline();

    DxvkAutoExposure& autoExposure = ctx.m_common->metaAutoExposure();
    const auto& rtOutput = frame.resources;
    if (!frame.neuralRendering) {
      autoExposure.dispatch(&ctx,
        ctx.getResourceManager().getSampler(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER),
      DxvkAutoExposure::DispatchResources(rtOutput), GlobalTime::get().deltaTimeMs());
    }

    const bool resetToneMapperHistory = ctx.m_resetHistory || ctx.getSceneManager().getCamera().isCameraCut();
    ctx.setFramePassStage(RtxFramePassStage::ToneMapping);
    if (RtxOptions::tonemappingMode() == TonemappingMode::Global) {
      DxvkToneMapping& toneMapper = ctx.m_common->metaToneMapping();
      toneMapper.dispatch(&ctx,
        ctx.getResourceManager().getSampler(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER),
        autoExposure.getExposureTexture().view,
        DxvkToneMapping::DispatchResources(rtOutput),
        GlobalTime::get().deltaTimeMs(),
        resetToneMapperHistory,
        autoExposure.enabled());
    }
    DxvkLocalToneMapping& localTonemapper = ctx.m_common->metaLocalToneMapping();
    if (localTonemapper.isActive()) {
      localTonemapper.dispatch(&ctx,
        ctx.getResourceManager().getSampler(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),
        autoExposure.getExposureTexture().view,
        DxvkLocalToneMapping::DispatchResources(rtOutput),
        GlobalTime::get().deltaTimeMs(),
        autoExposure.enabled());
    }
  }

  void RtxFramePipeline::dispatchBloom(RtxContext& ctx, DisplayFrame& frame) {
    ScopedCpuProfileZone();
    DxvkBloom& bloom = ctx.m_common->metaBloom();
    if (!bloom.isActive()) {
      return;
    }

    ctx.spillRenderPass(false);
    ctx.unbindComputePipeline();
    bloom.dispatch(&ctx, DxvkBloom::DispatchResources(frame.resources));
  }

  void RtxFramePipeline::dispatchPostFxMotionBlur(RtxContext& ctx, DisplayFrame& frame) {
    ScopedCpuProfileZone();
    DxvkPostFx& postFx = ctx.m_common->metaPostFx();
    postFx.dispatchMotionBlur(&ctx, DxvkPostFx::MotionBlurResources(frame.resources));
  }

  void RtxFramePipeline::dispatchPostFxLensEffects(RtxContext& ctx, DisplayFrame& frame) {
    ScopedCpuProfileZone();
    DxvkPostFx& postFx = ctx.m_common->metaPostFx();
    postFx.dispatchLensEffects(&ctx, DxvkPostFx::LensEffectsResources(frame.resources));
  }
}
