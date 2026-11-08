# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

import json
import re
import unittest

from kavora_tools.benchmark import util

REPO_ROOT = util.PROJECT_DIR


def gradle_mod_version() -> str:
    for line in (REPO_ROOT.parent / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("mod_version="):
            return line.split("=", 1)[1].strip()
    raise AssertionError("gradle.properties has no mod_version")


def gradle_dependency_version(group: str, artifact: str) -> str:
    text = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
    match = re.search(rf'"{re.escape(group)}:{re.escape(artifact)}:([^\"]+)"', text)
    if not match:
        raise AssertionError(f"build.gradle has no {group}:{artifact} dependency")
    return match.group(1)


class VersionConsistencyTests(unittest.TestCase):
    def test_runtime_dependency_versions_match_gradle(self):
        defaults = json.loads((REPO_ROOT / "packaging/launcher-defaults.json").read_text())
        for key, group, artifact in [
            ("MC_VERSION", "com.mojang", "minecraft"),
            ("FABRIC_LOADER_VER", "net.fabricmc", "fabric-loader"),
            ("FABRIC_API_VERSION", "net.fabricmc.fabric-api", "fabric-api"),
        ]:
            self.assertEqual(gradle_dependency_version(group, artifact), defaults[key])

    def test_runtime_code_does_not_hardcode_mod_version(self):
        benchmark_controller = (
            REPO_ROOT
            / "src"
            / "main"
            / "java"
            / "com"
            / "nvidia"
            / "kavora"
            / "minecraft"
            / "benchmark"
            / "BenchmarkController.java"
        ).read_text(encoding="utf-8")
        mod_entrypoint = (
            REPO_ROOT / "src" / "main" / "java" / "com" / "nvidia" / "kavora" / "minecraft" / "KavoraMod.java"
        ).read_text(encoding="utf-8")
        self.assertNotRegex(benchmark_controller, r'modVersion",\s*"\d+\.\d+\.\d+"')
        self.assertNotRegex(mod_entrypoint, r"Renderer v\d+\.\d+\.\d+")

    def test_fabric_metadata_matches_repository_contract(self):
        metadata = json.loads(
            (REPO_ROOT / "src" / "main" / "resources" / "fabric.mod.json").read_text(encoding="utf-8")
        )
        minecraft_version = gradle_dependency_version("com.mojang", "minecraft")
        fabric_loader_version = gradle_dependency_version("net.fabricmc", "fabric-loader")
        fabric_api_version = gradle_dependency_version("net.fabricmc.fabric-api", "fabric-api")

        self.assertIn("Apache-2.0", metadata["license"])
        self.assertIn("LGPL-3.0-only", metadata["license"])
        self.assertEqual(minecraft_version, metadata["depends"]["minecraft"])
        self.assertEqual(f">={fabric_loader_version}", metadata["depends"]["fabricloader"])
        self.assertEqual(f">={fabric_api_version}", metadata["depends"]["fabric-api"])
        self.assertNotIn("fabric-api", metadata.get("suggests", {}))

        icon = metadata.get("icon")
        if icon:
            self.assertTrue((REPO_ROOT / "src" / "main" / "resources" / icon).is_file())

        sources = metadata.get("contact", {}).get("sources", "")
        self.assertNotIn("kavora-sdk", sources)

    def test_readme_license_matches_repository_license(self):
        license_text = (REPO_ROOT / "LICENSE").read_text(encoding="utf-8")
        readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")

        self.assertIn("Apache License", license_text)
        self.assertIn("Apache-2.0", readme)
        self.assertNotRegex(readme, r"(?m)^MIT\s+[\u2014-]\s+See LICENSE file$")


if __name__ == "__main__":
    unittest.main()
