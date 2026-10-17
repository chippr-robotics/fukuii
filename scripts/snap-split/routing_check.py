#!/usr/bin/env python3
"""Routing-doc grep check (spec 016, slice R1 / T090, SC-006).

    routing_check.py [--root DIR] [--doc PATH] [--verbose]

Reads the "Phase / module routing" table of `.claude/agent-protocols/snap-sync.md` and checks every row:

  status  the Status cell starts with "target (post-split)" (after `**` markup is removed);
  files   every backticked `*.scala` path in the "Current location" and "Target (post-split)" cells exists
          (`SSC` is `SNAPSyncController.scala`; `{A,B}` is expanded; paths resolve against the snap package
          unless they start with `network/` (the network package), `src/.../` (the one file under `src/main/scala`
          whose path ends in the rest) or `src/` (the repository root));
  tags    every backticked log tag in the "Log tags" cell (`[UPPER-KEBAB]`, `UPPER_SNAKE` or `Word:`) occurs in a
          non-comment line of one of the row's files;
  metrics every backticked `snapsync.*` metric (`{a,b}` expanded; `*` is a wildcard) matches a metric name
          registered in `SNAPSyncMetrics.scala` or in one of the row's files;
  config  every backticked key in the "Config keys" cell (kebab case, `*` a wildcard) is parsed by
          `SNAPSyncConfig.fromConfig` (the key literal in `object SNAPSyncConfig`), and the field it sets is read
          (`.<field>`) in one of the row's files. Keys outside `sync.snap-sync` (a cell that starts with `network.`)
          are not SNAPSyncConfig keys and are skipped.

Extraction is by backticks only: anything a cell names in plain text (prose, a negative note) is not checked, so a
tag or key the doc mentions without claiming it is written without backticks. Exit 1 when any row fails.
Standard library only.
"""

from __future__ import annotations

import argparse
import fnmatch
import re
import sys
from pathlib import Path

SNAP = "src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap"
NETWORK = "src/main/scala/com/chipprbots/ethereum/network"
DOC = ".claude/agent-protocols/snap-sync.md"
SECTION = "## Phase / module routing"
COLUMNS = ["phase", "status", "current", "target", "config", "metrics", "tags", "reviewer"]
STATUS_PREFIX = "target (post-split)"

TICK_RE = re.compile(r"`([^`]+)`")
TAG_RE = re.compile(r"^(?:\[[A-Z0-9-]+\]|[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+|[A-Z][a-z]+:)$")
KEY_RE = re.compile(r"^[a-z*][a-z0-9*-]*$")
METRIC_LITERAL_RE = re.compile(r'"(snapsync\.[A-Za-z0-9_.]+)"')


def expand(token: str) -> list[str]:
    """Expand one `{a,b}` group (repeatedly), e.g. `x/{A,B}.scala` -> `x/A.scala`, `x/B.scala`."""
    m = re.search(r"\{([^{}]*)\}", token)
    if not m:
        return [token]
    out = []
    for alt in m.group(1).split(","):
        out.extend(expand(token[: m.start()] + alt.strip() + token[m.end():]))
    return out


def table_rows(doc_text: str) -> list[tuple[int, dict[str, str]]]:
    lines = doc_text.splitlines()
    try:
        start = next(i for i, l in enumerate(lines) if l.strip() == SECTION)
    except StopIteration:
        raise SystemExit(f"routing_check: section '{SECTION}' not found")
    rows = []
    for i in range(start + 1, len(lines)):
        l = lines[i]
        if l.startswith("## "):
            break
        if not l.startswith("|"):
            continue
        cells = [c.strip() for c in l.strip().strip("|").split("|")]
        if cells[0] in ("Phase / module",) or set(cells[0]) <= {"-", ":"}:
            continue
        if len(cells) != len(COLUMNS):
            raise SystemExit(f"routing_check: line {i + 1}: {len(cells)} cells, expected {len(COLUMNS)}")
        rows.append((i + 1, dict(zip(COLUMNS, cells))))
    return rows


def resolve(root: Path, token: str) -> Path:
    """The file a path token names. `src/.../<rest>` is shorthand for the one file under `src/main/scala` whose path
    ends in `/<rest>`; no match, or more than one, resolves to a path that does not exist."""
    if token == "SSC":
        token = "SNAPSyncController.scala"
    if token.startswith("src/.../"):
        rest = token[len("src/.../"):]
        hits = [p for p in (root / "src/main/scala").rglob(Path(rest).name) if p.as_posix().endswith("/" + rest)]
        return hits[0] if len(hits) == 1 else root / token
    if token.startswith("src/"):
        return root / token
    if token.startswith("network/"):
        return root / NETWORK / token[len("network/"):]
    return root / SNAP / token


