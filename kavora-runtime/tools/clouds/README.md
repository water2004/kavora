# Cloud field assets

The Vulkan runtime uploads three baked assets at first use. All views, shadows
and the neural scattering cache use the same density function and textures.

## Rebuild

Run these commands from the repository root in a Python environment with NumPy,
Pillow, SciPy and SlangPy. The noise baker uses Vulkan. Store the observation
cache outside the source tree.

```powershell
python kavora-runtime/tools/clouds/bake_noise.py --cache .local/cloud-observations
python kavora-runtime/tools/clouds/bake_weather.py --cache .local/cloud-observations
```

The outputs live in `src/dxvk/rtx_render/clouds`. Mips are contiguous from
largest to smallest. Every texel occupies four bytes. The runtime checks the
packed lengths at compile time.

## Weather and profiles

The low deck uses the 19 June 2026 afternoon Aqua/MODIS view centred near
11.065° S, 108.67° E. The highest retrieved cloud top in this marine crop
is 2.875 km. A second crop centred near 4.527° S, 104.222° E supplies elevated
clouds from the same day. The resulting field combines two observations.
The bounds and five GIBS layers are specified in the baker. Science colours
decode through their published palettes before interpolation. The outer 5%
of each weather map joins opposite edges for continuous world travel.

The first array layer stores low coverage, convective development, stratocumulus
weight and cellular thickness. The second stores upper coverage, cell contrast,
optical-depth weight and cellular thickness. High-resolution reflectance
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
