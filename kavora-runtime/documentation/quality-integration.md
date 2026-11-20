# Visual quality controls

The runtime reads individual rendering options. Minecraft quality-profile names
are absent from shader selection and integration. The host's profiles fill
editable options through its existing settings transaction. Display resolution,
reconstruction and frame generation have separate owners.

## Fog lighting integration

`rtx.volumetrics.fogIntegrationMode` uses two-pixel fog and clear-air columns
(`0`) or the width specified by `rtx.volumetrics.fogIntegrationPixelStride`
(`1`). `rtx.clouds.primaryPixelStride` caps the column width for clouds.
When clouds use a finer grid, the haze pass stores radiance and transmittance
from the camera to a set of depth anchors on the fog grid. Cloud integration
uses the cached transfer between cloud depths to compose the layers in ray order.
Equal grids share one integration pass.

A two-pixel column records transfer at every endpoint in its 2×2 block.
Wider columns retain a bounded set of depth anchors with a separate sky
endpoint when present.

The full-resolution pass reconstructs the recorded scattering, transmittance
and cloud distance. Neighboring columns contribute where the scene depths
match. The input slab is reused by the ray queues after primary shading.
Camera and wind reprojection use the existing cloud motion guides. The
directional atlas shares distant air and cloud transport between escaped rays
within its spatial support. Reflected and refracted rays project through the
first cloud interval beyond the atlas split. A footprint test bounds parallax
across the remaining cloud intervals, and the cached depth moment is projected
back onto the original ray. Fog lighting combines the same transfer with the
near atmosphere. Finite light paths resolve in world space.

Primary low-cloud columns combine the current quadrature observation with the
two previous raw observations. Each 12-byte record contains premultiplied
radiance, opacity and signed centroid distance. Each frame stores its projection
and displacement in a 48-byte header so both observations can be sampled directly.
Each observation undergoes at most one spatial interpolation. An empty observation
of a complete shell contributes zero opacity and radiance. A tap from the same
shell arc loses weight in proportion to its reprojection error in column pixels.
The sampling sequence belongs to the column and is independent of camera jitter.
High clouds use the current deterministic height and density integral.
Clipped terrain prefixes, newly visible pixels and camera cuts use the current
integral. Haze composes after this step at the filtered extinction centroid.

Air attenuation uses absolute optical-depth prefixes from a finite Chapman
approximation. Segment extinction comes from the difference between successive
prefixes. Radiance uses eight cells per air interval, with the source sampled
once per cell and weighted by the segment's integrated extinction. Air and
froxel fog form the background transfer. Each intersected cloud shell integrates
its own radiance, transmission and extinction moment. The compositor inserts
that transfer at the extinction centroid and integrates the background on
either side. This approximates the overlap of air and cloud within the shell
while keeping cloud steps out of the air and fog integration grid.

Both cloud layers integrate over grids anchored to their unclipped shell intervals.
The view budgets cap the number of cells independently of the light direction.
Cumulus widens cells continuously on long paths to cover the whole interval.
Clipped ranges start at their first covered cell and reuse the full grid's sample
positions and density footprints. A cell samples the HanPi density field once,
including billow and wisp erosion where the body can contribute.
A high cloud's coverage sets its vertical band. Altocumulus cells thin that band
and reduce density at the edges. Altostratus uses the same band with a weaker
cell effect. High-cloud spacing follows the projected weather texel and pixel
footprint. Each cell integrates the band's smooth edges in height, and a vertical
ray can cover the high shell with one cell.

The shared density field samples separate satellite-derived low and elevated
weather maps, a radial RGB profile LUT and four erosion bands. The offline
[asset workflow](../tools/clouds/README.md) documents the observations,
reconstructed inputs, channel layouts and generation commands.
The body texture stores the mean and central variance in RG16F. Each mip
merges its eight child distributions in float32 before quantization.
The low and high layers have separate view ranges with a short terminal fade.
The low-cloud shadow integrates the first body tile from the cloud entry with
a widening cone fitted to the available taps. The body mip advances along the
ray and each tap resolves the edge erosion at its native resolution.
The shared directional prefix covers the remaining optical path.
High clouds use uniform sunlight segments within a 3 km interval from shell entry.
Direct single scattering uses normalized forward and backward lobes. High-cloud
directional light includes HanPi's powder response. Diffuse boundary illumination
uses the sky and ground responses described below.

