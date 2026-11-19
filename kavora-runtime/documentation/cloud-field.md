# Cloud density and transport

This document records the cloud field before the HanPi experiment. The current
experimental integration is described in [Visual quality controls](quality-integration.md#fog-lighting-integration).

The native density field adapts the weather distribution and cloud profiles
from [Community Edition](https://github.com/CuteEthanLeo/rtx-remix-minecraft-ce),
revision `94400621dabc6b315d8e53b701c7be7beffa9303`. Its source and Apache-2.0
notice are recorded under `documentation/licenses/RTX-Remix-CE-Clouds.txt`.

[Photon](https://github.com/sixthsurge/photon/tree/2a3d502c17d2e310412c717be47241f014cba7fc)
was studied as a visual reference for cloud layers, weather, erosion and
atmospheric lighting. No Photon shader code or assets are included.

High-cloud morphology also draws on these published methods and observations:

- [WMO International Cloud Atlas](https://cloudatlas.wmo.int/en/species-cirrus-ci.html):
  distinct fine filaments, irregular curvature, and detached cloud patches.
  Parallel bands occur in nature, but should not impose uniform spacing and
  identical lengths across the whole field.
- [Nubis, 2017](https://www.guerrilla-games.com/read/nubis-authoring-real-time-volumetric-cloudscapes-with-the-decima-engine):
  regional coverage and smaller cloud footprints have separate controls. The
  presentation's PDF page 50 describes a two-dimensional cirrus lookup.
- [Hillaire, Frostbite, 2016](https://media.contentapi.ea.com/content/dam/eacom/frostbite/files/s2016-pbs-frostbite-sky-clouds-new.pdf),
  sections 5.3.1 and 5.4.1: separate distribution, shape and detail, and precombine
  density shaping to reduce per-sample work.
- [Dobashi et al., Modeling of Clouds from a Single Photograph, 2010](https://imelab.jp/doba/projects/photo_cloud_modeling/photo_cloud_modeling.htm):
  cloud types benefit from different representations, including two-dimensional
  morphology for cirrus. A photograph does not uniquely recover a cloud volume.
- [Webanck et al., Procedural Cloudscapes, 2018](https://doi.org/10.1111/cgf.13373),
  sections 4.1 and 4.2: finite cloud-cover primitives and type-specific detail.
  The paper's own evaluation found its cirrus unconvincing, so its hierarchy
  informs the model rather than establishing a visual acceptance criterion.

These rendering references contribute modeling principles, not copied code
or assets. High-cloud morphology uses a separately credited CC0 scalar atlas
derived from eight separate cloud groups in six photographs by Estormiz and John
Robert McPherson. Its sources, crop bounds, reproducible preparation and
limitations are recorded in
[CIRRUS.md](../../kavora/packaging/runtime-textures/CIRRUS.md).

## Low-cloud morphology

The low layer separates regional distribution, connected cloud bodies and
surface billows. It follows the hierarchy described in
[Schneider and Vos, 2015](https://www.guerrilla-games.com/read/the-real-time-volumetric-cloudscapes-of-horizon-zero-dawn)
and studied in [Revelation](https://github.com/HaringPro/Revelation/tree/d5911393856d9dd97375b46a1a088e6b8fddd755),
revision `d5911393856d9dd97375b46a1a088e6b8fddd755` (Apache-2.0).
Revelation uses an authored coverage/type map, a vertical-profile lookup and
a two-kilometre base volume. Here the field is independently generated from
Kavora's existing noise, analytical profiles and filtered density moments.
No Revelation source blocks, density formula, noise assets, cloud maps or
profile lookups are included. No Beer-Powder brightness term is used.

The low weather tile spans 64 shape-scale units. Broad moisture octaves at
32768 and 16384 metres define regional humidity, but dry regions retain a
nonzero residual cloud amount so cloud-rich skies do not collapse into a few
isolated islands. Mesoscale group fields at 8192, 4096 and 2048 metres form
connected parent formations and their subordinate groups. This replaces the
previous 2048, 1024 and 512 metre group frequencies, which made small cells
dominate the visible footprint. Three-dimensional noise supplies convex
billows within the parent formations rather than selecting isolated little
clouds. Type varies continuously over 16384- and 8192-metre fields, so broad
weather regions can support shallow stratiform formations, ordinary cumulus
or stronger vertical development. These are input-field scales, not fixed
cloud diameters.

Local allowed thickness is
`thickness * (0.22 + 0.78 * smoothstep(0.1, 0.9, type)) * (0.4 + 0.6 * coverage)`.
The continuous height profile and final density threshold determine the actual
occupied top. The shared condensation base remains 800 metres with the usual
192-metre host cloud height and 608-metre offset; it is not randomized between
neighbouring columns. The 1500-metre configured thickness remains the shell's
upper bound. This permits shallow and moderately developed bodies while
retaining a coherent base, instead of turning the field into independently
extruded terrain columns.

The base volume spans two shape-scale units, with billow inputs at 512, 256,
128, 64 and 32 metres by default. A smaller detail field displaces and erodes
its surface without dominating the parent body. Erosion strength is reduced,
and the condensation transition is widened from 0.04 to 0.08 to keep attached
small lobes and soft boundaries. Coverage remains separate from this transition;
opening a dry region does not make every surviving core transparent. Noise
height remains relative to the cloud base, so changing altitude moves the
field rather than silently selecting a different volume slice.

Four hashes at each lattice cell's corners vary the volume's phase. Quintic
interpolation keeps its first and second derivatives continuous. The phase
lattice spans four base-volume periods: 8192-metre cells and up to 512 metres
of displacement per component at default settings. Its maximum component
derivative remains `0.5 * 1.875 / 4 = 0.234375`; the horizontal derivative row
sum stays below one, preventing folds. The lattice follows wind and the
64-kilometre weather wrap, including negative coordinates. This is bounded
procedural variation, not a fluid or thermodynamic simulation.

The default unit-density extinction is 0.03 per meter. Separating coverage
from condensation increased density inside the retained cores; keeping the
former 0.06-per-meter coefficient made the fair-weather field too optically
thick in the photo comparisons. Recalibrating that coefficient changes optical
depth rather than color or exposure. For scale, the large-droplet relation
`sigma = 3 * LWC / (2 * rho_water * r_effective)` in
[Wood, 2006, equation 5](https://faculty.washington.edu/robwood2/wordpress/wp-content/uploads/2014/06/optical_depth_relations.pdf)
associates 0.03 per meter at unit density with 0.2 grams of liquid water per cubic
meter if the effective droplet radius is 10 micrometers. These are illustrative
microphysical assumptions, not values measured from the reference photographs.

Review uses both individual clouds and whole-sky views of natural shallow
cumulus, including [Toby Hudson's whole-sky photograph](https://commons.wikimedia.org/wiki/File:Cumulus_humilis_clouds.jpg)
and [Juan Lacruz's shallow cumulus photograph](https://commons.wikimedia.org/wiki/File:Cumulus-humilis.jpg):
unequal connected lobes, mostly white sunlit bodies with local soft gray
undersides, irregular blue openings, and a continuous near/middle/far size
hierarchy. Mixed cumulus/stratocumulus photographs also retain locally darker
cloud bases; the review does not require every shaded face to become white.
[Janne Naukkarinen's lake scene](https://commons.wikimedia.org/wiki/File:Cumulus_mediocris_3.JPG)
and [Kreuzschnabel's spring scene](https://commons.wikimedia.org/wiki/File:Cumulus_mediocris-1.jpg)
also constrain connected bodies, different development heights and broad blue
openings. Their camera fields of view, exposure and cloud distances are not
matched to the diagnostic renderer; image occupancy is not a physical size
measurement. Numerical density moments are filtering checks, not a visual
acceptance criterion.

## Density and filtering

`Resources::prepareCloudNoise` generates three periodic textures once per
device. All three have complete mip chains, built by the cloud noise filter:

| Texture | Extent | Format | Channels |
| --- | --- | --- | --- |
| Base shape | 128 cubed | RG16F | Cumulus shape and its square |
| Erosion | 32 cubed | RGBA8 | Three detail frequencies |
| Weather | 1024 square | RGBA16F | Coverage and its square, cumulus type, finished high-cloud density |

The scale option defaults to 1024 metres. The base volume spans two scale
units (2048 metres); erosion spans one sixteenth of that volume period
(128 metres). Low-cloud weather spans 64 scale units (65536 metres), and
high-cloud weather spans 32 (32768 metres). Camera motion neither regenerates noise nor
publishes a different density field. Wind translates both layers in the same
world frame, with a shared wrap at the larger of these power-of-two periods.
The low shape phase has the same 64-scale-unit wrap. These are finite periodic
fields; a sufficiently large orthographic map can still expose the full repeat.

Clouds have two non-overlapping curved shells. The lower shell contains
cumulus with local thickness, taper and anvil development driven by weather.
Rain builds a continuous stratiform component beneath the taller cells. The
upper shell contains feathered cloud groups and larger clear regions.
`cloud_shape.slang` places eight finite scalar morphology variants in regional
groups with varied size, position, heading and transverse reflection. Groups
of three, three and two shapes are assigned to a three-colour region lattice;
regions sharing an edge have disjoint variants, and each region uses its
variants without replacement. The source crops' aspect ratios are retained,
their longest axis controls size, and their main directions follow
a smooth regional flow. Regional humidity controls the amount of cloud,
including broad clear areas. Placement and atlas sampling run only during
the weather bake. Analytic texture-coordinate gradients preserve the
anisotropic footprint of each transformed tile.

`Resources` loads the linear R8 atlas through the existing texture manager,
waits for its upload before enabling cloud sampling, and releases its pinned
reference after recording the bake. The 1536-square atlas and its mip chain
occupy about 3.0 MiB while resident, about 1.67 MiB more than the former four-tile
atlas. The final weather texture is unchanged. No source photographs are loaded at
runtime, and no per-ray atlas lookup or placement loop is added.

The finished horizontal density is stored in the weather texture's alpha
channel. Its 32768-meter period and 1024 texels give a 32-meter texel spacing at
the default scale. A continuous height profile turns this field into a thin
volume with a base of 6375 meters, thickness of 50 meters and unit-density
extinction of 0.024 per meter. These defaults retain the 6400-meter layer center
and extinction-times-thickness product of the former 200-meter layer. A
thicker extrusion of an already projected morphology image averages away
fine filaments along oblique rays. The thinner sheet limits this extra
convolution. Its thickness describes this representation, not a measured
depth or a common thickness for all natural cirrus clouds.
The runtime keeps it at least 1000 meters above the cumulus top. Its coverage
can be set to zero without changing lower-cloud transport.
The weather mip chain occupies about 10.67 MiB, an 8 MiB increase over the
512-square field. The smaller texel spacing retains more feather structure
than a 512-square texture over this larger period, though very fine source
fibres remain unresolved. These are storage sizes, not measured GPU timings.

For cumulus, `cloud_density.slang` applies its continuous height profile,
bounded shape displacement and boundary erosion before one condensation
threshold. View footprints select the noise mip level. The first and second
moments preserve unresolved variation through this nonlinear threshold, using
a moment-matched distribution. This is a filtering approximation: it does not
retain every correlation between weather, shape, erosion and the local height
profile. The smooth height profile is integrated
analytically over the vertical footprint so a coarse sample cannot lose an
entire thin column just because its midpoint lies above the column top.
At a shell boundary, that vertical footprint uses the actual last cell span.
Prefix checkpoints retain the original cell's sample and footprint so splitting
a ray does not change its medium evaluation.
For the high layer's two-dimensional field, the density footprint uses the
ray cell's horizontal projection and the pixel footprint. The independent
height footprint integrates the fixed cell's radial span within the shell.
It does not add the pixel cone's radial extent: convolving the profile beyond
the shell and then clipping to the original shell would lose column density,
especially for thin distant layers. This is a central-ray thin-layer
approximation, not an integration of pixel-cone coverage against the sphere.
A vertical ray therefore does not blur horizontal filaments merely because
it advances through height. The lower volume retains its existing height
filtering.

High-cloud amount fades the pre-shaped density, including the tails, instead
of applying another threshold to filtered fine detail. Low-frequency weather
changes the relative amount between regions. This control is monotone and
bounded, with exact empty/full endpoints; it is not a literal percentage of
sky area. Extinction independently controls optical thickness. Its mip chain
stores linear mean density. Filtering the material and regional control
after combining them retains the mean of their product. The volume participates
in the shared extinction, light visibility and in-scattering calculations.
The scalar input is an authored relative shape estimate, not measured optical
depth or a thermodynamic simulation of ice formation. The native volume model
supplies extinction, layer thickness and atmospheric lighting.

All rays share the cloud shells and the current view's tangent frame. When
physical air is enabled, the native setup converts its sea-level radius to
world units and subtracts the atmosphere reference height. The cloud and air
shells then have the same planet center. Hosts without physical air use a
6360-kilometer cloud radius. Stable sphere roots and altitude evaluation avoid
subtracting nearly equal squared planet radii. A grazing ray may intersect
either shell twice. View bounds retain both segments and skip the clear air
between them. The shells supply bounds and altitude only; they introduce no
opaque ground sphere below the horizon.

## Light and weather ownership

The host publishes the atmosphere and celestial lights from the current
environment state. When volumetric clouds are active, the authored weather
panorama is not drawn and rain does not apply an additional empirical dimming
factor to direct sunlight. Cloud extinction supplies the weather occlusion.
Classic clouds and disabled volumetric clouds retain their authored weather
behavior.

`SkyAtmosphere` evaluates clear-air solar and lunar transport at the bottom and
top of both cloud shells. These four RGB/world-altitude samples travel through
the optional `LightInfoCloudRadianceProfileEXT` extension. The SDK copies and
validates the inline data. Existing clients can omit it and retain constant
cloud irradiance. A source that is dark at the camera can remain available to
high clouds without becoming an active surface light.

The shader interpolates this profile at each cloud sample's altitude. It uses
the same Rayleigh and aerosol coefficients as the host atmosphere. Four height
knots and the shared tangent-frame celestial direction are approximations to
full per-position spherical atmospheric transport. They do not introduce a
sunset color ramp or a saturation multiplier.

Diffuse cloud illumination uses the upper-hemisphere radiance integral divided
by four pi. `RtxGlobalVolumetrics` integrates the current lighting environment
once per frame into a shared one-texel image. The GPU reduction uses 8192
equal-area Hammersley directions fixed in the world frame, including low sky
elevations that carry twilight radiance. Its pattern does not rotate with the
sun or align a coarse azimuth sample with the narrow aerosol peak. A constant
environment contributes exactly half its radiance. Dome lighting and the
fallback cube probe use the same rule. Camera, secondary, sky-map and composite
paths read the same result without a CPU readback.

## Transport

World-volume transport integrates extinction and in-scattering along each ray
through the existing atmosphere-segment integrator. Physical air, authored fog
and cloud contributions share a Beer-Lambert transfer. Camera, reflection, refraction and light
visibility use the same density field, with footprints appropriate to each
integration. Each cloud layer has its own view range and fades before its
distance limit. Celestial shadow paths use a separate light-transport extent.

The host publishes the existing atmospheric optical tables and completed light
state through `LightInfoAtmosphereEXT`. Finite air transport attenuates distant
cloud contrast and adds in-scattered atmospheric radiance at the actual ray
distance. Full clear-sky radiance remains the cloud lighting environment;
view rays begin with an exterior boundary that excludes the integrated air.
See [air-transport.md](air-transport.md) for ownership, units and approximations.

The shared `CloudRayGrid` has a strict cell budget for each complete shell
interval. Short intervals retain their spatial spacing; long intervals use a
linear-plus-quadratic distance map within the same budget. Forward boundaries
and inverse lookup are shared by view transmission, atmosphere segments and
the sky pass. Prefix checkpoints and clipped tails keep the original samples.
Density filtering uses the actual cell span and pixel cone, including the last
partial cell. A curved shell can produce two intervals, and there are two
layers; the configured budget is per interval, not for the sum of all four.

### Shared low-cloud lighting

`RtxGlobalVolumetrics` builds low-cloud optical-depth data before primary
traversal, alongside the existing cloud-sky preparation. The shader calculation
lives in `cloud_lighting_cache.slang`; its entry point owns descriptor bindings
and output writes. The common bindings and the composite adapter publish the
same images to camera, PSR, reflection, transmission and visibility consumers.
There is no second cloud density model or screen-space shadow history.

Each active celestial light has a light-aligned R32F prefix volume. A thread
integrates one entire light column and writes cumulative optical depth at its
nodes. The grid encloses the current view's low shell and surface-shadow origins,
with the configured light range beyond them. Its extent follows the light
orientation, while its allocation depends on quality and whether the far
cascade is needed. R32F retains the
precision needed to subtract finite intervals from long, opaque prefixes.
Cells wholly outside the low shell are skipped conservatively. Within occupied
cells, bounded two-point Gaussian quadrature resolves density along the ray;
its footprint does not gather an entire transverse cache cell of off-ray cloud.

Up to 24 kilometres of low-cloud visibility, one grid serves each light. Longer
view ranges retain this full-resolution near field and add a second grid at
half the resolution on every axis. The near grid still includes the complete
60-kilometre light path; increasing view distance therefore does not coarsen
nearby self-shadowing. The two logical grids share one 3D image, stacked in
depth. Half-resolution columns occupy only their valid U/V region. Queries
stay between each grid's own endpoint texel centres and cannot filter across
the packed boundary. Between 18 and 24 kilometres from the camera anchor, a
smooth weight blends transmitted energy. A near-field query participates only
when its complete finite interval fits within the near grid. The 24-kilometre
near radius is explicitly converted from metres by the host, independent of
the artistic shape scale.

A lookup subtracts the two endpoints separately in each of four neighbouring
light columns, then interpolates **transmittance** between those columns.
Interpolating optical depth before exponentiation would incorrectly darken
unresolved clear paths. The returned effective depth is converted back with a
stable shifted logarithm. Filtering transmitted energy makes these effective
depths non-additive: `CloudOpticalDepth` keeps `local`, `external` and `total`
separate, and single scattering and visibility use `total` directly.

The near beam retains a bounded fine integration in the original density
field. Its range is the larger of one quarter of the shape scale and the
widest cache cell in world units, so a coarse table cannot replace local
cloud detail inside its own footprint. It replaces that interval in the
cache query; the remaining interval is queried directly, without subtracting
two already-filtered effective depths. The finite-side multiple-scattering
closure can request the same bounded correction in the reverse direction.
Light quality bounds the local sample count. The shared field amortizes
long-distance density integration across views.

Surface light sampling retains the sampled direction of the solar or lunar
disk for this local correction. A small celestial disk, up to 0.02 radians in
angular radius, shares its central-axis far shadow. Larger source cones and
rays outside the cache domain use a bounded, filtered fallback. Volume
illumination retains its existing central-direction celestial approximation.
This finite angular/spatial filtering is an approximation, particularly around
thin distant occluders; it does not replace surface path tracing or geometry
visibility.

Diffuse illumination keeps the original bounded, local radial-column
integration. Its samples share the prepared weather/phase column and preserve
small concavities and attached billows in the two-stream closure. A coarse
view-wide column texture was rejected because its transverse footprint flattened
those features. Only directional shadow prefixes are cached. They are rebuilt
from the current density and light state each frame, so camera cuts, wind and
sunset do not depend on stale light history.

The high shell retains its bounded exponential light cells and two-point
quadrature for wide cells. Its morphology and density field are independent of
the low-cloud cache. Both layers still participate in the shared extinction and
in-scattering calculation. Real low-sun shadow motion remains in the geometry.

Single scattering uses a normalized dual-lobe Henyey-Greenstein phase function
and the measured optical depth toward each light. Diffuse illumination uses a
local plane-parallel column aligned with the shell's radial direction. The
column fixes its weather sample and uses the shared density evaluator for its
three-dimensional shape and erosion. Its support includes both the cumulus
and rain profiles at that same weather-filter footprint. The resulting optical
depths above and below the scattering point are reused for sky and celestial
illumination.

`cloud_scattering.slang` solves a two-stream boundary-lit slab with ordinate
cosine `1 / sqrt(3)`. Its upper boundary is the sampled sky radiance. The
lower boundary is a local Lambertian terrain approximation with mean reflectance
`rtx.clouds.groundAlbedo` (default 0.18). Terrain receives the sky and celestial
flux transmitted through the cloud, including the downward diffuse beam flux.
The closure sums repeated reflections between the conservative column and the
terrain: `L_ground = rho * (T * L_sky + E_beam / pi) / (1 - rho * R)`, where
`T + R = 1`. Reflected flux returns only through the lower boundary; it is not
also added directly to the cloud source. A black terrain recovers the previous
boundary, while a uniformly lit conservative white cavity preserves radiance.

The boundary solution uses transport optical depth
`(1 - meanCosine) * opticalDepth`. This is an average local terrain boundary,
not a readback of actual terrain materials or full three-dimensional indirect
lighting. Surface path tracing continues to use the scene's materials. Cloud
irradiance profiles describe the cloud-altitude light; the separate light
irradiance member supplies the host's actual observer-height irradiance for
the terrain approximation, avoiding use of the brighter high-cloud profile
as a ground source at sunset.

The collimated-light solution generates a separate diffuse source from the
incident beam. Its two vertical streams use unit cosine, a conservative escape
approximation for the independently resolved single-scattering phase. It
vanishes continuously at zero thickness and grazing incidence.
The illuminated boundary follows the sign of the radial light cosine. Cloud
optical depth beyond that local column attenuates its incident beam. The direct
source retains the full three-dimensional light ray. This closure approximates
horizontal diffusion and multiple scattering in an irregular cloud. It does
not replace their transport with a second density field or a color ramp.

For finite cumulus, the same directional shadow trace separates optical depth
inside one shape-period neighborhood from optical depth outside it. The fixed
sample supports are weighted on both sides of this boundary, rather than
assigning a Gaussian node discontinuously to a side. A second ray measures only the
opposite half of that local neighborhood when a side-light contribution is
needed. The existing vertical samples also define a continuous piecewise-linear
profile; integrating it along each light ray predicts the optical depth of a
plane-parallel column over the **same local range**, without additional density
lookups. The difference between that prediction and the measured local depth
controls a convex blend with a beam-axis two-stream slab. Exterior blockers
attenuate the side slab's incident beam exponentially and cannot change the
geometric blend weight. A homogeneous column recovers the vertical model; a
horizontal beam can illuminate a finite cloud through its side. No tint or
ambient brightness floor is added.

This finite neighborhood is not a connected-cloud detector: it can include
several nearby billows or truncate a larger cloud. The method also omits diffuse
exchange between separate neighborhoods and does not solve a full 3D radiance
field. [Hogan et al., 2016](https://www.met.reading.ac.uk/~swrhgnrj/publications/spartacus_part2.pdf)
explains the physical role of lateral cloud transport; this local rendering
closure is independently derived and does not implement the SPARTACUS solver.

Fog subdivisions reuse a cloud cell's extinction and incident source. A prefix
checkpoint recomputes the same fixed cell sample, so splitting a ray preserves
the medium evaluation. This is deterministic volume ray marching with
approximate multiple scattering, **not a stochastic volumetric path tracer**.
The surface path tracer remains the renderer for surfaces.

Atmosphere segments also accumulate cloud opacity and the first extinction
distance moment. The constant-cell moment is analytic and composes across
prefix checkpoints and clipped continuations. The stored motion guide packs
conditional mean distance and opacity into one half2 word; radiance and
transmittance retain their existing precision. The guide reaches the normal
G-buffer motion path, including camera translation and inter-frame wind.
Camera cuts and discontinuous cloud frames reset the wind history.

The directional sky atlas caches distant transport and motion-guide distance
moments. Escaping rays reuse it when their projected path fits the atlas
footprint. Near segments and light visibility resolve in world space.

## Quality and cost

All profiles share the same cloud transport, density and scattering model.
The two view budgets bound cells per shell interval. Distant cells widen
smoothly to fit the budget, and density filtering follows the cell span.
See the [settings guide](../../docs/SETTINGS.md#quality-profiles) for profile
values and [Quality integration](quality-integration.md) for
the current view and lighting contracts.

The old nested loop could issue 1875 forward density queries per occupied
view sample at the default 60-kilometre light range, plus a vertical column and
reverse local beam. Its early-out included a plane-parallel depth divided by
the solar elevation cosine; near sunset this could retain the entire long
trace even through occupied clouds. The previous view-quality setting also
controlled spacing rather than a total cell count. Splitting command submission
cannot reduce that repeated shader work. The shared prefixes remove this long
per-sample loop from camera and PSR paths alike.

Let `N = 16 * clamp(lightSteps, 4, 16)`. Each directional image contains
`N * (N / 2) * N` R32F values at view ranges up to 24 kilometres. With the far
cascade, physical depth becomes `N + N / 2`; only the valid half-resolution
columns of the extra region are generated. The owner reuses matching allocations
and releases inactive light images. One active light costs 32 / 48 MiB at Ultra
(single / two cascades); two lights cost 64 / 96 MiB, before driver overhead.
The corresponding two-light totals for High, Medium and Low are 27 / 40.5,
8 / 12 and 1 / 1.5 MiB. This is new storage in exchange for sharing long-path
work across all viewing and visibility rays.

Prefix construction has a finite budget independent of output resolution.
Each directional grid has `N * (N / 2) * (N - 1)` segments, each with at most
`max(1, lightSteps / 2)` Gaussian pairs before shell skipping. At Ultra this is
at most 133,693,440 density queries for the near grid of each active light. The
half-resolution far grid adds at most 16,646,144 queries, making 150,339,584
per active light when both grids are needed, a 12.45% increase. This work is
shared by the whole frame. These are worst-case bounds: off-shell segments skip density and empty weather
can skip the base/detail lookups. Cache generation must be included when
comparing performance, rather than timing only the subsequent ray dispatch.

At an occupied low-cloud view sample, an in-domain celestial beam uses at most
`max(2, lightSteps)` local density queries, or 16 at Ultra. The finite-side
solve may add 16 in the reverse direction. The 18–24 kilometre transition
region can evaluate both cascades, doubling these bounded local beam queries.
The radial column is still shared. Each cached interval uses at most sixteen
integer texel loads (four U/V columns, two endpoints, two depth nodes) and
interpolates transmitted energy. Explicit depth interpolation avoids touching
uninitialized packed padding even through a hardware filter's zero-weight tap. A visibility or unsplit
query needs one interval; a beam split between local and external blockers
needs up to three intervals, including the independent complete beam. The
local radial column retains `max(2, lightSteps)` density samples, or 16 at
Ultra, shared by both celestial lights and the sky. An uncached low-light ray is
limited to `max(4, 2 * lightSteps)` density queries, or 32 at Ultra, with explicit
filtering for its wider supports. The fallback loses fine distant shadow
detail; it never restarts the old 1875-query path.

The upper layer uses half the light-quality value, with a minimum of four
cells and up to two queries per cell or clipped part. Its local vertical
profile uses two density samples. The environment integral remains one
256-thread workgroup with 8192 environment reads per frame, shared across all
quality levels. Its output is one RGBA32F texel. No per-pixel sky-hemisphere
integration is added.

The three source noise textures and their mip chains still occupy about
20.0 MiB before driver allocation overhead. The morphology change adds no
texture allocation or density sampler. `Resources::prepareCloudNoise` returns
when the base LUT is already valid; its three generators and 22 mip dispatches
run once per resource lifetime, not once per frame or camera movement. A low
cloud density query still performs at most one weather, one base and one
detail lookup. An empty column can skip both 3D reads. Noise octave and Worley
neighbourhood loops stay in the bake, outside the ray integrators. They are independent of output resolution. Motion
guides add one word per primary atmosphere record and one per checkpoint. At
1920 by 1080 internal resolution with the Ultra checkpoint layout this adds
about 19.8 MiB to path storage. These are storage calculations, not measured
GPU performance. The shared prefix construction, local corrections and view budgets require
hardware profiling before assigning a frame-time cost.
High-density evaluation needs one weather-texture lookup. Placement and atlas
sampling are confined to the once-per-device generator, which visits at most
24 packets rather than the former 36. The pinned atlas reference is released
after the bake. The finite 32768-meter high-cloud period remains a memory/detail
tradeoff. Diagonal regions can reuse a variant, and the complete field repeats;
it is not an infinite nonrepeating distribution. Finite-air resource costs are
recorded separately in [air-transport.md](air-transport.md).

## Numerical validation

The optional Vulkan readback tests execute production Slang helpers and can
use either a hardware device or a software ICD. Install `numpy` and `slangpy`
in the Python environment used by the test, then run from `kavora-runtime`:

```sh
python tests/rtx/unit/test_cloud_transport.py
python tests/rtx/unit/test_cloud_lighting.py
python tests/rtx/unit/test_cloud_scattering.py --device vulkan
python tests/rtx/unit/test_atmosphere_moments.py
```

Equivalent Meson run targets have the same names. The tests compare Beer
integration, shell intersections, noise mip averages, light profiles, shadow
quadrature and motion moments with independent analytic or numerical
references. High-cloud checks also compare column transmission across thin
and thick layers with the independent height-profile integral. Constant-field
checks compare spherical-shell column depth across view angles, pixel
footprints and sampling budgets. They do not validate
game presentation, temporal reconstruction quality or GPU frame time.
Low-cloud checks exercise the shared 64-scale-unit wind wrap, negative
coordinates, phase bounds, continuity and derivative bounds at the phase
lattice boundaries, and independence from coverage and weather controls.
Lighting-cache tests exercise prefix differences against constant-density
analytic intervals, forward/reverse rays, out-of-domain
fallbacks, finite celestial disks and a fine independent density integration.
They test the actual production cache builders, not a substitute Python density
model. Filtered effective depths are not assumed to be additive. View-grid
checks enforce the configured interval budget and verify prefix/inverse
consistency across long grazing rays.
The scattering test checks sky quadrature normalization and angular moments
in several up frames. It compares the production boundary and beam solutions
with independent integrals, exercises reflective-terrain, finite-domain,
external-blocker, thin and grazing limits, and integrates
outgoing slab flux with the actual dual-HG single-scattering phase. Its parameter
grid covers the supported anisotropy range and maximum multiple-scattering
strength. This checks that grid, not energy conservation for every heterogeneous
three-dimensional cloud. `--device cpu` also runs these math kernels through
Slang's CPU backend. The normal Slang compiler and SPIR-V validation
remain separate build gates.

## Visual morphology inspection

Install `numpy`, `Pillow` and `slangpy`, then run the optional preview from
`kavora-runtime`:

```sh
python tests/rtx/unit/render_cloud_preview.py --output /path/to/cloud-preview --radiance
```

The `render_cloud_preview` Meson run target writes to its build directory.
The script executes the production noise generators, mip filter, density,
transmittance and cloud scattering helpers on Vulkan. It saves a vertical
column optical-depth map and perspective views at several elevations and
headings, along with linear numerical output and a parameter/source manifest.
The default map is 32 kilometers wide. `--origin`, `--wind`, `--coverage`,
`--weather` and `--layers` vary the test conditions. `--shader-root` selects a
different revision's shader directory for a matched comparison. Generated
textures are cached by their shader and atlas-byte fingerprint. A test-only `--noise-seed`
override exercises other realizations of the same generator.
`--max-distance` and `--high-max-distance` independently fix the two view ranges;
pass both when comparing a 24-kilometre scene. `--warmup --repeat 3` excludes
the first dispatch from repeated timing and records the shared lighting build
separately from ray dispatch/readback. The preview executes the production
prefix builders and retains their source hashes. Software-Vulkan timings
characterize this diagnostic workload, not hardware GPU frame time.
`--sun-elevation` and `--sun-azimuth` select diagnostic light direction;
`--sun-irradiance-rgb` and `--ambient-rgb` select diagnostic illumination.
`--high-base`,
`--high-thickness` and `--high-extinction` isolate the physical layer geometry.
Selecting an earlier shader root does not load its historical native option
defaults. Pass the intended layer parameters explicitly when comparing revisions.

Radiance previews default to fixed diagnostic sunlight, ambient radiance and a
constant background. `--atmosphere-fixture` instead reads a directory containing
`atmosphere.npz` and `manifest.json`. The NPZ supplies a linear RGBA `sky` array.
The manifest records the observer's `worldHeight`, dome `verticalScale`,
`sunDirection`, and `javaLighting.cloudIrradianceProfile`: four RGB/world-height
knots generated by `SkyAtmosphere`. The preview uses the production dome
projection and hemisphere quadrature. Fixture producers must record their
atmosphere inputs and source hashes in the manifest; the preview preserves that
manifest and hashes the NPZ in its output. The fixture observer height must
match the camera. An older shader root retains its own quadrature rule.

`--lighting all`, `direct` and `ambient` isolate the cloud sources while keeping
the same density and sky background. `--exposure` applies a fixed display-only
multiplier before sRGB encoding for PNGs. The saved numerical radiance and
transmittance remain unmodified. Compare revisions at identical fixture,
camera, layer parameters and exposure, including below-horizon solar angles.

These are cloud-transport diagnostics, not game captures. A production
atmosphere fixture supplies sky and cloud illumination. Adding `--air` also
runs the production finite-distance air/cloud integrator. The preview does
not include terrain, visible celestial disks, exposure adaptation or temporal
reconstruction. Orthographic maps retain cloud-only optical depth.
Compare both opacity and radiance at identical settings. Inspect
distinct patches, fine internal filaments, tapered ends, spatial repetition
and distant merging. Inspect additional positions and wind phases before
accepting a shape change. Coverage statistics and numerical test success do
not establish natural appearance.
