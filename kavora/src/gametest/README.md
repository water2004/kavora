# Native client GameTest

`gradlew.bat runClientGameTest` runs Fabric's client GameTest harness on Java 25.
It creates a flat creative single-player world through Minecraft's create-world screen,
checks that the Copper Beacon recipe loaded, waits for native Remix initialization
and published terrain geometry plus 40 successful native submit/present calls,
rejects native frame failures and an all-black daylight scene, captures a screenshot, leaves, reopens the same
save, and repeats the rendering assertions and screenshot. Readback uses the
completed Minecraft framebuffer; Fabric's extra screenshot render would bypass
the frame hooks that pair Remix submission and presentation.

The test uses `build/run/clientGameTest/`, independent of `run/` and Prism.
The test resource pack deliberately gives stone bricks a face tint index without
a registered tint source. Vanilla renders that valid combination white; terrain
capture must preserve it without throwing or stalling the section builder pool.
The material gallery also includes untinted lava, and a resource reload must
restore all previously captured terrain before its screenshot is accepted.
It switches smooth relief filtering on and off through the native option,
checks resolved values, captures both results and restores the previous value.
The full suite also resizes a live world to 1280×720 and 1600×900 and back,
checking successful native frames and nonblack readbacks after each shared-image
replacement.
`setupClientGameTestRuntime` depends on the normal `setupRuntime` provenance and
native-support checks and copies its runtime, textures, configuration, and shipped
conf layers into the test directory. Stop clients from this checkout before
running Gradle. The client test inherits the normal client JVM tuning.

The mod selects Vulkan before device creation and disables OpenGL fallback.
Use `-PgameTestExpectedBackend=VULKAN` to assert the active backend. Keep Voxy out
of the test profile; it is unsupported.

For the optional Kavora Horizon addon, build its Fabric JAR first and select an
independent test directory:

```powershell
.\gradlew.bat runClientGameTest '-Parch=win64' '-PgameTestExpectedBackend=VULKAN' `
  '-PgameTestRunDir=build/run/remixHorizonsGameTest' `
  '-PgameTestFarTerrainJar=../rtx-remix-dh/fabric/build/libs/KavoraHorizon-0.2.0-26.3.jar'
```

The addon is copied as `mods/kavora-horizon-gametest.jar`; it is never embedded
in the renderer. Keep stock DH and Voxy out of this directory. Omitting the
property does not remove a previously installed addon, so use a separate empty
profile for tests without far terrain.

`compileGametestJava` compiles the test source without launching the client.
The normal JVM unit-test task does not run this GPU integration test. Fabric
bounds world creation/rejoin and shutdown at 1,200 ticks; additional readiness
checks allow 2,400 ticks. The Gradle launch task fails after five wall-clock
minutes and terminates its child client, including a stalled native call.
Screenshots are logged with absolute paths by the test and remain
available for visual inspection. No approved image baseline is bundled: passing
the test proves resource loading, native initialization, terrain publication and
session reuse, but does not establish visual correctness or performance.

`-Dkavora.gametest.streamingOnly=true` selects the continuous-flight regression.
`-Dkavora.gametest.includeStreaming=true` adds that flight to the full suite.
It flies 320 blocks out and back using held movement input, checks the surrounding
3×3 near columns at eight checkpoints, requires all resident terrain to finish
publishing, and verifies that each vacated end remains in the far scene at the
requested distance-dependent detail after its near column has unloaded. A coarse
fallback ancestor does not satisfy this check. After a turn, the entire observed view must reach its selected detail and acknowledge mesh updates. `-Dkavora.gametest.streamingJfr=<absolute path>` records
that flight for render-thread and worker analysis.

`-Dkavora.gametest.settingsOnly=true -Dkavora.gametest.reconstructionInputs=true`
isolates the RR input-resource regression. It renders all eight combinations of
sparse rendering, disocclusion-mask blur and specular hit distance with motion blur enabled, checks completed
frames, then switches RR and PSR off and on, exercises ray-traced render targets
and stochastic alpha, switches SER off and on, and returns to the RR/PSR pipeline.
It restores the original options, then resizes and reopens the world.
It uses successful native presentation and image readback rather than a fixed delay.

`-Dkavora.gametest.beaconOnly=true` isolates an active vanilla beacon with a
stained-glass colour transition, an unfiltered control, and day/night views.
`-Dkavora.gametest.retainedOnly=true` isolates mixed compact and legacy vertex
uploads, shared instances, material and texture updates, normal smoothing and
restoration, transforms, hiding, replacement and removal. It compares captured
pixels and excludes the HUD from the fixture. The pixel probe keeps one camera
jitter phase for its comparisons and restores the original sequence afterward.
It waits for the terrain-download screen to close before reading the world.
`-Dkavora.gametest.nativeOverlay=true` additionally checks a native menu in the
frame-generation fixture. Its screenshot is read after native-menu composition,
which occurs later than the ordinary world captures.
`-Dkavora.gametest.particleOnly=true` reads back a blue particle at alpha 0.25
against a fixed white wall. The ordinary opaque atlas layer must retain soft
blending, while a terrain particle must fail the reference cutout threshold.
`-Dkavora.gametest.width=3840 -Dkavora.gametest.height=2160` requests 4K gallery
and beacon captures through Fabric's window-resize API. Confirm the actual PNG
and native dimensions; changing the physical SDL window alone does not change
the test harness's virtual framebuffer.
The test-only Vulkan surface hook bounds Fabric's presentation blit to the
acquired swapchain while a physical resize is still pending. It does not change
the offscreen scene resolution or ship in the mod JAR.

