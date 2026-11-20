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



def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="An empty directory for generated files")
    parser.add_argument("--device", choices=("cpu", "cuda"), help="Defaults to CUDA for training and CPU for smoke")
    parser.add_argument("--activation", choices=("silu", "leaky_relu"), default="leaky_relu")
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

    fields, labels = output / "fields", output / "labels"
    smoke_flags = ["--smoke"] if args.smoke else []
    print(f"[{profile}] PyTorch {torch.__version__} on {device}. Output: {output}", flush=True)
    for script, arguments in (
        ("capture_fields", [fields]),
        ("transport", [fields, labels]),
        ("fit_solar", [labels, output, "--device", device, "--activation", args.activation]),
    ):
        subprocess.run([sys.executable, "-B", str(source / "hanpi" / (script + ".py")),
                        *map(str, arguments), *smoke_flags], cwd=output, env=env, check=True)
    binary = output / "cloud_neural_lighting.bin"
    print(f"[export] {binary} ({binary.stat().st_size} bytes)")
    print(f"[sha256] {hashlib.sha256(binary.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
