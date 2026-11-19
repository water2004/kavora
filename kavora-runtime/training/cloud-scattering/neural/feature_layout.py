# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Feature ordering shared by training and Vulkan weight packing."""

SUN_FEATURE_NAMES = [
    "rho",            # local density
    "rho_mid",        # density averaged over ~2 voxels
    "rho_large",      # density averaged over ~4 voxels
    "rho_xlarge",     # density averaged over ~8 voxels
    "sun_T",          # exp(-tau_sun)
    "sun_tau_n",      # tau_sun / (1 + tau_sun)
    "height",         # 0 at cloud base, 1 at cloud top
    "cos_sun",        # dot(sun_dir, view_ray_dir)
    "phase",          # HG phase at cos_sun
    "view_up",        # view ray y component
    "rho_toward_sun_150",   # density 150 m towards the sun
    "rho_toward_sun_500",   # density 500 m towards the sun
    "rho_toward_sun_1200",  # density 1200 m towards the sun
    "rho_away_sun_300",     # density 300 m away from the sun
    "rho_up_500",           # density 500 m above (sky occlusion probe)
]

SKY_FEATURE_NAMES = [
    "rho",
    "rho_mid",
    "rho_large",
    "rho_xlarge",
    "tau_up_n",       # vertical optical depth above the point, tau/(1+tau)
    "height",
    "cos_up",         # dot(view_ray_dir, up)
    "rho_up_300",     # density 300 m above
    "rho_up_800",     # density 800 m above
]

def feature_names(channel: str = "sun") -> list[str]:
    return SKY_FEATURE_NAMES if channel == "sky" else SUN_FEATURE_NAMES
