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
transmission. The dome compositor combines it with the weather layer. Cloud
ambient quadrature samples that lighting environment before any resolved
clouds have been integrated. Fog samples the resulting cloud sky atlas.

Optical lengths are in kilometres. Rayleigh RGB extinction and scalar Mie
extinction are inverse kilometres. `innerRadius` locates the lower optical
boundary; `lowerDepth` places sea level above that boundary; `atmosphereHeight`
is the top boundary's height measured from the lower boundary. Sea level is an
air-density reference and does not represent solid terrain. `seaLevelMeters`
places that reference on the host's world-up axis.

Each light has a world-space unit direction toward the emitter, its angular
radius in radians, and RGB irradiance. The host reverses the sky
texture's X mapping when publishing these directions. Irradiance is the same
`sourceRadiance * pi` used by the GLSL bake, scaled for finite air by the
weather layer's beam transmission towards that emitter. The dome's radiance
multiplier also scales that source.

The completed sky pixels and the native light state come from the same
fence-protected UBO snapshot. A world generation tag rejects readbacks queued
before a world transition. Reusing the Vulkan generator across worlds does not
publish the previous world's atmosphere into the next world.

The host attaches this extension in Volumetric cloud mode. Classic clouds and
fixed skies use the complete dome composition. Switching cloud modes updates
the dome extension even when the sky's light parameters have not changed.

## Table coordinates

The transmission table stores RGB transmission to the upper boundary. Its
horizontal coordinate parameterizes the ray length between the minimum and
maximum upper-boundary intersections for the sample radius. Its vertical
coordinate parameterizes radial distance above the lower boundary. Lookup
coordinates include the texel-centre inset used by `sky_atmosphere.comp`.

Direct sun at a scattering point and clear air along a camera or secondary ray
use this same table. Along an outward ray, subtract the optical depth at the
end of a finite segment from the optical depth at its start, independently for
red, green and blue. A ray crossing its closest approach to the planet joins
the two outward segments there. This recovers transmission through a long
segment with a bounded number of lookups. Cloud extinction is applied separately.

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

Clouds use the shared world-space integrator at every quality level. The view
distance controls detailed sampling in each shell. The remaining arc uses one
density sample, clipped to the visible atmosphere. A surface
hit truncates its optical path while retaining the same sample as the full ray.
The distant cell uses the weather column for beam attenuation and the existing
neural field for multiple scattering. The vertical optical-depth atlas covers
the view and shadow ranges, so air on either side of the detail range
receives skylight from the same field. The directional sky atlas shares distant
transport for eligible rays and the fog-lighting cache.

Outdoor fog integrates to the terrain hit or to the boundary of the weather
layer. Its reference transmission distance sets the extinction coefficient.
The lighting grid covers the near field. Past its far face an in-frustum ray
uses the last filtered incident-light cell in the same direction. A point
inside another active volume uses that volume's measured lighting first.
This extrapolation also carries any local light visible in that boundary cell.

Fog lighting represents the extended sky with two spherical-harmonic bands.
For extinction optical depth `t`, scattering optical depth `s` and HG
anisotropy `g`, the angular transport approximation weights the mean by
`exp(-(t-s))` and the directional band by `exp(-(t-g*s))`. An isotropic
sky therefore retains its radiance in a conservative medium. Finite lights
and solar beams use direct transmittance, and sky samples still require
unoccluded geometry. This two-band model approximates angular redistribution
along a segment without solving spatial diffusion through the full fog field.

At the existing 4096 by 2048 R11G11B10 dome resolution, the extra boundary image
requires 32 MiB. The two runtime RGBA32F tables require 4.0625 MiB. Their host
readback cache uses another 4.0625 MiB. These allocations are separate from the
generator's existing Vulkan tables. No extra table upload occurs per cloud
sample or per frame.

## Scope and validation

The air and cloud media share a ray-segment transport integrator. Authored local
fog remains an additional medium. `OvercastIllumination` models the unresolved
weather layer as a slab. Its vertical optical depth sets both the transmitted
beam and the fraction scattered downwards. The same beam multiplier applies to
the surface and to all four cloud lighting altitudes. The diffuse dome uses the
incident beam spectrum and cosine-weighted clear-sky irradiance. Its angular
profile integrates to that irradiance. Resolved cloud density supplies the
remaining local shadow and scattering.

The existing ambient dispatch averages the upper exterior lighting boundary
into a second texel. At the air sample, each cloud layer's cached vertical
optical depths describe how much cloud lies above and below it. The same
two-stream transfer and ground reflectance used for cloud illumination give
the local diffuse radiance. Finite air scatters it with its local RGB
scattering coefficient. Extinction still comes from the physical
column to the endpoint. In nonabsorbing air with a uniform exterior radiance
`B`, in-scatter plus transmitted background equals `(1 - T) B + T B = B`.
`test_air_transport.py` checks this for both complete and split segments.

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
