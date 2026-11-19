// SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
// SPDX-License-Identifier: MIT
#pragma once

#include "dxvk_buffer.h"

namespace dxvk {

  class DxvkContext;

  class CloudNeuralModel {
  public:
    // Prepares the device-native weight layout once for cooperative-vector inference.
    bool prepareTensorWeights(DxvkContext& ctx);
    const Rc<DxvkBuffer>& weights(DxvkContext& ctx, bool tensor);

  private:
    Rc<DxvkBuffer> m_scalarWeights;
    Rc<DxvkBuffer> m_tensorWeights;
    bool m_tensorConversionFailed = false;
  };

}
