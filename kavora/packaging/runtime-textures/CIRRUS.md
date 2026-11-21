# Cirrus morphology experiment

`cirrus_density.dds` is the linear scalar morphology atlas from the earlier
cirrus experiment. The production cloud density now uses Revelation's cirrus
lookup. The source atlas and its preparation steps are retained here.

## Sources and reproduction

The six original photographs, source pages, author names and
SHA-256 hashes are recorded in [cirrus_sources.json](cirrus_sources.json).
All six photographs were released under CC0. The shipped
[notice](../licenses/Cirrus-CC0.txt) credits them and describes the derivative.

Download the original JPEG files from the URLs in that manifest. With NumPy
and Pillow installed, supply them in manifest order:

```sh
python prepare_cirrus_density.py \
  /path/to/Cirrus_clouds_Haukipudas_Oulu_20170611.jpg \
  /path/to/Cirrus_from_Shorncliffe_Pier_Moora_Park_Shorncliffe_P1120160.jpg \
  /path/to/Cirrus_74_Sunbury_Street_Geebung_P1130433.jpg \
  /path/to/Cirrus_74_Sunbury_Street_Geebung_L1020751.jpg \
  /path/to/Cirrus_74_Sunbury_Street_Geebung_L1030327.jpg \
  /path/to/Cirrus_11_Hamilton_Place_Bowen_Hills_IMGP3448.jpg \
  --output cirrus_density.dds --preview /tmp/cirrus-density.png
```

The tool validates each original before processing. It does not download
anything and is not run by Gradle or at game startup. The committed DDS is
1536 by 1536, R8_UNORM, with eleven box-filtered mip levels: 3,145,874 bytes
including its header. Its SHA-256 is
`f8c34adc22f13fb1cb6d89b12927759de1ab6e736b71333dda958223b95b8423`.
The same invocation writes the native `cirrus_shapes.slangh` include from
the manifest and actual crop dimensions. Use `--geometry-output` when
preparing a candidate outside the repository. Keep both generated outputs
together; the preview cache fingerprints the atlas and this include.

## Extraction and interpretation

Eight separate groups were selected where blue-sky gaps bound their natural
filaments. The first selection follows a blue gap above a separate cloud
bank. An explicit mask removes foreground leaves at the upper-left corner
of the third selection. Later selections follow blue gaps around a long
hook and a thin ribbon, excluding adjacent cloud banks and a building corner.
No alternate photograph of the same cloud is counted as another variant.
The selected regions exclude the photographs' landscape and lens flare.

The tool linearizes the camera RGB values, fits a smooth local clear-sky
red/blue ratio from the crop border, rejects cloud outliers, and subtracts
the estimated camera-noise floor. The remaining colour excess defines a
nonnegative scalar. One scale is shared by all patches; no patch is
normalized to an arbitrary peak. Clear-sky padding and a small denoise
filter prevent quantization residue from forming tile borders.
The blue-sky fit excludes white cloud cores, but those cores remain valid
in the scalar output. Reusing the fit's colour rejection for the final
image would incorrectly punch holes into neutral or warm white clouds.

This is a relative shape estimate. A single processed JPEG cannot determine
cloud optical depth, particle phase function or three-dimensional geometry.
Exposure and camera processing also limit the interpretation of its scalar
values. The atlas does not preserve RGB, the original sun colour or a baked
scattering solution. Its height profile, extinction and lighting are supplied
by the native physical volume model. Local perspective within each small
crop remains an approximation; the complete photographed sky is never tiled.

## Atlas contract

Eight 512-square tiles are arranged in reading order in a three-by-three
grid; the last tile is empty. Some photographed crops contain fewer than
512 native pixels on one axis, so the tile size does not imply that many
independent photographic details. Their square storage does not stretch
the world-space cloud: `cloud_shape.slang` restores each crop's width/height
ratio when placing it, and aligns its long axis with the regional flow.
`flowAngle` in the manifest records the corresponding authored orientation.

| Tile | Source | World width / height | Flow angle, radians |
| --- | --- | --- | --- |
| 0 | Estormiz | 0.8636364 | 1.21 |
| 1 | McPherson P1120160 | 2.2222222 | 0.11 |
| 2 | McPherson P1130433 | 0.4256757 | -1.42 |
| 3 | McPherson P1130433 | 0.7875000 | 1.40 |
| 4 | McPherson L1020751 | 3.2889610 | -2.9330837 |
| 5 | McPherson L1030327 | 0.7423639 | 1.0710478 |
| 6 | McPherson L1030327 | 0.9354067 | 0.9571392 |
| 7 | McPherson IMGP3448 | 5.5849515 | -3.0583414 |

Regional placement varies location, size, heading and transverse reflection.
The longest crop axis defines its size, approximately 4.1 to 9.8 kilometres
at the default world scale. Using crop height alone would make a panoramic
fallstreak several times larger than a portrait feather with the same size
parameter. No nonlinear deformation is applied to the photographed fibres.

The manifest partitions the variants into groups of three, three and two.
A three-colour periodic region lattice assigns disjoint groups to regions
that share an edge. Each region uses its group's shapes without replacement,
in a deterministic permutation. Diagonal regions can share a group; the
32,768-metre field still repeats. This reduces local copies of recognizable
cloud heads, but does not make an infinite nonrepeating sky.

A separate regional humidity field leaves contiguous clear areas. The
finished field is baked into the existing weather texture, including its
mip chain. Runtime rays sample that field once for the high layer; they
never sample the atlas or execute the placement loop.

When changing the manifest, inspect the derived scalar image for foreground,
border and noise artifacts, then inspect production-shader perspective views
from multiple world positions. Regenerate both the atlas and shader metadata
when changing crop bounds, orientation or groups. Use independent natural photographs to judge feather
structure and whole-sky distribution; the source crops alone cannot validate
their placement or illumination. See the shared
[rendering diagnostics guide](../../../docs/RENDERING_DIAGNOSTICS.md).
