# SPDX-License-Identifier: MIT
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("shader_generation", Path(__file__).parents[1] / "shader_generation.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ShaderGenerationTest(unittest.TestCase):
    def test_bytecode_changes_invalidate_the_declared_header_only_once(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            output = root / "rtx_shaders"
            output.mkdir()
            header = output / "ray.h"
            header.write_bytes(b"const unsigned ray[] = {1, 2};\n")
            stamp = root / "_built_shaders.h"
            first = module.publish_shader_generation(output, stamp)
            self.assertTrue(header.read_text().startswith('#include "../_built_shaders.h"'))
            os.utime(stamp, ns=(100000000000, 100000000000))
            previous_time = stamp.stat().st_mtime_ns
            self.assertEqual(first, module.publish_shader_generation(output, stamp))
            self.assertEqual(previous_time, stamp.stat().st_mtime_ns)
            self.assertEqual(header.read_text().count('#include "../_built_shaders.h"'), 1)
            header.write_bytes(b"const unsigned ray[] = {1, 3};\n")
            self.assertNotEqual(first, module.publish_shader_generation(output, stamp))
            self.assertNotEqual(previous_time, stamp.stat().st_mtime_ns)

    def test_removed_shaders_also_invalidate_the_fingerprint(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            header = root / "ray.h"
            header.write_bytes(b"const unsigned ray[] = {1};\n")
            stamp = root / "_built_shaders.h"
            first = module.publish_shader_generation(root, stamp)
            header.unlink()
            self.assertNotEqual(first, module.publish_shader_generation(root, stamp))


if __name__ == "__main__":
    unittest.main()
