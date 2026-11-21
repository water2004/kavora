# SPDX-License-Identifier: Apache-2.0
import unittest

import numpy as np

from common.revelation_phase import transport_tables
from hanpi.transport import phase_probability, write_solar_features
from neural.feature_layout import SUN_FEATURE_NAMES


class SolarInputTest(unittest.TestCase):
    def test_opposite_views_at_the_same_collision(self):
        # At 75% height in a uniform slab the two views see different remaining
        # material, even though the point, column, and incoming light agree.
        density = np.ones((5, 4, 5), dtype=np.float32)
        point = np.array([5., 6., 5.])
        light = np.array([0., 1., 0.])
        views = np.stack([light, -light])
        rows, norms, moments, _ = transport_tables()
        features = np.zeros((2, len(SUN_FEATURE_NAMES)), dtype=np.float32)
        for out, view in zip(features, views):
            write_solar_features(out, density, density, point, 10., 8., 1., 8., light, view,
                                 rows[0], norms[0], moments[0], 6)
        expected = {
            "log_sun_tau": np.log1p([2., 2.]), "log_view_tau": np.log1p([2., 6.]),
            "log_column_tau": np.log1p([8., 8.]), "light_up": [1., 1.],
            "cos_sun": [1., -1.], "phase": [phase_probability(rows[0], norms[0], mu) for mu in (1., -1.)],
            "view_up": [1., -1.], "phase_g": [moments[0], moments[0]],
        }
        np.testing.assert_allclose(features, np.column_stack([expected[k] for k in SUN_FEATURE_NAMES]), rtol=2e-6)
