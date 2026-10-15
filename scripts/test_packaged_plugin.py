#!/usr/bin/env python3
"""Offline regressions for distribution validation; never execute fixture bytes."""
import hashlib
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from check_packaged_plugin import install
from check_codex_session import CheckFailed
from package_native_engine import dependency_inventory


class PackagingTest(unittest.TestCase):
    def package(self, archive, extra=None, digest=None):
        engine = b"test binary bytes"
        inventory = b"{}"
        manifest = {"schemaVersion": 1, "protocolVersion": 1, "target": "aarch64-apple-darwin",
                    "engineVersion": "0.1.0", "pluginVersion": "0.1.0", "enginePath": "bin/aarch64-apple-darwin/flow-engine",
                    "sha256": digest or hashlib.sha256(engine).hexdigest(), "inventorySha256": hashlib.sha256(inventory).hexdigest()}
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as bundle:
            bundle.writestr("META-INF/plugin.xml", "<idea-plugin/>")
        with zipfile.ZipFile(archive, "w") as bundle:
            bundle.writestr("Flow/distribution.json", json.dumps(manifest))
            bundle.writestr("Flow/dependency-inventory.json", inventory)
            bundle.writestr("Flow/bin/aarch64-apple-darwin/flow-engine", engine)
            bundle.writestr("Flow/lib/flow.jar", jar.getvalue())
            if extra:
                bundle.writestr(*extra)

    def test_normal_install_checks_hashes_and_handles_spaces(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.package(root / "plugin.zip")
            binary, manifest = install(root / "plugin.zip", root / "Installed with spaces")
            self.assertTrue(binary.is_file())
            self.assertEqual("0.1.0", manifest["engineVersion"])

    def test_unsafe_or_duplicate_paths_and_symlinks_are_refused(self):
        link = zipfile.ZipInfo("Flow/bin/link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        for name in ("../outside", "/absolute", "Flow/../outside", "Flow\\outside", "Flow/lib/flow.jar", link):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.package(root / "plugin.zip", (name, "unsafe"))
                with self.assertRaises(CheckFailed):
                    install(root / "plugin.zip", root / "installed")
                self.assertFalse((root / "outside").exists())

    def test_tampered_engine_is_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.package(root / "plugin.zip", digest="0" * 64)
            with self.assertRaisesRegex(CheckFailed, "digest mismatch"):
                install(root / "plugin.zip", root / "installed")

    def test_inventory_traverses_build_and_normal_but_excludes_dev(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            metadata = {"packages": [], "workspace_members": ["engine"], "resolve": {"nodes": []}}
            for name in ("engine", "normal", "build", "dev"):
                directory = root / name
                directory.mkdir()
                (directory / "LICENSE").write_text("fixture notice")
                metadata["packages"].append({"id": name, "name": name, "version": "1", "manifest_path": str(directory / "Cargo.toml"), "source": None, "license": "MIT"})
                metadata["resolve"]["nodes"].append({"id": name, "deps": []})
            metadata["resolve"]["nodes"][0]["deps"] = [{"pkg": name, "dep_kinds": [{"kind": kind}]} for name, kind in (("normal", None), ("build", "build"), ("dev", "dev"))]
            with patch("package_native_engine.run", return_value="fixture rustc"):
                inventory = dependency_inventory(metadata, root / "output")
            self.assertEqual({"engine", "normal", "build"}, {p["name"] for p in inventory["packages"]})
            self.assertTrue(all((root / "output" / p["noticeFiles"][0]).is_file() for p in inventory["packages"]))


if __name__ == "__main__":
    unittest.main()
