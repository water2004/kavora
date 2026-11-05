# Contributing to RTX Remix for Minecraft

This project is maintained by NVIDIA and released under the Apache License,
Version 2.0. Contributions are welcome.

This branch is the Minecraft 26.3 Vulkan port. Native integration is still
being validated; see [Getting Started](docs/GETTING_STARTED.md) for the Java 25
and source-runtime prerequisites. Upstream 1.21.1 binaries are incompatible.

## Which Repository

The renderer spans three layers across two repositories, so the first question
for any change is which repository it belongs in.

- Path tracing, denoising, upscaling, and the Remix C API itself live in
  [dxvk-remix](https://github.com/NVIDIAGameWorks/dxvk-remix).
- The Minecraft-agnostic Java facade, API types, FFM marshalling and layouts,
  and Windows crash-support source are tracked here under `rtxremix-java/`.
  Gradle validates a staged source-built runtime for this branch.
- Everything that reads Minecraft state and hands it to Remix lives here: the
  terrain, entity, texture, particle, and cloud pipelines, the gameplay-driven
  conf layers, the settings screen, and the Fabric mixins that feed them.

Binding and mod changes belong in the same pull request. This port uses the
source-built `water2004/dxvk-remix` runtime and its custom 1.5.0 C API.
Keep that runtime, its copied header, FFM layouts and descriptors, Java facade,
and mod callers aligned. Do not edit the copied
`rtxremix-java/native/include/remix_c.h` in isolation or substitute a published
upstream 0.x SDK.

Upstream fixes define behavior, not the implementation shape of this port.
Preserve their rendering, API lifetime, and synchronization guarantees while
adapting the implementation to this branch's Vulkan, FFM, and Sodium owners.
Replace superseded code rather than retaining parallel compatibility paths.
Validate correctness and measure whole-frame time and memory before accepting
an optimization; a smaller shader register count alone is not a speedup.

## Set Up

[Getting Started](docs/GETTING_STARTED.md) covers the prerequisites, how to
build and stage the source runtime, then launch through Gradle `runClient` or
the signed-in Prism workflow. Foreign layouts and calling descriptors must
match the runtime ABI; a version string alone cannot establish compatibility.

## Where To Start Reading

Two classes are the way in. `RtxRemixMod` is the Fabric entrypoint: lifecycle
hooks, key bindings, and the `/rtx` command tree. `RemixRenderer` is the render
thread's bridge into Remix, and every frame passes through it.

From there, read by pipeline rather than by file. Packages are named for what
they own, so the package holding a problem is usually the package named after
it, and the class-level Javadoc in each one carries the design decisions that
are not obvious from the code. `AGENTS.md` in the repository root summarises
each pipeline in a paragraph and lists the invariants that hold them together.

## Rules That Bite

These are the mistakes that pass review by looking reasonable and then fail
intermittently, on someone else's machine, weeks later.

**Every Remix call belongs to the render thread.** Creating, updating, or
destroying a mesh, instance, texture, material, or light is a native call into
the runtime, and only the render thread may make one. Worker threads produce
CPU-side data and hand it over through bounded queues tagged with a generation
or epoch that the render thread validates before it publishes anything. A
worker that touches a handle directly works in testing and corrupts the scene
under load.

**Retained instances are mutate-then-submit.** Remix keeps a scene instance
until it is explicitly removed, so terrain and entity instances are submitted
where they are created or mutated, not re-submitted every frame. Any new
mutation of a retained instance must be followed immediately by a submit, or
the change never reaches the scene.

**A mixin that cancels vanilla work inherits its side effects.** Preserve the
net GL, fog, lightmap and framebuffer state the caller expects, not only the
entry calls. Never cancel `WorldRenderer.render()` itself: that traversal
hosts Remix's frame. See [Cancelling Vanilla Render Work](#cancelling-vanilla-render-work).

**Only mixins live in the mixin package.** Mixin reserves that package, and a
helper class placed there crashes world load. `MixinPackageBoundaryTest` fails
the build if an unconfigured class appears in it.

**Runtime file changes touch three places at once.** The per-architecture native
compile tasks, `setupRuntime`, and `package` must agree on what a runtime
payload contains, and `NativeExtractor` must agree with what `package` produced.
A change to any one of them without the others produces a JAR that installs a
broken game directory.

**The FFM binding has no game dependencies.** `rtxremix-java/` must never import
`net.minecraft` or `net.fabricmc`; `RemixJavaBoundaryTest` enforces the
boundary. Java-side handles are raw `long` values, not wrapper objects or JNA
`Pointer`s, and the C API is reached only through FFM, never a JNA interface
table. Use direct `ByteBuffer`s wherever the native method requires them, and
document buffer directness and native segment lifetime on every new foreign entry point.

**An API change crosses the whole boundary.** Keep `StructType.java` constants
in step with the copied header and native runtime. Update the header, FFM
layouts and descriptors, Java facade and data types, provenance, and affected mod callers
together. After rebasing parallel API additions, check for conflicting struct
type values; the exact coordination points are in
[Runtime And Java API Changes](AGENTS.md#runtime-and-java-api-changes).

## Code Standards

These are principles, not an exhaustive list. Extrapolate to the situation in
front of you.

Match the surrounding code. The Java facade uses four-space indentation and
the naming conventions in `rtxremix-java/src/com/nvidia/rtxremix/`.

### Comments

Each file should read as if it was always designed this way. Comments guide the
next developer; they do not narrate the development journey.

Remove:

- **Implementation backstories**: "we do this because the other day X happened",
  "added after we found a bug where Y".
- **Obvious narration**: "create the attribute", "loop through keys". If the
  code says it, the comment is noise.
- **Debugging breadcrumbs** and **trial-and-error reasoning**: "we tried X but
  it caused Y so we do Z".
- **Foreign internals**: details of another system in code that is agnostic of
  it.

Keep:

- **Non-obvious design decisions**, stated as what and why this design, not what
  happened to us.
- **Tricky invariants**: conditions that are easy to break by accident.
- **API contracts**: docstrings on public methods with args, returns, raises.

Prefer renaming a variable or function over explaining it, type hints over
comments about types, and a short comment on the *why* over any comment on the
*what*.

### Engineering

Every change should make the codebase better, not just make the problem go
away. If a solution needs a paragraph to justify why it is not a hack, it is a
hack.

Remove:

- **Fixes in the wrong layer**. Put the fix where the problem originates.
- **Tolerance inflation**: widening deltas or adding retries to hide flaky
  behavior. If a value is wrong, find out why.
- **Catch-all exception swallowing** to hide symptoms.
- **Excessive error and null handling**. If an error is expected, handle it. If
  it is unexpected, raise it.
- **God methods**. Break them into named steps; design for the reader, and for
  fewer indentation levels.
- **Leaky abstractions**: implementation details escaping into layers that
  should be agnostic of them.

Design for single responsibility (if describing it needs an "and", split it),
ownership (the component that creates a problem owns its fix), and a minimal
public surface.

Commit to the new code:

- **No legacy fallbacks.** If you replace a system, remove the old one. No
  `try: new_way() except: old_way()` shims.
- **No dead code**: commented-out blocks, unused imports, orphan functions kept
  "just in case". Version control is the safety net.
- **No multiple paths to the same result.** If two exist, one is wrong.
- **No half-migrations.** Rename every reference; remove the API you replaced.

Smell tests:

- "It works if I add a sleep" means broken data flow.
- "It works if I read from the widget instead of storage" means the two are out
  of sync.
- "It passes alone but fails with other tests" means shared mutable state.
- "I added a flag to skip this code path" raises the question of why that path
  runs at all.

New files need the SPDX header used throughout the tree:

```java
/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
```

PowerShell scripts must be ASCII or start with a UTF-8 BOM. Windows
PowerShell 5.1, used by `packaging/install.bat`, reads a BOM-less script using
the system ANSI code page. Typographic punctuation inside a string can then
cause parse errors pointing at unrelated lines. Use plain ASCII punctuation
for new script text and preserve the BOM on `packaging/installer.ps1`.

### Cancelling Vanilla Render Work

A `HEAD` cancel, `@Redirect`, or `@WrapOperation` that skips a render method
must leave the net state its caller expects. Stale GL state can slow
`Remix.Present` only on another driver, without an obvious visual failure.
Disassemble both ends of the target with `javap -p -c` before changing it.

At entry, look for callbacks, `RenderSystem` calls,
`LightmapTextureManager.enable()`, and `Framebuffer.beginWrite`.
`renderSky` calls its fog-setup `Runnable` immediately. At return, look for
cleanup: `ParticleManager.renderParticles` enables the lightmap and depth
test, then ends with `depthMask(true)`, `disableBlend()`, and lightmap disable.
Replay the net change, not just the entry. `renderLayer` pairs
`startDrawing()` with `endDrawing()`, so skipping it is state-neutral while
replaying only its entry would not be.

A pure read or early-return guard needs no replay. In Minecraft 1.21.1,
`renderClouds` returns for a dimension without cloud height, `renderWeather`
returns without rain, and `renderEntity` starts with position interpolation.
Check the pinned Minecraft version rather than treating these examples as
permanent contracts.

Cancel only draws that Remix replaces, such as terrain layers, particles and
clouds. Do not bypass the outer vanilla traversal: camera, frustum, matrices,
framebuffers, tick delta, in-place entity capture, section scheduling, and
`WorldRenderEvents` all come from it. Judge a cancellation by its injection
and state correctness, not only a frame-time delta. A GPU-bound run can hide a
real render-thread saving.

### Reading Vanilla Bytecode

Minecraft 26.3 ships readable official names. The exact merged JAR Loom caches
is enough for Java 25 `javap`; generating every decompiled source is not
necessary. Read the Minecraft dependency in `build.gradle` and inspect that
version's cache, since older versions remain after an upgrade.

For example, from the repository root in PowerShell:

```powershell
$merged = Get-ChildItem .gradle/loom-cache/minecraftMaven -Recurse -Filter '*26.3.jar'
$merged | Select-Object FullName
# Select the matching minecraft-merged JAR shown above:
javap -p -c -classpath <merged-jar-path> com.mojang.blaze3d.vertex.VertexConsumer
```

If you need to search all callers of a descriptor, extract the whole JAR into
a temporary directory outside the repository. A shell `unzip` wildcard may
stop at a directory separator and silently omit subpackages. Search the
extracted classes for the exact descriptor, disassemble candidates, and remove
the extraction afterwards. Do not leave thousands of class files in the tree.

### Verifying A Mixin Injection

`Mixing X from ... into net.minecraft...` in `run/logs/debug.log` proves
registration, not an applied injection or an executed target. The check reads
Loom's officially named development client, which `runClient`, Fabric's standard
Gradle task, starts from `run/`. Temporarily add `-Dmixin.debug.export=true` to
the client `vmArgs` in `build.gradle`, launch `runClient`, and disassemble the
transformed class under
`run/.mixin.out/class/<package path>/<Class>.class` with `javap -p -c`.
Classes are exported at load, so a later shutdown crash does not invalidate
the export.

An `@Inject` merges a `handler$<id>$rtx-remix-renderer$<handler name>` method
and a call at the intended offset: `invokespecial` for a private instance
handler, `invokestatic` for a static one. If the call is present but does not
fire, investigate vanilla's frustum, dimension, cloud-mode and weather gates.
Exports and the classes loaded from `build/classes/` use official names. The
remapped JAR in `build/libs/` instead embeds intermediary `method_NNNNN` names
in injection annotations. Remove the diagnostic flag and export afterwards.

## Tests

```powershell
.\gradlew.bat test
```

The suite covers JVM-side behavior and data contracts.

It does not cross the FFM boundary and it does not render, so it cannot tell you
that a change looks right. Two things follow. First, `test` still needs the C++
toolchain, because every task that reaches `classes` builds the crash helper and
validates and stages the source runtime first. Second, a rendering change is not
reviewed until someone has looked at it in a running game: build and launch it
with `tools\rtx.bat launch`, and say in the pull request what you looked at and
what you saw. Screenshots of the before and after, taken from the same
position, are the most useful thing you can attach.

For binding changes, the root workflow compiles the Java facade with its
callers and builds `rtxremix-support.dll` into `build/native/{win64|arm64}/`.
Visual Studio C++ tools and a 64-bit Java 25 JDK are required. Stage the matching
source runtime and validated provenance before full builds or tests.
Use `.\tools\rtx.bat sdk local --path <runtime dir> --arch win64` for a local build,
as described in [Getting Started](docs/GETTING_STARTED.md#get-a-runtime).
Packman-managed runtime-texture directories are junctions into the shared
cache. Never copy native outputs into them or commit SDKs or native
build outputs. Launch with `tools\rtx.bat launch` before requesting review on an
API change: Java compilation alone does not validate native loading.

The Python tooling has a separate check command:

```powershell
.\tools\rtx.bat check --tests
```

It uses the shared uv project and tracked `tools/uv.lock` to run Ruff format
checks and linting, ty type checks, and the pytest suite, including the
public-boundary gate. The bootstrap installs uv when needed and manages Python
>=3.15; uv >=0.13 is required. This check needs neither a GPU nor a staged
world, art pack, or runtime. See [Developer tooling](tools/README.md#configuration)
for configuration and assets.

Before publishing, include the commit-message boundary check:

```powershell
$env:PUBLIC_COMMIT_RANGE = "main..HEAD"
.\tools\rtx.bat check --tests
Remove-Item Env:PUBLIC_COMMIT_RANGE
```

Use the actual target branch instead of `main` if the change targets another
branch. Keep machine-local choices in ignored `tools/config.local.json`; the
shipped defaults pin the demo world and art pack and select no graphics preset.

To build an installable release, run `.\gradlew.bat releaseArchive`. It runs
`package` and `verifyPackageJar`, stages the verified JAR, which carries the
runtime textures, with the installer scripts, installer documentation, and
license notices under `build/release/`, and writes a ZIP with the same layout
under `build/distributions/`. Use `-Parch=win64` or `-Parch=arm64` for one
architecture; omitting it includes both. The baseline resource pack is built
into the mod, so the archive has no `resourcepacks/`.

### Visual Comparisons

Capture both revisions from one dedicated, clean worktree so its
checkout-specific Prism instance keeps the save, pose, and `options.txt`
settings unchanged. Stop the client, check out the comparison revision detached, launch
it with `tools\rtx.bat launch` and capture it, then return to the feature
revision and repeat. The integrated Java facade and FFM sources switch with the
checkout. Stage the matching source runtime and its provenance before each
launch; this port cannot restore a compatible SDK from Packman. Keep the
shipped `packaging/rtx.conf` matched to the runtime too.
`tools\rtx.bat launch` rebuilds and installs each revision, and refuses to start
while a client from the checkout is still running.

F1 hides the first-person hand as well as the HUD. Vanilla skips
`HeldItemRenderer.renderItem` when the HUD is hidden, and Remix captures from
that call. Do not use F1 for a hand or held-item comparison. To avoid chat text,
wait eleven seconds after the last command, beyond its ten-second fade.

[Remix debug views](docs/GETTING_STARTED.md#viewing-a-remix-debug-view) separate
geometry and material inputs from the final shaded image. Their indices belong
to the matching dxvk-remix source, not a stable mod enum. Read
[Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md) before attributing a
clean geometry view with a final-image artifact to the capture path.

## Pull Requests

The GitHub repository is the source of truth. Fork it, make your change on a
branch, and open a pull request against `main`; that is where review and
merging happen, for NVIDIA developers as much as for anyone else. A maintainer
reviews on GitHub and, when a change warrants it, runs it on an RTX machine
before approving.

Keep a pull request to one logical change. Unrelated reformatting, renames, and
drive-by cleanups make review harder and are usually better as their own
request.

Write commit subjects in the imperative, describing what the commit changes
rather than the order it was written in: "Cap concurrent section builds", not
"fixed the thing from yesterday". Squash review fixups into the commits they
belong to before the final review pass, and make sure each commit builds on its
own. Commit messages and the pull request title and body are checked along
with the code, since the squashed commit is composed from them.

Every commit needs a `Signed-off-by` trailer. See
[Developer Certificate of Origin](#developer-certificate-of-origin) below.

Pull requests and pushes to `main` run two workflows on GitHub-hosted Windows
runners. [`check`](.github/workflows/check.yml) installs uv and runs
`tools\rtx.bat check --tests`: Ruff formatting and lint, ty, the Python tests,
and the public-boundary gate. On a pull request it also scans the commit
messages from the base SHA to the head SHA.
[`build`](.github/workflows/build.yml) installs the configured JDK and runs
`gradlew.bat build test`. Both use read-only repository permissions and no
secrets, including for fork pull requests. This source-only port requires
compatible native outputs for full integration checks; do not restore an
incompatible upstream runtime or add private credentials to a workflow.

Neither workflow launches Minecraft, measures GPU performance, or produces a
release archive. Run `releaseArchive` locally when validating packaging.
Benchmarks are a developer tool, not a public pull request or post-merge gate:
measure a performance change locally and put the numbers, workload, and visual
checks in the description. Do not depend on access to additional CI services
or GPU runners to contribute.

A pull request needs the required maintainer approvals before it merges, and
any push to the branch, a rebase included, dismisses the approvals it has, so
push fixups before asking for review and rebase only to resolve a conflict. It
is merged as one squashed commit, so write the pull request description as the
commit message you want to end up with.

## Developer Certificate of Origin

This project uses the Developer Certificate of Origin (DCO). There is no
Contributor License Agreement to sign. Every commit must carry a
`Signed-off-by` trailer certifying that you have the right to submit the work
under this project's license.

Git adds the trailer for you with `-s`:

```bash
git commit -s -m "Short description of the change"
```

Git uses your configured committer name and email for the trailer. Check your
identity before committing:

```bash
git config user.name
git config user.email
```

Three rules apply:

1. Use your real name. Anonymous contributions and pseudonyms cannot be
   accepted.
2. The sign-off address must match the commit author address.
3. Every commit in the pull request needs the trailer, not only the last one.

To add the trailer to the most recent commit:

```bash
git commit --amend -s --no-edit
```

To add it to every commit on a branch cut from `main`:

```bash
git rebase --signoff main
```

Both commands rewrite history, so force-push the branch afterwards.

### Developer Certificate of Origin 1.1

The full text of the certificate you agree to by signing off is reproduced
below and is also available at <https://developercertificate.org/>.

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

## Reporting Bugs

A bug report is not a contribution you have to build: open an issue on the
project's [issue tracker](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues),
and include everything listed in
[Reporting a Bug](docs/PLAYING.md#reporting-a-bug).
For wrapper failures, include the JDK version and failing Java stack trace or
native crash log as well.
Include the player's F3 coordinates. Held-item precision artifacts can appear
only thousands of blocks from the world origin.

[Reporting a Bug](docs/PLAYING.md#reporting-a-bug) also says which problems
belong to the runtime and go to the
[RTX Remix tracker](https://github.com/NVIDIAGameWorks/rtx-remix/issues)
instead; dxvk-remix itself takes no issues.

## Licensing Of Contributions

Contributions are accepted under the Apache License, Version 2.0, the same
license that covers this repository. See [LICENSE](LICENSE) for the full text,
[NOTICE](NOTICE) for attribution, and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for components carrying other
licenses. If your change vendors third-party code, add it to
`THIRD_PARTY_NOTICES.md` in the same pull request. Vendored code must have a
license compatible with this project's Apache-2.0 distribution. Do not copy,
translate, or lightly rewrite GPL-, LGPL-, or AGPL-licensed rendering-mod code
into this tree. A reference can inform an approach, but the submitted
implementation must be independently written against the Remix API and
Minecraft's interfaces, with rights you can certify through the DCO sign-off.
