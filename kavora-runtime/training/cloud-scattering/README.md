# Cloud scattering training

This workflow captures the production Revelation cloud density, generates
Monte Carlo targets for solar and upper-hemisphere multiple scattering, and
fits the networks embedded by the Vulkan runtime. The PyTorch model retains
the Apache 2.0 license of the original Neural Volumetric Cloud experiment.
The Vulkan weight packer retains its MIT notice.

## Setup

From this directory, create a Python 3.11 environment. The CUDA configuration
uses PyTorch 2.10.0 built with CUDA 12.8. Use the corresponding executable under
`.venv/bin/` on Linux.

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install torch==2.10.0 --index-url https://download.pytorch.org/whl/cu128
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

## Generate, train, and export

```powershell
.\.venv\Scripts\python.exe run.py --smoke
.\.venv\Scripts\python.exe run.py
```

`--out` selects an empty directory. The defaults are `outputs/smoke/` and
`outputs/reproduction/`. SlangPy captures disjoint periodic slabs of both
production layers on Vulkan using the native default heights and extinction.
The CPU path tracer generates solar and sky targets with the normalized scalar
Revelation table for the scattering PDF and the inverse CDF. It takes samples
from the forward lobe, backward hemisphere and full sphere. Points are drawn
from the density-weighted volume and uniformly over the slab. Half are traced
from the viewing boundary toward the first collision. Only material points
supervise the solar source. Ratio tracking
estimates sunlight visibility at subsequent collisions. Six regions fit the
model using 1,536 paths per target. Two independent regions select and evaluate
checkpoints with 4,096 paths per target. Checkpoint selection accounts for both
absolute error and mean energy in each solar angular band. Training uses 6,000
steps per channel. The smoke run reduces the
problem size and uses CPU training.

Both networks use Leaky ReLU with slope 0.01 and a Softplus output. The solar
network sees three log optical depths, light and view elevation, scattering
cosine and the LUT phase at that angle. Both see the first phase
moment as a material feature. The checkpoint folds feature normalization
into the first affine layer. The packer pads both input matrices to 16 columns,
matching the cooperative-vector input layout. The runtime converts the FP32
checkpoint to device-native FP16 weights at upload.

Deploy `cloud_neural_lighting.bin` to `src/dxvk/rtx_render/clouds/`.
Data, caches, intermediate checkpoints, and evaluation
reports stay in the ignored output directory. The embedded 237,320-byte
Leaky ReLU payload has SHA-256
`c8887a1462e3846215b1d28835fdf119afdaaad473f22d400930af48a8fda441`.

`--activation silu` trains the comparison model with the same reference
data. Evaluate its weights with the corresponding SiLU shader. Seeds are
fixed; library and hardware differences can change the weights.

## Regenerate the phase asset

```powershell
.\.venv\Scripts\python.exe common/revelation_phase.py
.\.venv\Scripts\python.exe -m unittest discover -s tests
```

The first three rows of the embedded asset are the original Revelation
`CloudPhaseLut.bin` bytes at revision
`d5911393856d9dd97375b46a1a088e6b8fddd755`. The generator preserves those bytes,
adds the finite-disk rows used by the camera, and derives the angular moments
and sky quadrature from the interpolated PDF. To import the original asset in
a fresh checkout, pass its path with `--source`.
