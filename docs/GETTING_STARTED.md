# Getting Started

Build and integrate the Minecraft 26.3 port from source. The mod selects Vulkan
before graphics-device creation and disables the OpenGL fallback. This branch is
not a validated playable release. Upstream 1.21.1 installation instructions and
published 0.x runtime binaries do not provide a compatible installation.

## Prerequisites

- **Windows 10 or 11**, x64 for the current integration work. ARM64 build tooling
  is retained but is not being validated in this port.
- **Java 25**, a 64-bit JDK matching the target host. Set `JAVA_HOME` to it.
  Remix calls use the Foreign Function and Memory API. The launch JVM needs
  `--enable-native-access=ALL-UNNAMED`.
- **Visual Studio C++ tools**, including the target architecture libraries,
  for `rtxremix-support.dll`. This crash-support library has a plain C ABI;
  it does not need JNI headers or a JDK include directory.
- **Git**, plus the prerequisites of the matching
  `water2004/dxvk-remix` runtime fork. The runtime is a separate source build.
- **An RTX-capable NVIDIA GPU** and a driver supporting Minecraft 26.3's
  Vulkan backend and the source runtime you build.
- **Prism Launcher** for the optional signed-in launcher workflow. Gradle
  `runClient` is also available for development validation.
- **Network access** for development dependencies and the pinned runtime textures.

## Get A Runtime

Build the matching `water2004/dxvk-remix` runtime from source for the
selected architecture. Its custom Remix C API is **1.5.0**. Follow that source
revision's build instructions. An upstream release archive, old `d3d9.dll`, or
Packman SDK does not implement this API.

The completed output must include `d3d9.dll`, its dependent runtime DLLs and
USD payload, and `.runtime-provenance.json`. The provenance records `fork`
(`water2004/dxvk-remix`), `apiVersion` (`1.5.0`), `architecture` (`win64`
or `arm64`), the 40-character `sourceCommit`, `sourceDirty`, and the SHA-256
`dllSha256`. It must identify the actual completed source build. Staging
validates the PE architecture, provenance, and DLL digest before replacing a
staged snapshot.

The staging script also records `buildType` and `tracyEnabled` from the completed
Meson build. Use a Release runtime without Tracy for player packages; retain a
separate debug-optimized build for assertions, instance lifetime diagnostics,
and native profiling. Its destroyed-instance quarantine retains allocations
until shutdown and can distort long-session memory measurements. Java JFR
profiling remains available with a Release runtime.

From this mod checkout:

```powershell
.\tools\rtx.bat sdk local --arch win64 --path <runtime output>
.\tools\rtx.bat sdk status --arch win64
```

Use `arm64` for an ARM64 output. Runtime snapshots live in
`remix-runtime/<arch>/`; local staging copies the output and records a
`.local-sdk-source` sentinel. After a successful runtime rebuild, refresh it
with `.\tools\rtx.bat sdk restage --arch win64`.

Without `--path`, local staging reuses the recorded source, then
`sdk.localPath` in ignored `tools/config.local.json`, then
`../dxvk-remix/_output`. Relative paths start at the repository root:

```json
{
    "sdk": {
        "localPath": "../dxvk-remix/_output"
    }
}
```

A stale output directory can survive a failed native build. Confirm the build
and its output-install step completed before staging. `sdk status` reports
staging freshness; it does not prove rendering works.

The Gradle tasks named `fetchRuntimeSdkWin64` and `fetchRuntimeSdkArm64` now
validate staged source builds. They do not download a compatible runtime.
`sdk packman` rejects the unsupported binary fallback without changing staging.
Historical package pins are not a source of compatible Vulkan binaries.

The Java facade and FFM layouts live under `rtxremix-java/src/`; the copied
`rtxremix-java/native/include/remix_c.h` describes the same custom API.
The separate Windows crash-support library is compiled into
`build/native/{win64|arm64}/rtxremix-support.dll` and layered over the runtime
when staging or packaging. Never write build outputs into a Packman-managed
junction. Runtime textures still use a read-only Packman cache under
`remix-runtime/textures/`, outside `build/`.

Compilation can be checked without runtime staging:

```powershell
.\gradlew.bat compileJava compileTestJava
```

