# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: Apache-2.0

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np
import torch

from neural.feature_layout import SUN_FEATURE_NAMES
from neural.model import MultiScatterMLP
from pack_weights import pack_weights


class WeightExportTest(unittest.TestCase):
    def test_packed_layers_match_pytorch(self):
        torch.manual_seed(137)
        model = MultiScatterMLP(15, hidden=96, layers=4)
        with torch.no_grad():
            model.head.weight.normal_(std=0.1)
        rng = np.random.default_rng(137)
        model.set_feature_norm(rng.normal(size=15), rng.uniform(0.2, 2.0, size=15))
        features = rng.normal(size=(256, 15)).astype(np.float32)
        expected = model(torch.from_numpy(features)).detach().numpy().ravel()

        with TemporaryDirectory() as directory:
            archive = Path(directory) / "trained.npz"
            binary = Path(directory) / "weights.bin"
            model.export_numpy(archive, SUN_FEATURE_NAMES)
            pack_weights(archive, binary)
            payload = np.fromfile(binary, dtype="<f4")

        actual = features
        offset = 0
        for inputs in (15, 96, 96, 96):
            end = offset + 96 + inputs * 96
            bias = payload[offset:offset + 96]
            matrix = payload[offset + 96:end].reshape(inputs, 96)
            actual = actual @ matrix + bias
            actual /= 1 + np.exp(-actual)
            offset = end
        logits = actual @ payload[offset + 1:offset + 97] + payload[offset]
        np.testing.assert_allclose(0.25 * np.logaddexp(0, logits), expected, rtol=1e-5, atol=2e-7)
        self.assertEqual(payload.size - offset - 97, 25 * (3 + 9))
        self.assertEqual(payload.nbytes, 119476)

    def test_rejects_reordered_features(self):
        model = MultiScatterMLP(15, hidden=96, layers=4)
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "reordered.npz"
            model.export_numpy(archive, SUN_FEATURE_NAMES[::-1])
            with self.assertRaisesRegex(ValueError, "feature order"):
                pack_weights(archive, Path(directory) / "weights.bin")
