# SPDX-License-Identifier: MIT
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("shader_xxd", Path(__file__).parents[1] / "shader_xxd.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ShaderHeaderTest(unittest.TestCase):
    def test_complete_header_replaces_previous_output(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "ray.h"
            output.write_text("old output")
            module.xxd_c_array(b"\x03\x02\x23\x07", output)
            self.assertIn("const uint32_t ray[]", output.read_text())
            self.assertIn("0x07230203", output.read_text())
            self.assertTrue(output.read_text().endswith("ray_sizeInBytes = 4;\n"))
            self.assertFalse(Path(str(output) + ".tmp").exists())

    def test_interrupted_publication_preserves_previous_header(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "ray.h"
            output.write_text("complete previous header")
            with patch.object(module.os, "replace", side_effect=OSError("interrupted")):
                with self.assertRaises(OSError):
                    module.xxd_c_array(b"\x03\x02\x23\x07", output)
            self.assertEqual("complete previous header", output.read_text())


if __name__ == "__main__":
    unittest.main()
