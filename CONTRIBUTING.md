# Contributing

This is the community Minecraft 26.3 Vulkan port. Contributions should follow
the license of the component they modify; preserve original copyright and
adaptation notices. See [the license overview](LICENSES/README.md).

## Source ownership

- Game state, Fabric, Sodium and visual settings: `kavora/`.
- Rendering, synchronization, shaders and C API: `kavora-runtime/`.
- Java FFM layouts, marshalling and support DLL: `kavora-sdk/`.
- Optional observed-terrain storage and meshing: `kavora-horizon/`.

API changes update both C headers, FFM layouts and descriptors, facade and
callers together. A matching version label cannot establish ABI compatibility.
Source-build provenance must describe the actual completed runtime DLL.

## Engineering

Keep one Vulkan path and explicit ownership. Render-thread-only native calls,
bounded worker queues and generation checks are correctness requirements.
Never evaluate shared Minecraft entity renderers on workers.

Avoid allocation in per-frame and per-quad hot paths. Retained instances are
submitted where created or mutated. Publish replacement resources before
retiring their predecessors, and release each retained CPU reference once.

Adapt upstream behavior to the current data flow rather than adding parallel
compatibility paths. Replace superseded implementations. Do not repair broken
ordering with arbitrary sleeps, or add scene-specific visual exceptions.

### Cancelling vanilla render work

Minecraft traversal hosts capture. Never cancel the outer world render or skip
terrain setup. Cancel only replaced draws and preserve the state and callbacks
their callers expect. Check the actual pinned 26.3 bytecode before changing an
injection; old 1.21.1 examples are not contracts for this branch.

### Style

Follow surrounding Java style; use two spaces in native C++/Slang. Name owners
and lifetimes clearly. New source needs an accurate copyright and SPDX notice,
not an automatically copied NVIDIA copyright. Adapted code retains its license.
PowerShell used with Windows PowerShell 5.1 must be ASCII or UTF-8 with BOM.
Use game-defined input constants rather than hardcoded mouse/key values.

## Validation

See [the build guide](docs/GETTING_STARTED.md). Use focused JVM or native
contracts for meaningful logic changes, and compile affected Java and shaders.
Do not claim playability or presentation correctness from compilation.

Rendering changes need a representative fixed scene, native-resolution timing,
image inspection and memory measurements. Keep DLSS, frame generation and
quality settings explicit. Use Nsight for GPU execution evidence; an isolated
shader statistic or a noisy FPS sample cannot establish a whole-frame gain.
Report actual checks and any missing hardware validation.

## Pull requests

Describe the concrete problem and resulting behavior. Explain changed data
ownership or ABI, validation and known limitations. Keep temporary captures,
world copies, downloaded packs and one-off analysis scripts out of source.
Publish matching modified sources and third-party notices with binary releases.