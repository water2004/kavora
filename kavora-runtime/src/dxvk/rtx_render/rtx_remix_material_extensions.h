/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
 * SPDX-License-Identifier: Apache-2.0
 */
#pragma once

#include <remix/remix_c.h>
#include "rtx_remix_pnext.h"

namespace dxvk {

  inline bool validGlintMaterial(const remixapi_MaterialInfo* info) {
    const auto* glint = pnext::find<remixapi_MaterialInfoGlintEXT>(info);
    return !glint || (glint->texture && glint->mode >= REMIXAPI_GLINT_MODE_ITEM &&
      glint->mode <= REMIXAPI_GLINT_MODE_ARMOR && pnext::find<remixapi_MaterialInfoOpaqueEXT>(info) &&
      !pnext::find<remixapi_MaterialInfoEndPortalEXT>(info));
  }

}
