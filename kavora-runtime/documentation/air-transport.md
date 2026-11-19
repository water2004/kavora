# Finite clear-air transport

`SkyAtmosphere` owns the optical coefficients used by direct celestial lighting,
the Java/GLSL sky bake and native finite rays. Its existing Vulkan compute
generator bakes two static RGBA32F tables: a 1024 by 256 transmission table and a
64 by 64 multiple-scattering table. `AtmosphericSkyGenerator` reads them once
after initialization. `SkyRenderer` publishes immutable runtime textures through
the ordinary texture store. The frame path updates the light state, not the
tables.

## Host contract

`LightInfoAtmosphereEXT` is an optional `LightInfo` extension attached with
`LightInfoDomeEXT` and `LightInfoDomeTransmittanceEXT`. The existing dome structure
and function table retain their sizes. The new extension has type 60 and a
144-byte Windows 64-bit layout. The Java FFM arena copies the complete extension
and its arrays before the foreign call returns. Native command submission keeps
the two referenced textures alive independently of the temporary arena.

The atmospheric dome must have unit opacity, latitude-longitude projection and
both transport tables. Its source radiance is the integrated clear atmosphere.
Its separate transmission texture is the matching camera-to-boundary RGB
transmission. This preserves the existing environment used by direct-light
sampling and cloud ambient lighting.

Optical lengths are in kilometres. Rayleigh RGB extinction and scalar Mie
extinction are inverse kilometres. `innerRadius` locates the lower optical
boundary; `lowerDepth` places sea level above that boundary; `atmosphereHeight`
is the top boundary's height measured from the lower boundary. Sea level is an
air-density reference and does not represent solid terrain. `seaLevelMeters`
places that reference on the host's world-up axis.

Each light has a world-space unit direction toward the emitter, its angular
radius in radians, and unattenuated RGB irradiance. The host reverses the sky
texture's X mapping when publishing these directions. Irradiance is the same
`sourceRadiance * pi` used by the GLSL bake. The dome's radiance multiplier also
scales native air's source irradiance.

The completed sky pixels and the native light state come from the same
fence-protected UBO snapshot. A world generation tag rejects readbacks queued
before a world transition. Reusing the Vulkan generator across worlds does not
publish the previous world's atmosphere into the next world.

The host attaches this extension in Volumetric cloud mode. Classic clouds and
fixed skies keep the complete dome composition, including the existing authored
weather layers. Switching cloud modes updates the dome extension even when the
sky's light parameters have not changed.

## Table coordinates

The transmission table stores RGB transmission to the upper boundary. Its
horizontal coordinate parameterizes the ray length between the minimum and
maximum upper-boundary intersections for the sample radius. Its vertical
coordinate parameterizes radial distance above the lower boundary. Lookup
coordinates include the texel-centre inset used by `sky_atmosphere.comp`.

The multiple-scattering table uses light zenith cosine on its horizontal axis
and normalized altitude on its vertical axis. It stores the existing clear-air
multiple-scattering closure per unit light irradiance. Native `air_medium.slang`
uses the same parameterization and coefficients. The tables are linear data;
texture colour-space conversion and authored sky colours do not apply to them.

## Native ownership

`DomeLight` retains an optional `AtmosphereLight` with the physical parameters
and two `TextureRef` values. `LightManager` enables finite transport when exactly
one active atmospheric dome has valid, ready RGBA32F tables. Otherwise it keeps
the ordinary complete dome path. Table dimensions are taken from the actual
image views. The shared `AirArgs` header has a 160-byte scalar layout and lives
inside `VolumeArgs`. The shader helper receives its texture and sampler inputs
explicitly so production and offline numerical tests use the same code.

The native reference point uses the current camera's horizontal position and
the host sea level. `worldToKilometers` converts native distances. Cloud shells
keep their absolute world-height controls, but their sphere centre matches the
air sphere when finite air is active.

The dome compositor retains the full lighting environment and two exterior
boundary variants: one excludes visible emitters already represented by
analytic lights, and one includes their visible disks. During finite transport,
the existing visible-background image stores the exterior visible boundary.
One additional image stores the exterior lighting boundary. The physical air
layer is omitted from both boundaries. Native path callers select a boundary
only when that ray integrates physical air. Ambient and direct-light sampling
continue to use the full lighting environment.

Clouds use the shared world-space integrator at every quality level. Sampling
budgets follow the selected quality settings. The directional cloud sky atlas
shares distant transport for eligible rays and the fog-lighting cache.

At the existing 4096 by 2048 R11G11B10 dome resolution, the extra boundary image
requires 32 MiB. The two runtime RGBA32F tables require 4.0625 MiB. Their host
readback cache uses another 4.0625 MiB. These allocations are separate from the
generator's existing Vulkan tables. No extra table upload occurs per cloud
sample or per frame.

## Scope and validation

The air and cloud media share a ray-segment transport integrator. Authored local
fog remains an additional medium. Direct solar attenuation already supplied in
the host's cloud radiance profiles must not be applied a second time.

Primary intersection depth terminates the view segment at the terrain. To shade
the air in front of that endpoint, each active physical atmosphere also builds
a pair of 128 by 128 light-space depth maps against the ordered scene TLAS.
The depth test uses the sun or moon direction and the midpoint of the portion
of the air cell actually traversed. The result modulates single scattering.
Diffuse multiple scattering and view transmittance retain their LUT values.
The sampled maps are shared by primary columns, secondary rays, the sky atlas
and alpha composition. They occupy 128 KiB in R32F. Resolution falls with
transverse distance from the eye. The maps represent opaque and cutout
geometry and do not model coloured transmission through glass.

The multiple-scattering source retains the existing clear-atmosphere
approximation. Clouds do not feed their shadowing back into the global air
multiple-scattering table. This is not a fully coupled three-dimensional
atmosphere/cloud light simulation.

`ffm-test/AtmosphereContractTest.java` checks the optional extension chain,
copied array ownership, layout and invalid inputs. `test_dome_atmosphere.py`
executes the production dome compositor to check the full lighting environment,
both exterior boundaries and transitions between transport modes. Numerical
transport checks and rendered natural-cloud comparisons remain necessary;
these ABI and ownership tests do not establish game presentation or performance.
