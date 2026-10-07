#!/usr/bin/env python3
"""Self-test for scripts/ci/hive_skip_regex.py.

The regex it builds is what keeps a skipped hive test from running while every other test in the suite still does,
so this checks both directions against the devp2p `eth` suite's real test names, and that hive's pattern splitter
leaves the regex in one piece.

    python3 scripts/ci/test_hive_skip_regex.py

Offline, standard library only. Exit 0 = all cases behaved.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from hive_skip_regex import skip_regex  # noqa: E402

# go-ethereum cmd/devp2p/internal/ethtest EthTests(), as of the hive devp2p image of 2026-09-24.
ETH_TESTS = """
Status MaliciousHandshake BlockRangeUpdateExpired BlockRangeUpdateFuture BlockRangeUpdateInvalid GetBlockHeaders
GetNonexistentBlockHeaders SimultaneousRequests SameRequestID ZeroRequestID GetBlockAccessLists GetBlockBodies
GetReceipts GetLargeReceipts LargeTxRequest Transaction InvalidTxs NewPooledTxs BlobViolations TestBlobTxWithoutSidecar
TestBlobTxWithMismatchedSidecar BlobTxAvailabilityFailure GetCells BlobTxWithInvalidCells
""".split()

SKIP = ["GetCells", "BlobTxWithInvalidCells"]


def split_regexp(s: str) -> list[str]:
    """hive's hivesim/testmatch.go splitRegexp: split on `/` outside brackets and parentheses."""
    parts, cs, cp, i = [], 0, 0, 0
    while i < len(s):
        c = s[i]
        if c == "[":
            cs += 1
        elif c == "]":
            cs = max(cs - 1, 0)
        elif c == "(" and cs == 0:
            cp += 1
        elif c == ")" and cs == 0:
            cp -= 1
        elif c == "\\":
            i += 1
        elif c == "/" and cs == 0 and cp == 0:
            parts.append(s[:i])
            s, i = s[i + 1 :], 0
            continue
        i += 1
    return parts + [s]


def matches(regex: str, name: str) -> bool:
    # hive wraps the test pattern as (?i:...) and the devp2p tool calls MatchString (unanchored).
    return re.search("(?i:" + regex + ")", name) is not None


def main() -> int:
    regex = skip_regex(SKIP)
    cases = [
        ("skips exactly the named tests", [n for n in ETH_TESTS if not matches(regex, n)] == SKIP),
        ("runs every other eth test", all(matches(regex, n) for n in ETH_TESTS if n not in SKIP)),
        ("skips regardless of case, as hive matches", not matches(regex, "getcells") and not matches(regex, "GETCELLS")),
        ("runs a prefix of a skipped name", matches(regex, "GetCell")),
        ("runs an extension of a skipped name", matches(regex, "GetCellsV2") and matches(regex, "XGetCells")),
        ("runs names with regex metacharacters", matches(regex, "Findnode/BasicFindnode") and matches(regex, "a.b(c)")),
        ("stays one piece under hive's splitter", split_regexp("eth/" + regex) == ["eth", regex]),
        ("escapes metacharacters in skipped names", not matches(skip_regex(["a.b/c"]), "a.b/c") and matches(skip_regex(["a.b/c"]), "axb/c")),
    ]
    try:
        skip_regex(["", " "])
        cases.append(("rejects an empty skip list", False))
    except ValueError:
        cases.append(("rejects an empty skip list", True))

    for name, ok in cases:
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}")
    failed = [name for name, ok in cases if not ok]
    print(f"\n{len(cases) - len(failed)}/{len(cases)} cases behaved as specified.")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