For native integration, stage the matching runtime, then run the full build
and a development client. Java compilation and unit tests do not verify Vulkan
device sharing, foreign calls, either presentation path, or captured scene output.

Sodium 0.9.2 is a required dependency and is on the Gradle runtime classpath.
Keep its JAR and Fabric API separate from the RTX Remix JAR when installing.
`gradlew.bat package -Parch=win64` builds the mod JAR with its native runtime
in `build/distributions/`. Open Video Settings → RTX Remix for
the Sodium configuration pages; changes commit through Apply and can be undone.
Advanced Runtime Settings is an internal Sodium page; it does not open the
runtime overlay. Choose Custom to edit its tracing and cache controls. Visual
effects open in the independent Shader Settings screen, accessible from Options
or Sodium's Shader Settings link.
Under the Custom quality preset, the DLSS Super Resolution switch selects DLSS
or native resolution. Its quality control includes Auto, Quality, Balanced,
Performance, Ultra Performance and native-resolution DLAA. Ray Reconstruction
is a separate control and requires DLSS to be enabled. Switching these options
recreates native upscaling resources; the first frame may include pipeline
creation. Remix supplies its own depth, jitter and scene motion vectors to NGX.

Voxy is unsupported and must be removed from this profile. Far terrain is provided
through a separately built extension; it shares the base renderer's materials
and Remix scene. No far-terrain implementation is bundled in the base mod.

Use `runClient -PclientRunDir=build/run/parityClient` for an isolated development
profile. Gradle stages the same validated native payload there while preserving
that profile's options, saves and separately installed mods. Vulkan is selected
by the mod; no launcher backend override is needed.

## Checklist

```powershell
# In a checkout of this port, after building the matching runtime:
.\tools\rtx.bat sdk local --arch win64 --path <runtime output>
.\tools\rtx.bat prism signin
.\tools\rtx.bat launch
```

For direct Gradle development validation, use the separate `run/` game
directory after staging the same runtime:

```powershell
.\gradlew.bat runClient -Parch=win64
```

This does not configure or launch Prism. Keep its saves and settings separate
when comparing it with a Prism instance.

### Automated native client GameTest

`gradlew.bat runClientGameTest -Parch=win64` runs Fabric's client GameTest
in `build/run/clientGameTest/`, with the normal client JVM tuning and a copy of
the provenance-validated runtime, freshly built support library, textures,
`rtx.conf`, and shipped conf layers. Stop clients from this checkout before
running Gradle. `compileGametestJava` compiles the test without launching it.

The test creates a flat world through Minecraft's create-world screen, checks
the Copper Beacon recipe, waits for native initialization and published terrain
geometry and 40 successful native submit/present calls, captures a screenshot,
then leaves and reopens the same save and
repeats the assertions. Screenshots are written under
`build/run/clientGameTest/screenshots/` with `remix-world-created` and
`remix-world-rejoined` in their filenames. Read that game directory's
`logs/latest.log` for assertion results and the selected and actual backends.

To validate each interop path, select Minecraft's actual setting before launch
in that directory's `options.txt`: `preferredGraphicsBackend:"vulkan"` or
`preferredGraphicsBackend:"opengl"`. Staging preserves this file. Add
`-PgameTestExpectedBackend=VULKAN` to assert the expected backend;
this property does not select or force a backend. An explicit setting that
falls back to another backend fails the test.

Edit an options file already written by this Minecraft version. If creating one
from scratch for 26.3, include `version:5023` and `startedCleanly:true` alongside
the backend setting. Without a version, vanilla migrates the file from version
zero and resets the graphics backend to Default. After a startup crash, vanilla
can reset Vulkan on the next launch; when deliberately retrying a fixed startup,
restore the selected backend and `startedCleanly:true` while the client is closed.

This is an opt-in GPU integration test, separate from the JVM `test` task.
Fabric bounds world loading and shutdown at 1,200 ticks; renderer readiness
allows 2,400 ticks. The Gradle launch task has a five-minute wall-clock timeout
that fails the task and terminates its child client, including a hung native call.
Passing establishes resource loading, native initialization, terrain publication,
and session reuse. Inspect the screenshots to establish visual correctness;
there is no approved image baseline or performance assertion.

