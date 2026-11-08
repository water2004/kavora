# Visual quality controls

The runtime reads individual rendering options. Minecraft quality-profile names
are absent from shader selection and integration. The host's profiles fill
editable options through its existing settings transaction. Display resolution,
reconstruction and frame generation have separate owners.

## Fog lighting integration

`rtx.volumetrics.fogIntegrationMode` chooses lighting-grid ray integration
(`0`) or a preintegrated primary-view volume (`1`). The latter keeps one
cumulative radiance/transmittance transfer per spatial column and depth slice.
`rtx.volumetrics.fogIntegrationPixelStride` sets the column width, from 2 to 32
pixels. Depth resolution follows the fog depth slices, capped at 48 checkpoints.
Both options occupy existing padding in `VolumeArgs`; the public Remix C API
is unchanged.

The column pass uses the existing world-space `integrateAtmosphereRange` at
the radiance cache's depth boundaries. The dense primary pass interpolates
transmittance exponentially in optical depth, and radiance using the matching
Beer-Lambert scattering weight. It resolves the transfer at the actual surface
distance. Segments beyond the finite volume use world-space integration.
Reflection and refraction rays also retain world-space integration. All methods
use `fogSegmentDensity` and `fogSegmentMedium`, preserving density noise,
ground mist and the existing ray-traced lighting cache.

Column prefix storage aliases Primary's existing tail slab until dense
integration finishes. Its allocation accounts for the selected spatial stride
and checkpoint capacity. Higher pixel strides reduce work and storage while
softening narrow spatial features; they do not replace depth-resolved lighting
with a uniform color over a long view segment.

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
