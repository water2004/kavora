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
#include "rtx_resources.h"
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
    void dispatchPrimaryTraversal(class RtxContext* ctx, const Resources::RaytracingOutput& rtOutput);
    void dispatch(class RtxContext* ctx, const Resources::RaytracingOutput& rtOutput);

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
