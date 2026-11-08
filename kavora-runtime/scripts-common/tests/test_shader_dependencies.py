# SPDX-License-Identifier: MIT
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("shader_dependencies", Path(__file__).parents[1] / "shader_dependencies.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ShaderDependencyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.manifest = self.root / "inputs.json"
        self.source = self.root / "source.slangh"
        self.source.write_bytes(b"old source")
        self.compiler = self.root / "slang.dll"
        self.compiler.write_bytes(b"compiler")
        self.commands = ["slangc -DMODE=1"]
        self.output = self.root / "shader.spv"

    def cache(self):
        return module.ShaderDependencyCache(self.manifest, [self.compiler])

    def publish(self):
        cache = self.cache()
        cache.record_success(self.output, [self.source], self.commands)
        cache.save()

    def stale(self, commands=None):
        return self.cache().needs_build(self.output, [self.source], self.commands if commands is None else commands)

    def test_first_build_and_unchanged_sources(self):
        self.assertTrue(self.stale())
        self.publish()
        self.assertFalse(self.stale())
        previous = self.manifest.stat().st_mtime_ns
        self.cache().save()
        self.assertEqual(previous, self.manifest.stat().st_mtime_ns)

    def test_backdated_restore_invalidates_newer_shader(self):
        self.publish()
        self.source.write_bytes(b"restored older source")
        os.utime(self.source, ns=(100000000000, 100000000000))
        self.assertTrue(self.stale())

    def test_content_change_with_identical_size_and_timestamp(self):
        self.publish()
        previous = self.source.stat()
        self.source.write_bytes(b"new source")
        os.utime(self.source, ns=(previous.st_atime_ns, previous.st_mtime_ns))
        self.assertTrue(self.stale())

    def test_compiler_and_variant_changes_are_dependencies(self):
        self.publish()
        self.assertTrue(self.stale(["slangc -DMODE=2"]))
        self.compiler.write_bytes(b"new compiler")
        self.assertTrue(self.stale())

    def test_failed_build_does_not_publish_new_fingerprint(self):
        self.publish()
        self.source.write_bytes(b"failing source")
        cache = self.cache()
        self.assertTrue(cache.needs_build(self.output, [self.source], self.commands))
        cache.save()
        self.assertTrue(self.stale())

    def test_missing_dependency_and_invalid_manifest_force_build(self):
        self.publish()
        self.source.unlink()
        self.assertTrue(self.stale())
        self.manifest.write_text("interrupted", encoding="utf-8")
        self.assertTrue(self.stale())


if __name__ == "__main__":
    unittest.main()
