# Cloud field assets

The Vulkan runtime uses Revelation's cloud density textures from revision
`d5911393856d9dd97375b46a1a088e6b8fddd755`. Given a checkout of that revision,
run `python kavora-runtime/tools/clouds/import_revelation.py <checkout>` with
Pillow installed. The importer preserves the source texels and resolutions.
Vulkan packs the six 2D images in two array layers and selects each image's
native mip level. The two 3D volumes supply the alligator FBM and curl field.

## Rebuild the HanPi training assets

Run these commands from the repository root in a Python environment with NumPy,
Pillow, SciPy and SlangPy. The noise baker uses Vulkan. Store the observation
cache outside the source tree.

```powershell
python kavora-runtime/tools/clouds/bake_noise.py --cache .local/cloud-observations
python kavora-runtime/tools/clouds/bake_weather.py --cache .local/cloud-observations
```

The HanPi outputs live in `src/dxvk/rtx_render/clouds`. Mips are contiguous
from largest to smallest. Every texel occupies four bytes. These files
reproduce the input field from the HanPi experiments.

## Weather and profiles

The low deck uses the 19 June 2026 afternoon Aqua/MODIS view centred near
11.065° S, 108.67° E. The highest retrieved cloud top in this marine crop
is 2.875 km. A second crop centred near 4.527° S, 104.222° E supplies elevated
cloud cover and optical-depth statistics from the same day.
The bounds and five GIBS layers are specified in the baker. Science colours
decode through their published palettes before interpolation. The outer 5%
of each weather map joins opposite edges for continuous world travel.

The first array layer stores low coverage, convective development, stratocumulus
weight and cellular thickness. The second stores upper coverage, cellular
thickness factor, low-deck softness control and upper optical-depth weight.
The upper deck uses the central 1024 × 1024 native samples of Sentinel-2A
tile T48MVA, band B10, acquired on 22 June 2026. This 1.375 µm band resolves
elevated cloud at 60 m. Its reflectance ranks supply the spatial distribution.
The MODIS scene supplies the coverage histogram and conditional optical
weights. The two overpasses are independent. The baker pins the source JP2
by checksum. The low weather and radial profile layers begin at mip 1,
with 512 × 512 native samples each. Only the upper layer is stored at mip 0.
High-resolution MODIS reflectance
guides the coarser retrievals. Cloud-top pressure and optical thickness
constrain the split into decks. For the low deck, an observed area fraction
is mapped to the body's density threshold by inverting the mean filled area of
HanPi's softened density ramp at the profile peak. The baker measures the
Cu body and billowy erosion at 262,144 common Sobol positions, preserving
their 16:1 frequency ratio. The unconverted fractions weight the
profile retrieval. Translating the ISCCP bins into HanPi's
continuous cloud types is a reconstruction.

The third layer is the radial profile LUT. Its X coordinate is normalized
height and Y is distance from the map centre. RGB represent Cu, TCu and Cb.
In each annulus the generator accumulates a weighted distribution of retrieved
cloud tops per type. For each plume it models rising condensate with an
adiabatic extinction gradient and integrates over the cloud-top distribution.
Normalization, cloud-base transition and entrainment are modelling choices.
Alpha carries conservative upper bounds for the vertical lighting bake.

The source data are [NASA GIBS](https://www.earthdata.nasa.gov/data/tools/gibs)
and the [MODIS cloud product](https://atmosphere-imager.gsfc.nasa.gov/products/cloud).
The classification follows the [ISCCP cloud types](https://isccp.giss.nasa.gov/cloudtypes.html).

## Base and erosion

The baker compiles the original `GenerateCloudNoise` kernel from 3DTextureExportTools.
It verifies the source SHA-256 before compilation. The RG16F base volume stores
the mean and variance of the R signal from the author's 64³ example. The generator uses eight octaves, a
starting Perlin frequency of four, Worley at half that frequency, persistence
0.678 and coverage 0.66. Each mip preserves the between-texel variance.

The RGBA8 erosion volume reconstructs the missing four-band interface. R/G
encode low/high wispy erosion and B/A low/high billowy erosion. Wisps use
inverted, directionally stretched cellular noise bent by a periodic curl
field. Billows use isotropic cellular noise. The octave bands fit the 64³
resolution, and eight subtexel samples integrate each voxel before filtering.
The baker checks the bands' spatial frequencies, preferred directions and
cross-channel correlation.

The density equations and noise primitives come from [HanPi Volume Cloud](https://github.com/AshenOneArt/HPVolumeCloud)
and [3DTextureExportTools](https://github.com/AshenOneArt/3DTextureExportTools).
The erosion reconstruction follows the curl-distorted edge detail described
by [Schneider and Vos](https://www.guerrilla-games.com/read/the-real-time-volumetric-cloudscapes-of-horizon-zero-dawn).
