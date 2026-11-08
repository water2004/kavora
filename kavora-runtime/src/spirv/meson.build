spirv_src = files([
  'spirv_code_buffer.cpp',
  'spirv_compression.cpp',
  'spirv_module.cpp',
  # NV-DXVK start: Shader resource interface reflection
  'spirv_resource_interface.h',
  # NV-DXVK end
])

spirv_lib = static_library('spirv', spirv_src,
  include_directories : [ dxvk_include_path ],
  override_options    : ['cpp_std='+dxvk_cpp_std])
