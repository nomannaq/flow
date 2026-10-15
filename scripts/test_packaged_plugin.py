#!/usr/bin/env python3
"""Offline regressions for distribution validation; never execute fixture bytes."""
import hashlib
import io
import json
from pathlib import Path
import stat
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from check_packaged_plugin import install
from check_codex_session import CheckFailed
from package_native_engine import dependency_inventory, inspect_binary
from native_platform import TARGETS, host_target, engine_name


class PackagingTest(unittest.TestCase):
    def package(self, archive, extra=None, digest=None, target=None, engine_path=None):
        target = target or host_target()
        relative = f"bin/{target}/{engine_name(target)}"
        engine = b"test binary bytes"
        inventory = b"{}"
        manifest = {"schemaVersion": 1, "protocolVersion": 1, "target": target,
                    "engineVersion": "0.1.0", "pluginVersion": "0.1.0", "enginePath": engine_path or relative,
                    "sha256": digest or hashlib.sha256(engine).hexdigest(), "inventorySha256": hashlib.sha256(inventory).hexdigest()}
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as bundle:
            bundle.writestr("META-INF/plugin.xml", "<idea-plugin/>")
        with zipfile.ZipFile(archive, "w") as bundle:
            bundle.writestr("Flow/distribution.json", json.dumps(manifest))
            bundle.writestr("Flow/dependency-inventory.json", inventory)
            bundle.writestr(f"Flow/{relative}", engine)
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

    def test_all_platform_paths_and_wrong_package_rejection(self):
        for target in TARGETS.values():
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.package(root / "plugin.zip", target=target)
                binary, _ = install(root / "plugin.zip", root / "installed", target)
                self.assertEqual(engine_name(target), binary.name)
                wrong = next(t for t in TARGETS.values() if t != target)
                with self.assertRaisesRegex(CheckFailed, "Unsupported package"):
                    install(root / "plugin.zip", root / "wrong", wrong)
                self.package(root / "bad.zip", target=target, engine_path="../../outside.exe")
                with self.assertRaisesRegex(CheckFailed, "executable selection"):
                    install(root / "bad.zip", root / "bad", target)

    def test_platform_aliases_and_32_bit_rejection(self):
        self.assertEqual("x86_64-pc-windows-msvc", host_target("Windows", "AMD64"))
        self.assertEqual("aarch64-apple-darwin", host_target("Darwin", "aarch64"))
        self.assertEqual("x86_64-unknown-linux-gnu", host_target("Linux", "x86_64"))
        with self.assertRaises(ValueError):
            host_target("Windows", "x86")

    def test_binary_architecture_and_external_dependencies_are_checked(self):
        for target in TARGETS.values():
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                binary = Path(temporary) / engine_name(target)
                header = bytearray(128)
                if target.endswith("apple-darwin"):
                    struct.pack_into("<II", header, 0, 0xFEEDFACF, 0x0100000C if target.startswith("aarch64") else 0x01000007)
                    linked, unsafe = "engine:\n /usr/lib/libSystem.B.dylib (version 1)", "engine:\n /opt/local/lib/external.dylib (version 1)"
                elif target.endswith("windows-msvc"):
                    header[:2] = b"MZ"
                    struct.pack_into("<I", header, 60, 64)
                    header[64:70] = b"PE\0\0\x64\x86"
                    linked, unsafe = "\n    KERNEL32.dll\n    BCRYPTPRIMITIVES.dll\n", "\n    VCRUNTIME140.dll\n"
                else:
                    header[:6] = b"\x7fELF\x02\x01"
                    struct.pack_into("<H", header, 18, 62)
                    linked, unsafe = "(NEEDED) Shared library: [libc.so.6] GLIBC_2.34", "(NEEDED) Shared library: [libexternal.so]"
                binary.write_bytes(header)
                with patch("package_native_engine.run", return_value=linked):
                    libraries, _ = inspect_binary(binary, target)
                    self.assertTrue(libraries)
                with patch("package_native_engine.run", return_value=unsafe):
                    with self.assertRaises(ValueError):
                        inspect_binary(binary, target)
                if target.endswith("linux-gnu"):
                    with patch("package_native_engine.run", return_value=linked.replace("2.34", "2.36")):
                        with self.assertRaisesRegex(ValueError, "baseline"):
                            inspect_binary(binary, target)
                binary.write_bytes(b"wrong executable architecture" * 5)
                with self.assertRaises(ValueError):
                    inspect_binary(binary, target)

    def test_unsafe_or_duplicate_paths_and_symlinks_are_refused(self):
        link = zipfile.ZipInfo("Flow/bin/link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        for name in ("../outside", "/absolute", "Flow/../outside", "Flow\\outside", "Flow/lib/flow.jar", "Flow/lib/FLOW.jar", "Flow/.. /outside", "Flow/bin/engine.", "Flow/NUL.txt", "Flow/COM¹", link):
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
                inventory = dependency_inventory(metadata, root / "output", host_target())
            self.assertEqual({"engine", "normal", "build"}, {p["name"] for p in inventory["packages"]})
            self.assertTrue(all((root / "output" / p["noticeFiles"][0]).is_file() for p in inventory["packages"]))


if __name__ == "__main__":
    unittest.main()
