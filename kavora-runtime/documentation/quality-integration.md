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
within its spatial support. Fog lighting uses the same transfer combined with the
near atmosphere. Finite light paths resolve in world space.

The cloud light atlas stores vertical optical depth and the isotropic radiance
induced by each unit celestial beam. The same field serves the primary,
reflection, transmission and indirect rays. The local diffuse solve uses finite
optical-depth intervals, escape through the cloud layer and attenuation by
exterior blockers. The existing multiple-scattering control scales this field.
View extinction and the directional single-scattering term retain their own
optical integration.

### Experimental solar multiple scattering

`rtx.clouds.neuralScattering` selects the experimental solar scattering cache.
It runs the `depth64_angles/model_last.pt` network from `Neural_Volumetric_Cloud`
in a Vulkan compute pass. The pass projects 25 directional responses at each
node of a 24×12×24 wind-aligned grid onto nine second-order harmonics. The
shared atmosphere integrator reads this field when tracing the lower cloud
layer. It blends to the analytic solar approximation at the grid boundary.
Moonlight, upper clouds, direct solar light, sky and ground boundaries and
visibility continue through their existing evaluators. The host supplies the
solar direction so light ordering cannot substitute the moon for the sun.

The embedded `cloud_neural_sun.bin` comes from the demo's selected `sun.npz`
export (SHA-256 `75d0aaddf7e87fbf74412f269998620b05ca1f4b2a5ed4fedd72ae42a63382b9`).
Use `scripts-common/export_cloud_neural_weights.py` to reproduce the packed
float32 weights from that export. The build embeds those bytes directly.

The `neuralCloudComparison` client GameTest option captures both solvers at
matching solar times and frozen exposure in the `sunset-ocean` fixture.
Use it with `cloudsOnly`, `sunsetRecovery` and `sunsetFixtures=sunset-ocean`.

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
