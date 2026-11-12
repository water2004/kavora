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

    static Rc<DxvkShader> getGbufferShader() {
      return GET_SHADER_VARIANT(VK_SHADER_STAGE_ANY_HIT_BIT_KHR, OrderedCoverageAnyHitShader, ordered_coverage_gbuffer);
    }

    static Rc<DxvkShader> getPathShader(bool nrc, bool sharcUpdate, bool sharcQuery, bool nee) {
#define COVERAGE_PATH_VARIANT(name) \
      return nee ? GET_SHADER_VARIANT(VK_SHADER_STAGE_ANY_HIT_BIT_KHR, OrderedCoverageAnyHitShader, name##_nee) \
                 : GET_SHADER_VARIANT(VK_SHADER_STAGE_ANY_HIT_BIT_KHR, OrderedCoverageAnyHitShader, name)
      if (nrc) {
        COVERAGE_PATH_VARIANT(ordered_coverage_path_nrc);
      }
      if (sharcUpdate && sharcQuery) {
        COVERAGE_PATH_VARIANT(ordered_coverage_path_sharc_combined);
      }
      if (sharcUpdate) {
        COVERAGE_PATH_VARIANT(ordered_coverage_path_sharc_update);
      }
      if (sharcQuery) {
        COVERAGE_PATH_VARIANT(ordered_coverage_path_sharc_query);
      }
      COVERAGE_PATH_VARIANT(ordered_coverage_path);
#undef COVERAGE_PATH_VARIANT
    }
  };
}
