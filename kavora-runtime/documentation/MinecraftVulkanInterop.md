# Minecraft Vulkan interoperability

Kavora uses the custom Remix C API **1.5.0**. Minecraft and the native runtime
share one Vulkan device and graphics queue. Minecraft owns its render targets
and window. The runtime owns the window swapchain, presentation, and frame
generation. An upstream NVIDIA 0.x runtime cannot implement this interface.

## Device and image ownership

`CreateVulkanDevice` creates the union of the host and runtime requirements.
`AdoptVulkanDevice` transfers destruction to the host after its dispatch table
is ready. The host calls `Shutdown` before destroying the device or instance,
including initialization failures before `Startup`.

Start with `StartupInfo.interopImage` enabled. `dxvk_GetVulkanInteropInfo`
reports the exact device, image formats, usage, extent, and resource generation.
On the shared device, image and semaphore handles are borrowed directly. The
client never destroys them, exports another device, or performs external queue
family ownership transfers. Resource generations invalidate cached borrowed
views. Color may alias the runtime's final output. Projected depth is optional
until a ray-traced frame has produced it. The native UI has its own transparent,
premultiplied RGBA8 image.

The API also describes opaque Win32 imports for independent API clients. Those
clients must match device and driver UUIDs, image creation parameters, dedicated
allocation size, memory type, and bind offset. They import the runtime-owned
handles without closing them, and use `GENERAL` layout with matching external
queue-family releases and acquires. This is not Minecraft's shared-device path.

## Frame synchronization

1. Submit scene changes and call `Submit`. The runtime latches the display
   contract, renders the scene, exposes color and depth, renders native UI, and
   signals the returned done-timeline value.
2. Minecraft waits for that value before reading the borrowed images. It
   composites the scene, game effects, and GUI into its own main target, then
   composites the premultiplied native UI. It keeps an optional pre-UI copy for
   frame generation.
3. The client restores borrowed images to `GENERAL`, signals a strictly
   increasing client timeline value, and calls `Present` to complete the source
   handoff. A submitted scene must be completed before another `Submit`.
4. `VulkanClientFrameInfoEXT` supplies the host's composed image and its separate
   ready timeline. The runtime waits, converts the image in the existing present
   pass, and signals `sourceConsumedValue`. Host writes must wait for that value.
   An optional HUDless image has the same format, extent, orientation, and lifetime.
   With `VulkanClientUiCoverageEXT`, the final source alpha contains HUD coverage.
   The host clears alpha at the pre-UI capture boundary. HUD draws accumulate
   coverage there while retaining their original RGB blend, including inversion.

An `image` value of zero synchronously releases borrowed presentation views
before the host destroys or replaces its target. Presentation counters restart
when `presentationEpoch` changes. Menu frames use the same presenter without
ending a ray-traced scene twice.

Returning from a metadata query establishes CPU availability, not GPU completion.
`FORCE_WAIT_DEVICE_IDLE` explicitly waits for native GPU completion.
`DISABLE_CPU_WAIT_FOR_VK_SUBMIT` changes the CPU submission wait only. Suppressing
the Vulkan submission is rejected because it cannot complete the timeline handoff.

## Color contract

The shared host's main target and color post-process targets remain
`R16G16B16A16_SFLOAT` for their device lifetime, including SDR output. They contain
**display-referred extended-sRGB**: exact piecewise sRGB encoding of linear Rec.709
display values, with one at paper white and values above one retaining HDR
headroom. They are not Windows linear scRGB images. Game and native GUI colors
retain their existing sRGB blending convention.

The runtime exposes its floating-point final output directly on the shared
device. Color copies and game effects preserve its format. The legacy 8-bit
external path retains its existing SDR behavior. There is no new C struct,
layout, function-table entry, or version change for HDR.

`DxvkDisplayOutput` owns display capabilities, requested settings, confirmed
output, fallback reason, and generations. Its platform query is separate from
the pure display policy. `DxvkDisplayFrame` is copied into the command stream
before scene post-processing and carried with the composed frame. It cannot be
replaced by newer settings during asynchronous presentation or frame generation.

### Negotiation and conversion

Auto requires a shared floating-point host, the optional Vulkan swapchain color
space extension, verified Windows HDR state for the window's monitor, and an
actual 10-bit UNORM / `HDR10_ST2084` surface pair. Wide-color SDR support alone
does not qualify. Monitor queries are read-only and search all display adapters.
An unconfirmed or failed HDR surface keeps scene output in SDR range. A rejected
HDR creation receives one SDR retry with an observable fallback reason.
The first valid client presentation confirms its FP16 or SDR image contract;
sharing a Vulkan device alone does not qualify an 8-bit client for HDR. Later
submissions must retain that contract. A zero-image retirement does not lock it.

