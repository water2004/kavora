#version 450

// NV-DXVK start: Share the display transfer functions.
#extension GL_GOOGLE_include_directive: require
// NV-DXVK end

layout(constant_id = 1) const bool s_gamma_bound = true;

layout(binding = 0) uniform sampler2D s_image;
layout(binding = 1) uniform sampler1D s_gamma;

layout(location = 0) in  vec2 i_coord;
layout(location = 0) out vec4 o_color;
layout(location = 1) out float o_ui_alpha;
layout(constant_id = 1226) const bool s_ui_coverage = false; // FirstPipelineConstant + 1

layout(push_constant)
uniform present_info_t {
  ivec2 src_offset;
  uvec2 src_extent;
  // NV-DXVK start: Host framebuffer orientation.
  uint bottom_up;
  uint source_height;
  // NV-DXVK end
  // NV-DXVK start: The output encoding is fixed for this presentation.
  uint display_transfer;
  uint display_frame_index;
  float source_paper_white_nits;
  float source_peak_nits;
  float target_paper_white_nits;
  float target_peak_nits;
  // NV-DXVK end

};

// NV-DXVK start: The final pass owns display encoding.
#include "dxvk_present_color.glsl"
// NV-DXVK end

void main() {
  // NV-DXVK start: Host framebuffer orientation.
  vec2 uv = bottom_up != 0 ? vec2(i_coord.x, 1.0f - i_coord.y) : i_coord;
  vec2 coord = vec2(src_offset) + vec2(src_extent) * uv;
  // NV-DXVK end
  o_color = textureLod(s_image, coord, 0.0f);
  o_ui_alpha = o_color.a;
  if (s_ui_coverage) o_color.a = 1.0;
  
  // NV-DXVK start: Legacy gamma ramps do not apply to explicit display encoding.
  if ((display_transfer & 255u) != 0u) {
    o_color.rgb = presentDisplayColor(o_color.rgb, uvec2(gl_FragCoord.xy));
  } else if (s_gamma_bound) {
  // NV-DXVK end
    o_color = vec4(
      texture(s_gamma, o_color.r).r,
      texture(s_gamma, o_color.g).g,
      texture(s_gamma, o_color.b).b,
      o_color.a);
  }
}