The two large runtime textures are not in git: the first build pulls them
through Packman into `remix-runtime/textures/`; later builds reuse the cached copy. The build checks that both
files arrived. The pin lives in
`packman/textures.packman.xml`. The water normals in the package are generated
by `tools\rtx.bat textures water-normals`. The build automatically fetches
`rtx_remix_minecraft_runtime_textures` version `1.3.0` through Packman.

The Java facade, FFM bindings, and crash-support source are tracked under `rtxremix-java/` and arrive
with the clone; no recursive checkout or hook setup is needed.

`tools\rtx.bat launch` validates the staged runtime and builds the mod and
crash-support library with Gradle. It installs the mod, the pinned Fabric API, the runtime,
`rtx.conf`, the gameplay conf payload, and the runtime textures into this
checkout's Prism instance, then starts Minecraft at the main menu through
Prism and waits until the game exits. Prism's own console output stays out of
the terminal; Prism keeps it in its launcher profile's `logs` folder. The
launch never touches `%APPDATA%\.minecraft` or your existing Prism instances,
and it needs no separate build or install step. `tools\rtx.bat` with no
arguments opens the [developer dashboard](../tools/docs/LAUNCHER.md) instead,
which launches into the pinned demo world.

Each launch writes the enabled resource packs in the game directory's
`options.txt`: vanilla, then Fabric's `fabric` pack with the built-in baseline,
then any world packs and the selected art pack. It removes an old generated
`rtx-remix` baseline pack, which it recognizes by its metadata and texture
inventory, leaving other packs alone.

### Sign In To Prism

The developer tools launch Minecraft only through Prism Launcher, signed in to
a Microsoft account that owns Minecraft: Java Edition. They look for
`prismlauncher.exe` in this order: `prism.executable` in ignored
`tools/config.local.json`, the copy the release installer manages under
`%LOCALAPPDATA%\RtxRemixMinecraft\PrismLauncher`, `prismlauncher` on `PATH`,
then `%LOCALAPPDATA%\Programs\PrismLauncher`. A configured path that does not
exist stops the launch rather than falling back. The tools need Prism
Launcher 9.0 or newer, the first release whose `--help` lists `--world` beside
`--profile`, and check for both before a launch.

An installed Prism uses its normal `%APPDATA%\PrismLauncher` profile and
existing Microsoft sign-in. The tools create a distinct `RTX Remix Dev`
instance there for each checkout, identified by the checkout name and a short
path hash; they never modify your existing instances. An official portable
Prism instead keeps its profile and fixed `RTX Remix Dev` instance in this
checkout's ignored `run-prism\` folder. Do not share, copy, or attach either
profile's `accounts.json`; it contains account tokens. Open the selected
profile when you need to sign in:

```powershell
.\tools\rtx.bat prism signin
```

In the Prism window that opens, sign in on the page Prism shows (or choose
Accounts > Manage Accounts > Add Microsoft) with the account that owns
Minecraft: Java Edition, and close Prism. A launch without such an account
opens the same window and stops with these steps. Installed Prism shares that
sign-in across worktrees; a portable Prism profile signs in separately. With
several accounts signed in, set `prism.account` to the profile name to play as:

```json
{
    "prism": {
        "account": "<profile name>"
    }
}
```

The tools read only each account's type, profile name, ownership, and active
flag from Prism's account list, never its tokens. The first launch of a
checkout's instance also downloads Minecraft through Prism, so it takes
longer. Close the selected Prism before launching: an existing process could
take over the launch without the command's settings, so the tools refuse to
start while it is running. `tools\rtx.bat doctor` reports which account a
launch will use, or why it cannot start.

## Verify

In game, run `/rtx status`. It should report `Renderer Initialized: true` and
`SDK Available: true`.
In `logs/latest.log` in the game directory, the exact success message is
`RTX Remix renderer initialized successfully!`; there must be no
`Failed to initialize RTX Remix` message. The similarly worded mod-entrypoint
startup message with a capital `Renderer` is not proof of native initialization.

The baseline PBR textures are built into the mod JAR and load through Fabric's
`fabric` resource pack, below user packs. No separate baseline pack needs
enabling.

