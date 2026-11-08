# Kavora SDK

`kavora-sdk` is a Minecraft-independent Java facade and Java 25 Foreign
Function and Memory binding for the source-built RTX Remix custom C API 1.5.0.
It calls the matching [runtime fork](https://github.com/water2004/dxvk-remix)
directly. It does not implement rendering or interpret game state.

Game integration belongs in the mod. This module must not import
`net.minecraft` or `net.fabricmc`; the root `KavoraJavaBoundaryTest` enforces that
boundary. There is no JNI or JNA bridge. `kavora-support.dll` is a separate
Windows crash/process helper and does not marshal Remix calls.

## Sources and ownership

| Path | Responsibility |
|---|---|
| `src/org/edtp/kavora/sdk/RemixAPI.java` | Public facade and error handling |
| `src/org/edtp/kavora/sdk/RemixBridge.java` | Foreign calls and native memory marshalling |
| `src/org/edtp/kavora/sdk/RemixLayouts.java` | Generated native structure layouts |
| `native/include/remix_c.h` | Matching copy of the runtime's custom C API |
| `native/crash_support.cpp` | Windows crash and process support |
| `ffm-test/` | C/Java ABI probes, mock runtime and lifecycle tests |

Java handles are raw `long` values; callers own their lifetime. The mod creates,
updates and destroys rendering handles on the render thread. Direct-buffer
arguments must remain alive until the foreign call returns. The bridge reads
buffers from index zero, independently of their current position; callers must
provide the documented native byte order and enough bytes for every element.
Native extension chains allocated by the binding must be released by their owner.

`updateLight` calls the additive `remixapi_UpdateLight` export without changing
the 1.5.0 interface-table layout. It copies the same `LightInfo` and extension
structures as creation, retaining the native handle and temporal light identity.
Creating another light with the same hash creates a separate resource. Dome
and analytical lights keep their respective kind across an update. The loader
requires this export from the matching source-built runtime.

`VulkanClientFrameInfo` extends Present with a host-owned color image on the
runtime's device. Signal its ready timeline after rendering and wait for the
returned consumed timeline before overwriting it. Presenting image zero
synchronously releases borrowed native views before the host destroys an image.
Presentation counters are asynchronous and restart with their epoch. NGX frame
generation capabilities are queried separately from the selected multiplier.

The DLL loader reference lives until process exit. Closing a rendering session
releases its API/device resources without unloading the library, whose Windows
Tracy teardown can otherwise terminate the JVM during a later reinitialization.

## Building and staging

Use Java 25, Windows and the Visual Studio C++ toolchain. The `:kavora-sdk` Gradle project builds the independent Java library and
source JAR. It builds the support library in
`kavora-sdk/build/native/{win64|arm64}/`. The mod depends on this project.
Running requires `--enable-native-access=ALL-UNNAMED`, included in Gradle and the development launcher defaults.

First build the matching runtime from source, generate provenance from its
completed output, and stage that output:

```powershell
.\stage-runtime.ps1 -Source <runtime-output>
.\gradlew.bat build test
```

Staging validates custom API 1.5.0, PE architecture, source revision and DLL
SHA-256. Published 0.x packages and upstream 1.21.1 installers are incompatible;
`sdk packman` cannot supply a fallback. `sdk restage` refreshes the recorded
source snapshot. Runtime outputs remain ignored under
`kavora/runtime/{win64|arm64}/`. Never write support-library outputs into the
read-only Packman texture junction.

To build only the support library, use `gradlew.bat :kavora-sdk:compileNativeWin64`
(`:kavora-sdk:compileNativeArm64` for ARM64), or
`kavora-sdk\native\build_native.bat x64` (`arm64` for cross-compilation).
See [Getting Started](../docs/GETTING_STARTED.md) for the complete staging and
packaging inventory. Stop clients from this checkout before Gradle stages files.

## API changes and validation

`TextureFromMemoryInfo.immutable` opts out of subsequent texture patches through
the `TextureImmutableEXT` creation extension. It transfers the native CPU mip
chain to the initial upload and releases it after staging has consumed it.
Omitting the extension preserves mutable-texture behavior. The direct Java pixel
buffer must remain alive until creation returns in either mode. Authored mip
chains can be combined with the immutable extension; texture purpose and mip
filtering are unchanged.

Change renderer behavior and the C API in the runtime fork, marshalling and
layouts here, and game capture in the mod. Update the runtime header, copied
header, `StructType.java`, layouts, foreign descriptors, bridge, facade, data
holders and callers together. A matching version label alone does not prove ABI
compatibility. `ffm-test/generate_layouts.py --check` detects stale layouts.

With `JAVA_HOME` pointing at Java 25, run from the repository root:

```powershell
.\kavora-sdk\ffm-test\test-layouts.bat
.\kavora-sdk\ffm-test\test-bridge.bat
.\gradlew.bat build test :kavora:compileGametestJava
```

The C layout probe checks Java sizes, alignments and field offsets. The mock
runtime exercises every foreign entry point, extension marshalling and repeated
shutdown/reinitialization. These tests do not establish native initialization,
shared-device presentation, image correctness or performance. Validate the exact
source-built runtime with Gradle `runClient`/`runClientGameTest` on the Vulkan backend, or the signed-in Prism workflow.

[CONTRIBUTING.md](../CONTRIBUTING.md) and the root
[FFM binding rules](AGENTS.md) apply to changes here.

## License

Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.

The binding is covered by the repository's Apache License, Version 2.0.
See [LICENSE](LICENSE), [NOTICE](../NOTICE) and
[THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) for applicable terms and
attributions.
