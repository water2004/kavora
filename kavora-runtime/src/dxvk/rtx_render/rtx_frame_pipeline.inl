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
#include "rtx_scene_manager.h"
#include "rtx_debug_view.h"
#include "rtx_texture_manager.h"
#include "rtx/pass/raytrace_args.h"
#include "rtx/utility/gpu_printing.h"

namespace dxvk {
  __forceinline void RtxFramePipeline::record(RtxContext& ctx, const Rc<DxvkImage>& targetImage, RtxFrameCapture capture) {
    VkExtent3D downscaledExtent = ctx.onInjectRtxFrameBegin(targetImage->info().extent);

    Resources::RaytracingOutput& rtOutput = ctx.getResourceManager().getRaytracingOutput();

    // The queues hold a single entry unless frame generation runs, see Resources::createDlfgResourceQueues.
    if (rtOutput.m_primaryDepthQueue[1].isValid()) {
      rtOutput.m_primaryDepthQueue.next();
      rtOutput.m_primaryScreenSpaceMotionVectorQueue.next();
    }

    rtOutput.m_primaryDepth = rtOutput.m_primaryDepthQueue.get();
    rtOutput.m_primaryScreenSpaceMotionVector = rtOutput.m_primaryScreenSpaceMotionVectorQueue.get();

    const auto& scene = ctx.getSceneManager();
    const bool hasSludge = scene.getSludgeWaterMaterialIndex() != SURFACE_INDEX_INVALID;
    const bool clientFrameGeneration = !ctx.m_device->vkd()->ownsDevice() && ctx.isDLFGEnabled();
    const bool needsInterfaceGuide = (scene.hasTranslucentSurfaces() || hasSludge) &&
      (RtxOptions::enablePSRR() || RtxOptions::enablePSTR());
    const bool needsParticleGuide = RtxOptions::renderPassGBufferRaytraceMode() == DxvkPathtracerGbuffer::RaytraceMode::RayQuery &&
      RtxOptions::enableSeparateUnorderedApproximations() && scene.getInstanceManager().hasViewModelParticles();
    if (!clientFrameGeneration || (!needsParticleGuide && !needsInterfaceGuide)) {
      rtOutput.m_fgForegroundGuide = {};
    } else if (!rtOutput.m_fgForegroundGuide.isValid() || rtOutput.m_fgForegroundGuide.image->info().extent != downscaledExtent) {
      Rc<DxvkContext> resourceContext(&ctx);
      rtOutput.m_fgForegroundGuide = Resources::createImageResource(resourceContext, "FG guide overrides", downscaledExtent, VK_FORMAT_R32G32_UINT);
    }

    ctx.getCommonObjects()->getTextureManager().prepareSamplerFeedback(&ctx);

    using Prepare = frame::Schedule<prepareWater, prepareRayTracing>;
    static_assert(Prepare::orders<prepareWater, prepareRayTracing>);
    Prepare::record(ctx, rtOutput, Preparation { downscaledExtent, targetImage->info().extent, hasSludge });
    recordSceneLighting(ctx, rtOutput, capture);

    ctx.getCommonObjects()->getTextureManager().copySamplerFeedbackToHost(&ctx);
    ctx.dispatchObjectPicking(rtOutput, downscaledExtent, targetImage->info().extent);

    reconstructSceneImage(ctx, rtOutput, capture);
    recordDisplayEffects(ctx, rtOutput, capture);

    // Set up output src
    Rc<DxvkImage> srcImage = rtOutput.m_finalOutput.resource(Resources::AccessType::Read).image;

    // Debug view
    dispatchDebugView(ctx, srcImage, rtOutput, capture.screenImage);

    prepareFrameInterpolation(ctx, rtOutput.m_primaryScreenSpaceMotionVector, rtOutput.m_primaryDepth,
                              rtOutput.m_fgForegroundGuide);

    // Blit to the game target
    {
      ScopedGpuProfileZone(&ctx, "Blit to Game");

      // Note: the resolution between srcImage and dstImage always matches
      // so we can use the same blit with nearest neighbor filtering
      assert(srcImage->info().extent == targetImage->info().extent);
      ctx.blitImageHelper(&ctx, srcImage, targetImage, VkFilter::VK_FILTER_NEAREST);
    }

    // Log stats when an image is taken
    if (capture.screenImage) {
      ctx.getSceneManager().logStatistics();
    }
  }

