#!/usr/bin/env python3
"""Judge a `snap-synctest` run (spec 016, S0e / T007) from its JUnit XML reports.

    synctest_report.py --reports DIR --allowlist FILE \
        [--expect-suite NAME ...] [--sbt-exit N] [--summary FILE]

Failed and errored test cases are collected as `<suite> :: <test name>` (suite is
the simple class name) and compared with the allow-list (one entry: task #68).
Exit 1 when:
  * no report was produced, or an --expect-suite has no report;
  * a failed test is not on the allow-list;
  * an allow-listed test passed (the list is stale), was skipped, or is missing
    (renamed, deleted, or not run);
  * a suite reports more failures/errors than it has failed test cases (an
    aborted suite), or sbt exited non-zero with nothing to explain it.
Per-suite counts (total / passed / ignored / failed) are printed, and appended
to --summary (GITHUB_STEP_SUMMARY) when given. Standard library only.
"""

from __future__ import annotations

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def read_allowlist(path: Path) -> set[str]:
    out = set()
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            out.add(line)
    return out


def parse_reports(reports: Path):
    """Return (counts, failed, passed, skipped, problems).

    counts: {suite: [total, passed, ignored, failed]}; failed/passed/skipped are
    sets of `<suite> :: <test>`; problems are strings.
    """
    counts: dict[str, list[int]] = {}
    failed: set[str] = set()
    passed: set[str] = set()
    skipped: set[str] = set()
    problems: list[str] = []
    for f in sorted(reports.glob("*.xml")) if reports.is_dir() else []:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            problems.append(f"unparseable report {f.name}: {e}")
            continue
        suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
        for s in suites:
            suite = (s.get("name") or "").rsplit(".", 1)[-1]
            c = counts.setdefault(suite, [0, 0, 0, 0])
            case_failures = 0
            for tc in s.iter("testcase"):
                key = f"{suite} :: {tc.get('name', '')}"
                c[0] += 1
                if tc.find("failure") is not None or tc.find("error") is not None:
                    c[3] += 1
                    case_failures += 1
                    failed.add(key)
                elif tc.find("skipped") is not None:
                    c[2] += 1
                    skipped.add(key)
                else:
                    c[1] += 1
                    passed.add(key)
            declared = int(s.get("failures") or 0) + int(s.get("errors") or 0)
            if declared > case_failures:
                problems.append(
                    f"{suite}: report declares {declared} failure(s)/error(s) but only "
                    f"{case_failures} failed test case(s) are attributable (aborted suite?)"
                )
    return counts, failed, passed, skipped, problems


def judge(counts, failed, passed, skipped, problems, allow, expect, sbt_exit):
    errs = list(problems)
    if not counts:
        errs.append("no test report was produced")
    for suite in expect:
        if suite not in counts:
            errs.append(f"expected suite {suite} has no report (not run?)")
    for name in sorted(failed - allow):
        errs.append(f"unexpected failure: {name}")
    for name in sorted(allow):
        if name in failed:
            continue
        if name in passed:
            errs.append(f"allow-listed test PASSED, so the allow-list is stale (remove it): {name}")
        elif name in skipped:
            errs.append(f"allow-listed test was skipped/ignored, not failing: {name}")
        else:
            errs.append(f"allow-listed test is MISSING from the reports (renamed, deleted or not run): {name}")
    if sbt_exit not in (None, 0) and not failed and not errs:
        errs.append(f"sbt exited {sbt_exit} but the reports show no failure to explain it")
    return errs


def render(counts, errs) -> str:
    lines = ["### snap-synctest per-suite counts", "", "| suite | total | passed | ignored | failed |", "|---|---|---|---|---|"]
    for suite in sorted(counts):
        t, p, i, f = counts[suite]
        lines.append(f"| {suite} | {t} | {p} | {i} | {f} |")
    lines.append("")
    lines.append("**Result: FAIL**" if errs else "**Result: PASS** (the only failure, if any, is the allow-listed task #68)")
    for e in errs:
        lines.append(f"- {e}")
    return "\n".join(lines) + "\n"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--reports", type=Path, required=True)
    ap.add_argument("--allowlist", type=Path, required=True)
    ap.add_argument("--expect-suite", action="append", default=[])
    ap.add_argument("--sbt-exit", type=int, default=None)
    ap.add_argument("--summary", type=Path, default=None)
    a = ap.parse_args(argv)
    counts, failed, passed, skipped, problems = parse_reports(a.reports)
    errs = judge(counts, failed, passed, skipped, problems, read_allowlist(a.allowlist), a.expect_suite, a.sbt_exit)
    text = render(counts, errs)
    sys.stdout.write(text)
    if a.summary:
        with a.summary.open("a", encoding="utf-8") as fh:
            fh.write(text)
    for e in errs:
        print(f"::error::{e}")
    return 1 if errs else 0


if __name__ == "__main__":
    raise SystemExit(main())
