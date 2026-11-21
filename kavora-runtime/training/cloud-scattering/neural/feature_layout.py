# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: Apache-2.0
"""Feature ordering shared by training and Vulkan weight packing."""

SUN_FEATURE_NAMES = [
    "log_sun_tau", "log_view_tau", "log_column_tau", "light_up", "cos_sun", "phase", "view_up", "phase_g",
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
    "phase_g",
]

def feature_names(channel: str = "sun") -> list[str]:
    return SKY_FEATURE_NAMES if channel == "sky" else SUN_FEATURE_NAMES
