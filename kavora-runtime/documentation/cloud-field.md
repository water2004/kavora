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

### Shared cloud-light integration

`cloudLightOpticalDepth` clips the ray to its reachable shell intervals. Each
layer uses Revelation's quadratic sampling pattern over at most one layer
thickness divided by `max(dot(lightDirection, radialUp), 0.5)`. The lower
deck uses up to six taps and curl detail on the first three. The upper deck
uses three taps through its horizontal density field. One budget covers both
pieces of a grazing shell intersection, skipping clear space between them.

Camera and PSR cloud single scattering and the sky atlas use the sum of both
layers' optical depths for direct transmission. The neural bake uses that
same short integral to generate its transmission and optical-depth inputs.
Diffuse lighting still uses vertical optical depths and neural multiple
scattering. The finite integration length approximates shadows from distant
cloud banks along grazing light rays.

### Projected cloud shadows

After hardware geometry visibility, direct surface and fog lighting can
sample the cloud-shadow map. It stores transmittance through both enabled
shells in a light-aligned plane. Each layer of the 256×256 R32F array covers
8,192 metres on a side, with the centre anchored near the observer. This
shares the same column between points along a parallel ray, including
surfaces reached through a reflection or refraction. The map is regenerated
when the cloud lighting inputs change.

Narrow celestial disks share the central-axis column. A query requires a
receiver below all enabled shells and an actual light segment that reaches
past them. Near the map and shell boundaries, transmittance blends into the
short integral. In-cloud, finite partial and local-light segments use the
same short integral as before. Sky radiance with embedded cloud transport
bypasses this visibility attenuation.

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

Each light ray takes at most six low-cloud and three high-cloud density
samples, independently of the ray's total distance. Empty shell intersections
use no density samples. The same budget applies to secondary view rays.

The vertical optical-depth atlas uses `lightSteps` up to 16 for the lower
clouds and two samples for the upper clouds. Vertical and neural atlases
amortize diffuse illumination across rays. The directional sky atlas
continues to cache distant view-ray transport.

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
Short-light tests verify the quadratic integral for homogeneous fields,
finite ray lengths, both layer budgets and the grazing-angle cap. View-grid
checks cover bounded intervals and consistent continuation samples.
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
shader directory for a matched comparison. Use the preview script from each
revision's own checkout so its lighting resources match its shaders. Generated
textures are cached by their shader and atlas-byte fingerprint. A test-only `--noise-seed`
override exercises other realizations of the same generator.
`--max-distance` and `--high-max-distance` independently fix the two view ranges;
pass both when comparing a 24-kilometre scene. `--warmup --repeat 3` excludes
the first dispatch from repeated timing and records the shared lighting build
separately from ray dispatch/readback. The preview executes the production vertical-depth and neural builders and
retains their source hashes. Software-Vulkan timings
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
