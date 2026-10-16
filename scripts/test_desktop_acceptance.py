"""Offline checks for isolation and protected inputs of the real IDE harness."""
import json
import os
from pathlib import Path
import tempfile
import unittest

from check_codex_session import CheckFailed
from check_desktop_acceptance import cached_ide, ide_metadata, java_command, project_bytes, upgrade_versions
from native_platform import host_target


class DesktopAcceptanceTest(unittest.TestCase):
    def test_product_pin_and_profile_arguments_with_spaces(self):
        with tempfile.TemporaryDirectory(prefix="Desktop fixture ") as temporary:
            root = Path(temporary)
            system = {"darwin": "macOS", "win32": "Windows", "linux": "Linux"}[__import__("sys").platform]
            arch = "aarch64" if host_target().startswith("aarch64") else "amd64"
            launch = {"os": system, "arch": arch, "additionalJvmArguments": ["-Djava.system.class.loader=com.intellij.util.lang.PathClassLoader"], "bootClassPathJarNames": ["app.jar"]}
            metadata = {"productCode": "IU", "buildNumber": "253.33813.55", "launch": [launch]}
            (root / "product-info.json").write_text(json.dumps(metadata))
            home, _, selected = ide_metadata(root)
            self.assertEqual(root, home)
            command = java_command(root / "jdk", home, selected, root / "scratch", "close", root / "project", root / "data",
                                   {"filename": "probe.txt", "sessionId": "owned", "versions": ["one\n", "two\n"], "installedEngine": str(root / "verified engine"), "pluginVersion": "0.1.1"})
            self.assertIn(f"-Didea.config.path={root / 'scratch/config'}", command)
            self.assertIn(f"-Didea.plugins.path={root / 'scratch/plugins with spaces'}", command)
            self.assertIn("-Dflow.acceptance.version1=one\n", command)
            self.assertIn("-Djava.awt.headless=false", command)
            self.assertFalse(any(v.startswith("-Dflow.engine.path=") or v.startswith("-Didea.is.unit.test=") for v in command))
            self.assertEqual("flow-desktop-acceptance", command[-1])
            metadata["buildNumber"] = "262.1"
            (root / "product-info.json").write_text(json.dumps(metadata))
            with self.assertRaises(CheckFailed):
                ide_metadata(root)
            with self.assertRaises(CheckFailed):
                cached_ide(root)
            cached = root / "caches/9.0/transforms/hash/transformed/pinned-ide"
            cached.mkdir(parents=True)
            metadata["buildNumber"] = "253.33813.55"
            (cached / "product-info.json").write_text(json.dumps(metadata))
            self.assertEqual(cached, cached_ide(root))

    def test_developer_files_and_index_are_protected_but_ide_metadata_is_expected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / ".git").mkdir()
            (root / ".git/index").write_bytes(b"original index")
            (root / "file.txt").write_bytes(b"developer")
            before = project_bytes(root)
            (root / ".idea").mkdir()
            (root / ".idea/workspace.xml").write_text("IDE metadata")
            self.assertEqual(before, project_bytes(root))
            (root / "file.txt").write_bytes(b"new edit")
            self.assertNotEqual(before, project_bytes(root))
            (root / "file.txt").write_bytes(b"developer")
            (root / ".git/index").write_bytes(b"new index")
            self.assertNotEqual(before, project_bytes(root))

    @unittest.skipIf(os.name == "nt", "POSIX symlink fixture")
    def test_file_links_are_hashed_as_links_without_reading_outside_the_project(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / ".git").mkdir()
            (root / ".git/index").write_bytes(b"index")
            (root / "dangling").symlink_to(root / "unreadable-outside")
            self.assertEqual({"symlink": str(root / "unreadable-outside")}, project_bytes(root)["dangling"])

    def test_upgrade_requires_an_actually_older_release(self):
        upgrade_versions("0.1.9", "0.1.10")
        for old, new in [("0.1.0", "0.1.0"), ("0.2.0", "0.1.1"), ("0.1.0-dev", "0.1.1"), ("01.1.0", "1.1.1")]:
            with self.subTest(old=old, new=new), self.assertRaises(CheckFailed):
                upgrade_versions(old, new)


if __name__ == "__main__":
    unittest.main()
