# Third-Party Notices

This project incorporates or redistributes the third-party components listed
below. The referenced license texts are included in this repository at the
locations given.

## Vendored source code

### Iris shader settings interface

- Adapted sources: `RtxSettingsScreen.java`, `ShaderMenuButton.java`,
  `ShaderOptionWidget.java`, `ShaderSliderWidget.java`, `ShaderCycleWidget.java`, and `settings/iris/IrisWidgets.java`.
- Unchanged asset: `assets/rtx-remix-renderer/textures/gui/iris_widgets.png`.
- Copyright Iris contributors; adaptation notices appear in each source file.
- License: LGPL-3.0-only. The corresponding LGPL and GPL license texts and
  the source revision and modification notice ship in every JAR under
  `META-INF/licenses/iris/`.
- Source: https://github.com/IrisShaders/Iris, revision
  `bff1e69cb6c5519d8745784aa9c8b92984de67e7`.
- Modified sources are included in this project's sources JAR and repository.
  These GUI files retain their LGPL license; other files retain their own
  SPDX identifiers. Iris's renderer and shader loader are not embedded.

### Gradle wrapper

- Files: `gradlew.bat`, `gradle/wrapper/`
- Copyright Gradle Inc. and the original authors
- License: Apache License, Version 2.0 (see [LICENSE](LICENSE))
- Source: https://github.com/gradle/gradle

## Redistributed binaries (packaged releases)

Packaged release JARs bundle the NVIDIA RTX Remix runtime (staged under
`native/` inside the JAR). The runtime is built from
[dxvk-remix](https://github.com/NVIDIAGameWorks/dxvk-remix) and carries the
third-party components that project redistributes. For the pinned runtime
version those are:

- DXVK
- Pixar OpenUSD, together with its USD plugin payload
- oneAPI Threading Building Blocks (oneTBB)
- Sentry Native SDK and Google Crashpad (crash reporting)
- Intel XeSS
- NVIDIA DLSS, including the Ray Reconstruction, Frame Generation, and
  3D-Guided Neural Rendering models
- NVIDIA Real-Time Denoisers (NRD)
- NVIDIA Neural Radiance Cache (NRC)
- NVIDIA Spatial Hash Radiance Cache (SHaRC)
- NVIDIA Reflex low latency SDK (`NvLowLatencyVk`)
- NVIDIA RTX IO
- NVIDIA Nsight Aftermath
- NVIDIA CUDA runtime and NVRTC

That list names the redistributed packages; the license-text entries below
also enumerate the open-source code compiled into them, such as Dear ImGui,
xxHash, and zlib. The exact set moves with the pinned runtime version. The
runtime is built against OpenUSD without Python support, so the payload
contains no Python interpreter and no USD Python bindings.

The OpenUSD and oneTBB binaries in the payload come from the NVIDIA-built
OpenUSD distribution pinned by dxvk-remix as `open_usd`
`25.11+07744417-windows-x86_64-release-nopython` (win64) and
`25.11+265538d9-windows-aarch64-release-nopython` (arm64). The payload carries
the OpenUSD core libraries (`usd_*.dll`), their plugin resources under `usd/`,
and oneTBB 2021.12 as `tbb12.dll` / `tbbmalloc.dll`. That distribution ships
no license files of its own, so the upstream license texts for its components
are reproduced under
[rtx-remix-minecraft/packaging/licenses/remix-runtime/openusd/](rtx-remix-minecraft/packaging/licenses/remix-runtime/openusd/).
OpenUSD is licensed under the Tomorrow Open Source Technology License 1.0, and
its license file also carries the notices for the third-party code compiled
into the OpenUSD libraries, among them RapidJSON, double-conversion, LZ4,
libdeflate, pugixml, Tessil robin-map, CLI11, and PEGTL.

The applicable license texts are included in this repository and in packaged
JARs:

- [rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.dxvk.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.dxvk.txt)
  covers DXVK, zlib/libpng license
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE-MIT.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE-MIT.txt)
  covers the RTX Remix runtime additions, MIT license
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/openusd/openusd-LICENSE.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/openusd/openusd-LICENSE.txt)
  covers OpenUSD, Tomorrow Open Source Technology License 1.0, together with
  the notices for the components compiled into it
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/openusd/onetbb-LICENSE.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/openusd/onetbb-LICENSE.txt)
  covers oneTBB (`tbb12.dll`, `tbbmalloc.dll`), Apache License, Version 2.0
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/openusd/onetbb-itt-notify-LICENSE.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/openusd/onetbb-itt-notify-LICENSE.txt)
  covers the Intel Instrumentation and Tracing Technology (ITT) Notify code
  compiled into both oneTBB DLLs, BSD 3-Clause; it is entry 1 of the oneTBB
  2021.12.0 third-party programs file, whose other entries concern code that
  is not compiled into these Windows binaries
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.xess.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.xess.txt)
  covers Intel XeSS (`libxess.dll`), Intel Simplified Software License
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.sentry-native.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.sentry-native.txt)
  covers the Sentry Native SDK 0.15.3 linked into the runtime, MIT license
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.crashpad.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.crashpad.txt)
  covers Google Crashpad, Apache License, Version 2.0: `crashpad_handler.exe`,
  `crashpad_wer.dll`, and the Crashpad client statically linked into `d3d9.dll`
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/crashpad/](rtx-remix-minecraft/packaging/licenses/remix-runtime/crashpad/)
  covers the third-party code Crashpad vendors and the runtime therefore
  carries: mini_chromium (BSD 3-Clause, The Chromium Authors) in `d3d9.dll`
  and `crashpad_handler.exe`, and MPack (MIT) and Gregory Pietsch's getopt
  (public domain, credit requested) in `crashpad_handler.exe`
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.zlib.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.zlib.txt)
  covers zlib, zlib license, compiled into `crashpad_handler.exe` (1.2.13) and
  the Nsight Aftermath library (`GFSDK_Aftermath_Lib.*.dll`, 1.2.7 on win64
  and 1.3.1 on arm64)
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.imgui.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.imgui.txt)
  covers Dear ImGui 1.88, MIT license, compiled into `d3d9.dll` for the
  runtime menu
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.xxhash.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.xxhash.txt)
  covers xxHash 0.8, BSD 2-Clause License, compiled into `d3d9.dll`
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/ThirdPartyLicenses.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/ThirdPartyLicenses.txt)
  collects license texts carried by the dxvk-remix build. This file covers GLI,
  Tracy, Slang, Omni-core-materials, glslangvalidator, the NVIDIA RTX SDKs
  license (DLSS, NGX, RTXGI, RTXDI, and NRD), the NVIDIA Aftermath SDK, the
  Turbo colormap approximation, the Meson build system, the ACES tonemapper,
  and xxHash. It is not a complete inventory of the redistributed runtime
  payload, which is why the texts above are shipped separately.
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.sharc.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/LICENSE.sharc.txt)
  covers the NVIDIA Spatial Hash Radiance Cache at the pinned source revision, whose shader headers
  are compiled into `d3d9.dll`; it is the NVIDIA RTX SDKs license as that
  project publishes it, with its own supplement
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/rtxio/licenses.txt](rtx-remix-minecraft/packaging/licenses/remix-runtime/rtxio/licenses.txt)
  is the third-party notice file shipped with the RTX IO SDK, reproduced
  verbatim: libdeflate 1.8 (MIT) and NVIDIA's Apache License, Version 2.0
  GDeflate changes, compiled into `rtxio.dll`
