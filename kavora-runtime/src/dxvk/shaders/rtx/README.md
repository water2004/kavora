# RTX Remix Shader Overview

This document provides an overview for the general structure, standards and conventions in the shader codebase for the RTX Remix project. Note that originally this codebase used GLSL primarily but has since switched to [Slang](https://github.com/shader-slang/slang) which provides back-compatibility in terms of syntax. In the future more of the codebase will be written to leverage Slang syntax, but much of it still is designed around its GLSL origins for now.

## File Conventions

### Ray tracing constant buffer

`pass/raytrace_args.h` defines `RaytraceArgs` for the host and for the shader's
`ConstantBuffer<RaytraceArgs>`. The host writes it in
`RtxContext::updateRaytraceArgsConstantBuffer` before recording the scene.
Use explicit-width storage and preserve the CPU/GPU field layout when adding
parameters. Each pass keeps its descriptor indices alongside its bindings.

The Meson `raytrace_layout_checks` target reflects a probe for `RaytraceArgs`.
`display_color_layout_checks` reflects `SRGBDitherArgs` from the real output
shader. Both use the production scalar-layout and matrix options and generate
C++ assertions for sizes, field offsets and array strides. The owning C++
translation units enforce the assertions at build time. Resource encodings,
image access modes and history lifetime belong to each pass's contract.

### Visual effect boundaries

Keep the entry point responsible for descriptor bindings, dispatch coordinates,
and output writes. Put an effect's calculations in a `.slangh` file with named
inputs and outputs. Keep its existing slot, access mode, coordinate space and
encoding when changing the calculation. Use `[ForceInline]` on helpers whose
boundary must disappear in the release shader, and compare the emitted SPIR-V
when moving work across that boundary.

| Effect | Calculation owner | Contract |
| --- | --- | --- |
| Final color grading | [`display_color_grade.slangh`](pass/srgb_dither/display_color_grade.slangh) | Linear post-tonemap RGB and grading values in. Linear graded RGB out. The entry point owns sRGB conversion and dither. |
| Cloud shape and motion | [`cloud_density.slangh`](utility/cloud_density.slangh) | World position and frame cloud parameters yield density. The same density feeds view rays, light visibility and the sky map. |
| Cloud sky contribution | [`cloud_sky_transport.slangh`](pass/clouds/cloud_sky_transport.slangh) | Extinction, step length, albedo and incident radiance update the accumulated radiance and transmittance. The entry point owns the ray grid and termination. |
| Cloud light and visibility | [`clouds.slangh`](algorithm/clouds.slangh) | A world-space ray segment yields cloud transmittance. A sample position, view direction and ambient radiance yield the incident cloud lighting. |
| Fog cache sampling | [`fog_froxel_sampling.slangh`](algorithm/fog_froxel_sampling.slangh) | Uses the frame's volume cameras and supplied radiance volumes to evaluate incident light. `atmosphere.slangh` owns the joint fog, cloud and medium integration. |
| Stars, galaxy and meteors | [`night_sky.slangh`](pass/dome_composite/night_sky.slangh) | Night-sky parameters, star and galaxy maps, and the view direction with neighboring directions yield linear radiance. The dome compositor owns layer composition. |
| End sky and planet | [`end_sky.slangh`](pass/dome_composite/end_sky.slangh) | End-sky parameters, background map and world direction yield linear radiance for the dome layer. |
| Visible moon | [`caustica_moon.slangh`](pass/dome_composite/caustica_moon.slangh) | Projected disc coordinates, gradients, texture and phase yield the disc's radiance and coverage. Scene illumination comes from the directional light. |
| Water normal motion | [`water_normal_coordinates.slangh`](concept/surface_material/water_normal_coordinates.slangh) | Sprite coordinates, time and motion or a texture transform produce the UVs for the material's existing normal layers. The material owns sampling, LOD and normal blending. |
| Translucent interface optics | [`translucent_interface_optics.slangh`](concept/surface_material/translucent_interface_optics.slangh) | Material thickness, IOR, color and ray geometry yield the relative indices and thin-sheet transmittance. BSDF and PSR share this calculation. |
| Thick-medium absorption | [`translucent_medium.slangh`](concept/surface_material/translucent_medium.slangh) | The current medium index yields RGB extinction per world unit. The ray segment applies distance and keeps track of its current medium. |
| Reflection and transmission continuation | [`translucent_bsdf.slangh`](concept/surface_material/translucent_bsdf.slangh), [`translucent_psr.slangh`](concept/surface_material/translucent_psr.slangh) | A decoded material and incident ray select the direction and throughput. PSR also owns its integration guides and disocclusion policy. |
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

- `*.h` - Primary structure definitions due to the one pass compilation model causing issues when structures are defined after includes within a file. Only to be used for big structures shared across files, not tiny internal or helper structures only used within a file. Shared with CPU occasionally for memory and code consistency. Note these headers should only be included in their respective `*.slangh` file (with some exceptions for bindings/macro definition files, or aforementioned CPU-shared helpers). Additionally, other files do not need to include these and should only include the respective `*.slangh` file for the desired types and functionality provided by the implementation.
- `*.slangh` - Implementations of struct "member" functions or other helper types and functions. These files are what should be included into others to bring in the functionality and types they offer.

The remainder of the extensions used on the other hand are used only for "pass" implementations, other functionality should be split into `.slangh` files. Additionally, note that the extensions in these cases are important for how the shader is compiled as otherwise indicating which shader stage to compile for would have to be done in another way.

- `*.slang` - Generic shader files, typically used when multiple types of shader stages will be generated via permutations (e.g. compute for ray query implementations and ray generation for trace ray implementations in the same file).
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

Passes are actual ray tracing or compute passes which invoke algorithms or perform other functions and make up the rendering pipeline. Typically they also are responsible for the various inputs and outputs a compute shader may be responsible to read or write data to. A list of the current passes in general order of execution is as follows:

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
