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
#include "rtx_animation_time.h"
#include "rtx_composite.h"
#include "rtx_debug_view.h"
#include "rtx_neural_radiance_cache.h"
#include "rtx_pathtracer_integrate_direct.h"
#include "rtx_pathtracer_integrate_indirect.h"
#include "rtx_restir_gi_rayquery.h"
#include "rtx_rtxdi_rayquery.h"
#include "rtx_scene_manager.h"
#include "rtx_sparse_rendering.h"
#include "rtx_spatially_hashed_radiance_cache.h"
#include "rtx/pass/raytrace_args.h"

namespace dxvk {
  __forceinline void RtxFramePipeline::recordSceneLighting(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput, RtxFrameCapture capture) {
    constexpr auto resolveNrc = frame::BorrowedPass<&DxvkObjects::metaNeuralRadianceCache,
      &NeuralRadianceCache::dispatchTrainingAndResolve, NrcResolveFrame>{};
    constexpr auto rtxdiConfidence = frame::BorrowedPass<&DxvkObjects::metaRtxdiRayQuery,
      &DxvkRtxdiRayQuery::dispatchConfidence, DxvkRtxdiRayQuery::ConfidenceDispatchResources>{};
    constexpr auto restirGi = frame::BorrowedPass<&DxvkObjects::metaReSTIRGIRayQuery,
      &DxvkReSTIRGIRayQuery::dispatch, DxvkReSTIRGIRayQuery::DispatchResources>{};

    using Lighting = frame::Schedule<recordCloudSky, recordPrimaryTraversal, dispatchVolumetrics,
      dispatchPathTracing, resolveNrc, rtxdiConfidence, restirGi>;
    static_assert(Lighting::orders<recordCloudSky, recordPrimaryTraversal>);
    static_assert(Lighting::orders<recordPrimaryTraversal, dispatchVolumetrics>);
    static_assert(Lighting::orders<dispatchVolumetrics, dispatchPathTracing>);
    static_assert(Lighting::orders<dispatchPathTracing, resolveNrc>);
    static_assert(Lighting::orders<dispatchPathTracing, rtxdiConfidence>);
    static_assert(Lighting::orders<resolveNrc, restirGi>);

    using Composition = frame::Schedule<captureLighting, recordDemodulation, recordDenoising, recordComposition>;
    static_assert(Composition::orders<captureLighting, recordDemodulation>);
    static_assert(Composition::orders<recordDemodulation, recordDenoising>);
    static_assert(Composition::orders<recordDenoising, recordComposition>);
    Lighting::record(ctx, rtOutput);
    Composition::record(ctx, rtOutput, capture);
  }

  __forceinline void RtxFramePipeline::recordCloudSky(RtxContext& ctx, const FrameResources& rtOutput) {
    ctx.m_common->metaGlobalVolumetrics().dispatchCloudSky(&ctx, rtOutput.m_raytraceArgs.volumeArgs.clouds);
  }

  __forceinline void RtxFramePipeline::recordPrimaryTraversal(RtxContext& ctx, const FrameResources& rtOutput) {
    if (RtxAnimationTime::overridden()) {
      const auto& args = rtOutput.m_raytraceArgs;
      const auto name = str::format("Animation ms=", RtxAnimationTime::milliseconds(),
        " fogMs=", args.volumeArgs.densityTimeMS, " cloudSeconds=", CloudOptions::worldTimeSeconds(),
        " frame=", args.frameIdx);
      VkDebugUtilsLabelEXT label = { VK_STRUCTURE_TYPE_DEBUG_UTILS_LABEL_EXT };
      label.pLabelName = name.c_str();
      ctx.beginDebugLabel(&label);
    }
    // First-hit traversal needs the scene and camera, but not volumetric radiance or sparse compaction.
    ctx.m_common->metaPathtracerGbuffer().dispatchPrimaryTraversal(
      &ctx, rtOutput.m_compositeOutputExtent, rtOutput.m_raytraceArgs.volumeArgs);
    if (RtxAnimationTime::overridden()) {
      ctx.endDebugLabel();
    }
  }

