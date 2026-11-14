# RTX Remix Shader Overview

This document provides an overview for the general structure, standards and conventions in the shader codebase for the RTX Remix project. Note that originally this codebase used GLSL primarily but has since switched to [Slang](https://github.com/shader-slang/slang) which provides back-compatibility in terms of syntax. In the future more of the codebase will be written to leverage Slang syntax, but much of it still is designed around its GLSL origins for now.

## File Conventions

### Ray tracing constant buffer

`pass/raytrace_args.h` defines `RaytraceArgs` for the host and for the shader's
`ConstantBuffer<RaytraceArgs>`. The host writes it in
`RtxContext::updateRaytraceArgsConstantBuffer` before recording the scene.
Use explicit-width storage and preserve the CPU/GPU field layout when adding
parameters. Each pass keeps its descriptor indices alongside its bindings.

Meson reflects `RaytraceArgs` through a build-only probe. Composite, its alpha
pass, Ray Reconstruction preparation, dome composition, night sky and display
color reflect their production entry points. The checks use the production
scalar-layout and matrix options and emit C++ assertions for sizes, offsets and
array strides. Reflected descriptor checks compare the slots, Vulkan types,
image view dimensions and descriptor counts with the C++ pipeline declarations.
The dome variants share the union of their slots. Composite and its alpha pass
may omit slots their entry point never uses. Resource encodings, access modes
and history lifetime belong to each pass's contract.

### Visual effect boundaries

Keep the entry point responsible for descriptor bindings, dispatch coordinates,
and output writes. Put an effect's calculations behind named inputs and outputs.
Use a Slang `module` and `import` for calculations with independent inputs.
Keep each resource's slot, access mode, coordinate space and
encoding when changing the calculation. Use `[ForceInline]` on helpers whose
boundary must disappear in the release shader, and compare the emitted SPIR-V
when moving work across that boundary.

Keep textual includes for dual C++/Slang layout headers, descriptor bindings
and shader variants that use caller-defined preprocessor symbols. For a shared
parameter type used by several modules, import the module that owns its Slang
declaration so every consumer uses the same type. The host still reads the
layout from the corresponding C++ header.

`material_effects.slang` expands a list of material effects at compile time.
`opaque_surface_material_interaction.slangh` lists the parameter modifiers
before BSDF construction and composes replacement emission before additive
emission. Both emission stages specialize against typed bindless accessors.

`water_surface.slang` owns the wave, slope and refracted-ray offset calculations.
`gbuffer_water.slangh` adapts the G-buffer variants' bindings and ray data to
its interface. `gbuffer_water_bindings.h` declares the shared water image slots.
`water_surface_args.h` declares the shared constant-buffer block written by
the simulation and used by the Slang water module. The layout reflection checks
the size and every member offset of that block.

`common_bindings.slangh` owns shared descriptor declarations. The G-buffer
entries include the implementation for the stage they dispatch.

| G-buffer stage | Owner | Inputs and outputs |
| --- | --- | --- |
| Primary resolve | [`gbuffer_primary.slangh`](pass/gbuffer/gbuffer_primary.slangh) | Camera ray and first intersection in. First-hit material, guides and PSR candidates out. |
| Shared hit resolution | [`gbuffer_segment.slangh`](pass/gbuffer/gbuffer_segment.slangh) | Ray state and nearest candidate in. Runs unordered resolve, then evaluates the ordered hit and its material. Returns the hit and segment contributions for the caller to accumulate. |
| Shared surface shading | [`gbuffer_shading_cache.slangh`](pass/gbuffer/gbuffer_shading_cache.slangh) | Surface material, layer weight and attenuation in. Surface radiance and optional NRC or SHaRC updates out. |
| Decals | [`gbuffer_decals.slangh`](pass/gbuffer/gbuffer_decals.slangh) | Ray cone and first-hit geometry in. Blended material out. The primary pass can call the same decal operations before publishing the first hit. |
| PSR setup | [`gbuffer_psr_prepare.slangh`](pass/gbuffer/gbuffer_psr_prepare.slangh) | First-hit material and guides in. Reflection and transmission seeds out. |
| PSR resolve | [`gbuffer_psr.slangh`](pass/gbuffer/gbuffer_psr.slangh) | A reflection or transmission seed in. Resolved optical surface and guides out. The entry manages its work queue. |

[`gbuffer_storage.slangh`](pass/gbuffer/gbuffer_storage.slangh) maps screen
pixels to compacted slots and owns shared buffer encodings.
[`gbuffer_output.slangh`](pass/gbuffer/gbuffer_output.slangh) publishes the hit
or miss and calls the [motion projection module](pass/gbuffer/gbuffer_motion.slang).
`ray_resolve.slang` owns the traversal loop, including queued Primary
continuations. Each pass specializes its tracing and hit policy at compile
time. `gbuffer_queue.slangh` owns wave-compacted enqueue and the shared half
encoding. The Primary and PSR queues own their record layouts and lifetimes.
`gbuffer_optical_ray.slangh` builds transmission rays for all PSR stages.
It also publishes the same packed reflection and transmission seeds for inline
sampling and the separate preparation pass.
[`resolve_ordered.slangh`](algorithm/resolve_ordered.slangh) decides whether
the nearest hit ends that loop or advances the ray.
[`resolve_unordered.slangh`](algorithm/resolve_unordered.slangh) accumulates
particles and selects decals along a ray segment. Visibility shares the
[portal transition rules](algorithm/resolve_portal.slangh) directly.

Indirect lighting is organized in [`pass/integrate`](pass/integrate).
[`indirect_path.slangh`](pass/integrate/indirect_path.slangh) starts the path
from the G-buffer, drives the traversal loop and publishes its radiance.
Ray Query and TraceRay both call [`indirect_vertex.slangh`](pass/integrate/indirect_vertex.slangh)
to integrate a hit. NRC, SHaRC, ReSTIR GI and NEE each own the state and
resource accesses specific to their integration with the path.

The pinned RTXDI volumetrics headers include the local camera, froxel and
spherical-harmonics headers. The camera header imports the same camera module
as Kavora's passes.

| Effect | Calculation owner | Contract |
| --- | --- | --- |
| Final color grading | [`display_color_grade.slang`](pass/srgb_dither/display_color_grade.slang) | Linear post-tonemap RGB and grading values in. Linear graded RGB out. The entry point owns sRGB conversion and dither. |
| Cloud shape and motion | [`cloud_density.slang`](utility/cloud_density.slang) | World position, selected cloud parameters, and weather, base, and detail samplers yield density. The same density feeds view rays, light visibility and the sky map. |
| Cloud sky contribution | [`cloud_sky_transport.slang`](pass/clouds/cloud_sky_transport.slang) | Extinction, step length, albedo and incident radiance update the accumulated radiance and transmittance. The entry point owns the ray grid and termination. |
| Cloud light and visibility | [`clouds.slang`](algorithm/clouds.slang) | Cloud parameters, a world-space ray and supplied cloud samplers yield transmittance or incident radiance. |
| Cloud sky map | [`cloud_sky.slang`](algorithm/cloud_sky.slang) | Converts world-space directions to sky-map coordinates and combines supplied cloud radiance and transmittance with the background. |
| Fog cache sampling | [`fog_radiance_cache.slang`](algorithm/fog_radiance_cache.slang) | Volume cameras, depth parameters and supplied radiance volumes yield cached fog radiance. |
| Fog and ground mist | [`fog_medium.slang`](algorithm/fog_medium.slang) | World-space segments, medium parameters and a supplied noise texture yield extinction and scattering. The SDK adapter supplies heterogeneous base density. |
| Atmosphere integration | [`atmosphere.slang`](algorithm/atmosphere.slang) | Integrates fog, clouds and medium extinction along a clipped ray. Receives samplers explicitly and specializes its fog provider at compile time. |
| Stars, galaxy and meteors | [`night_sky.slang`](pass/dome_composite/night_sky.slang) | Night-sky parameters, star and galaxy maps, and the view direction with neighboring directions yield linear radiance. The dome compositor owns layer composition. |
| End sky and planet | [`end_sky.slang`](pass/dome_composite/end_sky.slang) | End-sky parameters, background map and world direction yield linear radiance for the dome layer. |
| Visible moon | [`lunar_surface.slang`](pass/dome_composite/lunar_surface.slang) | Projected disc coordinates, gradients, texture and phase yield the disc's radiance and coverage. Scene illumination comes from the directional light. |
| Water normal motion | [`water_normal_coordinates.slang`](concept/surface_material/water_normal_coordinates.slang) | Sprite coordinates, time and motion or a texture transform produce the UVs for the material's existing normal layers. The material owns sampling, LOD and normal blending. |
| Water simulation at a surface | [`water_surface.slang`](concept/surface_material/water_surface.slang) | Simulation textures, settings and footprint produce wave height and slope. The transmission helper takes the scene acceleration structure for the shore test. |
| Portal and gateway emission | [`end_portal_emission_effect.slang`](concept/surface_material/end_portal_emission_effect.slang) | Selects a layer count from the material and evaluates star-layer radiance from the supplied camera and texture accessors. |
| Enchantment glint | [`surface_glint_effect.slang`](concept/surface_material/surface_glint_effect.slang) | Texture, UVs, gradients, material color and animation settings produce additive radiance. |
| Visibility through portals and thin media | [`visibility_material.slang`](algorithm/visibility_material.slang) | Ray and portal geometry yield intersection coordinates. Thin-sheet thickness, transmittance color and ray geometry yield attenuation. |
| Reconstruction guides | [`gbuffer_guide_math.slang`](pass/gbuffer/gbuffer_guide_math.slang) | Evaluates guide roughness, opacity and reflectivity from material values. The G-buffer output code owns resource writes and feature variants. |
| Parallax and relief visibility | [`parallax_occlusion.slang`](concept/surface_material/parallax_occlusion.slang) | The height texture, sampler, tangent-space ray, atlas bounds and parallax settings determine the displacement or visibility. The material owns texture indexing. |
| Opaque lobe selection | [`opaque_lobe_sampling.slang`](concept/surface_material/opaque_lobe_sampling.slang) | Material properties, view direction and sampling thresholds determine lobe probabilities and the selected lobe. The integrator owns random-number consumption. |
| Opaque BSDF | [`opaque_bsdf.slang`](concept/surface_material/opaque_bsdf.slang) | Decoded material, view and light directions, and supplied random samples determine throughput, PDFs and PSR. Thin film is selected at compile time. [`subsurface_material.slang`](concept/surface_material/subsurface_material.slang) owns subsurface decoding and classification. |
| Translucent interface optics | [`material_optics.slang`](utility/material_optics.slang) | Material thickness, relative IOR, color and ray geometry determine attenuation and reflectivity. BSDF and PSR share this calculation. [`translucent_interface_optics.slang`](concept/surface_material/translucent_interface_optics.slang) resolves the boundary from the material, incident medium and material buffer. |
| Thick-medium absorption | [`translucent_medium.slang`](concept/surface_material/translucent_medium.slang) | The current medium state and material buffer yield RGB extinction per world unit. The ray segment applies distance and keeps track of its current medium. |
| Reflection and transmission continuation | [`translucent_bsdf.slang`](concept/surface_material/translucent_bsdf.slang), [`translucent_psr.slang`](concept/surface_material/translucent_psr.slang) | A decoded material, view direction, medium boundary and sampling settings determine direction and throughput. PSR also receives its disocclusion settings and produces reconstruction guides. |
| Resolve attenuation and opacity | [`resolve_medium.slangh`](algorithm/resolve_medium.slangh), [`resolve_surface_approximations.slangh`](algorithm/resolve_surface_approximations.slangh) | Segment transport updates throughput and radiance once. Surface approximations report whether a hit contributed without becoming the integration surface. The resolver advances traversal. |

To change an effect with the same data, edit its calculation owner. For
example, a display tint fits in `gradeDisplayColor` and runs in the existing
output dispatch. If it needs a new uniform, declare it in `SRGBDitherArgs` and
fill it in `DxvkSRGBDither::dispatch`. Only effects that need a different image,
history or execution point require a change to the pass contract and its caller.

Rebuild affected variants with `meson compile -C _BuildMinecraft rtx_shaders`.
When comparing code generation, use the same compiler, variant and build
options. Check calls, branches, reads and writes in addition to instruction
count. Water and glass share the translucent optics contracts across all
consumers, including camera, secondary and visibility rays.

### Source-Level GPU Profiling

Slang debug builds embed `NonSemantic.Shader.DebugInfo.100` variable information
with `-g2`. Release profiling uses `-g1 -debug-info-include-source` for source lines
without the full variable tracking. To profile specific variants in a release
build, set `RTX_SLANG_DEBUG_VARIANTS` to their exact,
comma-separated names before running `meson compile`. For example:

```powershell
$env:RTX_SLANG_DEBUG_VARIANTS = 'gbuffer_psr_queue_shade_rr_psr_raygen_ser_sharc_wboit'
meson compile -C _CompMinecraftRelease_x64 d3d9
```

The shader compiler also accepts repeated `-slang-debug-variant <name>` arguments.
Unknown names fail the build. The selected variants and compiler commands are
recorded in the build output and shader dependency cache. Compare candidates with
the same debug variants and compiler settings throughout an experiment. Clear the
environment variable and rebuild for final release-performance verification and
packaging.

Capture the resulting runtime with Nsight Graphics shader profiling enabled and
verify that the recorded executable exposes source files and line counters. Source
inlining and optimization can make line attribution incomplete. Keep the runtime
hash and exact shader binaries with the capture. Even at the same optimization
level, debug information can retain operations that the compiler otherwise removes.
Neither debug level establishes identical driver code generation. Do not compare a debug candidate's
timing against a release baseline; confirm accepted improvements in the normal
release build too.

Set `RTX_PIPELINE_STATISTICS=1` to log driver executable statistics. With it,
`RTX_PIPELINE_REPRESENTATION_DUMP_PATH` names a directory for the driver's optional
compiled representations. Logs identify each file by shader key, pipeline handle,
executable index and representation index. The driver may return no executables
or representations. Vulkan capture flags preserve the final compiled pipeline,
but can increase pipeline creation time or disable compilation caching. Leave
the dump path unset for ordinary profiling runs.

GBuffer entry-point feature matrices explicitly select `GBUFFER_FEATURE_DEBUG_VIEW`.
Shared surface and material helpers use `shaderDebugView` so a debug-off variant
can discard diagnostic work. Entries without that feature contract retain the
configured debug view. Sampler feedback remains active independently of diagnostics.

### Naming

Files and folders should use `lower_snake_case` for their names and generally should be named the same as the primary type they contain or functionality they provide. For example a file named `surface_material.slangh` should contain the implementation of the `SurfaceMaterial` type.

For files which contain various shared helper functions related to another file or set of files, the `_helpers` suffix is also common. Similarly `_bindings` and `_binding_indices` are also common suffixes in pass-related files for holding information about bindings, and `_args` for constant buffer parameter structures. Generally patterns like these should be followed when apparent for consistency.

### Extensions

The following extensions are used in most of the project for general purpose files declaring/defining types and functions in an organized manner.

- `*.h` - Shared layouts, constants and bindings. C++ and Slang can compile the same declaration for a CPU/GPU layout. Keep types private to a Slang module in that module.
- `*.slangh` - Textual shader includes. Use them where the caller's preprocessor definitions, shared descriptor declarations or an SDK include contract must be visible.

The stage suffixes identify pass entry points. A plain `.slang` file can be a
pass entry point or an imported module. The shader build compiles imported
modules through their entry points.

- `*.slang` - Library modules and entry points that select a shader stage through variants, such as compute for ray query and ray generation for trace ray.
- `*.comp.slang` - Compute shader files.
- `*.rgen.slang` - Ray generation shader files.
- `*.rchit.slang` - Ray closest hit shader files.
- `*.rahit.slang` - Ray any hit shader files.
- `*.rmiss.slang` - Ray miss shader files.

### Organization

All RTX Remix shaders are to be put in the `rtx` shader folder, with the following subfolders utilized as applicable based on the Architecture section below:

- `external` - External pre-existing frameworks or helper files, typically with a differing license from the rest of RTX Remix.
- `utility` - General helper functionality for common operations. Examples include packing, texture sampling, random number generation and math.
- `concept` - Concept files for major organizational units in the codebase. Examples include surfaces, materials, lights and rays.
- `algorithm` - Algorithm files for major rendering logic.
- `pass` - Shader files for entry points into various rendering passes.

### Structure

Generally a file's structure should follow these guidelines, though they are not a strict requirement as some files may have unique requirements which may break from this pattern. Comments can also be used in larger files to help break up sections of the file into logical organizational parts. For example, breaking up a `*.slangh` file that implements member functions for multiple structures can group each set of functions and separate them with a comment indicating which section applies to which type.

#### `*.h` Files

```glsl
// [License]

#pragma once

// Constants

static const uint exampleConstant = 0u;

// Structures

struct Example
{
  uint foo;
};
```

#### `*.slangh` Files

```glsl
// [License]

#pragma once

// Includes

// Note: Respective *.h file must be first include to define required structures before including helpers
#include "rtx/concept/example.h"
#include "rtx/utility/helper.slangh"

// Helper Structs and Helper/Member Functions

struct InputData
{
  uint bar;
};

Example exampleCreate(InputStruct inputData)
{
  Example example;

  example.foo = helperFunc(inputData.bar);

  return example;
}
```

#### `*.slang` Files

```glsl
// [License]

// Special Variant Comments (If present)

//!variant foo.comp.slang
//!>       SOME_DEFINE
//!>       ANOTHER_DEFINE

//!end-variants

// Variant Matrix Comments (For cross-product variants)

//!variant-matrix foo
//!> common SOME_DEFINE
//!> axis mode raster.comp
//!> axis mode raytrace.rgen RAY_PIPELINE
//!> axis feature -
//!> axis feature feature_enabled ENABLE_FEATURE=1
//!> order mode feature
//!end-variants

// This also emits rtx_shaders/foo_variants.h with axis constants and filtered X-macros.

// Includes

// Note: It is common to put defines in pass files like this to define behavior which should
// apply to everything included below (similar to how the variant defines also are used for this).
#define ENABLE_SOME_FEATURE 1

// Note: Binding files must be included first to provide bindings for subsequent includes
#include "rtx/pass/foo/binding_indices.h"
#include "rtx/pass/foo/bindings.h"
#include "rtx/concept/example.slangh"

// Additional Buffers (Anything not included in the bindings if needed)

// Note: Format should match what is defined on the CPU side.
layout(r16ui, binding = SOME_BINDING_INPUT)
RWTexture2D<uint> SomeBinding;

// Compute Implementation

[shader("compute")]
[numthreads(16, 8, 1)]
void main(uvec2 threadID : SV_DispatchThreadID)
{
  // Implementation
}
```

## Programming Conventions

### Style

- 2 space indentation (Not 2 space wide tabs).
- lowerCamelCase for constant, function, parameter and variable names.
- UpperCamelCase for structure names and buffers.
- UPPER\_SNAKE\_CASE for macro names.
- Next-line braces for functions, conditionals (including single line then/else statements), loops, etc.
- Additionally, braces should always be added, even when optional (no one-line conditionals/loops).
- New lines should be used to separate code for better readability, for example after a block or after a comment or other significant logical code section.
- Spaces should surround binary operators on both sides, but should not be added around parentheses (be it in an expression, function call or otherwise).
- Delimiter commas (function calls or parameter lists) or semicolons (in for loops) should be followed by a space (or a new line if needed to split across lines).
- Lines should be kept at a reasonable length (100-120 ish characters max typically). Excessively long lines should be split up with newlines.
- Splitting across lines should be done ideally at delimiters or other logical points, for example after each item in a function call, after a bitwise or boolean and/or in a chain of expressions, or even at "important" binary math operators.
- An empty space should be between if/for/while/switch and (.
- Header files should be guarded with #pragma once.
A combined example of these stylistic choices in use is as follows:

```glsl
#pragma once

struct Foo
{
  uint fooBarBaz;

  uint16_t anotherFoo;
  float16_t anotherBaz;
};

// An example function, takes in a Foo object `foo` and a boolean `test` to
// do whatever with.
vec3 exampleHelper(Foo foo, bool test, inout bool specialFlag)
{
  // Return early if test is set

  if (test)
  {
    return vec3(0.0f, 0.0f, 0.0f);
  }

  // Do some calculations conditionally

  int loopCounter = 2;
  while (loopCounter-- > 0)
  {
    if (foo.anotherFoo > someConstant)
    {
      switch (foo.anotherFoo)
      {
      // Note: Fallthrough to Apple type.
      default:
      case anotherFooTypeApple:
        someExcessivelyLongFunctionNameForDemonstrationPurposes(
          foo.anotherBaz + 1.0h,
          foo.anotherBaz / 2.0h,
          foo.anotherBaz * 5.0h);

        break;
      case anotherFooTypeOrange:
        if (
          (foo.fooBarBaz == 26 && foo.anotherBaz == 2.0h) ||
          foo.fooBarBaz == 5
        )
        {
          const uint someScalar = uint(foo.anotherFoo) * foo.fooBarBaz;

          specialFlag = true;

          return vec3(float(someScalar));
        }

        break;
      }
    }
  }

  // Perform the sum operation

  float counter = 0.0f;

  for (uint i = 0u; i < foo.fooBarBaz; ++i)
  {
    counter += float(i) + 2.0f;
  }

  return vec3(counter, counter, counter);
}
```

### Naming

#### Structures

Structures should be named as some sort of noun describing what it is. An example is `Camera`, a type which contains data pertaining to a scene's camera.

#### Functions

Functions take two forms generally, "member" functions (conceptually part of a structure), and helper or other general functions. Below are a few common conventions:

- Member functions should take the naming form `[type][Function Name]`, for example `cameraSetMatrix` for a function which sets the matrix of a `Camera` object.
- Constuctors are a special kind of member function which always take the form `[type]Create*` and return the type they are constructing, for example `cameraCreate` to construct a `Camera` object. Note there may be more than one constructor in which case additional information can be provided, such as `rayCreateDirection` or `rayCreatePosition`.
- Functions acting as members which should not be called externally should be suffixed with `*Internal`, for example `rayCreateInternal`.
- These general "member" function conventions may change in the future when Slang's member function syntax is used more commonly.
- General additional function conventions are `calc*` or `eval*` for computing some quantity, or `*Helper` for a helper member function.

#### Enums

"Enum" value constants should contain their respective type name for clarity of which enum they belong to in the scheme `[type][Enum Value]`. For example `lightTypeSphere` for the `Sphere` value in the hypothetical enum type `LightType`. This may change once Slang enums are properly integrated into the codebase.

### Types

- Currently GLSL types are preferred over the HLSL types which also work in Slang, for example `vec3` should be used over `float3`. If a file is already predominantly using HLSL types however it is fine to keep consistent in such cases. This may change in the future if Slang is fully adopted into the codebase.
- Use "default" GLSL types where applicable, such as `vec3` or `uvec3` instead of `f32vec3` and `u32vec3` (as they mean the same thing).
- Prefer 16 bit types where possible such as `float16_t`, `uint16_t`, `f16vec3` or `u16vec3` as these will help reduce register pressure and increase compute performance.
- 16 bit floating point types should be used for values with small ranges such as normalized values (0 to 1, -1 to 1, etc) or direction/normal vectors. Note the maximum 16 bit float value is 65504 and the smallest non-denormalized value is around 0.00006, so values that will never exceed these orders of magnitude are generally fine, as long as the quantization from the reduced precision is acceptable (generally is for something like a color or a direction).
- 32 bit floating point types should be used for values with large ranges such as positions, distances, or anything that requires a high degree of precision.
- Never use 64 bit types, such operations are extremely slow on current consumer GPU hardware.

### Misc

- Comment code as needed, though typically comments starting with `// Note:` or `// Todo:` can help highlight a specific choice, clarify confusing code or mark something that needs to be done still. Additionally, placing a comment above a function to explain its usage or expectations for parameters, or simply commenting various logical sections of code is similarly useful. Remember that code will likely be read by someone else (or even yourself) in the future, so good commenting can reduce how long it takes to understand a section of code.
- Include paths should ideally be absolute paths from the root folder (rtx) always to avoid usage of relative paths. For example `#include "rtx/concepts/example.slangh"` instead of `#include "example.slangh"`.
- Use `inout` when needed, but do not use `in` to stay consistent with using default language behavior when available.
- Avoid usage of `out` parameters due to their ability to cause easy to miss undefined behavior when not written to within a function (e.g. when complex control flow is involved). This rule may be changed in the future once Slang's compiler can properly warn about incorrect use of out parameters.
- Use `const` where possible to prevent accidental modification of variables that are not meant to be changed. Additionally, use `static const` variables as constants where possible instead of defines as these will have better type checking (the static is required when defining a global constant otherwise Slang interprets it as something else).
- Use a shared definition between host and a shader exposed via respective `*.h` files for any shared types or constants such as constant buffers, bindings, enums, etc. See the section on file types for more info.
- Binding names should end in `_INPUT`, `_INPUT_OUTPUT` or `_OUTPUT` to state how the pass uses the resource, and bindings should be grouped under those three categories consistently everywhere they appear: the `*_binding_indices.h` defines, the `layout(binding = ...)` declarations in the shader, the host-side `BEGIN_PARAMETER` list, and the `bindResourceView`/`bindResourceBuffer` calls that fill them. Keeping the same categories in the same order in all four places makes it possible to compare them side by side and spot a binding that is declared but never bound, or used by the shader but missing from the parameter list. That check matters because a missing or mismatched entry does not fail to compile — the shader simply reads whatever resource happens to occupy that slot, which shows up as corrupt output rather than an error.
- Binding index defines in `*_binding_indices.h` files should be listed in ascending numeric order, and should leave a gap between logically distinct categories of bindings (for example starting each category at the next multiple of 10, so a `GBUFFER_*` group might occupy 30-34 and a following `CACHE_*` group start at 40). Ascending order makes the set of indices already in use readable at a glance, and the gaps mean a category that later gains a binding can take the next free index within its own group instead of being appended out of order at the end. Note that renumbering an existing index is not a free operation: every binding declaration and host-side `BEGIN_PARAMETER` list referring to it has to be updated in lockstep, and a mismatch there fails silently at runtime (a shader reads whatever resource happens to be bound at that slot) rather than failing to compile, so it is worth reserving the room up front.
- Avoid nesting scopes excessively when it can be avoided. Often various returns/continues/breaks in a function/loop can be modeled with a guard clause model, reducing the indentation depth of the rest of the code. See [here](https://en.wikibooks.org/wiki/Computer_Programming/Coding_Style/Minimize_nesting#Early_return) for more info.
- Try to use literals that match the type of whatever they are being assigned to to minimize the implicit conversions going on. `0` is a signed integer, `0u` is an unsigned integer, `0.0f` is a 32 bit float and `0.0h` is a 16 bit float.

## Architecture

### Concepts

Concepts are core types which represent general ideas for the renderer. They typically contain most of the actual information about what should be rendered as well as the functionality to implement rendering algorithms easily. They may additionally be polymorphic or specific to a polymorphic type. A list of the current concepts is as follows:

- Camera - Camera-related functionality such as its position and orientation for current and previous frames.
- Ray/Ray Interaction - Ray-related functionality for both spawning and manipulating as well as their raw interactions from ray queries.
- Ray Portal - Transformations and other information for handling ray portal teleportation.
- Surface/Surface Interaction - Information about scene geometry beyond just that which the hardware stores, as well calculated interaction information at a given hit point such as normals, texture coordinates and gradients, and motion vectors.
- Surface Material/Surface Material Interaction - Information about materials applied to scene surfaces, as well as calculated interaction information at a given hit point and functions to sample or evaluate the BSDF representing the material. Typically polymorphic.
- Volume/Volume Interaction - Unused currently.
- Volume Material/Volume Material Interaction - Unused currently.
- Light - Information about lights in the scene containing functions to sample or evaluate the light. Typically polymorphic.

### Algorithm

Algorithms are generally functions which implement commonly used algorithms for rendering, such as various integrators for computing radiance in the scene. A list of some of the current algorithms is as follows:

- Resolver - Handles logic which occurs on the majority of typical ray casts such as particles (separate unordered approximations), ray portals, decals, and creation of various hit structures.
- Geometry Resolver - Handles various "primary ray" logic to resolve the G-Buffer(s). Includes viewmodel code, [PSR](https://developer.nvidia.com/blog/rendering-perfect-reflections-and-refractions-in-path-traced-games/) and primary/secondary G-Buffer creation.
- Integrator - Direct/Indirect integration path tracing logic, handles the evaluation of direct lighting (initial hit next event estimation), as well as indirect lighting (material sampling, path continuation, further next event estimation).
- Volume Integrator - Froxel radiance cache integration logic, handles next event estimation on froxel grid cells.

### Pass

Pass entries own the shader's inputs and outputs and invoke the algorithms that
operate on them. The host records them in
[`RtxFramePipeline`](../../rtx_render/rtx_frame_pipeline.inl). See the lighting,
reconstruction and display stages linked from its implementation for execution
order and variant selection. Common passes include:

- Froxel Radiance Cache (Volumetrics) - Handles lighting and filtering of the froxel radiance cache for usage in various volume-based lighting techniques. Invokes the volume integrator.
- G-Buffer - Primarily invokes the geometry resolver, see the Algorithm second for more details.
- RTXDI - Handles direct lighting ReSTIR preparation via the RTXDI SDK.
- Integration - Primarily invokes the integrator, see the Algorithm section for more details.
- RTXDI Confidence - Computes RTXDI confidence and temporal gradients for usage in denoising.
- ReSTIR GI - Handles ReSTIR GI evaluation and final shading.
- Demodulate - Demodulates the rendered signals when denoising is enabled to preserve various noise-free aspects of detail across the denoising process.
- (Denoise) - Denoising if enabled will implicitly be inserted at this stage (not an actual shader pass on Remix's side).
- Composite - Handles remodulation of denoised signals and compositing together into a single image.
- (Upscaling) - Upscaling if enabled will implicitly be inserted at this stage (not an actual shader pass on Remix's side except in the case of TAA).
- Bloom - Applies a post processing bloom effect.
- Post FX - Applies various additional post processing such as motion blur, vignetting and chromatic aberration.
- Tonemapping - Preforms tonemapping of the fully rendered image to convert from the linear HDR space all rendering is done in to the desired output space.

Other passes which execute outside the main rendering pipeline are as follows:

- Opacity Micromap Generation - Handles dynamic generation of opacity micromap information for use in rendering.
- View Model Correction - Applies a correction transformation to view model vertices, specifically meant to assist in how viewmodels are rendered in Remix.
- Triangle List Generation - Handles generation of triangle lists from topologies such as triangle strips for usage in ray tracing (as BLAS generation only supports triangle lists currently).
- GPU Skinning - Applies bone-based skinning to vertices for handling specific types of animated objects.
- Geometry Interleaving - Handles interleaving of separate buffers (for instance separate vertex and normal buffers) into a singular buffer to match Remix's expectations.
