#!/usr/bin/env python3
"""Self-test for the Gate Integrity meta-check.

A linter that has never been shown to fail is itself an unbacked claim — the
exact defect spec 008 exists to remove. This suite mutates a copy of the repo
and asserts the check goes red for each condition it advertises (SC-004, SC-006
direction two), and green on the tree as committed.

    python3 scripts/ci/test_gate_integrity.py

Offline, no dependencies beyond PyYAML. Exit 0 = all cases behaved.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# Only what the check reads — keeps each case fast.
COPY = [
    ".github/gates.yml",
    ".github/workflows",
    ".specify/memory/constitution.md",
    "docs/governance/constitution-enforcement.md",
    "docs/STATUS.md",
    "docs/index.md",
    "README.md",
    "scripts/ci",
    "version.sbt",
]

RESULTS: list[tuple[bool, str]] = []


def build_sandbox(dst: Path) -> None:
    for rel in COPY:
        src = REPO / rel
        out = dst / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        if src.is_dir():
            shutil.copytree(src, out, dirs_exist_ok=True)
        elif src.exists():
            shutil.copy2(src, out)


def run_check(root: Path, today: str | None = None) -> tuple[int, str]:
    env = dict(os.environ)
    env.pop("GITHUB_STEP_SUMMARY", None)
    if today:
        env["GATE_INTEGRITY_TODAY"] = today
    p = subprocess.run(
        [sys.executable, str(root / "scripts/ci/check_gate_integrity.py")],
        capture_output=True, text=True, env=env, cwd=root,
    )
    return p.returncode, p.stdout + p.stderr


def case(name: str, expect_fail: bool, expect_code: str | None,
         mutate=None, today: str | None = None) -> None:
    with tempfile.TemporaryDirectory() as td:
        root = Path(td)
        build_sandbox(root)
        if mutate:
            mutate(root)
        rc, out = run_check(root, today)
        failed = rc != 0
        ok = failed == expect_fail
        if ok and expect_code:
            ok = f"[{expect_code}]" in out
        RESULTS.append((ok, name))
        mark = "PASS" if ok else "FAIL"
        print(f"  [{mark}] {name}")
        if not ok:
            detail = "\n".join(f"        {ln}" for ln in out.strip().splitlines()[:6])
            print(f"        expected {'failure' if expect_fail else 'success'}"
                  f"{' with ' + expect_code if expect_code else ''}, got rc={rc}")
            print(detail)


# --- mutations --------------------------------------------------------------

def edit(root: Path, rel: str, old: str, new: str):
    p = root / rel
    s = p.read_text(encoding="utf-8")
    assert old in s, f"fixture drift: {old[:60]!r} not in {rel}"
    p.write_text(s.replace(old, new, 1), encoding="utf-8")


def m_undeclared_caller(root: Path) -> None:
    """C1 — a new Hive caller with no matrix entry."""
    (root / ".github/workflows/hive-brandnew.yml").write_text(
        "name: Hive · brandnew\non:\n  workflow_dispatch:\n"
        "jobs:\n  run:\n    uses: ./.github/workflows/_hive-sim.yml\n"
        "    with:\n      sim: ethereum/brandnew\n      label: brandnew\n",
        encoding="utf-8")


def _hive_gate_whose_only_mechanism_is_min_tests(root: Path) -> dict:
    """An informational Hive gate that `min_tests` alone makes able to fail.

    This case used to name `hive-graphql` literally and broke the moment graphql
    gained a `gate_pattern` (its waiver, #1407) — with a pattern it still has a
    mechanism after `min_tests` is removed, so C3 correctly stays quiet. Same
    lesson as `_first_waiver`: pick the data, don't hardcode it.
    """
    import yaml as _y
    doc = _y.safe_load((root / ".github/gates.yml").read_text(encoding="utf-8"))
    for g in doc.get("gates") or []:
        wf = g.get("workflow")
        if not wf or g.get("tier") != "informational" or (g.get("pass_threshold") or 0) > 0:
            continue
        if not (g.get("min_tests") or 0) > 0:
            continue
        text = (root / ".github/workflows" / wf).read_text(encoding="utf-8")
        if "_hive-sim.yml" in text and not re.search(r"^\s*gate_pattern:\s*\S", text, re.M):
            return g
    raise AssertionError("no informational Hive gate relies on min_tests alone — C3 case has no subject")


def m_required_without_mechanism(root: Path) -> None:
    """C3 — a Hive gate declared required with nothing that can fail it."""
    g = _hive_gate_whose_only_mechanism_is_min_tests(root)
    p = root / ".github/gates.yml"
    s = p.read_text(encoding="utf-8")
    head = f"  - id: {g['id']}\n"
    start = s.index(head)
    nxt = s.find("\n  - id: ", start + len(head))
    end = len(s) if nxt == -1 else nxt
    block = s[start:end]
    block = block.replace("    tier: informational\n", "    tier: required\n", 1)
    block = re.sub(r"^    min_tests: \d+\n", "", block, count=1, flags=re.M)
    p.write_text(s[:start] + block + s[end:], encoding="utf-8")


def m_required_without_evidence(root: Path) -> None:
    """C4 — required Hive gate that cites no green run."""
    edit(root, ".github/gates.yml",
         "  - id: hive-devp2p\n    workflow: hive-devp2p.yml\n"
         "    context: Hive · devp2p\n    tier: informational",
         "  - id: hive-devp2p\n    workflow: hive-devp2p.yml\n"
         "    context: Hive · devp2p\n    tier: required")


def _first_waiver(root: Path) -> dict:
    """Read the first declared waiver instead of hardcoding one.

    These mutations used to name `sync-server-geth-from-fukuii` literally,
    which broke the moment that waiver was renamed (#1407). A self-test that
    hardcodes the data it mutates tests the fixture, not the checker.
    """
    import yaml as _y
    doc = _y.safe_load((root / ".github/gates.yml").read_text(encoding="utf-8"))
    waivers = doc.get("waivers") or []
    assert waivers, "gates.yml declares no waivers — these cases need at least one"
    return waivers[0]


def m_expired_waiver(root: Path) -> None:
    """C6 — a waiver past its expiry date."""
    w = _first_waiver(root)
    edit(root, ".github/gates.yml",
         f"    expires: {w['expires']}",
         "    expires: 2020-01-01")


def m_incomplete_waiver(root: Path) -> None:
    """C6 — a waiver missing its owner."""
    w = _first_waiver(root)
    edit(root, ".github/gates.yml",
         f"  - id: {w['id']}\n    gate: {w['gate']}\n"
         f"    pattern: {w['pattern']}\n    owner: {w['owner']}\n",
         f"  - id: {w['id']}\n    gate: {w['gate']}\n"
         f"    pattern: {w['pattern']}\n")


def m_undeclared_exclusion(root: Path) -> None:
    """C7 — a workflow excludes a test no waiver declares.

    Extends whichever workflow already excludes something, or adds an exclusion under a
    gate_pattern when none does. This case used to name hive-sync.yml, whose exclusion went
    away when both of its waivers were retired (#1407).
    """
    undeclared = "a test no waiver declares"
    workflows = sorted((root / ".github/workflows").glob("*.yml"))
    for p in workflows:
        txt = p.read_text(encoding="utf-8")
        m = re.search(r"gate_exclude: '([^']+)'", txt)
        if m:
            p.write_text(txt.replace(m.group(0), f"gate_exclude: '{m.group(1)}|{undeclared}'", 1),
                         encoding="utf-8")
            return
    for p in workflows:
        txt = p.read_text(encoding="utf-8")
        m = re.search(r"^(\s*)gate_pattern: '[^']+'\n", txt, re.M)
        if m and "_hive-sim.yml" in txt:
            p.write_text(txt.replace(m.group(0), f"{m.group(0)}{m.group(1)}gate_exclude: '{undeclared}'\n", 1),
                         encoding="utf-8")
            return
    raise AssertionError("no hive workflow has a gate_pattern to add an exclusion to — C7 case has no subject")


def m_undeclared_skip(root: Path) -> None:
    """C7 — a workflow stops running a test no waiver declares (sim_skip)."""
    for p in sorted((root / ".github/workflows").glob("*.yml")):
        txt = p.read_text(encoding="utf-8")
        m = re.search(r"sim_skip: '([^']+)'", txt)
        if m:
            p.write_text(txt.replace(m.group(0), f"sim_skip: '{m.group(1)}|a test no waiver declares'", 1),
                         encoding="utf-8")
            return
    raise AssertionError("no workflow sets sim_skip — C7 skip case has no subject")


def m_undeclared_exclusion_in_second_job(root: Path) -> None:
    """C7 — an undeclared exclusion in the SECOND _hive-sim.yml job of a workflow still fails.

    The check used to read only the first `gate_exclude:` in a file. So the first job here gets an
    exclusion that IS declared, and the second an undeclared one: a first-match reader sees only
    the declared one and passes. hive-devp2p.yml runs two jobs.
    """
    import yaml

    doc = yaml.safe_load((root / ".github/gates.yml").read_text(encoding="utf-8"))
    for p in sorted((root / ".github/workflows").glob("*.yml")):
        txt = p.read_text(encoding="utf-8")
        jobs = list(re.finditer(r"^(\s*)sim: .+\n", txt, re.M))
        if len(jobs) < 2 or txt.count("uses: ./.github/workflows/_hive-sim.yml") < 2:
            continue
        gate = next((g["id"] for g in doc["gates"] if g.get("workflow") == p.name), None)
        declared = next((w["pattern"] for w in doc.get("waivers", []) if w.get("gate") == gate), None)
        if not declared:
            continue
        first, second = jobs[0], jobs[1]
        patched = (txt[: first.end()] + f"{first.group(1)}gate_exclude: '{declared}'\n"
                   + txt[first.end(): second.end()]
                   + f"{second.group(1)}gate_exclude: 'a test no waiver declares'\n"
                   + txt[second.end():])
        p.write_text(patched, encoding="utf-8")
        return
    raise AssertionError("no two-job hive workflow with a declared waiver — C7 second-job case has no subject")


def m_overdue_promotion(root: Path) -> None:
    """C5 — an informational gate past its promote_by date."""
    edit(root, ".github/gates.yml",
         "    promote_by: 2026-11-30\n    min_tests: 1\n"
         "    covers: Client starts from a hive-supplied genesis and reports a chain head.",
         "    promote_by: 2020-01-01\n    min_tests: 1\n"
         "    covers: Client starts from a hive-supplied genesis and reports a chain head.")


def m_unmapped_principle(root: Path) -> None:
    """C8 — a constitution principle with no enforcement-map row."""
    p = root / ".specify/memory/constitution.md"
    s = p.read_text(encoding="utf-8")
    s = s.replace("## Technology & Architecture Constraints",
                  "### VIII. Brand New Principle\n\nSomething MUST hold.\n\n"
                  "## Technology & Architecture Constraints", 1)
    p.write_text(s, encoding="utf-8")


def m_enforced_by_informational(root: Path) -> None:
    """C9 — a rule claiming `enforced` via a non-required gate."""
    edit(root, "docs/governance/constitution-enforcement.md",
         "| IV.1 | All code passes `scalafmt` | `enforced` | `gate:ci-test-build` — `sbt scalafmtCheckAll` |",
         "| IV.1 | All code passes `scalafmt` | `enforced` | `gate:hive-graphql` — pretend |")


def m_second_version_scheme(root: Path) -> None:
    """C10 — a rival version file (FR-019)."""
    (root / "VERSION").write_text("0.8.0\n", encoding="utf-8")


def m_dead_badge(root: Path) -> None:
    """C11 — a badge for a workflow that does not exist."""
    p = root / "README.md"
    p.write_text(p.read_text(encoding="utf-8") +
                 "\n[![Hive · ghost](https://github.com/chippr-robotics/fukuii/actions/"
                 "workflows/hive-ghost.yml/badge.svg)](https://github.com/chippr-robotics/"
                 "fukuii/actions/workflows/hive-ghost.yml)\n", encoding="utf-8")


def m_overclaiming_doc(root: Path) -> None:
    """C12 — prose claiming verification for an informational suite."""
    p = root / "docs/index.md"
    p.write_text(p.read_text(encoding="utf-8") +
                 "\nFukuii is Hive verified across every simulator.\n", encoding="utf-8")


def m_stale_status(root: Path) -> None:
    """C13 — the generated status doc no longer matches the matrix."""
    p = root / "docs/STATUS.md"
    p.write_text(p.read_text(encoding="utf-8").replace("Fukuii Verification Status",
                                                       "Stale Heading", 1), encoding="utf-8")


def main() -> int:
    print("Gate Integrity self-test\n")

    print(" baseline")
    case("clean tree passes", expect_fail=False, expect_code=None)

    print(" declared-matrix checks")
    case("C1  undeclared hive caller fails", True, "C1", m_undeclared_caller)
    case("C3  required gate with no mechanism fails", True, "C3", m_required_without_mechanism)
    case("C4  required hive gate without evidence fails", True, "C4", m_required_without_evidence)
    case("C5  overdue promote_by fails", True, "C5", m_overdue_promotion)

    print(" waiver checks")
    case("C6  expired waiver fails", True, "C6", m_expired_waiver)
    case("C6  incomplete waiver fails", True, "C6", m_incomplete_waiver)
    case("C7  undeclared gate_exclude fails", True, "C7", m_undeclared_exclusion)
    case("C7  undeclared sim_skip fails", True, "C7", m_undeclared_skip)
    case("C7  undeclared gate_exclude in a second job fails", True, "C7", m_undeclared_exclusion_in_second_job)

    print(" constitution checks")
    case("C8  unmapped principle fails", True, "C8", m_unmapped_principle)
    case("C9  `enforced` via informational gate fails", True, "C9", m_enforced_by_informational)

    print(" public-truth checks")
    case("C10 second version scheme fails", True, "C10", m_second_version_scheme)
    case("C11 dead badge fails", True, "C11", m_dead_badge)
    case("C12 over-claiming doc fails", True, "C12", m_overclaiming_doc)
    case("C13 stale status doc fails", True, "C13", m_stale_status)

    print(" time travel")
    case("waivers still valid today", False, None, None, today="2026-09-20")
    case("every waiver expired by 2030", True, "C6", None, today="2030-01-01")

    failed = [n for ok, n in RESULTS if not ok]
    print()
    print(f"{len(RESULTS) - len(failed)}/{len(RESULTS)} cases behaved as specified.")
    if failed:
        for n in failed:
            print(f"  unexpected: {n}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
