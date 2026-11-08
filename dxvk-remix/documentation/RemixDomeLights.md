# API dome lights

The source runtime composes the dome lights submitted through `DrawLightInstance`
in submission order. Each handle contributes at most once per frame. Creation
and updates retain a dome definition; a frame without a draw does not include it.
Removing a light before scene preparation removes its contribution.

The first dome blends over black. Each source is sampled in its own transformed
equirectangular coordinates, multiplied by its radiance, and blended over the
previous result. A missing texture represents white. Texture alpha controls alpha
blend modes; the explicit opacity scales the complete blend operation. Opacity
zero therefore leaves the previous result unchanged for every mode. Opacity is
clamped to `[0, 1]`; non-finite opacity and unsupported blend types are rejected.

For a background channel `D`, radiance-modulated source channel `S`, clamped
texture alpha `A`, and `C = clamp(S, 0, 1)`, the blend result `B` is:

| Type | Mode | B |
| --- | --- | --- |
| 0 | Alpha | `S*A + D*(1-A)` |
| 1 | Alpha emissive | `S*A + D` |
| 2 | Reverse alpha emissive | `S*(1-A) + D` |
| 3 | Color | `S*C + D*(1-C)` |
| 4 | Color emissive | `S*C + D` |
| 5 | Reverse color emissive | `S*(1-C) + D` |
| 6 | Emissive | `S + D` |
| 7 | Multiplicative | `S*D` |
| 8 | Double multiplicative | `2*S*D` |
| 9 | Reverse alpha | `S*(1-A) + D*A` |
| 10 | Reverse color | `S*(1-C) + D*C` |

The final channel is `max(lerp(D, B, opacity), 0)`. Radiance remains HDR.

Composition runs on the GPU before publishing the frame's bindless texture
table. A cached RGBA16F image uses the largest submitted source dimensions.
One compute dispatch per dome preserves ordering without a fixed layer-count
limit. Storage-image read/write dependencies separate dispatches. The same
result feeds primary sky composition, reflected and indirect rays, and radiance
cache updates, so weather opacity affects the visible sky and its lighting
together. The compositor recomputes every frame to include texture animation.
