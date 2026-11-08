/* SPDX-License-Identifier: MIT */
#pragma once
#include "../../utility/shader_types.h"

#define TEXTURE_MIP_SOURCE 0
#define TEXTURE_MIP_OUTPUT 1
#define TEXTURE_MIP_TILES 2

struct TextureMipArgs {
  uint sourceWidth;
  uint sourceHeight;
  uint targetWidth;
  uint targetHeight;
  uint firstTile;
  uint tileCount;
  uint groupsX;
  uint format;
  uint purpose;
};
