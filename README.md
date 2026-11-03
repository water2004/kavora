# RTX Remix for Minecraft

This branch ports the Fabric renderer to Minecraft Java Edition 26.3, using
Vulkan exclusively for the game renderer and Remix interoperation.
It uses Java 25 and the source-built RTX Remix runtime from
`water2004/dxvk-remix`, with its custom 1.5.0 C API.

**Integration is still pending.** Compilation and unit tests do not establish
that a playable client, presentation, or every captured scene works.
Build from source and verify the matching runtime before treating an artifact
as a release. Upstream Minecraft 1.21.1 installers, JARs, and published 0.x
Remix SDK packages are incompatible with this branch.

## What To Expect

The upstream mod was built as a technology showcase and tuned against one showcase
world, built for this project by [BlockWorks](https://www.blockworks.uk/) (see
[Showcase World And Art Pack](#showcase-world-and-art-pack)), and the places
that world visits received the most testing. The upstream project has
[known issues](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues),
and there are certainly bugs yet to be discovered.

Three areas have had less attention than the rest:

- **Other mods.** It has had limited testing alongside other mods, and a
  renderer that takes over the scene the way this one does is likely to
  conflict with anything that changes rendering or the chunk pipeline.
- **A full playthrough.** Blocks, mobs, dimensions, and situations the showcase
  never reaches have had less care, and some will look wrong or run slowly.
- **The 26.3 port.** The rendering and native interfaces changed substantially.
  The feature descriptions below describe the behavior being preserved;
  validate it on this branch before relying on it.

Sodium 0.9.2 is required for terrain rendering and the shared video settings UI.
Sodium and Fabric API remain separate JARs. Voxy is unsupported. Far-terrain
rendering is an optional [Remix Horizons extension](docs/FAR_TERRAIN.md), built
separately from this renderer.
Other mod versions and combinations need their own integration tests.

We hope the community helps fill it out. If something breaks,
[report it](docs/PLAYING.md#reporting-a-bug); if you can fix it, see
[Contributing](#contributing).

## Download

Build the Windows x64 mod JAR with `gradlew.bat package -Parch=win64`,
after staging the source runtime. The JAR in `build/distributions/` includes
the matching native runtime. Install Sodium 0.9.2 and Fabric API separately.
See [Getting Started](docs/GETTING_STARTED.md) to build from source.
The upstream releases target a different Minecraft version and native API.

## Source installation

1. Build and stage the matching Vulkan runtime from source.
2. Build the mod and its separate Windows crash-support library with Java 25.
3. Validate a development client using Gradle `runClient` or the Prism workflow.

See [Getting Started](docs/GETTING_STARTED.md). The existing player and installer
guides describe upstream behavior and are not evidence of port release readiness.

## Requirements

- Windows 10 or 11, x64. ARM64 is outside the current integration work.
- Minecraft Java Edition 26.3, with Java 25 and matching Fabric dependencies.
- An RTX-capable NVIDIA GPU and a driver supporting the selected 26.3 renderer
  and the exact runtime build being tested.
- Visual Studio C++ tools and the matching runtime fork's build dependencies.
- A source-built runtime and validated provenance for each selected architecture.
- Network access for development dependencies and runtime textures.
- 16 GB of system memory is recommended.

Java calls the Remix C API through the Foreign Function and Memory API.
`rtxremix-support.dll` provides Windows crash and process support separately.
The JVM needs `--enable-native-access=ALL-UNNAMED`.

Building and running from source has its own
[prerequisites](docs/GETTING_STARTED.md#prerequisites), including Prism
Launcher, signed in to a Microsoft account that owns Minecraft: Java Edition.

## Crash Reports And Privacy

The mod collects no data of its own. The RTX Remix runtime it carries can send
basic session information and crash reports to NVIDIA. Its First Use Guide asks
about both, with both on by default, and the Permissions tab of the RTX Remix
Graphics Settings menu changes them at any time.
[Crash Reports And Privacy](docs/PLAYING.md#crash-reports-and-privacy) lists
what is sent.

## What it does

These are the upstream feature goals being preserved by the port. They still
require visual and native integration checks on Minecraft 26.3.

**Every block, mob, and item is lit by a path tracer.** Light bounces off walls,
through glass, and out of torches the way it does in a film renderer, so shadows,
reflections, and the colour a nearby block casts on its neighbours are all
computed rather than painted in.

**The look follows the game.** Time of day, weather, and being underwater each
bring their own lighting, and a map author can attach a look to a place in the
world. Nothing needs switching on.

**A new block.** The Copper Beacon is a directional lamp you can place or
carry, and its beam is a real light that picks out walls, rain, and anything
standing in it.

**Camera flythroughs.** Record a path by walking it, then replay it, for a video
or for an exact before-and-after comparison.

**A settings screen in the game.** Distances, framerate, sky, lighting,
exposure, materials, DLSS, and advanced runtime controls in Sodium's RTX Remix
pages, with Apply and Undo and no config files to edit.

## Showcase World And Art Pack

Two optional extras go with the mod. The developer launcher in
[`tools/`](tools/README.md) downloads both the first time it runs and launches
into the world with the pack on.

- **The showcase world**, the map the renderer was tuned against, was built for
  this project by BlockWorks: [website](https://www.blockworks.uk/),
  [YouTube](https://www.youtube.com/@BlockWorksYT),
  [X](https://x.com/BlockWorksYT), and
  [Instagram](https://www.instagram.com/blockworks/).
- **The RTX art pack**, a texture resource pack, was created by an art team at
  NVIDIA. Its normal and specular maps follow the
  [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard), so
  other renderers and shader packs that read LabPBR may be able to use it too,
  but it has only been tested with this mod. Its `_sss` subsurface maps are
  this mod's own addition, described in
  [Resource Packs](docs/RESOURCE_PACKS.md).

## Start Here

| You want to | Read |
|---|---|
| Play with path tracing | [Playing](docs/PLAYING.md) |
| Check the known issues | [Issue tracker](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues) |
| Play on a server | [Multiplayer](docs/PLAYING.md#multiplayer) |
| Configure the renderer | [Settings](docs/SETTINGS.md) |
| Look up a command, a key, or a mod-added block | [In-Game Guide](docs/IN_GAME_GUIDE.md) |
| Give your own map its own lighting | [Gameplay-driven profiles](packaging/rtx-remix/mods/minecraft-logic/README.md) |
| Make a texture pack this renderer understands | [Resource Packs](docs/RESOURCE_PACKS.md) |
| Record a camera flythrough | [Camera Paths](docs/CAMERA_PATHS.md) |
| Install, repair, or uninstall in detail | [packaging/INSTALL.md](packaging/INSTALL.md) |
| Build and run from source | [Getting Started](docs/GETTING_STARTED.md) |
| Tune the developer knobs behind the renderer | [Tuning](docs/TUNING.md) |
| Diagnose a rendering or native integration problem | [Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md) |
| Contribute a change | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Report a bug | [Reporting a Bug](docs/PLAYING.md#reporting-a-bug) |
| Report a security vulnerability | [SECURITY.md](SECURITY.md) |
| See what changed | [CHANGELOG.md](CHANGELOG.md) |

## Source And Tooling Layout

| Path | Purpose |
|---|---|
| `src/` | Mod implementation and JVM tests |
| `rtxremix-java/` | Minecraft-agnostic Java facade, Java 25 FFM bindings, and Windows crash-support source |
| `tools/` | Developer launcher, benchmarks, profiling, SDK staging, and development installation |
| `packman/` | Runtime-texture fetching and historical SDK manifests; the Vulkan runtime requires source staging |
| `packaging/` | Public installer, runtime defaults, and distribution payload |
| `docs/` | Player, authoring, and source-build guides |

NVIDIA maintains some release and CI tooling outside this repository. It is
not required by the public Gradle build, the developer tools, or the installed
mod.

## Contributing

External contributions are welcome, and the gaps above are where help matters
most: compatibility with other mods, coverage of the blocks, mobs, and places a
real playthrough reaches, and performance on hardware we do not have. Fork this
repository, create a development branch, and submit a pull request. Sign off
each commit with `git commit -s` to certify it under the
[Developer Certificate of Origin](https://developercertificate.org/); there is
no Contributor License Agreement to sign. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the full guidelines.

## License

This project is licensed under the Apache License, Version 2.0 (`Apache-2.0`).
See the [LICENSE](LICENSE) file. Attribution notices are in [NOTICE](NOTICE),
and third-party software distributed with this project is documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Release archives also carry
the RTX Remix runtime, whose NVIDIA components are covered by their own license
terms; THIRD_PARTY_NOTICES.md lists them.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR
MICROSOFT. Minecraft is a trademark of Mojang Synergies AB.

NVIDIA, GeForce, and RTX are trademarks and/or registered trademarks of NVIDIA
Corporation in the U.S. and other countries.
