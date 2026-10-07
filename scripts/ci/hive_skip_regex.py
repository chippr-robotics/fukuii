#!/usr/bin/env python3
"""Compile test names to SKIP into a hive test regex that matches every OTHER name.

hive selects tests with one regex (`--sim.limit suite/test`) and cannot say "all but these", and Go's regexp (RE2) has
no lookahead, so "anything except GetCells" has to be spelled out. The regex built here accepts a name exactly when it
leaves the skipped names' trie at some character, or stops short of a skipped name. It is anchored, so a skipped
name's prefixes and extensions still run, and it spells each letter in both cases, because hive matches
case-insensitively (it wraps the pattern in `(?i:...)`) and the devp2p tool matches the raw pattern too.

Tests that are skipped rather than run must be declared waivers in .github/gates.yml; Gate Integrity (C7) checks every
`sim_skip` name the same way it checks a `gate_exclude` branch.

    python3 scripts/ci/hive_skip_regex.py 'GetCells|BlobTxWithInvalidCells'
"""

from __future__ import annotations

import sys

_SPECIAL = set("\\.+*?()|[]{}^$/")
_CLASS_SPECIAL = set("\\]^-[")


def _atom(c: str) -> str:
    """One character, matching either case."""
    lo, up = c.lower(), c.upper()
    if lo != up:
        return f"[{_class_char(lo)}{_class_char(up)}]"
    return f"\\{c}" if c in _SPECIAL else c


def _class_char(c: str) -> str:
    return f"\\{c}" if c in _CLASS_SPECIAL else c


def _not_any_of(chars: list[str]) -> str:
    """A character none of `chars`, in either case."""
    members = []
    for c in chars:
        for v in dict.fromkeys((c.lower(), c.upper())):
            members.append(_class_char(v))
    return "[^" + "".join(members) + "]"


def _node(trie: dict, terminal: bool) -> str:
    """Strings that, read from this trie node on, are not a skipped name."""
    kids = {k: v for k, v in trie.items() if k != ""}
    alts = []
    if not terminal:
        alts.append("")  # the name ends here, short of every skipped name
    alts.append(_not_any_of(sorted(kids)) + ".*" if kids else ".+")  # leaves the trie here
    for c in sorted(kids):
        alts.append(_atom(c) + "(?:" + _node(kids[c], "" in kids[c]) + ")")
    return "|".join(alts)


def skip_regex(names: list[str]) -> str:
    """An anchored regex matching every test name except `names` (case-insensitively)."""
    names = [n.strip() for n in names if n.strip()]
    if not names:
        raise ValueError("nothing to skip")
    trie: dict = {}
    for name in names:
        node = trie
        for c in name.lower():
            node = node.setdefault(c, {})
        node[""] = {}  # terminal marker
    return "^(?:" + _node(trie, False) + ")$"


def main(argv: list[str]) -> int:
    if len(argv) != 2 or not argv[1].strip():
        print("usage: hive_skip_regex.py 'NameOne|NameTwo'", file=sys.stderr)
        return 2
    print(skip_regex(argv[1].split("|")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
