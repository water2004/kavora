# SPDX-FileCopyrightText: Copyright (c) 2026 Kavora contributors
# SPDX-License-Identifier: Apache-2.0

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

import numpy as np
import torch

from neural.feature_layout import SKY_FEATURE_NAMES, SUN_FEATURE_NAMES
from neural.model import MultiScatterMLP
from pack_weights import model_payload, pack_weights


class WeightExportTest(unittest.TestCase):
    def test_packed_layers_match_pytorch(self):
        with TemporaryDirectory() as directory:
            directory = Path(directory)
            for channel, names in (("sun", SUN_FEATURE_NAMES), ("sky", SKY_FEATURE_NAMES)):
                self.check_model(directory / (channel + ".npz"), channel, names)
            binary = directory / "weights.bin"
            pack_weights(directory / "sun.npz", directory / "sky.npz", binary)
            self.assertEqual(binary.stat().st_size, 237320)

    def check_model(self, archive, channel, names):
        torch.manual_seed(137)
        inputs = len(names)
        model = MultiScatterMLP(inputs, hidden=96, layers=4, activation="leaky_relu")
        with torch.no_grad():
            model.head.weight.normal_(std=0.1)
        rng = np.random.default_rng(137)
        model.set_feature_norm(rng.normal(size=inputs), rng.uniform(0.2, 2.0, size=inputs))
        features = rng.normal(size=(256, inputs)).astype(np.float32)
        expected = model(torch.from_numpy(features)).detach().numpy().ravel()

        model.export_numpy(archive, names)
        payload = model_payload(archive, channel)
        actual = np.pad(features, ((0, 0), (0, 16 - inputs)))
        offset = 0
        for inputs in (16, 96, 96, 96):
            end = offset + 96 + inputs * 96
            bias = payload[offset:offset + 96]
            matrix = payload[offset + 96:end].reshape(inputs, 96)
            actual = actual @ matrix + bias
            actual = np.maximum(actual, 0.01 * actual)
            offset = end
        logits = actual @ payload[offset + 1:offset + 97] + payload[offset]
        np.testing.assert_allclose(0.25 * np.logaddexp(0, logits), expected, rtol=1e-5, atol=2e-7)
        self.assertEqual(payload.size, offset + 97)

    def test_rejects_reordered_features(self):
        model = MultiScatterMLP(16, hidden=96, layers=4, activation="leaky_relu")
        with TemporaryDirectory() as directory:
            archive = Path(directory) / "reordered.npz"
            model.export_numpy(archive, SUN_FEATURE_NAMES[::-1])
            with self.assertRaisesRegex(ValueError, "feature order"):
                model_payload(archive, "sun")
