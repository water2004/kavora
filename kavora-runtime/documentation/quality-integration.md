# Visual quality controls

The runtime reads individual rendering options. Minecraft quality-profile names
are absent from shader selection and integration. The host's profiles fill
editable options through its existing settings transaction. Display resolution,
reconstruction and frame generation have separate owners.

## Fog lighting integration

`rtx.volumetrics.fogIntegrationMode` uses two-pixel primary columns (`0`)
or the width specified by `rtx.volumetrics.fogIntegrationPixelStride` (`1`).
The configurable width accepts 2 to 32 pixels. A two-pixel column records
transfer at every endpoint in its 2×2 block. Wider columns retain a bounded
set of depth anchors with a separate sky endpoint when present.

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

Air attenuation uses absolute optical-depth prefixes from a finite Chapman
approximation. Segment extinction comes from the difference between successive
prefixes. Radiance uses eight cells per air interval, with the source sampled
once per cell and weighted by the segment's integrated extinction.

Each cloud layer has an independent view sample budget that falls to half
when looking overhead. A fractional budget clips
only the terminal cell. A direction-derived offset stratifies the cumulus samples
across nearby rays and stays constant when a ray is split. The high shell uses
its integrated height profile and one cell where the horizontal footprint covers
the segment. The low and high layers have separate view range controls. The
directional shadow prefix field uses its own light-transport extent.

The shared direct-light field bakes the solar and lunar transmission to points
in both cloud layers, including the transmission used for ground bounce.
Its two wind-aligned grids blend across their edges and use resolved transport
for short segments and positions beyond the field. Primary rays, PSR and sky
generation sample the same field. View extinction is still integrated along
each ray.

### Cloud multiple scattering

The local atlas stores optical depth toward the top and bottom of each cloud
layer in RG16F. The `depth64_angles/model_last.pt` network from
`Neural_Volumetric_Cloud` supplies multiple scattering for the two cloud layers
and the active directional beams. It projects 25 directional responses onto nine
second-order harmonics at each node of a wind-aligned grid. Each layer has
24×24 horizontal nodes with 12 height samples for cumulus and four for high
clouds. The bake reuses the direct-light field where it covers a node. The
sky and ground boundary terms continue to use the two-stream transport model.

The embedded `cloud_neural_sun.bin` comes from the demo's selected `sun.npz`
export (SHA-256 `75d0aaddf7e87fbf74412f269998620b05ca1f4b2a5ed4fedd72ae42a63382b9`).
The [cloud scattering training workflow](../training/cloud-scattering/README.md)
generates the reference data, trains the model, and packs the weights. Its packer
accepts the selected export above to reproduce the embedded float32 payload.

`rtx.clouds.neuralTensorCores` defaults to enabled. A Vulkan device with
the FP16 cooperative-vector compute format uses it to bake the same field.
The runtime converts the embedded checkpoint to the device's optimal
inference layout once. The shared features and harmonic projection use
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
