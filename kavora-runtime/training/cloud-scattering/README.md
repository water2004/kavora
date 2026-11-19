# Cloud scattering training

This workflow regenerates the reference images, trains the solar
multiple-scattering model, and packs it as `cloud_neural_sun.bin` for Kavora's
Vulkan shader. The procedural density, Monte Carlo reference renderer, and
PyTorch trainer come from GBwater's `Neural_Volumetric_Cloud` experiment at
`0597294234d7a3902131e0ea286a12759661903c`. The imported training code uses the
repository's [Apache 2.0 license](../../../LICENSE). The relocated Vulkan packer
retains its MIT notice.

## Setup

From this directory, create a Python 3.11 environment. The reference CUDA setup
uses the CUDA 12.8 build of PyTorch 2.10.0. On Linux, use `.venv/bin/python` in
the same commands. For a CPU-only installation, select the `cpu` PyTorch index.

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install torch==2.10.0 --index-url https://download.pytorch.org/whl/cu128
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

See [PyTorch's wheel list](https://pytorch.org/get-started/previous-versions/#v2100)
for supported platforms and CUDA builds.

## Generate, train, and export

```powershell
.\.venv\Scripts\python.exe run.py --smoke
.\.venv\Scripts\python.exe run.py
```

The smoke run uses small images and two optimizer steps per stage on CPU to
exercise the pipeline. The full run uses CUDA and the recorded training recipe.
`--out` selects an empty directory for a new run. The default directories are
`outputs/smoke/` and `outputs/reproduction/`.

| Stage | Reference images | Training |
| --- | --- | --- |
| `exp4` | Depth 8, 160×90, 512 spp. One validation view uses 2048 spp. | 7,000 iterations from scratch |
| `depth64_sun` | Depth 64, 96×54, 128 spp | 2,000 iterations from the best `exp4` checkpoint |
| `depth64_angles` | The same depth-64 set plus eight views at 5° and 12° solar elevations | 1,800 iterations from the best `depth64_sun` checkpoint |

Each run records its training settings and validation losses in `runs/`. The
pipeline exports the **last** `depth64_angles` checkpoint, folds the feature
normalization into the first layer, and appends the shader's 25 directions and
second-order spherical harmonic projection. The resulting file and its SHA-256
are printed at the end. Data, caches, checkpoints, and candidate weights stay
under the ignored output directory. [Meson](../../src/dxvk/meson.build) selects
the trusted weight file embedded by the runtime build.

The generator and trainer use fixed seeds. A fresh run reproduces the recipe.
PyTorch, GPU, and numerical library differences can change the resulting weights.

## Pack a saved export

```powershell
.\.venv\Scripts\python.exe pack_weights.py outputs/reproduction/runs/depth64_angles/model_numpy.npz outputs/reproduction/cloud_neural_sun.bin
.\.venv\Scripts\python.exe -m unittest discover -s tests
```

The packer checks feature order, dimensions, and numeric values. It also accepts
the original `sun.npz` identified by SHA-256
`75d0aaddf7e87fbf74412f269998620b05ca1f4b2a5ed4fedd72ae42a63382b9`.
In the pinned environment it repacks to the embedded 119,476-byte payload with
SHA-256 `09f50f813810e98a774da591b790b48995f54e06c59341f0dc902392bf67396e`.
