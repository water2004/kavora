# Visual quality controls

The runtime reads individual rendering options. Minecraft quality-profile names
are absent from shader selection and integration. The host's profiles fill
editable options through its existing settings transaction. Display resolution,
reconstruction and frame generation have separate owners.

## Fog lighting integration

`rtx.volumetrics.fogIntegrationMode` uses two-pixel fog and clear-air columns
(`0`) or the width specified by `rtx.volumetrics.fogIntegrationPixelStride`
(`1`). The haze pass records clear-air and fog transfer at eight depth anchors,
including the nearest and farthest finite surface depths in each column.

Every primary pixel integrates its own cloud shells along its jittered camera
ray up to its first surface. Ray budgets and density mip levels use the view
angle and pixel footprint. The pass composes the haze prefixes and cloud
transfer at each extinction centroid, then publishes the result and the
opacity-weighted layer motion to primary shading. The ray queues reuse this
storage after primary shading. DLSS consumes the result at its input resolution.

Reflected and refracted rays evaluate world-space transport. The directional
atlas shares distant air and cloud transport between escaped rays within its
spatial support. A footprint test bounds parallax across the cloud intervals
and projects the cached depth moment back onto the original ray.

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
positions. Beyond the detailed range, a second grid divides the full remaining
arc into up to twelve cells. The cross section of the view and the covered cell
length determine the detail mip in both grids. The low shell uses the original
low weather map, regional noise, vertical profile, 3D curl and alligator noise.
The high shell uses the sum of
Revelation's cirrus, cirrostratus and cirrocumulus fields. The high density is
horizontal and uniform through the shell thickness. Its spacing follows the
projected cirrus texel and pixel footprint.

The [asset workflow](../tools/clouds/README.md) records the source and packing
process. Low weather, three-dimensional noise and cirrus shapes have independent
filtered mips. The low-cloud erosion lookup stores the first two noise moments
so filtered cells can integrate density shaping over a range of values.
Other weather lookups and the vertical profiles retain their native resolutions.
Revelation's quadratic light-ray integration samples up to six taps in the
low deck and three in the high deck. It is shared by camera and secondary
cloud shading, sky generation and neural feature preparation. Surface and fog
visibility first trace geometry, then use the [projected cloud shadow](cloud-field.md#projected-cloud-shadows)
for complete celestial columns and the short integral for other segments.
Direct single scattering uses the normalized scalar Revelation phase table.
The low layer uses its liquid cumulus row and the high layer its cirrus row.
A finite-disk lookup is shared by all cells along a view ray. View extinction
is integrated along the camera or secondary ray.

### Cloud multiple scattering

The local atlas stores optical depth toward the top and bottom of each cloud
layer in RG16F. The neural network uses features and reference transport sampled
from the Revelation field and supplies multiple scattering for both layers and
the active directional beams. A beam lookup uses the optical depths toward
the light, onward along the view ray, and through the vertical column. The
three log-depth axes share six light-relative polar knots at each node.
The knots are closer together in the forward lobe.
Each knot spans the two reachable view elevations, which coincide at the poles.
The ten distinct predictions occupy three RGBA16F texels. The 30×20 optical
grid has 12 column-depth samples for cumulus and four for high clouds. The
ray computes inward depth with three density samples and reuses its light ray
and vertical column for the other axes. Both direct and learned scattering
receive illumination from the upper deck.
The second network predicts higher-order scattering for a unit upper hemisphere.
Its wind-aligned spatial grids blend between near, middle and far coverage.
Its four view elevations share two extra atlas bands with the corresponding lower-boundary
response. The bake combines its prediction with the training renderer's four-band
single-scattering integral. Conservation for unit-albedo droplets gives the complementary
ground response at full scattering strength. The ray consumer weights both coefficients
with the actual sky and ground radiance. The solar flux incident on the ground
uses a conservative two-stream slab with a delta-scaled forward peak from each
layer's phase moment. The default scattering strength is one.

The runtime embeds both networks in [`cloud_neural_lighting.bin`](../src/dxvk/rtx_render/clouds/cloud_neural_lighting.bin).
The [cloud scattering training workflow](../training/cloud-scattering/README.md)
generates the reference data, trains both models, and packs the selected exports.

Device creation requires the FP16 Vulkan cooperative-vector compute format.
The runtime converts the embedded checkpoint to the device's optimal
inference layout once. The shared features and angular interpolation use
float32 arithmetic.

Use `cloudFullDayProfile` with `cloudsOnly` and `cloudProfileStartFile` to
record cloud lighting with advancing time and the normal wind speed.

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