The existing present pass decodes extended-sRGB, scales by paper-white luminance,
converts Rec.709 to Rec.2020, and encodes absolute-nit ST.2084 PQ. The 10-bit output
receives quantization dither. Optional HDR metadata describes the negotiated
luminance but does not establish the image's transfer function. An SDR attachment
uses the appropriate sRGB output conversion, including hardware sRGB encoding.

When Windows HDR or the target monitor changes after a scene was rendered, the
source retains its own luminance parameters. HDR-to-SDR fallback applies a
bounded shoulder instead of clipping that frame's highlights. The next scene
uses the new output contract. Luminance calibration changes invalidate frame
generation history without reallocating an otherwise compatible swapchain.

### Tone mapping and frame generation

Global HDR tone mapping applies the existing exposure to scene-linear color,
then a common RGB-scale highlight shoulder. Local HDR tone mapping retains its
exposure fusion and guided upsampling using a reversible logarithmic luminance
coordinate. Both preserve positive RGB ratios through highlight compression
and keep values above one through grading. Shared FP16 output uses exact sRGB
encoding without 8-bit clipping or dither. SDR-only tone curve controls continue
to describe the SDR path.

Local HDR exposure weights account for the distance between synthetic exposures
on that log axis. Guided reconstruction keeps a nonnegative local slope and
stays within the pixel's available exposure range. These constraints prevent
brightness reversal and black halos without adding a pass or history image.

Composed and HUDless images use the same source snapshot, output transfer,
calibration, orientation, and dither coordinates. NGX receives the actual
negotiated encoding. The presentation draw writes coverage to an R8 attachment
in the same virtual backbuffer slot. NGX UI recomposition consumes that mask and
HUDless color for each generated frame. Its context identity includes extent,
format, HDR mode and UI recomposition;
source and output generations reset interpolation history. Swapchain changes
synchronize the command stream, presenter, and outstanding GPU use before
retiring resources.

An NGX initialization or evaluation failure disables frame generation and
publishes its reason while ordinary presentation continues. An HDR10 FG failure
does not disable HDR rendering. Changing HDR Auto/Off or the native FG preset
requests one retry; the context is retired only after the FG queue drains at a
batch boundary. There is no automatic per-frame retry loop.

### Screenshots and status

Ordinary screenshots and world thumbnails remain SDR PNGs. FP16 readback decodes
half-floats, applies the same source-dependent SDR shoulder and sRGB encoding,
and preserves the existing filename, notification, and asynchronous save flow.
The source snapshot is captured when readback is submitted, not when its callback
eventually runs. These PNGs cannot demonstrate physical HDR brightness.

`GetConfigVariableResolved` exposes read-only `rtx.display.status.*` values:
`mode`, `reason`, `paperWhiteNits`, `peakNits`, `generation`, and `sourceFrame`.
The last is one atomic, space-separated snapshot of `frameIndex flags
paperWhiteNits peakNits`; flag 1 denotes extended-sRGB and flag 2 denotes HDR
scene range. Status is owned by the display subsystem and never persisted as
options. `SetConfigVariable` rejects the reserved status prefix. The existing
buffer-size query and retry contract applies.

## Cost and validation boundaries

RGBA16F adds four bytes per pixel over RGBA8 for each changed host color target:
7.91 MiB at 1080p, 14.06 MiB at 1440p, and 31.64 MiB at 4K. Optional effect and
HUDless targets incur that difference only when allocated. The runtime's final
output was already FP16. Display conversion is fused into the present pass.

Numerical shader and policy tests cover transfer functions, luminance limits,
format selection, and mode transitions. They do not establish Windows compositor
behavior, monitor calibration, physical display output, or NGX image quality.
Those require a supported Windows HDR display and an actual client run.

`test_display_color` and `test_display_output` exercise the shared color math
and pure display policy. The opt-in Meson targets `test_display_color_gpu` and
`test_display_present` require NumPy, SlangPy, and a hardware or software Vulkan
device. They execute the production tone passes and final-present helper,
including HDR-to-SDR transitions, FP16 headroom, local exposure bounds, and
source/target luminance changes. Java screenshot tests write actual SDR PNGs
from half-float input and compare their decoded pixel values.
