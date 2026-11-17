# SPDX-FileCopyrightText: Copyright (c) 2026 GBwater
# SPDX-License-Identifier: MIT
"""CPU contract for the extra standard-mode far-cloud checkpoint."""
import math
import unittest

FROXEL_CAPACITY = 6
CHECKPOINT_CAPACITY = 7


def checkpoint_max_distance(froxel_distance, clouds_enabled, low_active,
                            high_active, low_distance, high_distance):
    distance = froxel_distance
    if clouds_enabled:
        if low_active:
            distance = max(distance, low_distance)
        if high_active:
            distance = max(distance, high_distance)
    return distance


def cloud_checkpointing_enabled(froxel_distance, clouds_enabled, low_active,
                                 high_active, low_distance, high_distance):
    return checkpoint_max_distance(froxel_distance, clouds_enabled, low_active,
                                   high_active, low_distance, high_distance) > froxel_distance


def reserved_checkpoint_capacity(froxel_distance, clouds_enabled,
                                 configured_low_distance, configured_high_distance):
    configured_distance = max(configured_low_distance, configured_high_distance)
    return CHECKPOINT_CAPACITY if clouds_enabled and configured_distance > froxel_distance else FROXEL_CAPACITY


def checkpoint_distance(index, froxel_distance, cloud_distance, exponent):
    if index == CHECKPOINT_CAPACITY:
        return cloud_distance
    return (index / FROXEL_CAPACITY) ** exponent * froxel_distance


def checkpoint_for_distance(distance, froxel_distance, cloud_distance, exponent):
    if distance >= cloud_distance:
        return CHECKPOINT_CAPACITY
    w = min(1.0, max(0.0, distance / froxel_distance)) ** (1.0 / exponent)
    return min(FROXEL_CAPACITY, math.floor(w * FROXEL_CAPACITY))


class AtmosphereCheckpointCoverageTests(unittest.TestCase):
    def test_active_cloud_range_adds_far_checkpoint(self):
        maximum = checkpoint_max_distance(24_000, True, True, True, 96_000, 80_000)
        self.assertEqual(maximum, 96_000)
        self.assertTrue(cloud_checkpointing_enabled(24_000, True, True, True, 96_000, 80_000))
        self.assertEqual(checkpoint_distance(6, 24_000, maximum, 4), 24_000)
        self.assertEqual(checkpoint_distance(7, 24_000, maximum, 4), 96_000)
        self.assertEqual(checkpoint_for_distance(96_000, 24_000, maximum, 4), 7)

    def test_empty_clouds_keep_froxel_range(self):
        self.assertEqual(
            checkpoint_max_distance(24_000, True, False, False, 96_000, 80_000),
            24_000,
        )
        self.assertEqual(
            checkpoint_max_distance(24_000, False, True, True, 96_000, 80_000),
            24_000,
        )
        self.assertFalse(cloud_checkpointing_enabled(24_000, False, True, True, 96_000, 80_000))
        self.assertFalse(cloud_checkpointing_enabled(0, True, False, False, 96_000, 80_000))

    def test_cloud_range_inside_froxel_keeps_legacy_mapping(self):
        self.assertFalse(cloud_checkpointing_enabled(24_000, True, True, True, 16_000, 20_000))
        self.assertFalse(cloud_checkpointing_enabled(24_000, False, True, True, 96_000, 80_000))

    def test_reserved_capacity_is_stable_across_weather_transitions(self):
        capacity = reserved_checkpoint_capacity(24_000, True, 96_000, 80_000)
        self.assertEqual(capacity, CHECKPOINT_CAPACITY)
        for low_active, high_active in ((False, False), (True, False), (False, True), (True, True)):
            active = cloud_checkpointing_enabled(
                24_000, True, low_active, high_active, 96_000, 80_000
            )
            self.assertEqual(active, low_active or high_active)
            self.assertEqual(capacity, reserved_checkpoint_capacity(24_000, True, 96_000, 80_000))

    def test_checkpoint_selection_never_passes_endpoint(self):
        for index in range(1_001):
            distance = 96_000 * index / 1_000
            checkpoint = checkpoint_for_distance(distance, 24_000, 96_000, 4)
            self.assertLessEqual(checkpoint_distance(checkpoint, 24_000, 96_000, 4),
                                 distance + 1e-7)

    def test_full_cloud_ray_has_no_dense_cloud_tail(self):
        checkpoint = checkpoint_for_distance(96_000, 24_000, 96_000, 4)
        begin = checkpoint_distance(checkpoint, 24_000, 96_000, 4)
        self.assertEqual(checkpoint, CHECKPOINT_CAPACITY)
        self.assertEqual(begin, 96_000)
        self.assertEqual(96_000 - begin, 0)

    def test_grazing_ray_near_checkpoint_stays_before_surface(self):
        froxel_distance = 24_000
        exponent = 4
        for direction_depth in (0.05, 0.1, 0.25, 0.5, 1.0):
            for ray_distance in (1_000, 8_000, 20_000, 40_000, 80_000, 95_000):
                projected_depth = min(direction_depth * ray_distance, froxel_distance)
                w = (projected_depth / froxel_distance) ** (1.0 / exponent)
                checkpoint = min(FROXEL_CAPACITY, math.floor(w * FROXEL_CAPACITY))
                checkpoint_ray_distance = checkpoint_distance(
                    checkpoint, froxel_distance, 96_000, exponent
                ) / direction_depth
                if checkpoint > 0 and checkpoint_ray_distance > ray_distance:
                    checkpoint -= 1
                    checkpoint_ray_distance = checkpoint_distance(
                        checkpoint, froxel_distance, 96_000, exponent
                    ) / direction_depth
                self.assertLessEqual(checkpoint_ray_distance, ray_distance + 1e-7)

    def test_far_checkpoint_can_precede_projected_near_depths(self):
        froxel_distance = 256
        cloud_distance = 300
        direction_depth = 0.5
        near = [checkpoint_distance(i, froxel_distance, cloud_distance, 4) / direction_depth
                for i in range(1, FROXEL_CAPACITY + 1)]
        self.assertLess(near[4], cloud_distance)
        self.assertGreater(near[5], cloud_distance)
        requested = list(enumerate(near, start=1)) + [(CHECKPOINT_CAPACITY, cloud_distance)]
        ordered = sorted(requested, key=lambda checkpoint: checkpoint[1])
        self.assertEqual(ordered[-2:], [(CHECKPOINT_CAPACITY, 300), (FROXEL_CAPACITY, 512)])

    def test_near_checkpoint_positions_are_unchanged(self):
        old = [(i / FROXEL_CAPACITY) ** 4 * 24_000 for i in range(FROXEL_CAPACITY + 1)]
        new = [checkpoint_distance(i, 24_000, 96_000, 4)
               for i in range(FROXEL_CAPACITY + 1)]
        self.assertEqual(new, old)


if __name__ == "__main__":
    unittest.main(verbosity=2)