The first launch of a new runtime can compile shaders and initially show a
partial scene. A persistent empty scene is also an integration failure to
investigate; do not assume every missing surface is shader compilation.
`/rtx status` does not report completion of native shader compilation. Judge
interactive readiness from the settled image.

## Other Commands

```powershell
.\gradlew.bat build                         # development JAR in build/libs/
.\gradlew.bat test                          # JUnit suites
.\gradlew.bat package -Parch=win64           # installable JAR in build/distributions/
.\gradlew.bat releaseArchive -Parch=win64    # installable release ZIP in build/distributions/
```

`.\gradlew.bat compileJava` checks Java compilation without fetching or staging
the runtime or compiling the crash-support library. It is a useful iteration check, not
validation of native integration.

The JAR from `package` is the one players get: it embeds the runtime, gameplay
conf payload, runtime textures, and baseline PBR textures. To try it in a
normal installation of Minecraft 26.3 that already has Fabric Loader and
Fabric API, use the instance's `mods` folder. Native integration and rendering still need
verification before this becomes a supported installation.
The release archive's installer also migrates old generated baseline packs and
preserves user-pack ordering.

Every task that reaches `classes` runs `setupRuntime` first, which compiles the
crash-support library and stages the runtime. `test` and `build` therefore need a staged
runtime and the C++ toolchain just as `tools\rtx.bat launch` does.
`setupRuntime` also syncs a full copy of the runtime, PDBs included, with the
crash-support library, `rtx.conf`, the gameplay conf payload, and the runtime textures into
`run/`, the game directory of Gradle's `runClient` task, so every build
refreshes it. The developer tools' Prism launches use `run-prism\` instead.

`build` produces a lightweight JAR that is not installable on its own.
`package` embeds the runtime, `rtx.conf`, the gameplay conf payload, and the
runtime textures.

`verifyPackageJar` rejects Python interpreter and binding payloads because the
runtime uses Python-free OpenUSD. Plain `.py` templates are not interpreters:
`usd/usd/resources/codegenTemplates/__init__.py` is deliberately permitted.
The task also requires every `packaging/licenses/` file in the JAR.

| `package` invocation | Output |
|---|---|
| `package` | Dual-architecture fat JAR. Needs both architectures staged from the same kind of source |
| `package -Parch=win64` | win64 only, skips the arm64 support-library compile |
| `package -Parch=arm64` | arm64 only, useful for cross-building for device testing |

`releaseArchive` runs `package` and its `verifyPackageJar` check, then stages
the verified JAR under `build/release/mods/` and the installer scripts,
documentation, and license notices beside it. It writes that layout as a ZIP
in `build/distributions/`. The same `-Parch` selection applies; omitting it
includes both architectures. No separate baseline resource-pack directory is
needed.

## Tooling And CI

The Python CLI under `tools/` launches the mod and provides local world
import, benchmarks, and JFR/Tracy analysis. Run its checks from the repository
root:

```powershell
.\tools\rtx.bat check --tests
```

This uses the tracked `tools/uv.lock` to run Ruff format checks and linting,
ty type checks, and the pytest suite, including the public-boundary
gate. It does not build or launch Minecraft. Without `--tests`, it runs only
format, lint, and type checks.

