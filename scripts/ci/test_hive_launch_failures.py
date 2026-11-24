#!/usr/bin/env python3
"""Self-test for scripts/ci/hive_launch_failures.py.

A test whose client never started is counted apart from a failed assertion in the hive summary and gate (B-314), so
this checks that the list finds exactly those: in a details log by byte span (hive since 2024), inline (older hive),
past non-ASCII text earlier in the log, and never in a test that passed or failed for another reason.

    python3 scripts/ci/test_hive_launch_failures.py

Offline, standard library only. Exit 0 = all cases behaved.
"""

from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from hive_launch_failures import launch_failures  # noqa: E402

# hive's own words, hivesim testapi.go and libhive api.go / libdocker container.go
LAUNCH = "can't launch node (type fukuii_pathlive): client did not start: terminated unexpectedly\n"
TIMEOUT = "can't launch node (type fukuii): client did not start: timed out waiting for container startup\n"
ASSERTION = "forkchoiceUpdated: expected VALID, got SYNCING (client did start, then answered)\n"
# 40 three-byte characters: a span read as characters, not bytes, lands 80 characters late and misses the next line
LOADER = "loader " + "\u20ac" * 40 + "\n"


def suite(tmp: Path, name: str, spans: list[tuple[str, bool, str]]) -> Path:
    """A result file and its details log: one case per (id, pass, text), each text a span of the log."""
    log = b""
    cases = {}
    for case_id, passed, text in spans:
        raw = text.encode("utf-8")
        cases[case_id] = {
            "name": f"test {case_id}",
            "summaryResult": {"pass": passed, "log": {"begin": len(log), "end": len(log) + len(raw)}},
        }
        log += raw
    (tmp / "details").mkdir(exist_ok=True)
    (tmp / "details" / f"{name}.log").write_bytes(log)
    path = tmp / f"{name}.json"
    path.write_text(json.dumps({"name": name, "testDetailsLog": f"details/{name}.log", "testCases": cases}))
    return path


def main() -> int:
    with tempfile.TemporaryDirectory() as d:
        tmp = Path(d)
        spans = suite(
            tmp,
            "engine",
            [
                ("1", True, LOADER),
                ("2", False, LAUNCH),
                ("3", False, ASSERTION),
                ("4", False, TIMEOUT),
                ("5", True, "a pass that mentions client did not start in passing\n"),
            ],
        )
        inline = tmp / "inline.json"
        inline.write_text(
            json.dumps(
                {
                    "testCases": {
                        "7": {"summaryResult": {"pass": False, "details": LAUNCH}},
                        "8": {"summaryResult": {"pass": False, "details": ASSERTION}},
                    }
                }
            )
        )
        lost = tmp / "lost.json"
        lost.write_text(
            json.dumps(
                {
                    "testDetailsLog": "details/missing.log",
                    "testCases": {"9": {"summaryResult": {"pass": False, "log": {"begin": 0, "end": 10}}}},
                }
            )
        )
        bad = tmp / "bad.json"
        bad.write_text("{ not json")

        found = launch_failures(spans)
        cases = [
            ("finds a client that terminated, by byte span after non-ASCII text", found.get("2") == "client did not start: terminated unexpectedly"),
            ("finds a client that timed out at its start", found.get("4") == "client did not start: timed out waiting for container startup"),
            ("leaves out a failed assertion", "3" not in found),
            ("leaves out every passed test", "1" not in found and "5" not in found),
            ("finds nothing else", set(found) == {"2", "4"}),
            ("reads inline details (older hive)", launch_failures(inline) == {"7": "client did not start: terminated unexpectedly"}),
            ("says nothing when the details log is missing", launch_failures(lost) == {}),
            ("says nothing for a file that is not JSON", launch_failures(bad) == {}),
            ("says nothing for a file that does not exist", launch_failures(tmp / "absent.json") == {}),
        ]

    for name, ok in cases:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    failed = [name for name, ok in cases if not ok]
    print(f"\n{len(cases) - len(failed)}/{len(cases)} cases behaved as specified.")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
