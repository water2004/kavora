# Cloud density and transport

The native density field adapts the weather distribution and cloud profiles
from [Community Edition](https://github.com/CuteEthanLeo/rtx-remix-minecraft-ce),
revision `94400621dabc6b315d8e53b701c7be7beffa9303`. Its source and Apache-2.0
notice are recorded under `documentation/licenses/RTX-Remix-CE-Clouds.txt`.

## Density

`Resources::prepareCloudNoise` generates three periodic RGBA8 textures once
per device: a 128-cubed Perlin-Worley base volume, a 32-cubed erosion volume,
and a 512-square weather map. The base period defaults to 1024 meters. Erosion
occupies one quarter of that period and weather sixteen periods. This replaces
the camera-windowed coefficient and support caches; camera motion does not
regenerate noise or publish a different density field.

Weather red controls coverage and green controls cloud type. Type determines
local thickness, the upper density profile, narrowing and anvil development.
Rain and thunder change these weather inputs. A separate height profile
controls condensation at the bottom and dissipation at the top. Base shape
and edge erosion are evaluated in `cloud_density.slang`.

All rays share a curved shell with a 6360-kilometer radius and one tangent
frame for the current view. Stable sphere roots and altitude evaluation avoid
subtracting nearly equal squared planet radii. The shell supplies bounds and
altitude only; it does not introduce an opaque ground sphere. Wind translates
the world-space field using synchronized host time.

## Transport

World-volume transport integrates extinction and in-scattering along each ray
through the existing atmosphere-segment integrator. Fog and cloud contributions
share a Beer-Lambert transfer rather than separate overlay blends. Camera,
reflection and refraction rays sample the same density. Light visibility uses
that density including erosion; the CE base-only shadow approximation is not
used.

Self-shadowing samples optical depth toward the sun and moon. The existing
phase-function and higher-order scattering approximation remain. This is
deterministic volume ray marching with approximate multiple scattering, **not
a stochastic volumetric path tracer**. The surface path tracer and cloud
transport must not be described as the same algorithm.

Sky-map transport runs the same volume integration into a camera-centered
equirectangular radiance/transmittance texture. Background rays reuse it, while
light visibility stays world-space. Camera-ray motion uses the opacity-weighted
distance from the same integration and the wind displacement between frames.
Secondary rays approximate parallax with the camera-origin map. The map
approximates local cloud occlusion against scene geometry. The transport option
is independent of host quality-profile names.

No OpenGL compositor, CPU cloud meshes, occupancy tiles, extra cloud layer,
or CE history system is included.
