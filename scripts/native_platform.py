"""Supported native engine packages; builds and smoke tests run on their target OS."""
import platform

TARGETS = {
    ("Darwin", "arm64"): "aarch64-apple-darwin",
    ("Darwin", "x86_64"): "x86_64-apple-darwin",
    ("Linux", "x86_64"): "x86_64-unknown-linux-gnu",
    ("Windows", "x86_64"): "x86_64-pc-windows-msvc",
}


def host_target(system=None, machine=None):
    system = system or platform.system()
    machine = (machine or platform.machine()).lower()
    machine = {"aarch64": "arm64", "amd64": "x86_64", "x64": "x86_64"}.get(machine, machine)
    try:
        return TARGETS[system, machine]
    except KeyError:
        raise ValueError("Supported packages: macOS arm64/x86-64, Linux x86-64, Windows x86-64") from None


def engine_name(target):
    if target not in TARGETS.values():
        raise ValueError("Unsupported engine target")
    return "flow-engine.exe" if target.endswith("windows-msvc") else "flow-engine"
