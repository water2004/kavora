# Cloud scattering training

This workflow regenerates the reference images, trains the solar and sky
multiple-scattering models, and packs them as `cloud_neural_lighting.bin` for Kavora's
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
exercise the pipeline. Capturing the density fields requires a Vulkan device with
SlangPy support. The full run uses CUDA for fitting and CPU path tracing for solar targets.
`--out` selects an empty directory for a new run. The default directories are
`outputs/smoke/` and `outputs/reproduction/`.

| Stage | Reference | Training |
| --- | --- | --- |
| `exp4_sky` | Upper-hemisphere illumination, depth 8, 160×90, 512 spp | 7,000 iterations from scratch |
| `depth64_sky` | Upper-hemisphere illumination, depth 64, 96×54, 128 spp | 2,000 iterations from the best `exp4_sky` checkpoint |
| `hanpi_sun` | Six weather regions in both production cloud layers, five solar elevations. Unit-albedo dual-HG transport with 96 paths per sample and 512 for the independent evaluation region. | 3,600 optimizer steps. Four regions for fitting, one for checkpoint selection, one for evaluation. |

The sky trainer records settings and validation losses in `runs/`. The pipeline
exports the solar network with minimum validation MAE and the **best** `depth64_sky`
checkpoint. It folds feature normalization into each input layer and pads the sky
network's nine inputs to the solar network's 15-column layout. The solar fit also
exports `cloud_source_interpolation.slang` from the fitting regions. Deploy this
file in `src/dxvk/shaders/rtx/algorithm/` with the matching weight payload.
The runtime selects its sampling directions relative to each light. The file and its SHA-256
are printed at the end. Data, caches, checkpoints, and candidate weights stay
under the ignored output directory. [Meson](../../src/dxvk/meson.build) selects
the trusted weight file embedded by the runtime build.

The generator and trainer use fixed seeds. A fresh run reproduces the recipe.
PyTorch, GPU, and numerical library differences can change the resulting weights.

## Pack a saved export

```powershell
.\.venv\Scripts\python.exe pack_weights.py outputs/reproduction/hanpi_sun.npz outputs/reproduction/runs/depth64_sky/model_best_numpy.npz outputs/reproduction/cloud_neural_lighting.bin
.\.venv\Scripts\python.exe -m unittest discover -s tests
```

The packer checks feature order, dimensions, and numeric values. The solar
export used in this build is
`ca4b06ab35ca3bab9b7a0c3c37e700e1b7c549137d5e72b2555d2608f03b1512`.
The sky export is the original experiment's selected checkpoint,
`217843c9dbedd97a32612b4b41d431e003af4b7c68e793efcb2109a4b6899220`.
Together they reproduce the embedded 236,552-byte payload with
SHA-256 `bb8444dd3c7b4e1be246de5f2edf3e8a180e633f94e3178adff88733abb652de`.
