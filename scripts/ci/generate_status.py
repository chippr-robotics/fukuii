#!/usr/bin/env python3
"""Generate docs/STATUS.md from .github/gates.yml — the one public truth (#1403).

The status document is GENERATED, never hand-edited. That is the whole point: a
public claim about what Fukuii verifies cannot drift from the checks that
enforce it, because the claim is a projection of the matrix.

    python3 scripts/ci/generate_status.py            # write docs/STATUS.md
    python3 scripts/ci/generate_status.py --check    # exit 1 if stale
"""

from __future__ import annotations

import datetime as _dt
import sys
from pathlib import Path

import yaml

REPO = Path(__file__).resolve().parents[2]
GATES = REPO / ".github" / "gates.yml"
OUT = REPO / "docs" / "STATUS.md"

TIER_BADGE = {
    "required": "**required** — must be green to merge",
    "informational": "informational — observed, not blocking",
    "quarantined": "quarantined — known broken, excluded from claims",
}


def render(doc: dict) -> str:
    canonical = doc.get("canonical", {})
    gates = doc.get("gates", [])
    waivers = doc.get("waivers", [])
    slice_ = doc.get("first_required_slice", {})

    by_tier: dict[str, list[dict]] = {"required": [], "informational": [], "quarantined": []}
    for g in gates:
        by_tier.setdefault(g.get("tier", "informational"), []).append(g)

    L: list[str] = []
    A = L.append

    A("<!-- GENERATED FILE — DO NOT EDIT.")
    A("     Source: .github/gates.yml")
    A("     Regenerate: python3 scripts/ci/generate_status.py")
    A("     The Gate Integrity check fails if this file is stale. -->")
    A("")
    A("# Fukuii Verification Status")
    A("")
    A("This page is generated from [`.github/gates.yml`](https://github.com/chippr-robotics/fukuii/blob/main/.github/gates.yml),")
    A("the single source of truth for what Fukuii's CI actually enforces.")
    A("")
    A("**How to read it.** A suite marked *required* must be green for a change to merge —")
    A("that is a claim backed by a check. A suite marked *informational* runs and reports, but")
    A("cannot block a merge; its results are data, **not a compliance claim**. A suite marked")
    A("*quarantined* is known broken and is excluded from every claim on this site.")
    A("")
    A("We publish this because the alternative — a wall of green badges with no gates behind")
    A("them — is how v0.8.0 shipped with Hive sync-server red and `consume-rlp` failing ~67% of")
    A("its cases. See issues")
    A("[#1402](https://github.com/chippr-robotics/fukuii/issues/1402),")
    A("[#1403](https://github.com/chippr-robotics/fukuii/issues/1403),")
    A("[#1404](https://github.com/chippr-robotics/fukuii/issues/1404).")
    A("")

    A("## Canonical facts")
    A("")
    A("| | |")
    A("|---|---|")
    A(f"| Binary | `{canonical.get('binary')}` |")
    A(f"| Container image | `{canonical.get('registry')}` |")
    A(f"| Mirror | `{canonical.get('registry_mirror')}` |")
    A(f"| Version source | `{canonical.get('version_source')}` ({canonical.get('version_scheme')}) |")
    A("")
    A(f"**Lineage.** {' '.join(str(canonical.get('lineage', '')).split())}")
    A("")
    sister = canonical.get("sister_project", {})
    if sister:
        A(f"**`{sister.get('repo')}`.** {' '.join(str(sister.get('relationship', '')).split())}")
        A("")

    A("## Required checks")
    A("")
    if by_tier["required"]:
        A("These must be green to merge. Every claim this project makes rests on this list and")
        A("nothing else.")
        A("")
        A("| Check | Covers | Owner |")
        A("|---|---|---|")
        for g in by_tier["required"]:
            A(f"| `{g['id']}` | {' '.join(str(g.get('covers','')).split())} | {g.get('owner')} |")
    else:
        A("_None._")
    A("")

    A("## Informational — runs, reports, does not block")
    A("")
    A("**These are not compliance claims.** Each carries a tracking issue and a date by which it")
    A("must become required or be formally re-scoped in a reviewed PR.")
    A("")
    A("| Check | Covers | Promote by | Issue | Note |")
    A("|---|---|---|---|---|")
    for g in by_tier["informational"]:
        note = " ".join(str(g.get("status_note", "")).split())
        A(f"| `{g['id']}` | {' '.join(str(g.get('covers','')).split())} "
          f"| {g.get('promote_by')} | #{g.get('issue')} | {note} |")
    A("")

    if by_tier["quarantined"]:
        A("## Quarantined — excluded from all claims")
        A("")
        A("| Check | Why | Promote by | Issue |")
        A("|---|---|---|---|")
        for g in by_tier["quarantined"]:
            A(f"| `{g['id']}` | {' '.join(str(g.get('status_note') or g.get('covers','')).split())} "
              f"| {g.get('promote_by')} | #{g.get('issue')} |")
        A("")

    if slice_:
        A("## First required slice")
        A("")
        A(f"Tracked by [#{slice_.get('issue')}](https://github.com/chippr-robotics/fukuii/issues/"
          f"{slice_.get('issue')}), target **{slice_.get('target')}**.")
        A("")
        A("| Item | Gate | Blocked by |")
        A("|---|---|---|")
        for m in slice_.get("members", []):
            blockers = ", ".join(f"`{b}`" for b in m.get("blocked_by", [])) or "—"
            A(f"| {m.get('item')} | `{m.get('gate')}` | {blockers} |")
        A("")
        A(f"> {' '.join(str(slice_.get('rule','')).split())}")
        A("")

    A("## Active waivers")
    A("")
    if waivers:
        A("A waiver suppresses one known-failing test inside an otherwise-gated subset. Every one")
        A("has an owner, an issue, and an expiry date; CI fails the day it expires. There is no")
        A("bypass — extending a waiver means editing the matrix in a reviewed PR.")
        A("")
        A("| Waiver | Gate | Excluded test | Owner | Issue | Expires |")
        A("|---|---|---|---|---|---|")
        for w in waivers:
            A(f"| `{w['id']}` | `{w.get('gate')}` | `{w.get('pattern')}` | {w.get('owner')} "
              f"| #{w.get('issue')} | {w.get('expires')} |")
    else:
        A("_None._")
    A("")
    return "\n".join(L) + "\n"


def main() -> int:
    doc = yaml.safe_load(GATES.read_text(encoding="utf-8"))
    content = render(doc)
    if "--check" in sys.argv:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != content:
            print("docs/STATUS.md is out of date with .github/gates.yml")
            return 1
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(content, encoding="utf-8")
    print(f"wrote {OUT.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