def row_files(cells: dict[str, str]) -> list[str]:
    out = []
    for col in ("current", "target"):
        for t in TICK_RE.findall(cells[col]):
            for e in expand(t.strip()):
                if e == "SSC" or e.endswith(".scala"):
                    if e not in out:
                        out.append(e)
    return out


def code_lines(text: str) -> str:
    """The file without comment lines (`//`, `/*`, `*`), so a tag named only in a comment does not count."""
    keep = []
    for l in text.splitlines():
        s = l.lstrip()
        if s.startswith("//") or s.startswith("/*") or s.startswith("*"):
            continue
        keep.append(l)
    return "\n".join(keep)


def snap_config(root: Path) -> dict[str, str]:
    """key -> field, from `object SNAPSyncConfig`'s `fromConfig` (one `field =` assignment per parsed key)."""
    text = (root / SNAP / "SNAPSyncController.scala").read_text(encoding="utf-8")
    m = re.search(r"^object SNAPSyncConfig\b.*?(?=^\S|\Z)", text, re.S | re.M)
    if not m:
        raise SystemExit("routing_check: object SNAPSyncConfig not found in SNAPSyncController.scala")
    body = m.group(0)
    out: dict[str, str] = {}
    parts = re.split(r"^\s+(\w+)\s*=(?!=)", body, flags=re.M)
    # parts: [prefix, field1, text1, field2, text2, ...]
    for field, chunk in zip(parts[1::2], parts[2::2]):
        for key in re.findall(r'get\w+\("([a-z0-9-]+)"\)', chunk):
            out.setdefault(key, field)
    return out


def check_row(root: Path, cells: dict[str, str], config: dict[str, str], metrics_text: str) -> list[str]:
    problems = []
    status = cells["status"].replace("**", "").strip()
    if not status.startswith(STATUS_PREFIX):
        problems.append(f"status: not marked '{STATUS_PREFIX}': {status[:60]!r}")

    files = row_files(cells)
    texts = {}
    for f in files:
        p = resolve(root, f)
        if not p.is_file():
            problems.append(f"files: `{f}` does not exist ({p.relative_to(root)})")
        else:
            texts[f] = p.read_text(encoding="utf-8")
    code = {f: code_lines(t) for f, t in texts.items()}

    for t in TICK_RE.findall(cells["tags"]):
        t = t.strip()
        if not TAG_RE.match(t):
            continue
        if not any(t in c for c in code.values()):
            problems.append(f"tags: `{t}` is not emitted in any of the row's files")

    registered = set(METRIC_LITERAL_RE.findall(metrics_text))
    for c in texts.values():
        registered |= set(METRIC_LITERAL_RE.findall(c))
    for t in TICK_RE.findall(cells["metrics"]):
        for m in expand(t.strip()):
            if not m.startswith("snapsync."):
                continue
            if not any(fnmatch.fnmatchcase(r, m) for r in registered):
                problems.append(f"metrics: `{m}` is not registered in SNAPSyncMetrics.scala or the row's files")

    if not cells["config"].lstrip().startswith("`network."):
        for t in TICK_RE.findall(cells["config"]):
            t = t.strip()
            if not KEY_RE.match(t):
                continue
            keys = [k for k in config if fnmatch.fnmatchcase(k, t)]
            if not keys:
                problems.append(f"config: `{t}` is not parsed in SNAPSyncConfig")
                continue
            for k in keys:
                field = config[k]
                if not any(re.search(rf"\.{field}\b", c) for c in code.values()):
                    problems.append(f"config: `{k}` (SNAPSyncConfig.{field}) is not read in any of the row's files")
    return problems


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=".", help="repository root (default: .)")
    ap.add_argument("--doc", default=None, help=f"routing doc (default: <root>/{DOC})")
    ap.add_argument("--verbose", action="store_true", help="list each row's files")
    a = ap.parse_args(argv)
    root = Path(a.root).resolve()
    doc = Path(a.doc) if a.doc else root / DOC
    rows = table_rows(doc.read_text(encoding="utf-8"))
    config = snap_config(root)
    metrics_text = (root / SNAP / "SNAPSyncMetrics.scala").read_text(encoding="utf-8")
    failed = 0
    for line, cells in rows:
        name = cells["phase"].replace("**", "")
        name = name[:70]
        problems = check_row(root, cells, config, metrics_text)
        print(f"{'FAIL' if problems else 'PASS'} line {line}: {name}")
        if a.verbose:
            print(f"     files: {', '.join(row_files(cells)) or '(none)'}")
        for p in problems:
            print(f"     {p}")
        failed += bool(problems)
    print(f"\nrouting_check: {len(rows) - failed}/{len(rows)} rows pass")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
