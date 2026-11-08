# Settings

Open **RTX Remix Settings...** in Options for the independent shader settings
screen. It has six categories: Lighting, Ray Tracing, Materials, Sky,
Weather & Volumes, and Image & Exposure. Each opens folders for related effects. Sodium's RTX Remix
sidebar also links to this screen through **Shader Settings...**.

Sodium contains DLSS, Ray Reconstruction, frame generation, capture distances,
and the background frame cap. These edits remain
pending until **Apply**; **Undo** discards them.

The independent shader screen adapts Iris's compact two-column menu, option
widgets, and widget texture. Edits remain pending across folders. **Apply**
commits them together; **Cancel** discards pending edits; **Done** applies and
closes. Drag an Iris numeric slider to adjust it, or right-click or Ctrl-click
for exact input. Named choices such as SHaRC and NRC use Iris cycle rows:
left-click advances and right-click goes back. They never open numeric input.
New configurations and the visual presets use NRC for indirect lighting.
The indirect-lighting row remains independently editable, with SHaRC and the
other modes available through the same cycle control.
Shift-click stages the default for either kind. The menu uses a full-screen
background. **Preview** hides the controls and background
to inspect the applied effect. This interface has no shader-pack or OpenGL
renderer dependency. Its adapted GUI sources and texture retain Iris's LGPLv3
license; see [Third-Party Notices](../THIRD_PARTY_NOTICES.md).

Tooltips explain each control. Pending changes appear in gold until Apply.

