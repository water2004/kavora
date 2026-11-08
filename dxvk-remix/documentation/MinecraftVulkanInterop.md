# Minecraft Vulkan interoperability

The `water2004/dxvk-remix` fork exposes Remix C API version 1.0.0. Its
interface includes texture creation and animation, retained scene instances,
material updates, configuration layers, resolved configuration reads, and
explicit Vulkan interoperability. The version check is exact. A published
NVIDIA 0.x runtime cannot implement this interface.

Minecraft owns its Vulkan device, window swapchain, and presentation. Start
Remix with `StartupInfo.interopImage` enabled. This selects the external
presenter without creating a second window swapchain. Both devices must select
the same physical device and driver. Compare both UUIDs returned by
`dxvk_GetVulkanInteropInfo` before importing anything.

## Import and lifetime

The query returns the original image creation parameters, dedicated allocation
size, memory type index, memory binding offset, allocation flags, and opaque
Win32 handles. Recreate the image with matching parameters and import with the
original allocation size and memory type. `OPAQUE_WIN32` imports cannot discover
this information through `vkGetMemoryWin32HandlePropertiesKHR`.

The runtime owns the returned Win32 handles. The client must not close them.
It may import them immediately or duplicate them if another asynchronous owner
needs to retain a handle. An image generation change requires the client to
retire its previous image and allocation imports safely and import the new
generation. Source allocations are dedicated, exclusive, single-sample 2D
images. Color, projected primary depth, and the separate UI overlay support transfer-source,
transfer-destination, and sampled usage. Depth is optional until the renderer
has produced a primary-depth image.

## Frame synchronization

Both shared semaphores are permanent `OPAQUE_WIN32` imports of timeline
semaphores. Their initial values are zero.

1. Submit scene changes and call `Submit`. Remix renders the frame and copies
   color and projected depth into the shared images and renders native menus into
   the transparent premultiplied `uiOverlay`. It releases ownership
   from its graphics queue family to `VK_QUEUE_FAMILY_EXTERNAL`, using
   `GENERAL -> GENERAL`, then signals the returned done-timeline value.
2. The client waits for that timeline value, acquires ownership from
   `EXTERNAL` to its queue family with `GENERAL -> GENERAL`, and uses the
   images. The client may transition layouts while it owns them.
3. Composite `uiOverlay` over the completed Minecraft screens and HUD using
   premultiplied alpha. Before returning the images, the client restores `GENERAL`, releases ownership
   from its queue family to `EXTERNAL`, and signals a strictly increasing
   client timeline value.
4. Call `Present` with `skipVulkanSwapchain = true`, `interopFlipY = false`,
   and that client timeline value. Remix queues the matching wait and
   `EXTERNAL -> graphics` ownership acquire. Minecraft presents its own
   swapchain normally.

`Submit` and `Present` must balance. Submitting another frame while the client
owns the output, presenting without a submitted frame, or reusing a client
timeline value fails. Ownership transitions cover each complete image, including `uiOverlay`.
Semaphore waits establish GPU completion; returning from a query establishes
only that the CPU metadata is available.

`Submit(nullptr)` uses default flags. `FORCE_WAIT_DEVICE_IDLE` waits for native
GPU completion. `DISABLE_CPU_WAIT_FOR_VK_SUBMIT` is an asynchronous-submission
hint; GPU use remains ordered by the shared timeline. The diagnostic
`DISABLE_QUEUE_SUBMIT` flag is rejected because a suppressed submission cannot
complete the external-image ownership handoff.

## Device and window lifetime

`Startup` publishes its device only after interop initialization succeeds. The API
keeps one device reference and one parent D3D9 reference; `Shutdown` releases
exactly those references. A device supplied to `dxvk_RegisterD3D9Device` retains
its caller-owned references. Temporary swapchain and backbuffer queries release
their acquired references before returning.

Device teardown restores the host window procedure and joins its input-ping
thread before destroying callback targets. The Minecraft window may remain alive
when Remix shuts down or the graphics backend changes.
