/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include "rtx_shader_manager.h"
#include <rtx_shaders/voxel_coverage.h>

namespace dxvk {
  class VoxelCoverageAnyHitShader : public ManagedShader {
    BEGIN_PARAMETER()
    END_PARAMETER()

  public:
    static Rc<DxvkShader> getShader() {
      return GET_SHADER_VARIANT(VK_SHADER_STAGE_ANY_HIT_BIT_KHR, VoxelCoverageAnyHitShader, voxel_coverage);
    }
  };
}
