# Settings

Open **RTX Remix Settings...** in Options for the independent shader settings
screen. It has four categories: Lighting, Sky & Weather, Image & Exposure,
and Materials. Each opens folders for related effects. Sodium's RTX Remix
sidebar also links to this screen through **Shader Settings...**.

Sodium contains the quality preset, DLSS, frame generation, tracing and cache
controls, capture distances, and the background frame cap. These edits remain
pending until **Apply**; **Undo** discards them.

The independent shader screen adapts Iris's compact two-column menu, option
widgets, and widget texture. Edits remain pending across folders. **Apply**
commits them together; **Cancel** discards pending edits; **Done** applies and
closes. Drag a numeric control to adjust it, right-click or Ctrl-click for
exact input, or Shift-click to stage its default. The menu uses a full-screen
background. **Preview** hides the controls and background
to inspect the applied effect. This interface has no shader-pack or OpenGL
renderer dependency. Its adapted GUI sources and texture retain Iris's LGPLv3
license; see [Third-Party Notices](../THIRD_PARTY_NOTICES.md).

Tooltips explain each control. Pending changes appear in gold until Apply.

## Performance

The first RTX Remix page selects the renderer's quality preset. Choose Custom
to edit DLSS Super Resolution, its quality mode, Ray Reconstruction, and the
Advanced Runtime Settings controls. Select **Upscaling Off (Native DLAA)** to
render at native output resolution with DLAA. Ray Reconstruction remains an
independent control and also works at native resolution.

DLSS Frame Generation is a separate control on that page. Off presents rendered
frames only; an N× mode adds N−1 generated frames per rendered frame. The menu
queries NGX on the current GPU and driver and offers only supported multipliers.
It does not change Super Resolution quality, water normals, or ray counts.
Applying a multiplier switches presentation live. Menus, overlays, and paused
screens use rendered frames only.

Advanced Runtime Settings exposes indirect lighting and bounce limits, RTXDI,
radiance-cache quality, combination and filtering, spatial and LOD jitter,
and sparse rendering. These use the same Apply/Undo
transaction as the main RTX page.
`/rtx preset <name>` sets the same tier for the current session only, for a
quick comparison without opening the menu; it changes nothing on these pages
and is not saved.

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

This category contains sun, moon, and ambient light, block lights, direct and
indirect lighting, reflection and refraction, and their bounce limits.

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

The sky, cloud, and volumetric fog folders live here. Minecraft's cloud option
remains the master switch: Off removes both styles. Volumetric clouds use a
world-space density field along camera, reflection, and refraction rays;
Classic uses Minecraft's blocky cloud pattern. Cloud distance is independent
of terrain render distance and does not require the far-terrain addon.

Volumetric controls include coverage, thickness, shape scale, density, wind,
scattering, distance, and view and shadow samples. Classic edge smoothing and
thickness affect only Classic clouds; the height offset applies to both styles.

Fog controls expose transmittance and scattering colors, optical distance,
height falloff, anisotropy, colored shadows, and density noise. Automatic ground
mist follows dawn, weather, and biome conditions rather than appearing
constantly. Its strength has a separate control. Set height falloff to zero
for uniform fog. Editing a native fog parameter overrides the corresponding
map or automatic-profile value for this session, just like image controls.

## Image & Exposure

The folders cover bloom, local and global tonemapping, color grading and RGB
balance, and eye adaptation, including metering and adaptation speed. Taking
control of a native value masks that option in a map's lighting profiles for
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
[Gameplay-driven profiles](../packaging/rtx-remix/mods/minecraft-logic/README.md).

**This settings screen.** Mod-owned controls drive the mod directly. Native
effect controls write a user override: moving a control, or loading a saved
value for it, hands that option to you until you
restart the game, masking any profile that wanted to author it. If you decide
the picture is too dark, a map cannot darken it again a minute later.

**The Remix runtime's overlays, under Alt+X.** This optional developer menu is
separate from Sodium's Advanced Runtime Settings page. Minecraft receives no
keyboard or mouse input while the overlay is open; close it with its own close
button or Alt+X. Remix saves changes made in its overlays separately from the
mod's own settings. A native Graphics Settings change wins
over the profiles. A developer-menu change can be held back by a stronger
layer, such as a profile or a value this settings screen has written. The menu
then reports the option as blocked and offers to unblock it, which removes the
stronger value. If a setting will not stick, that is the first place to look.
The developer menu's Option Layers view names the owner of any option.

## The Settings File

Mod settings live in `config/rtxremix.json`. Preset, DLSS, Ray Reconstruction,
and Advanced Runtime Settings overrides live in `config/rtxremix-video.json`.
Both interfaces save their respective files on Apply, including Block Lights.

The file records only what differs from the default. Return a control to its
default and its entry disappears; when nothing differs, the file is deleted.
A missing or empty file means you are on defaults.

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
| Give your own map its own lighting | [Gameplay-driven profiles](../packaging/rtx-remix/mods/minecraft-logic/README.md) |
| Make a texture pack this renderer understands | [Resource Packs](RESOURCE_PACKS.md) |
| Record a camera flythrough | [Camera Paths](CAMERA_PATHS.md) |
| Install, repair, or uninstall | [Playing](PLAYING.md) |
