import importlib.util
from pathlib import Path
import sys
import unittest

path = Path(__file__).resolve().parents[1] / 'analyze_gpu_zones.py'
spec = importlib.util.spec_from_file_location('zones', path)
zones = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = zones
spec.loader.exec_module(zones)

class ZoneTreeTest(unittest.TestCase):
    def test_identical_names_remain_distinct_by_ancestry(self):
        root = zones.Zone('frame', 0, 100)
        events = [zones.Zone('indirect', 10, 80, 1), zones.Zone('indirect', 11, 60, 2), zones.Zone('update', 72, 10, 3)]
        tree, ambiguity = zones.tree(root, events)
        data = {p: (inc, exc) for p, inc, exc in zones.visit(tree)}
        self.assertEqual(ambiguity, 0)
        self.assertEqual(data[('frame', 'indirect')], (80, 10))
        self.assertEqual(data[('frame', 'indirect', 'indirect')], (60, 60))
    def test_partial_overlap_is_rejected(self):
        with self.assertRaises(ValueError):
            zones.tree(zones.Zone('frame', 0, 100), [zones.Zone('a', 10, 40), zones.Zone('b', 20, 40)])
    def test_touching_siblings(self):
        root, ambiguity = zones.tree(zones.Zone('frame', 0, 100), [zones.Zone('a', 0, 50), zones.Zone('b', 50, 50)])
        self.assertEqual(len(root.children), 2)
        self.assertEqual(list(zones.visit(root))[0][2], 0)
    def test_equal_boundaries_are_reported(self):
        _, ambiguity = zones.tree(zones.Zone('frame', 0, 100), [zones.Zone('a', 0, 100)])
        self.assertEqual(ambiguity, 1)

if __name__ == '__main__':
    unittest.main()
