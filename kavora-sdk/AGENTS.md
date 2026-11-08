# Java SDK

This is the independent Java 25 FFM binding for custom Remix C API 1.5.0.
Do not import Minecraft or Fabric, or introduce JNI/JNA. The main mod consumes
this Gradle project; SDK source remains usable without the game.

Source authority: `src/org/edtp/kavora/sdk/`, the matching runtime C header,
then the copied `native/include/remix_c.h`. Keep both headers identical and
update layouts, descriptors, facade, data holders and callers together.

Handles are raw longs. Document direct-buffer requirements and segment
lifetimes; borrowed Java memory must remain live until its foreign call returns.
The support DLL handles Windows process/crash support, not Remix marshalling.
Its outputs belong in this project's `build/native/`.

Build with `gradlew.bat :kavora-sdk:build` from the repository root.
The layout and mock-runtime contracts in `ffm-test/` remain separate probes.
They do not establish Vulkan presentation correctness. See the root build guide
and the component README. Preserve the copied header's MIT notice.