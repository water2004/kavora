// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
#pragma once

#include "dxvk_buffer.h"

namespace dxvk {

  class DxvkContext;

  class CloudNeuralModel {
  public:
    const Rc<DxvkBuffer>& weights(DxvkContext& ctx);

  private:
    Rc<DxvkBuffer> m_weights;
  };

}