**Sky → End Sky** controls a ringed planet, accretion flow, nebula
and stars, adapted from Caustica-Horizon's End visuals. Environment brightness,
stellar background, accretion halo, animation speed and orientation are editable.
The complete cosmic sphere has no ground plane or horizon clipping. Animation
follows the world's elapsed time, independently of the Overworld day/night cycle.
The current Vulkan compositor produces one radiance environment for the camera,
reflections, refraction and path-traced illumination. The static stellar and
nebular field is cached; moving bodies and their occultation are evaluated in
the compositor. Licensing and source details are in
[Third-Party Notices](../THIRD_PARTY_NOTICES.md#caustica-horizon-end-environment).

Clouds separate appearance controls from **Rendering & Quality**. Fog separates
its optical controls from **Integration & Sampling** and **Density Noise**.
Materials puts heightfield controls under **Parallax & Relief**. Ray Tracing
separates Direct Lighting, Indirect Lighting, Shadows and Reflection/Refraction;
the indirect folder contains the radiance cache and sparse-sampling controls.
Folder Reset includes its child folders. All folders share the same pending
transaction and retain edits when navigating back.

## Quality profiles

The shader settings home page contains **Quality**. Selecting a profile fills
editable visual options in the current Apply/Cancel transaction. It never locks
the controls or changes DLSS, Ray Reconstruction, frame generation, or capture
distances. Editing a visual option labels the selection **Custom · High**, for
example, retaining the profile you started from. The source tier and values
persist together. Profiles leave artistic controls such as cloud shape, water
color, exposure and biome mist strength alone.

| Profile | Clouds | Fog lighting | Relief | Indirect bounces | Reflection/refraction limits |
| --- | --- | --- | --- | --- | --- |
| Ultra | World-space volume | Lighting-grid integration | Quadtree, 64 iterations | 2 | 10 / 10 |
| High | Shared 1536×768 sky | View volume, 8-pixel columns | Quadtree, 48 iterations | 2 | 8 / 8 |
| Medium | Shared 1024×512 sky | View volume, 12-pixel columns | Ray-marched, 24 iterations | 1 | 4 / 4 |
| Low | Shared 512×256 sky | View volume, 16-pixel columns | Two-sample offset parallax | 1 | 2 / 2 |

Ultra preserves the established visual baseline; it does not select the native
runtime's separately defined Ultra preset. Lower tiers keep path-traced scene
lighting, primary reflection/refraction, cloud shadows, scattering and
camera-surface relief. They use cheaper approximations and smaller light,
fog and cache sampling budgets. Offset parallax keeps normal-mapped shading
and relief shadows but approximates heightfield overlap and grazing angles.
Its iteration control still sets the relief-shadow budget. Every value remains editable.
These tiers are visual tradeoffs, not same-quality performance improvements.

`/rtx preset <low|medium|high|ultra>` fills the same visual options for the current
session. The developer launch preset uses the same path. Neither saves until
the player saves settings from the menu.

## Performance

DLSS and frame generation are always independent of the quality profile.
Select **Upscaling Off (Native DLAA)** to
render at native output resolution with DLAA. Ray Reconstruction remains an
independent control and also works at native resolution.

DLSS Frame Generation is a separate control on that page. Off presents rendered
frames only; an N× mode adds N−1 generated frames per rendered frame. The menu
queries NGX on the current GPU and driver and offers only supported multipliers.
It does not change Super Resolution quality, water normals, or ray counts.
Applying a multiplier switches presentation live. Menus, overlays, and paused
screens use rendered frames only.

Indirect lighting, bounce limits, RTXDI, sparse rendering and radiance-cache
controls are in the shader screen's Ray Tracing folders.

Distance controls trade scene coverage for performance. The two cull distances
cannot exceed Minecraft's render distance, and the far right of each of those
sliders is Auto, which follows it. Light culling chooses which chunks are
considered; light reach then decides whether each light is bright enough to
matter where you stand. Brighter lights reach further. Auto view fade never
overrides a stronger situational profile, so a map that authors its own fade
keeps it.

The background frame cap only lowers Minecraft's own Max Framerate;
Unlimited leaves that setting in charge. A running benchmark is never capped,
since it measures the renderer.

## Lighting

This category contains sun, moon, ambient light and block lights. Ray Tracing
contains direct and indirect integration, sampling budgets, reflection and
refraction, and their bounce limits.

**Block Lights** opens a table of multipliers for normal light brightness,
grouping related block lights into a row; the far left is Off. The Copper
Beacon is separate: right-click a placed beacon for its own controls. Lanterns
and sea pickles glow through their surfaces rather than casting separate
lights, so **Block Glow Intensity** under Materials controls them. Emitting
surfaces are not sampled directly, so a room lit only by them can be darker
than one with separate lights.

The Block Lights folder commits changed rows together on Apply and rebuilds
loaded terrain once. The root Reset stages defaults for all visual settings;
the category and folder Reset controls affect only their scope.

## Sky & Weather

The Stars & Milky Way folder contains a night-sky switch and separate controls for stars,
the Milky Way, meteor frequency and meteor brightness. These are artistic
settings, independent of the quality profile. Celestial radiance enters the
same environment used for path-traced lighting, reflections and transmission;
weather and clouds obscure it. Daylight fades it out, and dimensions without
an Overworld sky do not receive it. Meteors follow world time, so pausing the
world pauses their motion.

The Milky Way uses NASA SVS's catalogue-derived Deep Star Maps 2020. Its source,
credit and public-domain notice are in `packaging/licenses/NASA-Deep-Star-Maps.txt`.
The separate star field has sparse bright points over a much dimmer population.

Sky contains the atmosphere and celestial environments. Clouds, fog and ground
mist live in Weather & Volumes. Minecraft's cloud option
remains the master switch: Off removes both styles. Volumetric clouds use a
world-space density field along camera, reflection, and refraction rays;
Classic uses Minecraft's blocky cloud pattern. Cloud distance is independent
of terrain render distance and does not require the far-terrain addon.

Celestial Lights separates Moon Size from Moon Intensity. Moon Phase Contrast
controls the illumination change across the month: zero keeps constant light,
one makes new moon unlit, and the default 0.7 keeps a 30% new-moon baseline.
Full-moon illumination is independent of that control. The lunar surface uses
NASA color and elevation data through the Vulkan sky compositor.

For procedural clouds, **Cloud transport** selects either world-space
integration or a shared sky map. The map is regenerated every rendered frame
from the same density and lighting model and sampled along each background
ray, including distorted water reflections. Cloud shadows retain world-space
visibility sampling. The sky-map approximation omits local cloud occlusion
against geometry and cloud parallax between ray origins; use World volume
when flying through clouds. Its resolution is independent of DLSS.

The cloud shape and weather distribution adapt Community Edition's periodic
Perlin-Worley fields. All ray types use the same density including edge erosion.
World-volume transport uses ray marching with sampled self-shadowing and an
approximation for multiple scattering; it is not a stochastic volumetric path
tracer. The sky-map transport reuses that integration for background directions.

Volumetric controls include coverage, thickness, shape scale, density, wind,
scattering, distance, and view and shadow samples. Classic edge smoothing and
thickness affect only Classic clouds; the height offset applies to both styles.

Fog controls expose transmittance and scattering colors, optical distance,
height falloff, anisotropy, colored shadows, and density noise. Automatic ground
mist follows dawn, weather, and biome conditions rather than appearing
constantly. Its strength has a separate control. Set height falloff to zero
for uniform fog. Editing a native fog parameter overrides the corresponding
map or automatic-profile value for this session, just like image controls.

Fog's **Integration & Sampling** folder selects full lighting-grid integration
or a preintegrated view volume. Both use the same world-space fog density,
ray-traced lighting cache and depth integration. The view volume records
cumulative scattering and transmittance at the fog depth slices and samples
them at the actual surface distance. Its pixel size controls spatial reuse;
smaller columns preserve narrower light shafts. Reflection and refraction
segments keep world-space integration. Density noise and ground mist remain
active in both methods.

Indirect light candidate counts are independent of primary RTXDI candidates.
The volume sampler has separate light-grid and depth controls; depth presets
are 128 / 96 / 64 / 48 from Ultra to Low. Subsurface transmission and internal
single-scattering sample counts are independently editable.

## Material detail

LabPBR `_s` red stores perceptual smoothness. Its complement is uploaded as
perceptual roughness; Remix performs the square when evaluating the BRDF.
Static atlases, entity/particle textures and animated channel tables use the
same conversion. There is no legacy double-squared encoding option.

**Materials > Surface Detail > Automatic material detail** supplements missing
normal and material channels on static terrain sprites using CE's material
profiles and luminance-derived detail. Natural soil and vegetation stay rough;
metals, polished stone and gems retain their own responses. Authored channels
always take priority. Animated sprites keep their authored material maps.
Generation runs at atlas rebuild, without per-frame synthesis or an additional
height-field allocation. Apply commits the option with other material changes
and requests one terrain/material rebuild. Quality profiles leave it alone.

## Image & Exposure

The folders cover bloom, local and global tonemapping, color grading and RGB
balance, and eye adaptation, including metering and adaptation speed.
Color grading runs once in the shared output stage after either tone mapper;
its saturation, contrast and RGB balance controls work in both modes.
The Nether supplies a milder default saturation through
`rtx-remix/mods/minecraft-logic/confs/dimensions/nether.conf`. It changes display
color only and releases the adjustment when you leave that dimension.
Taking control of a native value masks that option in a map's lighting profiles for
the rest of the session; see [Where A Setting Can Come From](#where-a-setting-can-come-from).

## Materials

**Block Glow Intensity** changes the light emitted by glowing block surfaces,
rather than the separate sources in Block Lights. Applying it recreates shared
terrain materials and rebuilds the loaded terrain once, after the pending
values are committed together.

**Smooth Relief Maps** samples normal and height maps smoothly while block
textures keep their pixelated look, which removes banding from surface bumps
and parallax depth. It is a Remix option, so its default is the value Remix
loaded at launch. Like the other visual controls, it changes on Apply.

Water controls expose index of refraction, RGB transmittance, absorption
distance, and animation rate. They update existing material handles without
recapturing terrain. Transparent normal strength also affects other transparent
materials. Subsurface controls cover transmission, single scattering,
refraction visibility, and diffusion profile scale.

## Resetting

Sodium's reset controls stage defaults; **Apply** commits them. Shader settings
reset a folder, a category, or all visual settings, leaving performance
settings alone. Their resets remain pending until Apply, including Block Lights.

Visual settings use `config/rtxremix.json` format version 3. Earlier development
formats are not migrated. Preset-owned values and the source tier are saved;
other visual controls are saved only when overridden, so unedited defaults
remain available to world and biome profiles.

Restart the game after a reset if you had changed native effect controls.
Those values are handed to the Remix
runtime, which has no operation for taking a value back, so a reset writes the
default rather than releasing the control. A restart hands them back to the
lighting profiles.

## Where A Setting Can Come From

Rendering options can come from several layers. When they disagree, the later
one in this list wins.

**Conf files.** `rtx.conf` in the game folder sets the baseline look. On top of
it, the gameplay profiles fade in and out with the time of day, the weather,
whether you are underwater, and which biome or authored zone you are standing
in. This is the layer a map author writes, and it is described in
[Gameplay-driven profiles](../rtx-remix-minecraft/packaging/rtx-remix/mods/minecraft-logic/README.md).

**This settings screen.** Mod-owned controls drive the mod directly. Native
effect controls write a user override: moving a control, or loading a saved
value for it, hands that option to you until you
restart the game, masking any profile that wanted to author it. If you decide
the picture is too dark, a map cannot darken it again a minute later.

**The Remix runtime's overlays, under Alt+X.** This optional developer menu is
separate from the mod's settings pages. Minecraft receives no
keyboard or mouse input while the overlay is open; close it with its own close
button or Alt+X. Remix saves changes made in its overlays separately from the
mod's own settings. A native Graphics Settings change wins
over the profiles. A developer-menu change can be held back by a stronger
layer, such as a profile or a value this settings screen has written. The menu
then reports the option as blocked and offers to unblock it, which removes the
stronger value. If a setting will not stick, that is the first place to look.
The developer menu's Option Layers view names the owner of any option.

## The Settings File

Visual values and quality-profile provenance live in `config/rtxremix.json`.
DLSS, Ray Reconstruction and frame generation live in `config/rtxremix-video.json`.
Both interfaces save their respective files on Apply, including Block Lights.

The visual file retains the profile's values, including explicit defaults,
and non-default artistic/performance overrides. A default-valued artistic
control is omitted so world and biome profiles can continue authoring it.
The file remains present to retain the source profile. A missing file uses Ultra.

The mod settings file carries a version. A file written by a different version is ignored
rather than converted, because the meaning of a stored value can change between
versions and a silently misread value is worse than a reset. An entry naming a
setting that no longer exists, or holding a value outside the setting's range,
is skipped and reported in `logs/latest.log`. The rest of the file still loads.

The video settings file validates an explicit whitelist of native options and
their ranges. Delete the corresponding file to clear those saved overrides.

## Developer Notes

The registry is built after `initializeRemix()` because its getters and
appliers bind to the live renderer managers. A renderer reinitialization path
must rebuild those bindings. `RtxSettingsFactory` defines controls,
`RtxSodiumConfig` registers the public Sodium config API bindings. Its storage
handlers commit native overrides and terrain material changes after all changed
bindings have been applied. `RtxSettingsSession` stages visual edits without
calling their appliers. `RtxSettingsScreen` commits the session and performs
any required terrain recapture once per Apply transaction.

Ordinary `SimpleOption` sliders apply live, but their change-consumer runs only
when the value differs from the option's current value. Returning to a displayed
default is still a real write when the old value differs. A runtime-backed
Image & Exposure setting remains a User Settings opinion even after that reset;
the API has no clear operation.

### Settings Backed By A Remix Option

`RtxSettingsFactory.remixBool` binds a boolean Remix option, such as Smooth
Relief Maps, without a default authored in Java. It reads the option once
through `GetConfigVariableResolved` while the registry is built, before the
settings file loads, so the default is whatever `rtx.conf` and Remix's own
saved settings resolved to. After that the setting reports its own writes.
Remix resolves a `SetConfigVariable` write at a later option update, and a read
before then returns the previous value, so reading the option back after a
click could save or display the old one. The page therefore does not follow
later changes made in a Remix menu. Once the mod has written the option, a
developer-menu edit goes to the `rtx.conf` layer, beneath the mod's User
Settings value, so Remix reports it as blocked. Unblocking it there removes the
mod's value, and the page keeps showing its last value until the next launch.

A runtime that does not define the option fails the startup read. The setting
is then left off the page and the log names the option, so an older runtime
still starts.

### Widget Layout And Labels

`OptionListWidget` normally pairs two 150 px controls per row, or uses one
310 px control. A `Label: Value` string has a 146 px budget after its margins;
the 98 px footer buttons have 94 px. Overflow scrolls as a marquee through
`ClickableWidget.drawScrollableText`, rather than wrapping. A screenshot then
catches an arbitrary clipped label or value.

When adding a control, register its widest value in `SettingsRowWidthTest` and
add its label and tooltip in `assets/rtx-remix-renderer/lang/en_us.json`.
The test measures real default-font advances. Keep values short and shorten
labels before values; an overflowing existing label is not a precedent.

Do not refresh a `GameOptionsScreen` with `clearAndInit()`: its persistent
`ThreePartsLayoutWidget` would acquire another list and dark background.
Open a fresh screen instance, as the settings reset paths do.

## Going Further

| You want to | Read |
|---|---|
| Look up a command or a key | [In-Game Guide](IN_GAME_GUIDE.md) |
| Give your own map its own lighting | [Gameplay-driven profiles](../rtx-remix-minecraft/packaging/rtx-remix/mods/minecraft-logic/README.md) |
| Make a texture pack this renderer understands | [Resource Packs](RESOURCE_PACKS.md) |
| Record a camera flythrough | [Camera Paths](CAMERA_PATHS.md) |
| Install, repair, or uninstall | [Playing](PLAYING.md) |
