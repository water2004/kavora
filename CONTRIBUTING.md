# Contributing

Kavora is a community Minecraft 26.3 Vulkan renderer. Contributions should follow
the license of the component they modify; preserve original copyright and
adaptation notices. See [the license overview](LICENSES/README.md).

## Build environment

The release target is Windows x64. Install:

- A 64-bit JDK 25, with `JAVA_HOME` pointing to it.
- Visual Studio's Desktop development with C++ workload, C++20 support and a Windows SDK.
  The native scripts locate the toolchain through `vswhere`.
- Meson 1.8.2 or newer, Ninja and Python 3.9 or newer for the native build.
- The Vulkan SDK and [Slang 2026.20](https://github.com/shader-slang/slang/releases/tag/v2026.20).
- Git and network access for pinned submodules, Packman and Maven dependencies.

Run all commands below from the repository root. Exit any client launched from
this checkout before staging or building: its DLLs may be locked, and staging
must not replace files used by a running client.

## Build and run

Build the native runtime, validate and stage it, then assemble the release:

```powershell
git submodule update --init --recursive
.\build-runtime.ps1 -SlangCompiler 'C:\Tools\Slang\bin\slangc.exe'
.\stage-runtime.ps1
.\gradlew.bat release
```

The Slang path is an example; point it to your installation. The runtime script
configures a Release build in `kavora-runtime/_BuildMinecraft/`, compiles the
runtime and shaders, and prepares `kavora-runtime/_output/`. Rerun the runtime
script for incremental rebuilds; Meson reuses its build directory. Then rerun
`stage-runtime.ps1` before building the mod. Use `-Profiling` on the runtime
script to enable on-demand Tracy instrumentation. More details are in the
[build guide](docs/GETTING_STARTED.md).

This project requires the custom Remix C API **1.5.0**, independently of Kavora's
own version. Upstream 0.x binary packages are incompatible. The runtime build
generates `.runtime-provenance.json` for the completed DLL. `stage-runtime.ps1`
validates that provenance, the DLL hash and PE architecture before copying the
output into `kavora/runtime/win64/`. The `water2004/dxvk-remix` provenance value
identifies the custom API branch; it is not the mod's display name.

Gradle builds the separate Windows support library under
`kavora-sdk/build/native/win64/`. Do not write support-library outputs or local
changes into Packman-managed directories.

| Command | Purpose |
| --- | --- |
| `.\gradlew.bat build` | Build the Java projects and run their JVM checks |
| `.\gradlew.bat test` | Run the main mod and add-on JVM tests |
| `.\gradlew.bat :kavora-sdk:build` | Build the standalone Java SDK |
| `.\gradlew.bat :kavora:compileGametestJava` | Compile client GameTests |
| `.\gradlew.bat :kavora:runClient` | Launch a development client |
| `.\gradlew.bat release` | Verify release contents and collect artifacts in `dist/` |

`build` produces a lightweight development mod JAR. `release` assembles the
installable `Kavora.jar` and `Kavora-horizon.jar`, the SDK JAR, three source JARs,
and `SHA256SUMS.txt`. Packaging verifies the native payload, provenance, resources
and required license texts. It does not launch the game or run GPU tests.

The main mod includes the SDK classes directly. Sodium and Fabric API stay
external JARs; the optional add-on also remains a separate JAR. Neither the SDK
JAR nor the source JARs need to be installed by players.

The JVM requires `--enable-native-access=ALL-UNNAMED`; the Gradle development run
already supplies it. The optional signed-in Prism workflow and profiling tools
are documented under [kavora/tools](kavora/tools/README.md). They require Python
3.15 or newer and uv 0.13 or newer, and are not needed for ordinary Gradle builds.

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

Native test builds register `test_particle_drag`, `test_particle_screen_size`,
and `test_water_particle_contracts` with Meson. The optional Vulkan numerical
test runs with `python kavora-runtime/tests/rtx/unit/test_water_particle_gpu.py`
in an environment with NumPy and SlangPy. It reads back the water compute
passes and the shared particle and temporal-history routines independently of
Minecraft. Use the retained water GameTest to check material and PSR integration.

## Pull requests

Describe the concrete problem and resulting behavior. Explain changed data
ownership or ABI, validation and known limitations. Keep temporary captures,
world copies, downloaded packs and one-off analysis scripts out of source.
Publish matching modified sources and third-party notices with binary releases.
