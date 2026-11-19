# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: Apache-2.0
"""Generate references, train both cloud lighting channels, and export the Vulkan weights."""

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys

import torch

from neural.feature_layout import SKY_FEATURE_NAMES
from neural.model import MultiScatterMLP


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="An empty directory for generated files")
    parser.add_argument("--device", choices=("cpu", "cuda"), help="Defaults to CUDA for training and CPU for smoke")
    parser.add_argument("--smoke", action="store_true", help="Validate the complete pipeline on tiny inputs")
    args = parser.parse_args()

    source = Path(__file__).resolve().parent
    profile = "smoke" if args.smoke else "reproduction"
    output = (args.out or source / "outputs" / profile).resolve()
    device = args.device or ("cpu" if args.smoke else "cuda")
    if device == "cuda" and not torch.cuda.is_available():
        parser.error("CUDA is unavailable. Install a CUDA PyTorch wheel or specify --device cpu.")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        parser.error(f"Choose an empty output directory: {output}")
    output.mkdir(parents=True, exist_ok=True)
    env = {**os.environ, "PYTHONPATH": os.pathsep.join(filter(None, (str(source), os.environ.get("PYTHONPATH"))))}

    def run_module(module: str, *arguments: object) -> None:
        command = [sys.executable, "-B", "-m", module, "--device", device, *map(str, arguments)]
        subprocess.run(command, cwd=output, env=env, check=True)

    def generate(dataset: Path, split: str, width: int, height: int, spp: int,
                 depth: int, seed: int, *, only: str = "") -> None:
        filters = ["--only", only] if only else []
        if args.smoke:
            width, height, spp = 16, 10, min(spp, 4)
            filters.append("--smoke")
        run_module("reference.render_reference", "--out", dataset, "--split", split,
                   "--width", width, "--height", height, "--spp", spp,
                   "--max-depth", depth, "--seed", seed, *filters)

    def train_sky(dataset: Path, run: Path, iterations: int, learning_rate: str, eval_every: int,
                  *, initial: Path | None = None) -> None:
        steps = 96
        if args.smoke:
            iterations, steps, eval_every = 2, 16, 1
        initialize = ["--init-model", initial] if initial is not None else []
        run_module("neural.train", "--index", dataset / "index.json", "--out", run,
                   "--iterations", iterations, "--steps", steps, "--lr", learning_rate,
                   "--eval-every", eval_every, "--hidden", 96, "--layers", 4, "--out-scale", "0.25",
                   "--seed", 1234, "--lambda-log", "0.5", "--channel", "sky", "--sky-boundary", "sky", *initialize)

    base_data = output / "reference" / "depth8"
    deep_data = output / "reference" / "depth64"
    print(f"[{profile}] PyTorch {torch.__version__} on {device}. Output: {output}", flush=True)
    sky_base = output / "runs" / "exp4_sky"
    sky_deep = output / "runs" / "depth64_sky"
    sky_views = "A_s25_t0_sky,A_s25_v0_sky" if args.smoke else ""
    generate(base_data, "train_sky,val_sky", 160, 90, 512, 8, 2024, only=sky_views)
    generate(deep_data, "train_sky,val_sky", 96, 54, 128, 64, 31415, only=sky_views)
    train_sky(base_data, sky_base, 7000, "0.002", 500)
    train_sky(deep_data, sky_deep, 2000, "0.0007", 200, initial=sky_base / "model_best.pt")
    sky_model, _ = MultiScatterMLP.load(sky_deep / "model_best.pt")
    sky_export = sky_deep / "model_best_numpy.npz"
    sky_model.export_numpy(sky_export, SKY_FEATURE_NAMES)
    fields, labels = output / "hanpi/fields", output / "hanpi/labels"
    smoke_flags = ["--smoke"] if args.smoke else []
    for script, arguments in (("capture_fields", [fields]), ("transport", [fields, labels]),
                              ("fit_solar", [labels, sky_export, output, "--device", device])):
        subprocess.run([sys.executable, "-B", str(source / "hanpi" / (script + ".py")),
                        *map(str, arguments), *smoke_flags], cwd=output, env=env, check=True)
    binary = output / "cloud_neural_lighting.bin"
    print(f"[export] {binary} ({binary.stat().st_size} bytes)")
    print(f"[sha256] {hashlib.sha256(binary.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
