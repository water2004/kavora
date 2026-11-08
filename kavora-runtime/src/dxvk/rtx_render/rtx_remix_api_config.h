/*
 * Copyright (c) 2026 RTX Remix Minecraft contributors.
 * SPDX-License-Identifier: MIT
 */
#pragma once

#include <remix/remix_c.h>

namespace dxvk::remixapi_config {

  // Layer operations run on the CS thread. The API serializes their enqueue order.
  remixapi_ErrorCode acquireLayer(const char* path, float priority, float threshold, remixapi_ConfigLayerHandle* handle);
  remixapi_ErrorCode setLayerBlending(remixapi_ConfigLayerHandle handle, float strength, float threshold);
  remixapi_ErrorCode releaseLayer(remixapi_ConfigLayerHandle handle);
  void releaseAllLayers();

  // Validation is safe on application threads; it never dereferences the opaque handle.
  bool validateLayer(remixapi_ConfigLayerHandle handle);
  remixapi_ErrorCode getResolved(remixapi_ConfigVariableResolvedInfo* info);
  remixapi_ErrorCode setVariable(const char* key, const char* value);

  // Called after configuration initialization and before the first option update.
  void applyPendingVariables();

}