- [rtx-remix-minecraft/packaging/licenses/remix-runtime/nrc/](rtx-remix-minecraft/packaging/licenses/remix-runtime/nrc/)
  covers the open-source code compiled into the Neural Radiance Cache library
  (`NRC_Vulkan.dll`), which embeds tiny-cuda-nn and its dependencies as source
  for runtime compilation: tiny-cuda-nn and CUTLASS (BSD 3-Clause, NVIDIA),
  pcg32 (Apache License, Version 2.0, Wenzel Jakob), {fmt} (MIT),
  nlohmann/json (MIT), and GLM (Happy Bunny License or MIT). The NRC SDK ships
  no third-party notice file of its own and the embedded copies carry no
  version markers, so these are the current upstream texts.

NVIDIA-owned SDK components retain their separate terms, not the licenses
of the surrounding renderer. The exact NRC, DLSS, NRD and frame-generation
license texts are reproduced beside the other runtime notices as
`LICENSE.nrc.txt`, `LICENSE.dlss.txt`, `LICENSE.nrd.txt`, and `LICENSE.dlfg.txt`.
Do not apply this repository's Apache/MIT source licenses to those SDK binaries.
CUDA runtime and NVRTC terms are included as `CUDA-EULA.html`, captured from
[NVIDIA's CUDA EULA](https://docs.nvidia.com/cuda/eula/index.html).

## External dependencies

Minecraft, Java 25, Fabric Loader, Fabric API and Sodium are supplied by the
player and are not bundled in the mod JAR. Their own licenses apply. The
optional developer launcher uses an already installed Prism Launcher and the
user's signed-in profile; there is no release installer.

## Java FFM SDK and C API

`rtxremix-java/` is the independently buildable Java 25 FFM facade, under
Apache-2.0 except for the copied NVIDIA MIT C API header. The header matches
`dxvk-remix/public/include/remix/remix_c.h`, custom API 1.5.0. The separate
`rtxremix-support.dll` implements Windows crash/process support. There is no
JNI bridge. Native dependencies are not embedded in the SDK's Java JAR.

## Build tools

The Gradle wrapper and NVIDIA Packman bootstrap retain their Apache-2.0
notices. Packman fetches pinned build dependencies and runtime textures;
source-built runtime staging does not download an incompatible 0.x SDK.

## NASA Milky Way map

`rtx-remix-minecraft/packaging/runtime-textures/milky_way.dds` is derived from NASA SVS's
[Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851/), a public-domain
visualization of stellar catalogue data. Credit: NASA/Goddard Space Flight
Center Scientific Visualization Studio; Gaia DR2: ESA/Gaia/DPAC.

- [rtx-remix-minecraft/packaging/licenses/NASA-Deep-Star-Maps.txt](rtx-remix-minecraft/packaging/licenses/NASA-Deep-Star-Maps.txt)
  records the source, processing details, credit and redistribution notice.

## Caustica-Horizon End environment

The native End sky adapts the directional planet, rings, accretion flow,
nebula and star functions from
[Caustica-Horizon](https://github.com/West68/Caustica-Horizon), revision
`ea3dc4bf67fc3bfce1860dbb025c71626b26e983`. Copyright (c) 2026 ComfyFluffy
and contributors; Caustica-Horizon is maintained by Ruiwen68 (West68).

The adapted shader module remains **LGPL-3.0-or-later**. It feeds the current
Vulkan dome compositor and shared path-traced environment; it does not embed
the other renderer or its external ITRP/SEUS textures.

- [Integration notice](rtx-remix-minecraft/packaging/licenses/Caustica-Horizon-End.txt)
- [LGPLv3](rtx-remix-minecraft/packaging/licenses/Caustica-Horizon-LGPL-3.0.txt)
- [GPLv3](rtx-remix-minecraft/packaging/licenses/Caustica-Horizon-GPL-3.0.txt)

## Lunar surface

The lunar RGB/elevation map is NASA SVS CGI Moon Kit data supplied through
Caustica-Horizon at revision `ea3dc4bf67fc3bfce1860dbb025c71626b26e983`.
Lunar surface shading adapts its `realMoon()` function under LGPL-3.0-or-later
into the current Vulkan dome compositor. Camera, reflection and refraction
rays share this environment.

- [Source, credits and integration notice](rtx-remix-minecraft/packaging/licenses/NASA-CGI-Moon.txt)
- [NASA CGI Moon Kit](https://svs.gsfc.nasa.gov/4720/)
- [LGPLv3](rtx-remix-minecraft/packaging/licenses/Caustica-Horizon-LGPL-3.0.txt)
- [GPLv3](rtx-remix-minecraft/packaging/licenses/Caustica-Horizon-GPL-3.0.txt)


## Community Edition cloud field

The native runtime adapts the periodic cloud noise, weather distribution and
height/density profiles from [RTX Remix for Minecraft Community Edition](https://github.com/CuteEthanLeo/rtx-remix-minecraft-ce),
revision `94400621dabc6b315d8e53b701c7be7beffa9303`, under Apache-2.0.
Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors.
Its OpenGL renderer and near-cloud mesh framework are not included.

- [Integration notice](rtx-remix-minecraft/packaging/licenses/RTX-Remix-CE-Clouds.txt)
- [Apache-2.0 license](rtx-remix-minecraft/packaging/licenses/RTX-Remix-CE-Apache-2.0.txt)

## Community Edition material detail

`AutoPbr.java` adapts material classification, luminance-derived normals and
roughness variation from [Community Edition](https://github.com/CuteEthanLeo/rtx-remix-minecraft-ce),
revision `94400621dabc6b315d8e53b701c7be7beffa9303`, under Apache-2.0.
Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors.
The LabPBR conversion also follows CE's perceptual roughness correction.

- [Integration notice](rtx-remix-minecraft/packaging/licenses/RTX-Remix-CE-Materials.txt)
- [Apache-2.0 license](rtx-remix-minecraft/packaging/licenses/RTX-Remix-CE-Apache-2.0.txt)

## Remix Horizons and RocksDB

`remix-horizons/` is LGPL-3.0-only; its full LGPL/GPL texts and existing
copyright notices are retained. Independently authored material descriptors
keep Apache-2.0. RocksDB 10.2.1 is selected under its Apache-2.0 alternative;
its license and LevelDB notice are in `remix-horizons/licenses/` and included
in the add-on JAR. No Distant Horizons engine, SQLite backend, or Voxy source
or resources are included in this implementation.

## Release contents and corresponding source

The installable main JAR carries the runtime notices under
`packaging/licenses/`, Iris notices under `META-INF/licenses/iris/`, and the
repository license overview under `META-INF/licenses/project/`. File paths in
this document refer to the source repository; strip the component prefix when
locating the runtime notice directory inside a JAR.

Modified Java sources are available in the Java source JARs. The complete
modified renderer and shaders are in `dxvk-remix/`; pinned SDK dependencies
are Git submodules. See `docs/GETTING_STARTED.md` for rebuilding or replacing
these components. No signing restriction prevents loading a modified mod or
runtime. A binary release must be accompanied by its matching source revision,
including the modified LGPL components, rather than an unrelated upstream tag.
