# End environment

The End environment adapts `shaders/world/end_environment.slang` from
[Caustica-Horizon](https://github.com/West68/Caustica-Horizon), revision
`ea3dc4bf67fc3bfce1860dbb025c71626b26e983`. Copyright 2026 ComfyFluffy and
contributors. Caustica-Horizon is maintained by Ruiwen68 (West68).

The adapted `caustica_end.slangh` and `generate_end_background.comp.slang`
under `src/dxvk/shaders/rtx/pass/dome_composite/` remain LGPL-3.0-or-later.
The license texts are [LGPLv3](licenses/Caustica-Horizon-LGPL-3.0.txt) and
[GPLv3](licenses/Caustica-Horizon-GPL-3.0.txt). The native fork's modified
source and build instructions are available at
[water2004/dxvk-remix](https://github.com/water2004/dxvk-remix).

`NightSky` caches the directional nebula and stars in a floating-point image
while the End is active. The dome compositor samples its rotation and evaluates
the planet, rings and accretion flow from the host's synchronized world clock.
The composed radiance supplies the existing environment's background,
reflection, refraction and lighting paths, including downward rays below the
islands. The host owns sky selection and the native volume transport owns fog.

The planet uses the reference's uniform surface albedo and ring shadows.
Its 15-minute orbit changes the illuminated phase, starting almost fully
backlit. The surface has no separate texture asset.

This integration uses the reference's procedural noise path. It incorporates
no external ITRP or SEUS noise textures, no display-fog overlay and no separate
renderer framework. Environment intensity, background intensity, halo,
orientation and animation speed are exposed through `rtx.endSky` options.