The isolated far-terrain streaming profile also accepts
`-Dkavora.gametest.streamingWater=true` for a flat sand bed with four water
layers, or `-Dkavora.gametest.streamingNatural=true` for normal terrain with
seed 1. Both use the same 640-block round trip and assert far coverage after
near geometry unloads. The streaming profile closes and reopens its world,
requiring a fresh far-terrain generation, recovered geometry at the previously
visited far endpoint, whole-view refinement, and no failed builds. Failure diagnostics include actual
selection levels, worker admission, upload queues, and pending materials.
Water captures retain the same world-space wave scale through the
near/far boundary; inspect the completed-frame PNGs as well as the assertions.

`-Dkavora.gametest.waterGeometry=true` exports retained water geometry to
`water-geometry.bin`, then temporarily hides water for seabed/material captures.
`-Dkavora.gametest.waterDiagnostics=true` captures normals, depth, material and
individual reflection/refraction options. Both use completed native frames and
wait for each restored runtime option to resolve before the next probe. The
geometry recorder and instance-visibility hooks are test-only and never ship in
the renderer JAR.

`KavoraMaterialSceneGameTest` builds an original fixed gallery with server commands.
Time and weather progression, random block ticks, and ambient mob spawning are
disabled. It captures the following views with the HUD hidden; filenames contain
the actual graphics backend. All views require native ray-traced frames, retained
terrain, successful submission/presentation and a nonblack scene.

| View | Coverage |
|---|---|
| `materials-day` | Stone relief, lava, metal PBR, glass, ice, leaves, stairs/slabs, chest, banner, sign, item frames with a map/item, cow, sheep and armor stand |
| `materials-relief-smooth` / `materials-relief-point` | Native normal/height filtering switch with colour sampling preserved |
| `materials-foliage-sharc` / `materials-foliage-nrc` | Backed oak, cherry and birch leaf panels under both radiance caches, switching modes in a live world |
| `materials-underwater` | Water surface from below, absorption, refraction and the submerged room |
| `materials-foliage-refraction` | Waterlogged leaves viewed through the water surface, with the native thin opaque refraction policy |
| `materials-emission-night` | Sea lantern, torch, glowstone, soul lantern and campfire |
| `materials-glowstone-only` | Emissive illumination with the other gallery lights removed |
| `materials-beacon` | Copper Beacon in a separate camera view |
| `materials-vanilla-beacon-night` / `materials-vanilla-beacon-day` | Active vanilla beacon, opaque emissive core and blended halo, with a stained-glass colour transition |
| `materials-rain` | The daylight gallery under rain |

The gallery must finish publishing all known terrain before each view, preventing
an optional far source from hiding a stalled near install behind a nonblack image.
The daylight view also requires neutral, textured stone bricks in its fixed
foreground region. A sky-only image with floating entities must fail this check
even though it is nonblack and scene handles exist.
The Beacon is separated from glowstone to avoid attributing its occluded spot
beam to the emissive block.

The retained-scene fixture checks shared multi-material meshes while updating
each material independently, removing a referenced material, moving/hiding
instances, replacing meshes, and removing instances.
It also updates two textures in one call, each through multiple pitched,
read-only buffer slices, and checks the changed and restored albedo pixels.

`KavoraDimensionGameTest` changes dimension without disconnecting, visiting the
Nether, End and then Overworld. Each destination has a small lit platform and
requires its correct sky type plus successful native frames and terrain output.

The vanilla section path also checks that the captured gallery floor retains
its real face-connectivity graph despite having no raster geometry. Resource
reload testing looks away from terrain before invalidation, so vanilla's
ordinary visible-only rebuild cannot hide a lost explicit recapture.

These original fixtures catch regressions; matched official 1.21.1 showcase
captures provide a separate visual comparison. Neither establishes parity for
every block, a full playthrough, or large-world performance.

With `kavora.gametest.particleOnly=true`, the additional
`kavora.gametest.particleLighting=true` diagnostic captures a non-emissive blue
particle against the same wall while changing volumetric atmosphere, attenuation,
reference integration, and the four reservoir reuse options individually. It
restores the resolved settings on exit and logs pixel deltas without treating a
brighter image as proof of correctness or official parity.

World readiness also checks that the runtime recognizes and resolves the shipped
UV bounds, NRC wrapping, thin opaque SSS, camera-medium sky and resident-scene
options. A matching configuration file cannot establish that these features exist.
