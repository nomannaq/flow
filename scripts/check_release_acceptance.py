#!/usr/bin/env python3
"""B26/B29 local acceptance. Builds and tests the host ZIP without real model calls.

Automated success and release acceptance are separate results. Desktop observations
must name this exact package; --require-release fails while any gate is pending.
No publishing, signing, Git mutations, or host configuration changes are performed.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

from check_codex_session import CheckFailed, require
from check_packaged_plugin import install, smoke
from native_platform import host_target

ROOT = Path(__file__).resolve().parents[1]
HOST_CHECKS = (
    "cleanInstall", "ideRestartWithHistory", "upgradeWithHistory",
    "renderedReadOnlyDiffAndMerge", "keyboardAndScreenReader",
    "projectCloseAndPluginUnload", "realPinnedAdapter",
)
# These methods exercise real IDE documents, immutable native merge inputs,
# disposal, and unknown engine outcomes. Empty or filtered reports cannot pass.
REQUIRED_TESTS = {
    "dev.flow.ui.SafeApplicationTest": {
        "testNativeMergeRenewedReviewApplyAndRollbackPreserveDeveloperWork",
        "testUnsavedTypingExternalWritesAndClosedBinaryPreconditions",
    },
    "dev.flow.spikes.NativeCompatibilityTest": {
        "testHistoricalInputsSurviveLaterComparison", "testResolvedMergeReturnsExactOutput",
        "testCancelledMergeReturnsNoAcceptedContent",
    },
    "dev.flow.bridge.EngineBridgeTest": {
        "nativeControllerRunsAgentAndBindsPermissionDecision",
        "agentCommandsAndPermissionsUseTypedSessionIdentity",
        "retainedConversationRequiresExplicitStoppedWriterConfirmation",
        "boundedFramesAndReplayRejectCorruption",
        "realEngineTwoProjectsHealthRestartAndScopeDisposal",
        "replayFloodAndInvalidCursorRefreshAuthoritativeSnapshot",
        "fakeInjectionFloodStaleReplyTimeoutAndCleanup",
        "brokenEngineStopsAfterBoundedRetries",
        "cancelledServiceKeepsTransportAliveForGracefulShutdown",
    },
    "dev.flow.ui.FirstVerticalSliceTest": {
        "testTwoEditsSurviveRestartAndLiveDeletion",
        "testCancellationAfterFirstWriteKeepsPartialHistory",
        "testFailedSecondWriteStillRecordsChangedBytes",
    },
}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def build_inputs():
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT,
    ).decode().split("\0")
    files = {}
    for name in sorted(set(names)):
        if not name or name.startswith("docs/") or name.endswith(".md"):
            continue
        path = ROOT / name
        files[name] = digest(path) if path.is_file() else "deleted"
    return {"sha256": hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(), "files": files}


def native_results(directory, target=None):
    cases = []
    skipped = []
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        require(suite.tag == "testsuite", "Unexpected native test report")
        for case in suite.findall("testcase"):
            name = (case.get("classname"), case.get("name"))
            require(case.find("failure") is None and case.find("error") is None,
                    "Native acceptance contains failed tests")
            if case.find("skipped") is not None:
                require(target == "x86_64-pc-windows-msvc" and name == (
                    "dev.flow.bridge.EngineExecutableTest", "verifiesIdentityAndRestoresOnlyOwnerExecute"),
                    "Unexpected skipped native acceptance scenario")
                skipped.append(name)
            else:
                cases.append(name)
    require(len(cases + skipped) == len(set(cases + skipped)), "Duplicate native acceptance cases")
    for name, methods in REQUIRED_TESTS.items():
        actual = {method for cls, method in cases if cls == name}
        require(actual and methods <= actual, "Required native acceptance scenario missing: " + name)
    return {"passed": True, "tests": len(cases), "skipped": len(skipped), "expectedPlatformSkips": skipped, "classes": sorted({cls for cls, _ in cases})}


def capture_gate(baseline):
    fixtures = baseline.get("fixtures", [])
    measured = [f for f in fixtures if f.get("files") == 1000 and f.get("status") == "supported"]
    require(len(measured) == 1 and baseline.get("applicationEnabled") is True, "Missing B19/B27 performance fixture")
    stats = measured[0]["tenFileReconciliationAndDurableCapture"]
    samples = stats["samplesMs"]
    require(len(samples) >= 20 and all(type(n) in (int, float) and 0 <= n < float("inf") for n in samples),
            "Invalid capture performance samples")
    # Recompute the benchmark's nearest-rank p95; never trust a supplied pass flag.
    ordered = sorted(samples)
    p95 = ordered[(len(ordered) * 95 + 99) // 100 - 1]
    return {"status": "passed" if p95 < 500 else "failed", "p95Ms": p95, "targetMs": 500, "samples": len(samples)}


def host_gates(observations, identity):
    if observations is None:
        return {name: {"status": "pending"} for name in HOST_CHECKS}
    require(all(observations.get(key) == value for key, value in identity.items()),
            "Desktop observations do not match this artifact and source")
    gates = {}
    for name in HOST_CHECKS:
        result = observations.get("checks", {}).get(name, {})
        status = result.get("status", "pending")
        require(status in ("passed", "failed", "pending"), "Invalid desktop observation status")
        note = result.get("evidence", "")
        require(status != "passed" or (isinstance(note, str) and 0 < len(note.strip()) <= 2048),
                "Passed desktop checks require bounded evidence references")
        gates[name] = {"status": status, "evidence": note}
    return gates


def main(args):
    target = host_target()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    logs = Path(tempfile.mkdtemp(prefix="acceptance-", dir=args.output.parent))
    before = build_inputs()
    report = {"schemaVersion": 1, "target": target, "startedAt": datetime.now(timezone.utc).isoformat(),
              "baseRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "buildInputs": before, "automatedPassed": False, "releaseAccepted": False, "steps": [],
              "hostChecks": host_gates(None, {})}

    def run(label, command, cwd=ROOT):
        log = logs / (label + ".log")
        print(label, flush=True)
        with log.open("wb") as output:
            result = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT, timeout=3600)
        report["steps"].append({"name": label, "exitCode": result.returncode, "log": str(log)})
        require(result.returncode == 0, "Acceptance step failed: " + label)
        require("private-fixture-token" not in log.read_text(errors="replace"), "Credential canary leaked into acceptance diagnostics")

    try:
        cargo = ["--locked"] + (["--offline"] if args.offline else [])
        gradle = [str(ROOT / "integrations/jetbrains" / ("gradlew.bat" if os.name == "nt" else "gradlew"))]
        options = ["--dependency-verification=strict", "--console=plain"] + (["--offline"] if args.offline else [])
        run("python", [sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"])
        run("rust-format", ["cargo", "fmt", "--all", "--check"])
        run("rust-clippy", ["cargo", "clippy", "--workspace", "--all-targets", *cargo, "--", "-D", "warnings"])
        run("rust-tests", ["cargo", "test", "--workspace", "--all-targets", *cargo])
        rust_log = (logs / "rust-tests.log").read_text()
        summaries = re.findall(r"test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored;", rust_log)
        require(summaries and all(int(failed) == int(ignored) == 0 for _, failed, ignored in summaries),
                "Rust acceptance contains failed or ignored cases")
        for name in ("b26_seeded_manifests_reconstruct_and_mutations_replay_exactly",
                     "b26_bounded_decompression_rejects_bombs_truncation_and_seeded_corruption",
                     "b26_sqlite_full_during_apply_keeps_last_durable_journal_and_checkpoint",
                     "b26_seeded_fragmented_frames_recover_without_echoing_private_input",
                     "b26_malformed_acp_corpus_and_seeded_updates_never_reach_projection",
                     "b26_seeded_apply_faults_preserve_index_unrelated_bytes_and_recovery_preimages",
                     "b26_portable_path_corpus_and_seeded_component_mutations_are_rejected"):
            require(name + " ... ok" in rust_log, "Required B26 scenario missing: " + name)
        report["rust"] = {"testsIncludingExamples": sum(int(passed) for passed, _, _ in summaries), "ignored": 0}
        ide = ROOT / "integrations/jetbrains"
        run("build-package", [*gradle, "buildPlugin", *options], ide)
        # Pick the exact host/version archive from the version declared by Gradle.
        version = next(line.split("=", 1)[1].strip() for line in (ide / "gradle.properties").read_text().splitlines() if line.startswith("version="))
        archive = ide / "build/distributions" / f"flow-jetbrains-{version}-{target}.zip"
        require(archive.is_file(), "Host package missing")
        report["artifact"] = {"archive": archive.name, "sha256": digest(archive)}
        with tempfile.TemporaryDirectory(prefix="Flow release acceptance ") as temporary:
            binary, manifest = install(archive, Path(temporary) / "Installed plugin with spaces")
            report["artifact"]["engineSha256"] = manifest["sha256"]
            report["packageSmoke"] = smoke(archive, session_fixture=True)
            # Every native test uses the verified, installed release binary.
            run("packaged-native", [*gradle, "check", "verifyPluginProjectConfiguration", "verifyPlugin",
                f"-PflowAcceptanceEngine={binary}", "-PflowPerformance=true", *options], ide)
            report["native"] = native_results(ide / "build/test-results/test", target)
            require(digest(archive) == report["artifact"]["sha256"], "Plugin archive changed during native acceptance")
            verdicts = list((ide / "build/reports/pluginVerifier").glob("*/plugins/*/*/verification-verdict.txt"))
            require(len(verdicts) == 1 and verdicts[0].read_text().strip().startswith("Compatible."), "Plugin Verifier compatibility not established")
            report["pluginVerifier"] = {"verdict": verdicts[0].read_text().strip(), "ideBuild": verdicts[0].parents[3].name}
        baseline = logs / "runtime-baseline.json"
        run("runtime-performance", ["cargo", "run", "-p", "runtime", "--example", "first_slice_bench", "--release", *cargo, "--", str(baseline), "--apply"])
        report["performance"] = capture_gate(json.loads(baseline.read_text()))
        require(build_inputs() == before, "Build inputs changed during acceptance; rerun against a stable tree")
        identity = {"target": target, "archiveSha256": report["artifact"]["sha256"],
                    "buildInputsSha256": before["sha256"], "ideBuild": report["pluginVerifier"]["ideBuild"]}
        report["observationIdentity"] = identity
        observations = json.loads(args.observations.read_text()) if args.observations else None
        report["hostChecks"] = host_gates(observations, identity)
        report["automatedPassed"] = True
        report["releaseAccepted"] = report["performance"]["status"] == "passed" and all(g["status"] == "passed" for g in report["hostChecks"].values())
    except (CheckFailed, OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError, ET.ParseError):
        # Raw process/adapter errors and command lines may contain private data.
        report["failure"] = "Acceptance incomplete; inspect the recorded local step logs"
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    print("Evidence:", args.output, flush=True)
    print("Automated checks:", report["automatedPassed"], "Release accepted:", report["releaseAccepted"], flush=True)
    return 0 if (report["releaseAccepted"] if args.require_release else report["automatedPassed"]) else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--observations", type=Path, help="Desktop checks tied to observationIdentity from a prior report")
    parser.add_argument("--require-release", action="store_true", help="Fail until performance and desktop gates pass")
    sys.exit(main(parser.parse_args()))