  __forceinline void RtxFramePipeline::captureLighting(RtxContext& ctx, const FrameResources& rtOutput, RtxFrameCapture capture) {
    if (capture.capturesDebugImages()) {
      ctx.takeScreenshot("baseReflectivity", rtOutput.m_primaryBaseReflectivity.image(Resources::AccessType::Read));
      if (rtOutput.m_sharedSubsurfaceData.isValid()) {
        ctx.takeScreenshot("sharedSubsurfaceData", rtOutput.m_sharedSubsurfaceData.image);
      }
      if (rtOutput.m_sharedSubsurfaceDiffusionProfileData.isValid()) {
        ctx.takeScreenshot("sharedSubsurfaceDiffusionProfileData", rtOutput.m_sharedSubsurfaceDiffusionProfileData.image);
      }
      ctx.takeScreenshot("sharedMaterialData1", rtOutput.m_sharedMaterialData1.image);
      ctx.takeScreenshot("sharedFlags", rtOutput.m_sharedFlags.image);
      if (!rtOutput.m_sharedFlagsDense.empty()) {
        ctx.takeScreenshot("sharedFlagsDense", rtOutput.m_sharedFlagsDense.image(Resources::AccessType::Read));
      }
      ctx.takeScreenshot("sharedIntegrationSurfacePdf", rtOutput.m_sharedIntegrationSurfacePdf.image(Resources::AccessType::Read));
      ctx.takeScreenshot("primaryDirectDiffuse", rtOutput.m_primaryDirectDiffuseRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("primaryDirectSpecular", rtOutput.m_primaryDirectSpecularRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("primaryIndirectDiffuse", rtOutput.m_primaryIndirectDiffuseRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("primaryIndirectSpecular", rtOutput.m_primaryIndirectSpecularRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("sharedRadianceRG", rtOutput.m_sharedRadianceRG.image);
      ctx.takeScreenshot("sharedRadianceB", rtOutput.m_sharedRadianceB.image);
      ctx.takeScreenshot("primaryWorldPositionWorldTriangleNormal", rtOutput.getCompactedPrimaryWorldPositionWorldTriangleNormal().image(Resources::AccessType::Read));
    }
  }

  __forceinline void RtxFramePipeline::recordDemodulation(RtxContext& ctx, const FrameResources& rtOutput, RtxFrameCapture capture) {
    {
      ScopedCpuProfileZoneN("dxvk::RtxContext::dispatchDemodulate");
      ctx.getCommonObjects()->metaDemodulate().dispatch(&ctx, DemodulateResources(rtOutput));
    }

    // Note: Primary direct diffuse/specular radiance textures noisy and in a demodulated state after demodulation step.
    if (capture.capturesDebugImages()) {
      ctx.takeScreenshot("noisyDiffuse", rtOutput.m_primaryDirectDiffuseRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("noisySpecular", rtOutput.m_primaryDirectSpecularRadiance.image(Resources::AccessType::Read));
    }
  }

  __forceinline void RtxFramePipeline::recordDenoising(RtxContext& ctx, const FrameResources& rtOutput, RtxFrameCapture capture) {
    dispatchDenoise(ctx, rtOutput);

    // Note: Primary direct diffuse/specular radiance textures denoised but in a still demodulated state after denoising step.
    if (capture.capturesDebugImages()) {
      ctx.takeScreenshot("denoisedDiffuse", rtOutput.m_primaryDirectDiffuseRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("denoisedSpecular", rtOutput.m_primaryDirectSpecularRadiance.image(Resources::AccessType::Read));
    }
  }

  __forceinline void RtxFramePipeline::recordComposition(RtxContext& ctx, const FrameResources& rtOutput, RtxFrameCapture capture) {
    if (capture.capturesDebugImages()) {
      ctx.takeScreenshot("primaryIndirectDiffuseComposite", rtOutput.m_primaryIndirectDiffuseRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("primaryIndirectSpecularComposite", rtOutput.m_primaryIndirectSpecularRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("primaryAttenuation", rtOutput.m_primaryAttenuation.image);
      ctx.takeScreenshot("primaryAlbedoComposite", rtOutput.m_primaryAlbedo.image);
      ctx.takeScreenshot("primarySpecularAlbedoComposite", rtOutput.m_primaryBaseReflectivity.image(Resources::AccessType::Read));
      ctx.takeScreenshot("primaryNormalRoughnessComposite", rtOutput.m_primaryVirtualWorldShadingNormalPerceptualRoughness.image);
      ctx.takeScreenshot("bsdfFactor", rtOutput.m_bsdfFactor.image);
      ctx.takeScreenshot("secondaryCombinedDiffuse", rtOutput.m_secondaryCombinedDiffuseRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("secondaryCombinedSpecular", rtOutput.m_secondaryCombinedSpecularRadiance.image(Resources::AccessType::Read));
      ctx.takeScreenshot("secondaryAttenuation", rtOutput.m_secondaryAttenuation.image);
      ctx.takeScreenshot("secondaryAlbedo", rtOutput.m_secondaryAlbedo.image);
      ctx.takeScreenshot("secondarySpecularAlbedo", rtOutput.m_secondaryBaseReflectivity.image(Resources::AccessType::Read));
    }
    dispatchComposite(ctx, rtOutput);

    // Post composite Debug View that may overwrite Composite output
    dispatchReplaceCompositeWithDebugView(ctx, rtOutput);

    if (capture.capturesDebugImages()) {
      ctx.takeScreenshot("rtxImagePostComposite", rtOutput.m_compositeOutput.resource(Resources::AccessType::Read).image);
    }
  }

  void RtxFramePipeline::dispatchVolumetrics(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    ScopedGpuProfileZone(&ctx, "Volumetrics");
    ctx.setFramePassStage(RtxFramePassStage::Volumetrics);

    // Volume Raytracing
    {
      ctx.m_common->metaGlobalVolumetrics().dispatch(&ctx, rtOutput.m_raytraceArgs.volumeArgs.numActiveFroxelVolumes);
    }
  }

  void RtxFramePipeline::dispatchIntegrate(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    ScopedGpuProfileZone(&ctx, "Integrate Raytracing");

    // Integrate direct and prepare the RTXDI gradient before indirect integration.
    constexpr auto direct = frame::BorrowedPass<&DxvkObjects::metaPathtracerIntegrateDirect,
      &DxvkPathtracerIntegrateDirect::dispatch, DirectIntegrationResources>{};
    constexpr auto gradient = frame::BorrowedPass<&DxvkObjects::metaRtxdiRayQuery,
      &DxvkRtxdiRayQuery::dispatchGradient, DxvkRtxdiRayQuery::GradientDispatchResources>{};
    using Direct = frame::Schedule<direct, gradient>;
    static_assert(Direct::orders<direct, gradient>);
    Direct::record(ctx, rtOutput);

    // Integrate indirect
    {
      ScopedGpuProfileZone(&ctx, "Indirect Integration Pipeline");

      SpatiallyHashedRadianceCache& sharc = ctx.m_common->metaSpatiallyHashedRadianceCache();
      const bool isSharcUpdateCombinedWithQuery = sharc.isUpdateCombinedWithQuery();

      // SHaRC Update passes
      if (RtxOptions::integrateIndirectMode() == IntegrateIndirectMode::SHaRC && !isSharcUpdateCombinedWithQuery) {
        // Indirect PT - SHaRC Update pass
        if (SpatiallyHashedRadianceCache::SharcOptions::enableUpdate())
        {
          ScopedGpuProfileZone(&ctx, "Indirect (SHaRC Update)");
          ctx.setFramePassStage(RtxFramePassStage::IndirectIntegrationUpdate);

          const bool doSharcUpdate = true;
          ctx.m_common->metaPathtracerIntegrateIndirect().dispatch(&ctx, IndirectIntegrationResources(rtOutput), doSharcUpdate);
        }

        sharc.dispatch(ctx);
      }

      // Indirect PT
      {
        ctx.setFramePassStage(RtxFramePassStage::IndirectIntegration);

        ctx.m_common->metaPathtracerIntegrateIndirect().dispatch(&ctx, IndirectIntegrationResources(rtOutput));
      }

      // The resolve needs the combined dispatch's update samples, so the dispatch's queries read the previous frame's cache.
      if (isSharcUpdateCombinedWithQuery) {
        sharc.dispatch(ctx);
      }
    }

    // Integrate indirect - NEE Cache pass
    frame::record<
      frame::BorrowedPass<&DxvkObjects::metaPathtracerIntegrateIndirect, &DxvkPathtracerIntegrateIndirect::dispatchNEE, NeeIntegrationResources>{}
    >(ctx, rtOutput);
  }

  void RtxFramePipeline::dispatchPathTracing(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    // Sparse rendering prepares the compacted coordinates used by the G-buffer.
    constexpr auto sparse = frame::BorrowedPass<&DxvkObjects::metaSparseRendering,
      &SparseRendering::dispatch, SparseRendering::DispatchResources>{};
    constexpr auto gbuffer = frame::BorrowedPass<&DxvkObjects::metaPathtracerGbuffer,
      &DxvkPathtracerGbuffer::dispatch, DxvkPathtracerGbuffer::DispatchResources>{};
    constexpr auto rtxdi = frame::BorrowedPass<&DxvkObjects::metaRtxdiRayQuery,
      &DxvkRtxdiRayQuery::dispatch, DxvkRtxdiRayQuery::DispatchResources>{};
    constexpr auto nee = frame::BorrowedPass<&DxvkObjects::metaNeeCache,
      &NeeCachePass::dispatch, NeeCachePass::DispatchResources>{};
    using PathTracing = frame::Schedule<sparse, gbuffer, rtxdi, nee, dispatchIntegrate>;
    static_assert(PathTracing::orders<sparse, gbuffer>);
    static_assert(PathTracing::orders<gbuffer, rtxdi>);
    static_assert(PathTracing::orders<gbuffer, nee>);
    static_assert(PathTracing::orders<rtxdi, dispatchIntegrate>);
    static_assert(PathTracing::orders<nee, dispatchIntegrate>);
    PathTracing::record(ctx, rtOutput);
  }

  void RtxFramePipeline::dispatchDenoise(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    // Primary direct denoiser used for primary direct lighting when separated, otherwise a special combined direct+indirect denoiser is used when both direct and indirect signals are combined.
    DxvkDenoise& denoiser0 = RtxOptions::denoiseDirectAndIndirectLightingSeparately() ? ctx.m_common->metaPrimaryDirectLightDenoiser() : ctx.m_common->metaPrimaryCombinedLightDenoiser();
    DxvkDenoise& referenceDenoiserSecondLobe0 = ctx.m_common->metaReferenceDenoiserSecondLobe0();
    // Primary Indirect denoiser used for primary indirect lighting when separated.
    DxvkDenoise& denoiser1 = ctx.m_common->metaPrimaryIndirectLightDenoiser();
    DxvkDenoise& referenceDenoiserSecondLobe1 = ctx.m_common->metaReferenceDenoiserSecondLobe1();
    // Secondary combined denoiser used for secondary lighting when NRD is active.
    DxvkDenoise& denoiser2 = ctx.m_common->metaSecondaryCombinedLightDenoiser();
    DxvkDenoise& referenceDenoiserSecondLobe2 = ctx.m_common->metaReferenceDenoiserSecondLobe2();

    // The same condition the GBuffer's guide writes are gated on, so the two cannot disagree.
    const bool shouldDenoise = ctx.getResourceManager().areNrdDenoisingGuideResourcesAllocated();

    if (!shouldDenoise) {
      denoiser0.releaseResources();
      denoiser1.releaseResources();
      denoiser2.releaseResources();
      referenceDenoiserSecondLobe0.releaseResources();
      referenceDenoiserSecondLobe1.releaseResources();
      referenceDenoiserSecondLobe2.releaseResources();
      return;
    }

    ScopedGpuProfileZone(&ctx, "Denoising");
    ctx.setFramePassStage(RtxFramePassStage::NRD);

    auto runDenoising = [&](DxvkDenoise& denoiser, DxvkDenoise& secondLobeReferenceDenoiser, DxvkDenoise::Input& denoiseInput, DxvkDenoise::Output& denoiseOutput) {
      // Since NRDContext doesn't use DxvkContext abstraction
      // but its using Compute, mark its DxvkContext's Cp pipelines as dirty
      {
        ctx.spillRenderPass(false);
        ctx.m_flags.set(
          DxvkContextFlag::CpDirtyPipeline,
          DxvkContextFlag::CpDirtyPipelineState,
          DxvkContextFlag::CpDirtyResources,
          DxvkContextFlag::CpDirtyDescriptorBinding);
      }

      // Need to run the denoiser twice for diffuse and specular when reference denoising is enabled on non-combined inputs
      if (denoiser.isReferenceDenoiserEnabled()) {
        denoiseInput.reference = denoiseInput.diffuse_hitT;
        denoiseOutput.reference = denoiseOutput.diffuse_hitT;
        denoiser.dispatch(&ctx, ctx.m_execBarriers, rtOutput.m_compositeOutputExtent, denoiseInput, denoiseOutput);

        // Reference denoiser accumulates internally, so the second signal has to be denoised through a separate reference denoiser
        secondLobeReferenceDenoiser.copyNrdSettingsFrom(denoiser);
        denoiseInput.reference = denoiseInput.specular_hitT;
        denoiseOutput.reference = denoiseOutput.specular_hitT;
        secondLobeReferenceDenoiser.dispatch(&ctx, ctx.m_execBarriers, rtOutput.m_compositeOutputExtent, denoiseInput, denoiseOutput);
      } else {
        denoiser.dispatch(&ctx, ctx.m_execBarriers, rtOutput.m_compositeOutputExtent, denoiseInput, denoiseOutput);
      }
    };

    const auto primaryGuides = [&]() {
      return DxvkDenoise::Input {
        .normal_roughness = &rtOutput.m_primaryVirtualWorldShadingNormalPerceptualRoughnessDenoising.resource(Resources::AccessType::Read),
        .linearViewZ = &rtOutput.m_primaryLinearViewZ,
        .motionVector = &rtOutput.m_primaryVirtualMotionVector.resource(Resources::AccessType::Read),
        .disocclusionThresholdMix = &rtOutput.m_primaryDisocclusionThresholdMix,
        .reset = ctx.m_resetHistory,
      };
    };

    // Primary Direct light denoiser
    {
      ScopedGpuProfileZone(&ctx, "Primary Direct Denoising");

      const auto* diffuse = &rtOutput.m_primaryDirectDiffuseRadiance.resource(Resources::AccessType::Read);
      const auto* specular = &rtOutput.m_primaryDirectSpecularRadiance.resource(Resources::AccessType::Read);
      auto denoiseInput = primaryGuides();
      denoiseInput.diffuse_hitT = diffuse;
      denoiseInput.specular_hitT = specular;

      if (RtxOptions::useRTXDI() && ctx.m_common->metaRtxdiRayQuery().getEnableDenoiserConfidence(ctx)) {
        denoiseInput.confidence = &rtOutput.getCurrentRtxdiConfidence().resource(Resources::AccessType::Read);
      }

      DxvkDenoise::Output denoiseOutput;
      denoiseOutput.diffuse_hitT = &rtOutput.m_primaryDirectDiffuseRadiance.resource(Resources::AccessType::Write);
      denoiseOutput.specular_hitT = &rtOutput.m_primaryDirectSpecularRadiance.resource(Resources::AccessType::Write);

      runDenoising(denoiser0, referenceDenoiserSecondLobe0, denoiseInput, denoiseOutput);
    }

    // Primary Indirect light denoiser, if separate denoiser is used.
    if (RtxOptions::denoiseDirectAndIndirectLightingSeparately())
    {
      ScopedGpuProfileZone(&ctx, "Primary Indirect Denoising");

      const auto* diffuse = &rtOutput.m_primaryIndirectDiffuseRadiance.resource(Resources::AccessType::Read);
      const auto* specular = &rtOutput.m_primaryIndirectSpecularRadiance.resource(Resources::AccessType::Read);
      auto denoiseInput = primaryGuides();
      denoiseInput.diffuse_hitT = diffuse;
      denoiseInput.specular_hitT = specular;

      DxvkDenoise::Output denoiseOutput;
      denoiseOutput.diffuse_hitT = &rtOutput.m_primaryIndirectDiffuseRadiance.resource(Resources::AccessType::Write);
      denoiseOutput.specular_hitT = &rtOutput.m_primaryIndirectSpecularRadiance.resource(Resources::AccessType::Write);

      runDenoising(denoiser1, referenceDenoiserSecondLobe1, denoiseInput, denoiseOutput);
    } else {
      denoiser1.releaseResources();
      referenceDenoiserSecondLobe1.releaseResources();
    }

    // Secondary Combined light denoiser
    {
      ScopedGpuProfileZone(&ctx, "Secondary Combined Denoising");

      DxvkDenoise::Input denoiseInput = {};
      denoiseInput.diffuse_hitT = &rtOutput.m_secondaryCombinedDiffuseRadiance.resource(Resources::AccessType::Read);
      denoiseInput.specular_hitT = &rtOutput.m_secondaryCombinedSpecularRadiance.resource(Resources::AccessType::Read);
      denoiseInput.normal_roughness = &rtOutput.m_secondaryVirtualWorldShadingNormalPerceptualRoughnessDenoising;
      denoiseInput.linearViewZ = &rtOutput.m_secondaryLinearViewZ;
      denoiseInput.motionVector = &rtOutput.m_secondaryVirtualMotionVector.resource(Resources::AccessType::Read);
      denoiseInput.reset = ctx.m_resetHistory;

      DxvkDenoise::Output denoiseOutput;
      denoiseOutput.diffuse_hitT = &rtOutput.m_secondaryCombinedDiffuseRadiance.resource(Resources::AccessType::Write);
      denoiseOutput.specular_hitT = &rtOutput.m_secondaryCombinedSpecularRadiance.resource(Resources::AccessType::Write);

      runDenoising(denoiser2, referenceDenoiserSecondLobe2, denoiseInput, denoiseOutput);
    }
  }

  void RtxFramePipeline::dispatchComposite(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    if (ctx.getSceneManager().getSurfaceBuffer() == nullptr) {
      return;
    }

    ScopedGpuProfileZone(&ctx, "Composite");
    ctx.setFramePassStage(RtxFramePassStage::Composition);

    bool isNRDPreCompositionDenoiserEnabled = RtxOptions::useDenoiser() && !RtxOptions::useDenoiserReferenceMode();

    CompositePass::Settings settings;
    settings.fog = ctx.getSceneManager().getFogState();
    settings.isNRDPreCompositionDenoiserEnabled = isNRDPreCompositionDenoiserEnabled;
    settings.useUpscaler = ctx.shouldUseUpscaler();
    settings.useDLSS = ctx.shouldUseDLSS();
    settings.demodulateRoughness = ctx.m_common->metaDemodulate().demodulateRoughness();
    settings.roughnessDemodulationOffset = ctx.m_common->metaDemodulate().demodulateRoughnessOffset();
    ctx.m_common->metaComposite().dispatch(&ctx,
      ctx.getSceneManager(),
      CompositeResources(rtOutput), settings);
  }

  void RtxFramePipeline::dispatchReplaceCompositeWithDebugView(RtxContext& ctx, const Resources::RaytracingOutput& rtOutput) {
    ScopedCpuProfileZone();

    DebugView& debugView = ctx.m_common->metaDebugView();

    if (!debugView.isActive()) {
      return;
    }

    debugView.dispatchAfterCompositionPass(&ctx,
      ctx.getResourceManager().getSampler(VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),
      ctx.getResourceManager().getSampler(VK_FILTER_LINEAR, VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE),
      DebugView::DispatchResources(rtOutput), *ctx.m_common);
  }
}
