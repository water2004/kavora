/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include "rtx_shader_manager.h"
#include <rtx_shaders/ordered_coverage_variants.h>

namespace dxvk {
  class OrderedCoverageAnyHitShader : public ManagedShader {
    BEGIN_PARAMETER()
    END_PARAMETER()
    BINDLESS_ENABLED()

  public:
    static Rc<DxvkShader> getTraversalShader() {
      return GET_SHADER_VARIANT(VK_SHADER_STAGE_ANY_HIT_BIT_KHR, OrderedCoverageAnyHitShader, ordered_coverage_traversal);
    }

    static Rc<DxvkShader> getPathShader(bool nrc, bool sharc) {
      if (nrc) {
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_ANY_HIT_BIT_KHR, OrderedCoverageAnyHitShader, ordered_coverage_path_nrc_nee);
      }
      if (sharc) {
        return GET_SHADER_VARIANT(VK_SHADER_STAGE_ANY_HIT_BIT_KHR, OrderedCoverageAnyHitShader, ordered_coverage_path_sharc_combined_nee);
      }
      return GET_SHADER_VARIANT(VK_SHADER_STAGE_ANY_HIT_BIT_KHR, OrderedCoverageAnyHitShader, ordered_coverage_path_nee);
    }
  };
}
