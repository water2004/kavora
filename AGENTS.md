# Agent guide

This repository contains four independently named components:

- `kavora/`: Fabric 26.3 integration, Sodium capture and settings.
- `kavora-runtime/`: native Vulkan runtime, custom Remix C API 1.5.0 and Slang.
- `kavora-sdk/`: Minecraft-independent Java 25 FFM SDK and support DLL.
- `kavora-horizon/`: optional voxel far-terrain add-on.

Read the nearest component guide and [CONTRIBUTING.md](CONTRIBUTING.md).
Source and build definitions take precedence over prose. Shared guides live in
`docs/`; do not create a second architecture document that duplicates source.

## Build

Run Gradle from this root. See [the build guide](docs/GETTING_STARTED.md).
`release` produces installable JARs in `dist/`; ordinary mod `build` produces
a lightweight development JAR. Runtime binaries must be source-built and carry
validated provenance. Do not substitute upstream 0.x binaries.

Do not run staging or Gradle against a live client from this checkout.
Do not start GPU tests unless the current task requires them. Compilation alone
does not prove Vulkan sharing, presentation or image correctness.

## Cross-component contracts

- C API changes cross the runtime header, SDK header, layouts, descriptors,
  facade and callers together. Keep both copied headers byte-identical.
- Render thread owns Remix handles. Workers publish CPU data through bounded,
  generation-tagged queues. World transitions invalidate stale work.
- One Vulkan device and one runtime presenter; no OpenGL fallback, second LOD
  renderer, JNI or JNA binding.
- Sodium and Fabric API remain external JARs. Java SDK classes are flattened
  into the main mod; the far-terrain add-on remains separate.
- Release is traditional BLAS/TLAS. Preserve experimental Git history outside
  the release source tree rather than restoring CLAS/PTLAS branches here.
- Every retained mesh/buffer reference has one corresponding release.
- Visual presets fill editable options; DLSS and frame generation are separate.
- Keep Gradle JVM defaults and the development launcher's
  `kavora/packaging/launcher-defaults.json` aligned.

## Licenses and local files

Preserve component licenses and source-file notices. See `LICENSES/README.md`
and `THIRD_PARTY_NOTICES.md`. Do not relabel LGPL-derived code as Apache/MIT.
Include the matching source when preparing binary releases.

Build output, downloaded SDK binaries, Packman junctions, captures, worlds,
resource-pack downloads and machine-local settings are not source. Never write
through a shared-cache junction or commit account data. Root Git submodules pin
the public runtime dependencies; update `.gitmodules` and gitlinks together.