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
production layers on Vulkan. The CPU path tracer generates solar and sky
targets with the normalized scalar Revelation table for both the scattering
PDF and the inverse CDF. It uses 96 paths per sample and 512 for the evaluation
region. Four regions fit the model, one selects checkpoints, and a sixth
reports independent errors including forward and backward subgroups. The
training uses 3,600 steps per channel on CUDA. The smoke run reduces the
problem size and uses CPU training.

Both networks use Leaky ReLU with slope 0.01 and a Softplus output. The solar
network sees the LUT phase at the viewing angle. Both see the first phase
moment as a material feature. The checkpoint folds feature normalization
into the first affine layer. The packer pads the sky inputs to 16 columns,
matching the cooperative-vector input layout. The runtime converts the FP32
checkpoint to device-native FP16 weights at upload.

Keep `cloud_neural_lighting.bin` and the generated
`cloud_source_interpolation.slang` together when deploying a new fit. The
binary belongs in `src/dxvk/rtx_render/clouds/` and the shader in
`src/dxvk/shaders/rtx/algorithm/`. Data, caches, checkpoints, and evaluation
reports stay in the ignored output directory. The embedded 237,320-byte
Leaky ReLU payload has SHA-256
`1a02df286eb8f6fe311b910bb9628a3cb577ed09a801bf6e8f83738a33674e49`.

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
