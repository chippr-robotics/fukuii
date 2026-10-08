#!/usr/bin/env python3
"""Self-test for synctest_report.py: builds fixture JUnit reports and checks each rule.

    python3 scripts/snap-split/tests/test_synctest_report.py
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "synctest_report.py"
ALLOW_FILE = HERE.parent / "synctest-allowlist.txt"
T68 = "leave SNAP's resume alone once SNAP has taken over the best block of an upgraded node"
T68_NAME = "SyncController should " + T68
PKG = "com.chipprbots.ethereum.blockchain.sync."
SUITES = ["SyncControllerSpec", "PivotHeaderBootstrapSpec", "SnapServingActorSpec"]


def suite_xml(suite, cases, suite_failures=None, suite_errors=0):
    """cases: list of (name, outcome) with outcome pass|fail|error|skip."""
    nfail = sum(1 for _, o in cases if o == "fail") if suite_failures is None else suite_failures
    body = ""
    for name, o in cases:
        name = name.replace("&", "&amp;").replace("'", "&apos;")
        inner = {"pass": "", "fail": '<failure message="x"/>', "error": '<error message="x"/>', "skip": "<skipped/>"}[o]
        body += f'<testcase classname="{PKG}{suite}" name="{name}" time="0.1">{inner}</testcase>'
    return (
        f'<?xml version="1.0"?><testsuite name="{PKG}{suite}" tests="{len(cases)}" '
        f'errors="{suite_errors}" failures="{nfail}" skipped="0">{body}</testsuite>'
    )


def run(reports, allow=None, expect=SUITES, sbt_exit=None):
    with tempfile.TemporaryDirectory() as d:
        d = Path(d)
        rdir = d / "r"
        rdir.mkdir()
        for suite, xml in reports.items():
            (rdir / f"TEST-{PKG}{suite}.xml").write_text(xml, encoding="utf-8")
        allow_path = d / "allow.txt"
        allow_path.write_text(allow if allow is not None else f"SyncControllerSpec :: {T68_NAME}\n", encoding="utf-8")
        cmd = [sys.executable, str(SCRIPT), "--reports", str(rdir), "--allowlist", str(allow_path)]
        for s in expect:
            cmd += ["--expect-suite", s]
        if sbt_exit is not None:
            cmd += ["--sbt-exit", str(sbt_exit)]
        p = subprocess.run(cmd, capture_output=True, text=True)
        return p.returncode, p.stdout


def good(t68="fail", sync_extra=()):
    return {
        "SyncControllerSpec": suite_xml("SyncControllerSpec", [("SyncController should a", "pass"), (T68_NAME, t68), *sync_extra]),
        "PivotHeaderBootstrapSpec": suite_xml("PivotHeaderBootstrapSpec", [("p should b", "pass"), ("p should c", "skip")]),
        "SnapServingActorSpec": suite_xml("SnapServingActorSpec", [("s should d", "pass")]),
    }


class SyncTestReport(unittest.TestCase):
    def test_only_t68_failing_passes(self):
        rc, out = run(good(), sbt_exit=1)
        self.assertEqual(rc, 0, out)
        self.assertIn("| SyncControllerSpec | 2 | 1 | 0 | 1 |", out)
        self.assertIn("| PivotHeaderBootstrapSpec | 2 | 1 | 1 | 0 |", out)

    def test_other_failure_fails(self):
        rc, out = run(good(sync_extra=[("SyncController should z", "fail")]))
        self.assertEqual(rc, 1)
        self.assertIn("unexpected failure: SyncControllerSpec :: SyncController should z", out)

    def test_error_counts_as_failure(self):
        r = good()
        r["SnapServingActorSpec"] = suite_xml("SnapServingActorSpec", [("s should d", "error")], suite_failures=0, suite_errors=1)
        rc, out = run(r)
        self.assertEqual(rc, 1)
        self.assertIn("unexpected failure: SnapServingActorSpec :: s should d", out)

    def test_t68_passing_fails_as_stale(self):
        rc, out = run(good(t68="pass"))
        self.assertEqual(rc, 1)
        self.assertIn("allow-list is stale", out)

    def test_t68_skipped_fails(self):
        rc, out = run(good(t68="skip"))
        self.assertEqual(rc, 1)
        self.assertIn("skipped/ignored", out)

    def test_t68_missing_fails(self):
        r = good()
        r["SyncControllerSpec"] = suite_xml("SyncControllerSpec", [("SyncController should a", "pass")])
        rc, out = run(r)
        self.assertEqual(rc, 1)
        self.assertIn("MISSING", out)

    def test_t68_renamed_fails(self):
        r = good()
        r["SyncControllerSpec"] = suite_xml("SyncControllerSpec", [("SyncController should renamed", "fail")])
        rc, out = run(r)
        self.assertEqual(rc, 1)
        self.assertIn("MISSING", out)
        self.assertIn("unexpected failure", out)

    def test_no_report_fails(self):
        rc, out = run({})
        self.assertEqual(rc, 1)
        self.assertIn("no test report was produced", out)

    def test_expected_suite_without_report_fails(self):
        r = good()
        del r["SnapServingActorSpec"]
        rc, out = run(r)
        self.assertEqual(rc, 1)
        self.assertIn("SnapServingActorSpec has no report", out)

    def test_aborted_suite_fails(self):
        r = good()
        r["SnapServingActorSpec"] = suite_xml("SnapServingActorSpec", [("s should d", "pass")], suite_failures=0, suite_errors=1)
        rc, out = run(r)
        self.assertEqual(rc, 1)
        self.assertIn("aborted suite", out)

    def test_sbt_failure_without_any_failed_test_fails(self):
        r = good(t68="pass")
        rc, out = run(r, allow="", sbt_exit=1)
        self.assertEqual(rc, 1)
        self.assertIn("sbt exited 1", out)

    def test_committed_allowlist_is_the_one_entry(self):
        lines = [l for l in ALLOW_FILE.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")]
        self.assertEqual(lines, [f"SyncControllerSpec :: {T68_NAME}"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