The shared direct-light field bakes incoming solar and lunar transmission one
body texel toward the light, clipped at the cloud boundary. Each shading sample
applies Beer attenuation for that short segment using its own extinction.
The ground-bounce channel already includes the complete exterior transmission.
Its three wind-aligned grids blend across their edges and use resolved transport
close to the camera and beyond the field. The far grid grows with the view
range while the near and middle grids retain their spatial resolution.
Primary rays, PSR and sky generation sample the same field. A PSR
continuation uses camera-to-sample distance for the resolved region. View
extinction is integrated along each ray.

### Cloud multiple scattering

The local atlas stores optical depth toward the top and bottom of each cloud
layer in RG16F. The solar network trained on the production density fields with
the renderer's dual-lobe phase supplies multiple scattering for the two cloud layers
and the active directional beams. Each wind-aligned node stores the network response
at six light-relative polar knots. The knots are closer together in the forward lobe.
Each knot spans the two reachable view elevations, which coincide at the poles.
The ten distinct predictions occupy three RGBA16F texels. A 30×20 horizontal
atlas packs 10×10 near and middle grids beside a 20×20 full-range grid.
The grids follow the wind and blend at their edges. Each has 12 height samples
for cumulus and four for high clouds. The bake reuses the direct-light field where it covers a node.
Before interpolating the solar prediction, the bake subtracts its fitted variation
with density and beam transmission. Each ray restores that term with the density
and transmission it has already evaluated. This preserves the network output
at grid nodes and keeps empty neighboring cells from dimming sunlit detail.
`depth64_sky/model_best.pt` predicts higher-order scattering for a unit upper hemisphere.
Its four view elevations share two extra atlas bands with the corresponding lower-boundary
response. The bake combines its prediction with the training renderer's four-band
single-scattering integral. Conservation for unit-albedo droplets gives the complementary
ground response at full scattering strength. The ray consumer weights both coefficients
with the actual sky and ground radiance. The default scattering strength is one.

The runtime embeds both networks in [`cloud_neural_lighting.bin`](../src/dxvk/rtx_render/clouds/cloud_neural_lighting.bin).
The [cloud scattering training workflow](../training/cloud-scattering/README.md)
generates the reference data, trains both models, and packs the selected exports.

`rtx.clouds.neuralTensorCores` defaults to enabled. A Vulkan device with
the FP16 cooperative-vector compute format uses it to bake the same field.
The runtime converts the embedded checkpoint to the device's optimal
inference layout once. The shared features and angular interpolation use
float32 arithmetic. The scalar bake remains available for other devices
and as a comparison mode.

Use `neuralTensorProfile` with `cloudsOnly` and `cloudProfileStartFile` to
record both neural backends with advancing time and the normal wind speed.

## Offset parallax

`rtx.displacement.mode = 3` selects offset-limited parallax in the existing
opaque-material evaluator. Two samples of the captured height texture estimate
the view-dependent coordinate offset. The normal, roughness, metallic and
albedo textures are evaluated at that coordinate. Atlas wrapping and captured
sprite bounds use the existing sampling functions. The iteration control
continues to set the relief-shadow budget.

This approximation preserves the apparent depth motion of a height field,
but does not search for its first intersection. Overlapping relief and grazing
angles are less accurate than ray-marched or quadtree POM. The method follows
the classic offset-mapping approach described in
[GPU Gems 2, Chapter 8](https://developer.nvidia.com/gpugems/gpugems2/part-i-geometric-complexity/chapter-8-pixel-displacement-mapping-distance-functions).

The profile controls also expose RTXDI candidate, spatial and disocclusion
budgets, and the volume reservoir's candidate and spatial budgets. These retain
direct illumination, scattering and ray-traced visibility while trading
sampling stability for cost. Reflection/refraction transport, radiance-cache
updates and surface shading stay in the existing Vulkan rendering pipeline.
