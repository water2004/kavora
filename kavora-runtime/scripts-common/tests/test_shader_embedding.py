# SPDX-License-Identifier: MIT
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
import shader_embedding as module
from shader_generation import publish_shader_generation, shader_generation_include
from shader_xxd import xxd_c_array


class ShaderEmbeddingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / 'rtx_shaders'
        self.output.mkdir()
        self.shared_header = self.root / 'shared.h'
        self.sources = [self.root / f'shared_{i}.cpp' for i in range(3)]
        self.entries = []

    def shader(self, name, words):
        binary = self.output / f'{name}.spv'
        header = self.output / f'{name}.h'
        binary.write_bytes(struct.pack('<' + 'I' * len(words), *words))
        xxd_c_array(binary.read_bytes(), header)
        self.entries.append((str(binary), str(header)))
        return binary, header

    def deduplicate(self, prefix=''):
        return module.deduplicate_shaders(self.entries, self.shared_header, self.sources, prefix)

    def test_duplicate_variants_keep_array_types_and_original_names(self):
        words = [0x07230203, 0, 0xffffffff, 0x80000000]
        for i in range(3):
            self.shader(f'ray_{i}', words)
        _, unique = self.shader('unique', [3, 7])
        original = unique.read_bytes()
        result = self.deduplicate()
        self.assertEqual({'bytes': 56, 'uniqueBytes': 24, 'savedBytes': 32, 'sharedGroups': 1}, result)
        self.assertEqual(original, unique.read_bytes())
        self.assertEqual(1, self.shared_header.read_text().count('extern const uint32_t'))
        for i in range(3):
            header = (self.output / f'ray_{i}.h').read_text()
            self.assertIn(f'inline constexpr auto& ray_{i} = ', header)
            self.assertIn(f'const size_t ray_{i}_sizeInBytes = 16;', header)
        sources = ''.join(source.read_text() for source in self.sources)
        for word in words:
            self.assertEqual(1, sources.count(f'0x{word:08x}'))

    def test_repeated_generation_does_not_touch_any_output(self):
        self.shader('first', [3, 7])
        self.shader('second', [3, 7])
        stamp = self.root / '_built_shaders.h'
        prefix = shader_generation_include(self.output, stamp)
        self.deduplicate(prefix)
        publish_shader_generation(self.output, stamp)
        outputs = [self.shared_header, *self.sources, stamp, *self.output.glob('*.h')]
        for output in outputs:
            os.utime(output, ns=(100000000000, 100000000000))
        self.deduplicate(prefix)
        publish_shader_generation(self.output, stamp)
        for output in outputs:
            self.assertEqual(100000000000, output.stat().st_mtime_ns, str(output))

    def test_group_changes_and_removal_restore_unique_arrays(self):
        first_binary, first_header = self.shader('first', [3, 7])
        second_binary, second_header = self.shader('second', [3, 7])
        third_binary, third_header = self.shader('third', [3, 8])
        self.deduplicate()
        second_binary.write_bytes(third_binary.read_bytes())
        result = self.deduplicate()
        self.assertEqual(8, result['savedBytes'])
        self.assertFalse(module._is_shared(first_header))
        self.assertIn('0x00000007', first_header.read_text())
        self.assertTrue(module._is_shared(second_header))
        self.assertTrue(module._is_shared(third_header))
        self.entries.remove((str(second_binary), str(second_header)))
        result = self.deduplicate()
        self.assertEqual(0, result['savedBytes'])
        self.assertFalse(module._is_shared(third_header))
        self.assertNotIn('extern const uint32_t', self.shared_header.read_text())
        self.assertTrue(all('blob_' not in source.read_text() for source in self.sources))

    def test_standalone_mode_restores_normal_headers(self):
        self.shader('first', [3, 7])
        self.shader('second', [3, 7])
        original = [Path(header).read_bytes() for _, header in self.entries]
        self.deduplicate()
        module.restore_inline_shaders(self.entries)
        self.assertEqual(original, [Path(header).read_bytes() for _, header in self.entries])

    def test_invalid_word_length_does_not_publish_partial_outputs(self):
        binary, header = self.shader('bad', [3, 7])
        original = header.read_bytes()
        for contents in (b'', b'abc'):
            binary.write_bytes(contents)
            with self.assertRaises(ValueError):
                self.deduplicate()
            self.assertEqual(original, header.read_bytes())
            self.assertFalse(self.shared_header.exists())

    def test_interrupted_publication_keeps_the_previous_file(self):
        self.shader('first', [3, 7])
        self.shader('second', [3, 7])
        self.shared_header.write_text('previous complete header')
        with patch.object(module.os, 'replace', side_effect=OSError('interrupted')):
            with self.assertRaises(OSError):
                self.deduplicate()
        self.assertEqual('previous complete header', self.shared_header.read_text())


if __name__ == '__main__':
    unittest.main()