  __forceinline void RtxFramePipeline::prepareWater(RtxContext& ctx, FrameResources&, const Preparation& preparation) {
    ctx.m_common->waterSimulation().prepare(&ctx, ctx.getSceneManager().hasSimulatedWater(), preparation.hasSludge);
  }

  __forceinline void RtxFramePipeline::prepareRayTracing(RtxContext& ctx, FrameResources& frame, const Preparation& preparation) {
    ctx.updateRaytraceArgsConstantBuffer(frame, preparation.traceExtent, preparation.displayExtent);
  }

  void RtxFramePipeline::dispatchDebugView(RtxContext& ctx, Rc<DxvkImage>& srcImage, const Resources::RaytracingOutput& rtOutput, bool captureScreenImage)  {
    ScopedCpuProfileZone();

    DebugView& debugView = ctx.m_common->metaDebugView();
    const uint32_t frameIdx = ctx.m_device->getCurrentFrameId();

    if (debugView.gpuPrint.enable()) {
      // Read from the oldest element as it is guaranteed to be written on the GPU by now
      VkDeviceSize offset = ((frameIdx + 1) % kMaxFramesInFlight) * sizeof(GpuPrintBufferElement);
      GpuPrintBufferElement* gpuPrintElement = reinterpret_cast<GpuPrintBufferElement*>(rtOutput.m_gpuPrintBuffer->mapPtr(offset));

      if (gpuPrintElement && gpuPrintElement->isValid()) {
        static std::string previousString = "";
        const std::string newString = str::format("GPU print value [", gpuPrintElement->threadIndex.x, ", ", gpuPrintElement->threadIndex.y, "]: ", Config::generateOptionString(reinterpret_cast<Vector4&>(gpuPrintElement->writtenData)));

        // Avoid spamming the console with the same output
        if (newString != previousString) {
          previousString = newString;

          // Add additional info on which we don't want to differentiate when printing out
          const std::string fullInfoString = str::format("Frame: ", gpuPrintElement->frameIndex, " - ", newString);
          Logger::info(fullInfoString);
        }

        // Invalidate the element so that it's not reused
        gpuPrintElement->invalidate();
      }
    }

    if (!debugView.isActive()) {
      return;
    }

    debugView.dispatch(&ctx,
      ctx.getResourceManager().getSampler(VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),
      ctx.getResourceManager().getSampler(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),
      srcImage, DebugView::DispatchResources(rtOutput), *ctx.m_common);

    if (captureScreenImage) {
      // For overlayed debug views, we preserve the post tonemapping naming since the post tonemapped image is a base image.
      // The benefit is retention of most of the existing testing pipeline.
      if (debugView.getOverlayOnTopOfRenderOutput()) {
        ctx.takeScreenshot("rtxImagePostTonemapping", srcImage);
      } else {
        ctx.takeScreenshot("rtxImageDebugView", srcImage);
      }
    }
  }

  void RtxFramePipeline::prepareFrameInterpolation(RtxContext& ctx, const Resources::Resource& motion,
                                                  const Resources::Resource& depth, const Resources::Resource& foreground) {
    if (!ctx.isDLFGEnabled()) {
      return;
    }

    // force vsync off if DLFG is enabled, as we don't properly support FG + vsync
    if (ctx.m_device->vkd()->ownsDevice() && RtxOptions::enableVsyncState != EnableVsync::Off) {
      RtxOptions::enableVsync.setDeferred(EnableVsync::Off);
      RtxOptions::enableVsyncState = EnableVsync::Off;
    }

    ctx.m_frameInterpolation = {
      ctx.m_device->getCurrentFrameId(),
      RtxFrameGenerationCamera(ctx.m_device->getCommon()->getSceneManager().getCamera()),
      motion.view,
      motion.image->info().layout,
      depth.view,
      depth.image->info().layout,
      ctx.getSceneManager().getCamera().isCameraCut(),
      ctx.m_common->metaDLFG().getInterpolatedFrameCount(),
      foreground.view,
    };
    // Host presentation binds frame metadata after composing the client's GUI.
    if (ctx.m_device->vkd()->ownsDevice()) {
      ctx.m_device->setupFrameInterpolation(ctx.m_frameInterpolation);
    }
  }
}
