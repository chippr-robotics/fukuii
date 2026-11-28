#!/usr/bin/env python3
"""The failed tests of one hive suite result whose client never started, by test case id.

hive fails a test whose client container could not be started with the line

    can't launch node (type fukuii_pathlive): client did not start: terminated unexpectedly

(hivesim testapi.go `can't launch node`, libhive api.go `client did not start: %v`; the reason is libdocker's
`terminated unexpectedly` or `timed out waiting for container startup`). Such a test ran nothing against the client:
its failure is a start-up failure, not a protocol result. B-314 read 78 of them as re-org failures, because nothing in
the summary told them apart from a failed assertion. `_hive-sim.yml` counts them separately with this list.

A case's details are inline (`summaryResult.details`, older hive) or a byte span (`summaryResult.log`) of the suite's
`testDetailsLog`, a path relative to the result file's directory.

    python3 -I scripts/ci/hive_launch_failures.py workspace/logs/<suite>.json

Prints one JSON object, test case id -> the reason (`client did not start: ...`); `{}` when there is none, or when the
file cannot be read. Standard library only.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

SIGNATURE = "client did not start"


def case_details(case: dict, details: bytes | None) -> str:
    """The text hive recorded as the case's result: inline, or its span of the suite's details log."""
    result = case.get("summaryResult")
    if not isinstance(result, dict):
        return ""
    inline = result.get("details")
    if isinstance(inline, str):
        return inline
    span = result.get("log")
    if details is None or not isinstance(span, dict):
        return ""
    begin, end = span.get("begin"), span.get("end")
    if not isinstance(begin, int) or not isinstance(end, int) or not 0 <= begin <= end <= len(details):
        return ""
    return details[begin:end].decode("utf-8", "replace")


def launch_failures(result_path: Path) -> dict[str, str]:
    """Test case id -> why its client did not start, for every failed case of the suite at `result_path`."""
    try:
        suite = json.loads(result_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    cases = suite.get("testCases") if isinstance(suite, dict) else None
    if not isinstance(cases, dict):
        return {}
    details = None
    details_log = suite.get("testDetailsLog")
    if isinstance(details_log, str) and details_log:
        try:
            details = (result_path.parent / details_log).read_bytes()
        except OSError:
            details = None
    failures: dict[str, str] = {}
    for case_id, case in cases.items():
        if not isinstance(case, dict):
            continue
        result = case.get("summaryResult")
        if not isinstance(result, dict) or result.get("pass") is not False:
            continue
        text = case_details(case, details)
        at = text.find(SIGNATURE)
        if at >= 0:
            failures[str(case_id)] = text[at:].splitlines()[0].strip()
    return failures


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: hive_launch_failures.py <hive suite result .json>", file=sys.stderr)
        return 2
    print(json.dumps(launch_failures(Path(argv[1])), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
