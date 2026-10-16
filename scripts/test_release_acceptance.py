"""Fail-closed regressions for B29 evidence, using no native processes."""
from pathlib import Path
import tempfile
import unittest

from check_codex_session import CheckFailed
from check_release_acceptance import HOST_CHECKS, REQUIRED_TESTS, capture_gate, host_gates, native_results


class ReleaseAcceptanceTest(unittest.TestCase):
    def test_performance_is_recomputed_from_raw_samples(self):
        baseline = {"applicationEnabled": True, "fixtures": [{"files": 1000, "status": "supported",
            "incremental500msP95Met": True, "tenFileReconciliationAndDurableCapture": {
                "p95Ms": 1, "samplesMs": [100] * 18 + [900, 1000]}}]}
        self.assertEqual("failed", capture_gate(baseline)["status"])
        baseline["fixtures"][0]["tenFileReconciliationAndDurableCapture"]["samplesMs"] = [100] * 20
        self.assertEqual("passed", capture_gate(baseline)["status"])
        for bad in ([], [False] * 20, [float("nan")] * 20, [-1] * 20, [float("inf")] * 20):
            baseline["fixtures"][0]["tenFileReconciliationAndDurableCapture"]["samplesMs"] = bad
            with self.assertRaises(CheckFailed):
                capture_gate(baseline)

    def test_desktop_evidence_must_match_artifact_source_target_and_ide(self):
        identity = {"target": "test", "archiveSha256": "a", "buildInputsSha256": "b", "ideBuild": "253"}
        self.assertTrue(all(g["status"] == "pending" for g in host_gates(None, identity).values()))
        observations = {**identity, "checks": {name: {"status": "passed", "evidence": "local host record"} for name in HOST_CHECKS}}
        self.assertTrue(all(g["status"] == "passed" for g in host_gates(observations, identity).values()))
        for key in identity:
            with self.subTest(key=key), self.assertRaises(CheckFailed):
                host_gates({**observations, key: "other"}, identity)
        observations["checks"]["cleanInstall"]["evidence"] = ""
        with self.assertRaises(CheckFailed):
            host_gates(observations, identity)

    def test_empty_partial_skipped_duplicate_or_failed_native_reports_cannot_pass(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(CheckFailed):
                native_results(root)
            cases = []
            for cls, methods in REQUIRED_TESTS.items():
                for method in methods:
                    cases.append(f'<testcase classname="{cls}" name="{method}"/>')
            path = root / "TEST-fixture.xml"
            path.write_text("<testsuite>" + "".join(cases) + "</testsuite>")
            self.assertEqual(len(cases), native_results(root)["tests"])
            for body in (cases[:-1], [*cases, cases[0]], [cases[0].replace('/>', '><skipped/></testcase>'), *cases[1:]],
                         [cases[0].replace('/>', '><failure/></testcase>'), *cases[1:]]):
                path.write_text("<testsuite>" + "".join(body) + "</testsuite>")
                with self.assertRaises(CheckFailed):
                    native_results(root)
            skipped = '<testcase classname="dev.flow.bridge.EngineExecutableTest" name="verifiesIdentityAndRestoresOnlyOwnerExecute"><skipped/></testcase>'
            path.write_text("<testsuite>" + "".join(cases) + skipped + "</testsuite>")
            self.assertEqual(1, native_results(root, "x86_64-pc-windows-msvc")["skipped"])
            with self.assertRaises(CheckFailed):
                native_results(root, "aarch64-apple-darwin")


if __name__ == "__main__":
    unittest.main()
