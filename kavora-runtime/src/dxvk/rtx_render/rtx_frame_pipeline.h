// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors.
// SPDX-License-Identifier: MIT
#pragma once

#include "rtx_resources.h"

namespace dxvk {
  class RtxContext;
  struct RtxFrameCapture {
    bool screenImage;
    bool debugImages;

    bool capturesDebugImages() const { return screenImage && debugImages; }
  };

  class RtxFramePipeline {
  public:
    // Records one prepared scene and publishes the frame-generation inputs.
    static void record(RtxContext& ctx, const Rc<DxvkImage>& targetImage, RtxFrameCapture capture);

  private:
    using FrameResources = Resources::RaytracingOutput;

    struct Preparation {
      VkExtent3D traceExtent;
      VkExtent3D displayExtent;
      bool hasSludge;
    };
    static void prepareWater(RtxContext& ctx, FrameResources& frame, const Preparation& preparation);
    static void prepareRayTracing(RtxContext& ctx, FrameResources& frame, const Preparation& preparation);
    static void recordSceneLighting(RtxContext& ctx, const FrameResources& frame, RtxFrameCapture capture);
    static void reconstructSceneImage(RtxContext& ctx, const FrameResources& frame, RtxFrameCapture capture);
    static void recordDisplayEffects(RtxContext& ctx, FrameResources& frame, RtxFrameCapture capture);

    static void recordCloudSky(RtxContext& ctx, const FrameResources& frame);
    static void recordPrimaryTraversal(RtxContext& ctx, const FrameResources& frame);
    static void captureLighting(RtxContext& ctx, const FrameResources& frame, RtxFrameCapture capture);
    static void recordDemodulation(RtxContext& ctx, const FrameResources& frame, RtxFrameCapture capture);
    static void recordDenoising(RtxContext& ctx, const FrameResources& frame, RtxFrameCapture capture);
    static void recordComposition(RtxContext& ctx, const FrameResources& frame, RtxFrameCapture capture);
    static void dispatchVolumetrics(RtxContext& ctx, const FrameResources& frame);
    static void dispatchIntegrate(RtxContext& ctx, const FrameResources& frame);
    static void dispatchPathTracing(RtxContext& ctx, const FrameResources& frame);
    static void dispatchDenoise(RtxContext& ctx, const FrameResources& frame);
    static void dispatchComposite(RtxContext& ctx, const FrameResources& frame);
    static void dispatchReplaceCompositeWithDebugView(RtxContext& ctx, const FrameResources& frame);

    static void dispatchDLSS(RtxContext& ctx, const FrameResources& frame);
    static void recordUpscaling(RtxContext& ctx, const FrameResources& frame, RtxFrameCapture capture);
    static void rememberUpscaler(RtxContext& ctx, const FrameResources& frame, RtxFrameCapture capture);
    static void prepareReconstructionExposure(RtxContext& ctx, const FrameResources& frame);
    static void dispatchRayReconstruction(RtxContext& ctx, const FrameResources& frame);
    static void dispatchNIS(RtxContext& ctx, const FrameResources& frame);
    static void dispatchXeSS(RtxContext& ctx, const FrameResources& frame);
    static void dispatchTemporalAA(RtxContext& ctx, const FrameResources& frame);
    static void copySceneImage(RtxContext& ctx, const FrameResources& frame);
    static void captureReconstruction(RtxContext& ctx, const FrameResources& frame, RtxFrameCapture capture);

    static bool dispatchDlssNR(RtxContext& ctx, const FrameResources& frame);
    struct DisplayFrame {
      FrameResources& resources;
      RtxFrameCapture capture;
      bool neuralRendering = false;
    };
    static void recordDust(RtxContext& ctx, DisplayFrame& frame);
    static void recordNeuralRendering(RtxContext& ctx, DisplayFrame& frame);
    static void dispatchToneMapping(RtxContext& ctx, DisplayFrame& frame);
    static void dispatchBloom(RtxContext& ctx, DisplayFrame& frame);
    static void dispatchPostFxMotionBlur(RtxContext& ctx, DisplayFrame& frame);
    static void dispatchPostFxLensEffects(RtxContext& ctx, DisplayFrame& frame);
    static void recordDisplayConversion(RtxContext& ctx, DisplayFrame& frame);
    static void captureDisplay(RtxContext& ctx, DisplayFrame& frame);

    static void dispatchDebugView(RtxContext& ctx, Rc<DxvkImage>& srcImage, const FrameResources& frame,
                                  bool captureScreenImage);
    static void prepareFrameInterpolation(RtxContext& ctx, const Resources::Resource& motion,
                                         const Resources::Resource& depth, const Resources::Resource& foreground);
  };
}
