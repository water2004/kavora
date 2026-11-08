#version 450

layout(constant_id = 1) const bool s_gamma_bound = true;

layout(binding = 0) uniform sampler2D s_image;
layout(binding = 1) uniform sampler1D s_gamma;

layout(location = 0) out vec4 o_color;

layout(push_constant)
uniform present_info_t {
  ivec2 src_offset;
  ivec2 dst_offset;
  // NV-DXVK start: Host framebuffer orientation.
  uint bottom_up;
  uint source_height;
  // NV-DXVK end
};

void main() {
  ivec2 coord = ivec2(gl_FragCoord.xy) + src_offset - dst_offset;
  // NV-DXVK start: Flip texel coordinates without filtering adjacent pixels.
  if (bottom_up != 0) {
    coord.y = src_offset.y + int(source_height) - 1 - (int(gl_FragCoord.y) - dst_offset.y);
  }
  // NV-DXVK end
  o_color = texelFetch(s_image, coord, 0);
  
  if (s_gamma_bound) {
    o_color = vec4(
      texture(s_gamma, o_color.r).r,
      texture(s_gamma, o_color.g).g,
      texture(s_gamma, o_color.b).b,
      o_color.a);
  }
}
