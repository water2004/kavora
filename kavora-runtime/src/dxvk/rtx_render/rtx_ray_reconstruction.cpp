/*
* Copyright (c) 2024-2026, NVIDIA CORPORATION. All rights reserved.
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
#include <locale>
#include <codecvt>
#include <cassert>

#include "rtx.h"
#include "rtx_context.h"
#include "rtx_options.h"
#include "rtx/pass/tonemap/tonemapping.h"
#include "rtx/pass/ray_reconstruction/ray_reconstruction.h"
#include "ray_reconstruction_layout_asserts.h"
#include "dxvk_device.h"
#include "rtx_dlss.h"
#include "dxvk_scoped_annotation.h"
#include "rtx_ngx_wrapper.h"
#include "rtx_shader_manager.h"
#include "rtx_imgui.h"
#include "rtx_debug_view.h"

#include "rtx_matrix_helpers.h"

#include "rtx_ngx_wrapper.h"

#include "rtx_shaders/prepare_ray_reconstruction.h"
#include "rtx_shader_manager.h"
#include "rtx_ray_reconstruction.h"
#include "rtx_restir_gi_rayquery.h"

namespace dxvk {

  // Defined within an unnamed namespace to ensure unique definition across binary
  namespace {
    class PrepareRayReconstructionShader : public ManagedShader {
      SHADER_SOURCE(PrepareRayReconstructionShader, VK_SHADER_STAGE_COMPUTE_BIT, prepare_ray_reconstruction)

        BEGIN_REFLECTED_PARAMETER()
        CONSTANT_BUFFER(RAY_RECONSTRUCTION_CONSTANTS_INPUT)
        // Primary surface data
        TEXTURE2D(RAY_RECONSTRUCTION_PRIMARY_ATTENUATION_INPUT)
        TEXTURE2D(RAY_RECONSTRUCTION_PRIMARY_VIRTUAL_WORLD_SHADING_NORMAL_INPUT)
        TEXTURE2D(RAY_RECONSTRUCTION_PRIMARY_DISOCCLUSION_MASK_INPUT)
        // Secondary surface data
        TEXTURE2D(RAY_RECONSTRUCTION_SECONDARY_ALBEDO_INPUT)
        TEXTURE2D(RAY_RECONSTRUCTION_SECONDARY_SPECULAR_ALBEDO_INPUT)
        TEXTURE2D(RAY_RECONSTRUCTION_SECONDARY_ATTENUATION_INPUT)
        TEXTURE2D(RAY_RECONSTRUCTION_SECONDARY_VIRTUAL_WORLD_SHADING_NORMAL_INPUT)

        TEXTURE2D(RAY_RECONSTRUCTION_SHARED_FLAGS_INPUT)
        TEXTURE2D(RAY_RECONSTRUCTION_NORMALS_DLSSRR_INPUT)
        TEXTURE2D(RAY_RECONSTRUCTION_DEPTHS_INPUT)
        TEXTURE2D(RAY_RECONSTRUCTION_MOTION_VECTOR_INPUT)
        TEXTURE2D(RAY_RECONSTRUCTION_INPUT_COLOR_INPUT)

        RW_TEXTURE2D(RAY_RECONSTRUCTION_PRIMARY_ALBEDO_INPUT_OUTPUT)
        RW_TEXTURE2D(RAY_RECONSTRUCTION_PRIMARY_SPECULAR_ALBEDO_INPUT_OUTPUT)

        RW_TEXTURE2D(RAY_RECONSTRUCTION_DEBUG_VIEW_OUTPUT)
        RW_TEXTURE2D(RAY_RECONSTRUCTION_PRIMARY_DISOCCLUSION_MASK_OUTPUT)

        END_REFLECTED_PARAMETER(checkRayReconstructionSlots)
    };
  }

  // Combo box shared with dxvk_imgui.cpp
  extern RemixGui::ComboWithKey<DxvkRayReconstruction::RayReconstructionPreset> rayReconstructionPresetCombo;

  DxvkRayReconstruction::DxvkRayReconstruction(DxvkDevice* device)
    : DxvkDLSS(device)
    , m_prevPreset(preset()) {

    DxvkBufferCreateInfo info;
    info.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.stages = VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
    info.access = VK_ACCESS_TRANSFER_WRITE_BIT;
    info.size = sizeof(RayReconstructionArgs);
    m_constants = device->createBuffer(info, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, DxvkMemoryStats::Category::RTXBuffer, "DLSS-RR constant buffer");
  }

  void DxvkRayReconstruction::prewarmShaders(DxvkPipelineManager& pipelineManager) const {
    if (!RtxOptions::enableRayReconstruction()) {
      return;
    }

    PrepareRayReconstructionShader::getShader();
  }

  bool DxvkRayReconstruction::supportsRayReconstruction() const {
    return m_device->getCommon()->metaNGXContext().supportsRayReconstruction();
  }

  void DxvkRayReconstruction::release() {
    m_rayReconstructionContext = {};
    mRecreate = true;
  }
  
  void DxvkRayReconstruction::onDestroy() {
    release();
  }

  bool DxvkRayReconstruction::useRayReconstruction() const {
    return supportsRayReconstruction() && RtxOptions::isRayReconstructionEnabled();
  }

  bool DxvkRayReconstruction::isEnabled() const {
    return RtxOptions::isRayReconstructionEnabled();
  }

  void DxvkRayReconstruction::onDeactivation() {
    if (m_rayReconstructionContext && m_rayReconstructionContext->isRayReconstructionInitialized()) {
      m_device->waitForIdle();
    }
    release();
  }

  void DxvkRayReconstruction::dispatch(
      Rc<RtxContext> ctx,
      DxvkBarrierSet& barriers,
      DispatchResources resources,
      const DispatchConstants& frameConstants,
      const DispatchSettings& dispatchSettings) {
    ScopedGpuProfileZone(ctx, "Ray Reconstruction");
    ctx->setFramePassStage(RtxFramePassStage::DLSSRR);

    if (!useRayReconstruction()) {
      return;
    }

    bool dlssAutoExposure = true;
    mRecreate |= (mAutoExposure != dlssAutoExposure)
      || m_prevPreset != preset();
    mAutoExposure = dlssAutoExposure;
    m_prevPreset = preset();

    if (mRecreate) {
      initializeRayReconstruction(ctx);
      mRecreate = false;
    }

    SceneManager& sceneManager = device()->getCommon()->getSceneManager();
    DebugView& debugView = ctx->getDevice()->getCommon()->metaDebugView();

    const auto& primaryDisocclusionThresholdMix = resources.get<&RaytracingFrame::m_primaryDisocclusionThresholdMix>();
    const auto& primaryWorldShadingNormalDLSSRR = resources.get<&RaytracingFrame::m_primaryWorldShadingNormalDLSSRR>();
    const auto& compositeOutput = resources.get<&RaytracingFrame::m_compositeOutput>();
    const auto& primaryDisocclusionMaskForRR = resources.get<&RaytracingFrame::m_primaryDisocclusionMaskForRR>();

    // prepare DLSS-RR inputs
    VkExtent3D workgroups = util::computeBlockCount(frameConstants.compositeOutputExtent, VkExtent3D { 16, 16, 1 });

    // Geometry and material guides describe the same resolved primary PSR surface.
    const auto* motionVectorInput = &resources.get<&RaytracingFrame::m_primaryScreenSpaceMotionVector>();
    auto depthInput = &resources.get<&RaytracingFrame::m_primaryDepthDLSSRR>().resource(Resources::AccessType::Read);
    
    // The compacted GBuffer's albedo sources are compacted, so DLSS-RR reads the dense copies instead.
    const bool compactedGBuffer = frameConstants.sparseRenderingArgs.mode != SparseRenderingMode::Off;
    const Resources::Resource& diffuseAlbedoGuide = compactedGBuffer
      ? resources.get<&RaytracingFrame::m_primaryAlbedoDLSSRR>()
      : resources.get<&RaytracingFrame::m_primaryAlbedo>();
    const Resources::Resource& specularAlbedoGuide = compactedGBuffer
      ? resources.get<&RaytracingFrame::m_primarySpecularAlbedoDLSSRR>()
      : resources.get<&RaytracingFrame::m_primarySpecularAlbedo>().resource(Resources::AccessType::ReadWrite);

    // The compacted GBuffer writes the guides in their final form,
    // so this pass only has work for the disocclusion mask blur and debug views.
    const bool preparePassHasWork =
      !compactedGBuffer ||
      enableDisocclusionMaskBlur() ||
      frameConstants.debugView != DEBUG_VIEW_DISABLED;

    if (preparePassHasWork) {
      ScopedGpuProfileZone(ctx, "Prepare DLSS");

      RayReconstructionArgs constants = { };
      constants.enableRaytracedRenderTarget = frameConstants.enableRaytracedRenderTarget;
      constants.camera = sceneManager.getCamera().getShaderConstants();
      constants.debugView = frameConstants.debugView;
      constants.debugKnob = frameConstants.debugKnob;
      constants.enableDemodulateRoughness = demodulateRoughness() ? 1 : 0;
      constants.upscalerRoughnessDemodulationMultiplier = upscalerRoughnessDemodulationMultiplier();
      constants.upscalerRoughnessDemodulationOffset = upscalerRoughnessDemodulationOffset();
      constants.frameIdx = frameConstants.frameIdx;
      constants.enableDisocclusionMaskBlur = enableDisocclusionMaskBlur();
      constants.disocclusionMaskBlurRadius = disocclusionMaskBlurRadius();
      constants.rcpSquaredDisocclusionMaskBlurGaussianWeightSigma = 1.0f / (disocclusionMaskBlurNormalizedGaussianWeightSigma() * disocclusionMaskBlurNormalizedGaussianWeightSigma());
      constants.enableReSTIRGI = RtxOptions::useReSTIRGI();
      constants.sparseRenderingArgs = frameConstants.sparseRenderingArgs;

      ctx->updateBuffer(m_constants, 0, sizeof(constants), &constants);
      ctx->getCommandList()->trackResource<DxvkAccess::Read>(m_constants);

      // Inputs

      ctx->bindResourceBuffer(RAY_RECONSTRUCTION_CONSTANTS_INPUT, DxvkBufferSlice(m_constants, 0, m_constants->info().size));

      // Primary data
      ctx->bindResourceView(RAY_RECONSTRUCTION_PRIMARY_ATTENUATION_INPUT, resources.get<&RaytracingFrame::m_primaryAttenuation>().view, nullptr);
      ctx->bindResourceView(RAY_RECONSTRUCTION_PRIMARY_VIRTUAL_WORLD_SHADING_NORMAL_INPUT, resources.get<&RaytracingFrame::m_primaryVirtualWorldShadingNormalPerceptualRoughness>().view, nullptr);
      ctx->bindResourceView(RAY_RECONSTRUCTION_PRIMARY_DISOCCLUSION_MASK_INPUT, primaryDisocclusionThresholdMix.view, nullptr);

      // Secondary data
      ctx->bindResourceView(RAY_RECONSTRUCTION_SECONDARY_ALBEDO_INPUT, resources.get<&RaytracingFrame::m_secondaryAlbedo>().view, nullptr);
      ctx->bindResourceView(RAY_RECONSTRUCTION_SECONDARY_SPECULAR_ALBEDO_INPUT, resources.get<&RaytracingFrame::m_secondarySpecularAlbedo>().view(Resources::AccessType::Read), nullptr);
      ctx->bindResourceView(RAY_RECONSTRUCTION_SECONDARY_ATTENUATION_INPUT, resources.get<&RaytracingFrame::m_secondaryAttenuation>().view, nullptr);
      ctx->bindResourceView(RAY_RECONSTRUCTION_SECONDARY_VIRTUAL_WORLD_SHADING_NORMAL_INPUT, resources.get<&RaytracingFrame::m_secondaryVirtualWorldShadingNormalPerceptualRoughness>().view, nullptr);

      ctx->bindResourceView(RAY_RECONSTRUCTION_SHARED_FLAGS_INPUT, resources.get<&RaytracingFrame::m_sharedFlags>().view, nullptr);
      // DLSSRR data
      ctx->bindResourceView(RAY_RECONSTRUCTION_NORMALS_DLSSRR_INPUT, primaryWorldShadingNormalDLSSRR.view(Resources::AccessType::Read), nullptr);
      ctx->bindResourceView(RAY_RECONSTRUCTION_DEPTHS_INPUT, depthInput->view, nullptr);
      ctx->bindResourceView(RAY_RECONSTRUCTION_MOTION_VECTOR_INPUT, motionVectorInput->view, nullptr);
      // Color fed into DLSS-RR (composite output), for the input-color debug view.
      ctx->bindResourceView(RAY_RECONSTRUCTION_INPUT_COLOR_INPUT, compositeOutput.view(Resources::AccessType::Read), nullptr);

      // Inputs/Outputs

      ctx->bindResourceView(RAY_RECONSTRUCTION_PRIMARY_ALBEDO_INPUT_OUTPUT, diffuseAlbedoGuide.view, nullptr);
      ctx->bindResourceView(RAY_RECONSTRUCTION_PRIMARY_SPECULAR_ALBEDO_INPUT_OUTPUT, specularAlbedoGuide.view, nullptr);

      // Outputs

      ctx->bindResourceView(RAY_RECONSTRUCTION_DEBUG_VIEW_OUTPUT, debugView.getDebugOutput(), nullptr);
      // The disocclusion mask is left out when Ray Reconstruction will not read it.
      // See Resources::needsDisocclusionMaskForRR.
      const Rc<DxvkImageView> disocclusionMaskOutput = primaryDisocclusionMaskForRR.empty()
        ? nullptr
        : primaryDisocclusionMaskForRR.view(Resources::AccessType::Write);
      ctx->bindResourceView(RAY_RECONSTRUCTION_PRIMARY_DISOCCLUSION_MASK_OUTPUT, disocclusionMaskOutput, nullptr);

      ctx->bindShader(VK_SHADER_STAGE_COMPUTE_BIT, PrepareRayReconstructionShader::getShader());

      ctx->dispatch(workgroups.width, workgroups.height, workgroups.depth);
    }

    {
      // The DLSS y coordinate is pointing down
      float jitterOffset[2];
      RtCamera& camera = sceneManager.getCamera();
      camera.getJittering(jitterOffset);
      mMotionVectorScale = MotionVectorScale::Absolute;

      float motionVectorScale[2] = { 1.f, 1.f };

      NGXRayReconstructionContext::NGXBuffers buffers = {};
      buffers.pUnresolvedColor = &compositeOutput.resource(Resources::AccessType::Read);
      buffers.pResolvedColor = &resources.get<&RaytracingFrame::m_finalOutput>().resource(Resources::AccessType::Write);
      buffers.pMotionVectors = motionVectorInput;
      buffers.pDepth = depthInput;
      buffers.pDiffuseAlbedo = &diffuseAlbedoGuide;
      buffers.pSpecularAlbedo = &specularAlbedoGuide;
      buffers.pNormals = &primaryWorldShadingNormalDLSSRR.resource(Resources::AccessType::Read);
      buffers.pRoughness = &resources.get<&RaytracingFrame::m_primaryPerceptualRoughness>();
      buffers.pSpecularMotion = &resources.get<&RaytracingFrame::m_rayReconstructionSpecularMotion>();
      buffers.pDisocclusionMask = enableDisocclusionMaskBlur()
        ? &primaryDisocclusionMaskForRR.resource(Resources::AccessType::Read)
        : &primaryDisocclusionThresholdMix;

      // The same selected resources drive NGX evaluation, synchronization and lifetime tracking.
      const Resources::Resource* const pInputs[] = {
        buffers.pUnresolvedColor,
        buffers.pMotionVectors,
        buffers.pDepth,
        buffers.pDiffuseAlbedo,
        buffers.pNormals,
        buffers.pSpecularAlbedo,
        buffers.pRoughness,
        buffers.pSpecularMotion,
        buffers.pDisocclusionMask
      };

      const Rc<DxvkImageView> pOutputs[] = {
        buffers.pResolvedColor->view
      };

      for (const auto* pInput : pInputs) {
        if (pInput == nullptr || pInput->view == nullptr) {
          continue;
        }
        const auto& input = pInput->view;
        
        barriers.accessImage(
          input->image(),
          input->imageSubresources(),
          input->imageInfo().layout,
          input->imageInfo().stages,
          input->imageInfo().access,
          input->imageInfo().layout,
          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
          VK_ACCESS_SHADER_READ_BIT);

#ifdef REMIX_DEVELOPMENT
        ctx->cacheResourceAliasingImageView(input);
#endif
        ctx->getCommandList()->trackResource<DxvkAccess::None>(input);
        ctx->getCommandList()->trackResource<DxvkAccess::Read>(input->image());
      }

      for (const auto& output : pOutputs) {
        barriers.accessImage(
          output->image(),
          output->imageSubresources(),
          output->imageInfo().layout,
          output->imageInfo().stages,
          output->imageInfo().access,
          output->imageInfo().layout,
          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
          VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT);

#ifdef REMIX_DEVELOPMENT
        ctx->cacheResourceAliasingImageView(output);
#endif
      }

      barriers.recordCommands(ctx->getCommandList());


      // NGX creates this feature with hardware depth. The GBuffer supplies projected virtual depth.
      m_rayReconstructionContext->setWorldToViewMatrix(camera.getWorldToView());
      m_rayReconstructionContext->setViewToProjectionMatrix(camera.getViewToProjection());

      NGXRayReconstructionContext::NGXSettings settings;
      settings.resetAccumulation = dispatchSettings.resetHistory;
      settings.preExposure = mPreExposure;
      settings.jitterOffset[0] = jitterOffset[0];
      settings.jitterOffset[1] = jitterOffset[1];
      settings.motionVectorScale[0] = motionVectorScale[0];
      settings.motionVectorScale[1] = motionVectorScale[1];
      settings.frameTimeMilliseconds = dispatchSettings.frameTimeMilliseconds;

      {
        ScopedGpuProfileZone(ctx, "RR NGX Evaluate");
        m_rayReconstructionContext->evaluateRayReconstruction(ctx, buffers, settings);
      }

      for (const auto& output : pOutputs) {
        barriers.accessImage(
          output->image(),
          output->imageSubresources(),
          output->imageInfo().layout,
          VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
          VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
          output->imageInfo().layout,
          output->imageInfo().stages,
          output->imageInfo().access);

        ctx->getCommandList()->trackResource<DxvkAccess::None>(output);
        ctx->getCommandList()->trackResource<DxvkAccess::Write>(output->image());
      }
      barriers.recordCommands(ctx->getCommandList());
    }
  }

  void DxvkRayReconstruction::showRayReconstructionImguiSettings(bool showAdvancedSettings) {
    if (showAdvancedSettings) {
      bool presetChanged = RemixGui::Combo("DLSS-RR Preset", &pathTracerPresetObject(), "Default\0ReSTIR Finetuned\0");
      if (presetChanged) {
        RtxOptions::updatePathTracerPreset(pathTracerPreset());
      }

      constexpr ImGuiSliderFlags sliderFlags = ImGuiSliderFlags_AlwaysClamp;

      RemixGui::Checkbox("Estimate Specular Motion From Hit Distance", &useSpecularHitDistanceObject());
      RemixGui::Checkbox("Preserve Settings in Native Mode", &preserveSettingsInNativeModeObject());
      RemixGui::Checkbox("DLSS-RR Detail Enhancement", &enableDetailEnhancementObject());
      RemixGui::Checkbox("DLSS-RR Demodulate Roughness", &demodulateRoughnessObject());
      RemixGui::DragFloat("DLSS-RR Roughness Sensitivity", &upscalerRoughnessDemodulationOffsetObject(), 0.01f, 0.0f, 2.0f, "%.3f");
      RemixGui::DragFloat("DLSS-RR Roughness Multiplier", &upscalerRoughnessDemodulationMultiplierObject(), 0.01f, 0.0f, 20.0f, "%.3f");
      rayReconstructionPresetCombo.getKey(&presetObject());

      if (RemixGui::CollapsingHeader("Disocclusion Mask")) {
        ImGui::Indent();

        RemixGui::Checkbox("Blur", &enableDisocclusionMaskBlurObject());
        RemixGui::DragInt("Blur Radius", &disocclusionMaskBlurRadiusObject(), 1.f, 1, 64, "%d", sliderFlags);
        RemixGui::DragFloat("Blur Normalized Gaussian Weight Sigma", &disocclusionMaskBlurNormalizedGaussianWeightSigmaObject(), 0.01f, 0.0f, 3.0f, "%.3f", sliderFlags);
        RemixGui::Checkbox("Invalidate History for Animated Water", &invalidateHistoryForAnimatedWaterObject());

        ImGui::Unindent();
      }
    }
  }

  void DxvkRayReconstruction::setSettings(const uint32_t displaySize[2], const DLSSProfile profile, uint32_t outRenderSize[2]) {
    ScopedCpuProfileZone();
    // Handle the "auto" case, this is the param we'll pass to determine optimal resolution setting
    DLSSProfile actualProfile = profile;
    if (actualProfile == DLSSProfile::Auto) {
      actualProfile = getAutoProfile(displaySize[0], displaySize[1]);
    }

    if (mActualProfile == actualProfile && displaySize[0] == mDLSSOutputSize[0] && displaySize[1] == mDLSSOutputSize[1]) {
      // Nothing changed that would alter DLSS resolution(s), so return the last cached optimal render size
      outRenderSize[0] = mInputSize[0];
      outRenderSize[1] = mInputSize[1];
      return;
    }

    mActualProfile = actualProfile;

    // We need to force a recreation of resources before running DLSS.
    mRecreate = true;

    // Update our requested profile
    mProfile = profile;

    NVSDK_NGX_PerfQuality_Value perfQuality = profileToQuality<NVSDK_NGX_PerfQuality_Value>(mActualProfile);

    if (!m_rayReconstructionContext) {
      m_rayReconstructionContext = m_device->getCommon()->metaNGXContext().createRayReconstructionContext();
    }
    if (m_rayReconstructionContext) {
      const auto optimalSettings = m_rayReconstructionContext->queryOptimalSettings(displaySize, perfQuality);

      mInputSize[0] = outRenderSize[0] = optimalSettings.optimalRenderSize[0];
      mInputSize[1] = outRenderSize[1] = optimalSettings.optimalRenderSize[1];
    }

    mDLSSOutputSize[0] = displaySize[0];
    mDLSSOutputSize[1] = displaySize[1];

    // Note: Input size used for DLSS must be less than or equal to the desired output size. This is a requirement of the DLSS API currently.
    assert(mInputSize[0] <= mDLSSOutputSize[0] && mInputSize[1] <= mDLSSOutputSize[1]);
  }

  void DxvkRayReconstruction::initializeRayReconstruction(Rc<DxvkContext> renderContext) {
    if (!m_rayReconstructionContext) {
      m_rayReconstructionContext = m_device->getCommon()->metaNGXContext().createRayReconstructionContext();
    }

    NVSDK_NGX_PerfQuality_Value perfQuality = profileToQuality<NVSDK_NGX_PerfQuality_Value>(mActualProfile);

    if (m_rayReconstructionContext) {

      // RayReconstructionPreset enum values match the NGX preset enum, so a direct cast is valid.
      // Fall back to the default preset for any unexpected/out-of-range value.
      NVSDK_NGX_RayReconstruction_Hint_Render_Preset dlssdModel;
      switch (preset()) {
      case RayReconstructionPreset::D:
      case RayReconstructionPreset::E:
      case RayReconstructionPreset::F:
        dlssdModel = static_cast<NVSDK_NGX_RayReconstruction_Hint_Render_Preset>(preset());
        break;
      default:
        dlssdModel = NVSDK_NGX_RayReconstruction_Hint_Render_Preset_Default;
        break;
      }

      auto optimalSettings = m_rayReconstructionContext->queryOptimalSettings(mInputSize, perfQuality);

      m_rayReconstructionContext->initialize(renderContext, mInputSize, mDLSSOutputSize, mIsHDR, mInverseDepth, mAutoExposure, false, dlssdModel, perfQuality);
    }
  }
} // namespace dxvk
