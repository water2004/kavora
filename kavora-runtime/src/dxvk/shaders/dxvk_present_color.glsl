// SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
// SPDX-License-Identifier: MIT

#include "dxvk_display_color.h"

vec3 presentDisplayColor(vec3 encoded, uvec2 pixel) {
  uint transfer = display_transfer & 255u;
  bool sourceLinear = (display_transfer & 1024u) != 0u;
  vec3 linear = displaySanitizeColor(sourceLinear ? encoded : displaySrgbDecode(encoded));
  vec3 outputColor;
  if (transfer == 2u) {
    bool sourceHdr = (display_transfer & 512u) != 0u;
    float sourceWhite = sourceHdr ? source_paper_white_nits : target_paper_white_nits;
    vec3 relative = linear * (sourceWhite / target_paper_white_nits);
    if (sourceHdr && source_peak_nits > target_peak_nits) {
      relative = displayHdrShoulder(relative, target_peak_nits / target_paper_white_nits);
    }
    vec3 nits = relative * target_paper_white_nits;
    float maximum = max(nits.r, max(nits.g, nits.b));
    nits *= min(1.0, target_peak_nits / max(maximum, 1e-6));
    outputColor = displayPqEncodeNits(displayRec709ToRec2020(nits));
  } else {
    if (transfer == 3u) {
      linear = displaySdrShoulder(linear, source_peak_nits / source_paper_white_nits);
    }
    outputColor = displaySrgbEncode(linear);
  }

  // Both the composed and hudless image use the same frame's quantization noise.
  uint noise = pixel.x * 1664525u + pixel.y * 1013904223u + display_frame_index * 747796405u;
  noise = (noise ^ (noise >> 16)) * 2246822519u;
  noise = (noise ^ (noise >> 13)) * 3266489917u;
  noise ^= noise >> 16;
  float dither = (float(noise & 65535u) / 65535.0 - 0.5) / (transfer == 2u ? 1023.0 : 255.0);
  outputColor = clamp(outputColor + dither, 0.0, 1.0);
  return (display_transfer & 256u) != 0u ? displaySrgbDecode(outputColor) : outputColor;
}