The shipped defaults pin the demo world and the art pack and select no
graphics preset, so `.\tools\rtx.bat` launches into the world with the pack;
both download on first launch. If the art pack cannot be downloaded, the launch
warns, names the `.\tools\rtx.bat resourcepack fetch` command that retries it,
and starts without it. Import your own save, or use the
[local fixture workflow](../tools/docs/BENCHMARK_FIXTURES.md#authoring-and-updating)
to record a benchmark camera. Save local choices in ignored
`tools/config.local.json`, not the tracked defaults.

The CLI merges shared defaults, benchmark defaults, ignored local settings,
the selected fixture's configuration, and explicit CLI overrides in that order.
`check --tests` runs the pytest suite and needs no staged assets or runtime.
See [Configuration](../tools/README.md#configuration).

Pull requests and pushes to `main` run the GitHub
[`check`](../.github/workflows/check.yml) and
[`build`](../.github/workflows/build.yml) workflows on Windows.
They run `tools\rtx.bat check --tests` and `gradlew.bat build test`,
respectively, with read-only repository permissions and no secrets. Pull
request checks also scan the added commit messages. These workflows do not
launch a GPU client, run benchmarks, or produce a release archive; run those
locally when they are relevant. Native integration requires the source runtime described in
[Get A Runtime](#get-a-runtime).

## Choosing A Launch Workflow

| Goal | Use | Minecraft directory |
|---|---|---|
| Iterate on this repository | `tools\rtx.bat launch`, or `tools\rtx.bat` for the developer dashboard, through this checkout's Prism instance | Installed Prism's profile, or `run-prism\instances\rtx-remix-dev\.minecraft` for portable Prism |
| Produce an artifact | `gradlew build`, `gradlew package`, or `gradlew releaseArchive` | None |
| Test inside an existing Microsoft Launcher install | `tools\rtx.bat install` after `gradlew build` | `%APPDATA%\.minecraft`, or `--game-dir DIR` |

`build.gradle` also keeps `runClient`, Fabric's standard Gradle task. It runs
from the repository-local `run/` directory, not the Prism instance, so the two
share no saves, settings, or logs. The developer tools retain their Prism workflow; `runClient` can be used
directly for source-port validation after staging the matching runtime.

`rtx install` is an alternative to `tools\rtx.bat launch`, not a step before
it. Build, then install from the repository root:

```powershell
.\gradlew.bat build
.\tools\rtx.bat install
```

The CLI bootstrap manages uv and its Python environment. The target installation
must already have a compatible Fabric Loader profile and Fabric API. The command
warns if Fabric API is missing; it does not install Fabric or launch Minecraft.
Add `--game-dir DIR` to select another installation and `--arch win64` or
`--arch arm64` to select its architecture. Relative paths use the repository
root. Without `--arch`, `REMIX_ARCH` wins, then the host architecture.

The command installs the newest non-sources development JAR, the staged runtime
from `remix-runtime/<arch>/`, the matching crash-support library from `build/native/<arch>/`,
`packaging/rtx.conf`, the `packaging/rtx-remix/` gameplay conf payload, and runtime
textures. Baseline PBR textures are built into the JAR. The command removes its
former generated `rtx-remix-labpbr` pack and orders vanilla and Fabric below
existing user packs. Shipped gameplay confs are refreshed while extra
author-created profiles are preserved. An unchanged runtime is not recopied,
but the crash-support library and other payloads are always refreshed.

Set `REMIX_BUILD` to install a custom runtime directory containing `d3d9.dll`
instead of the staged runtime. Set `REMIX_FORCE_SDK_COPY=1` to force a full SDK
recopy. Run `.\tools\rtx.bat install --help` for the command reference.

## Profiling

The mod can emit Java phase events to JDK Flight Recorder, and can mirror the
same phases as Tracy zones. JFR works with any runtime. Tracy zones only appear
when the staged runtime was built with Tracy support, because the zones are
submitted through the runtime's own profiler.

`tools\rtx.bat run` records each benchmark run with JFR, Java phases on.
For an interactive client, put the recording arguments in `launch.jvmArgs`, as
[Profiling](../tools/docs/PROFILING.md#manual-capture-beside-another-client)
shows. The individual switches, and the rest of the JVM system properties the
mod reads, are in [Tuning](TUNING.md).

## Viewing A Remix Debug View

`tools\rtx.bat launch` starts Prism with the shell's environment, and Prism
passes it on to the game. Set a debug-view override there instead of editing
`rtx.conf` in the game directory, which every launch replaces, or saving a User
Setting through Alt+X:

```powershell
$env:DXVK_RTX_DEBUG_VIEW_INDEX = 51
.\tools\rtx.bat launch
# Clear the override before the next normal launch.
Remove-Item Env:DXVK_RTX_DEBUG_VIEW_INDEX
```

The runtime reads the override at startup, so changing it needs a relaunch.
The authority for the numbers is `DEBUG_VIEW_*` in the matching dxvk-remix
revision's `src/dxvk/shaders/rtx/utility/debug_view_indices.h`. This mod's CI
cannot detect renumbering there. Useful values in the pinned runtime are:

| Index | View |
|---|---|
| 0 | Normal image |
| 12 | Texture coordinates |
| 15 | Geometry normal |
| 30 | Emissive radiance |
| 51 | `DEBUG_VIEW_VOLUME_PREINTEGRATION`, pre-integrated volumetric in-scatter |
| 277 | Geometry hash, one color per distinct mesh |

For a fog contribution, view 51 shows a light as in-scatter or its absence.
`/weather thunder` selects the densest shipped weather fog. The
[profile authoring guide](../packaging/rtx-remix/mods/minecraft-logic/README.md)
explains which profile owns it. See
[Rendering Diagnostics](RENDERING_DIAGNOSTICS.md) for capture-versus-shading
triage; confirm the indices against the runtime you are actually testing.

## Trying A Runtime Option

The runtime reads `dxvk.conf` from the process working directory, which for
`tools\rtx.bat launch` is the checkout-specific Prism instance's game
directory. Put dxvk-level options such as
`d3d9.*` swapchain settings or `dxvk.enableAftermath` there without rebuilding.
It is the lowest config layer: built-in application defaults and `rtx.conf`
outrank it. An `rtx.*` key already authored in `rtx.conf` can therefore appear
inert in `dxvk.conf`. `DXVK_CONFIG_FILE` redirects the lookup with comma-separated
paths, as `DXVK_RTX_CONFIG_FILE` does for the Remix config.

Neither a launch's install nor benchmark stale-state parking removes
`dxvk.conf`. A forgotten one in the game directory silently affects later
measured runs. Remove the experiment when finished. For a value meant to blend
live, use a gameplay conf layer instead of a User Settings write.

`DXVK_HUD=fps,frametimes`, or `dxvk.hud = fps,frametimes` in that file, draws
a runtime HUD at present time. It helps distinguish an old scene being
presented from a whole frame repeated by the compositor. Disable it for
measurements. To confirm which DLSS libraries a release runtime carries,
inspect its loaded modules or the `nvngx_dlss.dll`, `nvngx_dlssd.dll`,
`nvngx_dlssg.dll` and `nvngx_dlssnr.dll` payloads, not an absent feature
announcement in `latest.log`.

## Working In A Worktree

A new `git worktree add` checkout includes the integrated Java and FFM sources,
but not its ignored runtime directories, saves, or options. Installed Prism
reuses its normal sign-in and gives the worktree a distinct instance. Portable
Prism uses that worktree's ignored `run-prism\` profile, so sign it in before
its first launch. Portable copies can run worktrees side by side; one installed
Prism executable serves one launch at a time. Stage the matching source runtime
and provenance in each worktree before its first full build.
For local native work, run `tools\rtx.bat sdk local --path <absolute output path>`
there; do not copy a runtime from another checkout whose API or pin may differ.
Art packs, saves and player settings also need deliberate staging for a visual
comparison.

Before removing a worktree, stop its client, close its Prism, and run
`gradlew.bat --stop` there so open files do not block removal. Use ordinary
`git worktree remove <path>`; preserve any local saves and uncommitted work
first. If a directory was already removed manually, `git worktree prune`
removes its stale registration.

## When It Does Not Work

| Symptom | Cause |
|---|---|
| `vswhere.exe not found` or `vcvarsall.bat failed` | Visual Studio C++ tools or the selected target architecture libraries are missing |
| Wrong Java version or missing FFM classes | Set `JAVA_HOME` to a 64-bit Java 25 JDK; the crash helper does not use `jni.h` |
| `fetchRuntimeSdk*` rejects the staged runtime | Build the matching fork and stage its output with valid provenance; there is no compatible published SDK fallback |
| A launch reports that Prism Launcher is not installed | Install it from [prismlauncher.org](https://prismlauncher.org), or point `prism.executable` in `tools/config.local.json` at its `prismlauncher.exe` |
| A launch opens Prism and stops with sign-in steps | No Microsoft account that owns Minecraft is signed in to the selected Prism profile, or `prism.account` names another. See [Sign In To Prism](#sign-in-to-prism) |
| A launch reports that Prism is already running | Close the selected Prism window and launch again |
| A launch reports that Minecraft 26.3 needs a 64-bit JDK 25 | Select a Java 25 JDK matching the target architecture |
| A JVM option set in `JAVA_TOOL_OPTIONS` has no effect | Prism does not pass it to the game. Use `launch.jvmArgs`; see [JVM Arguments](TUNING.md#jvm-arguments) |
| `Failed to initialize RTX Remix` with error code 8, and the game renders vanilla | The staged runtime predates the API in `rtxremix-java/native/include/remix_c.h`. Stage one of the runtimes listed in [Get A Runtime](#get-a-runtime) |
| `UnsatisfiedLinkError` naming `rtxremix-support` | Build the crash-support library for the correct architecture and restage; obsolete `remixbridge.dll` is not used |
| Missing native export or incompatible version | Check the custom 1.5.0 runtime, copied header, FFM layouts, and foreign function descriptors together |
| Minecraft still uses an old local runtime build | The staged runtime is a copy; run `tools\rtx.bat sdk restage` |
| The build fails at `fetchRuntimeTextures` | Packman could not pull the runtime textures pinned in `packman/textures.packman.xml`. Check access to Packman's download host |
| The build reports that the runtime textures are missing files | The shared Packman cache holds the package without its textures. Delete the folder the message names and build again; Packman downloads the package afresh |
| `package` reports mixed sources | Stage both architectures from one kind of source, or package one with `-Parch=` |
| Empty or partial scene | Shader compilation can cause a delay; persistent failures also require Vulkan sharing, synchronization, and capture diagnostics on this port |
| Frame-time spikes at high render distance | Garbage collection is one possible cause. Every launch gives the Prism instance the tuned JVM arguments and their heap limit; check that `launch.jvmArgs` does not override them |
| A clone or checkout reports `Filename too long` | Enable `git config --global core.longpaths true` before cloning, or choose a shorter checkout path |
| `package` cannot build ARM64 support | Install ARM64 C++ tools and stage an ARM64 source runtime, or use `package -Parch=win64` |
| Initialization reports `DRIVER_VERSION_BELOW_MINIMUM` | Update the driver. The minimum is a capability gate; lowering `dxvk.nvidiaMinDriver` can enable unsupported paths and cause GPU faults |
| Startup or device registration fails | Check Vulkan device/queue ownership, custom API layout, and the exact source runtime; do not continue calls with a mismatched ABI |
| Comparing `/rtx off` and `/rtx on` | Validate both Vulkan paths and match internal resolution before drawing performance conclusions; a toggle is not a separate vanilla baseline |

For `INCOMPATIBLE_VERSION`, rebuild and stage the matching custom 1.5.0
runtime, with provenance identifying the completed output. Keep the copied
header, `RemixLayouts.java`, foreign function descriptors, and Java API aligned.
`sdk packman` cannot repair this port with an upstream binary. A missing export
or incompatible calling layout is a native integration failure even when the
runtime claims a matching version.

For an ARM64 toolset failure, `verifyPackageJar -Parch=win64` checks the
single-architecture package contents without running the tests. The
dual-architecture artifact still needs both toolchains.

## Where To Go Next

- [Contributing](../CONTRIBUTING.md): which repository a change belongs in, the
  rules that bite, code standards, and how a pull request is reviewed. Read it
  before your first change.
- [Tuning](TUNING.md): JVM system properties, Gradle pass-throughs, and the
  files the mod owns in the game directory.
- [In-Game Guide](IN_GAME_GUIDE.md): the `/rtx` command tree and the keys.
- [Camera Paths](CAMERA_PATHS.md): recording and replaying camera routes, which
  is also how before-and-after comparisons are made.
- [Settings](SETTINGS.md): the settings model and persistence guide.
- [Resource Packs](RESOURCE_PACKS.md): the texture and block-light inputs the
  renderer reads.
- [Gameplay-driven profiles](../packaging/rtx-remix/mods/minecraft-logic/README.md):
  authoring conf profiles for zones, biomes, weather, and time of day.
- [Rendering Diagnostics](RENDERING_DIAGNOSTICS.md): lifecycle, terrain
  recapture, material updates, and native scene symptoms.
- `AGENTS.md` in the repository root: the architecture summary and the
  invariants to respect.
