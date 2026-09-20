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


def m_required_without_mechanism(root: Path) -> None:
    """C3 — a Hive gate declared required with nothing that can fail it."""
    edit(root, ".github/gates.yml",
         "  - id: hive-graphql\n    workflow: hive-graphql.yml\n"
         "    context: Hive · graphql\n    tier: informational",
         "  - id: hive-graphql\n    workflow: hive-graphql.yml\n"
         "    context: Hive · graphql\n    tier: required")
    edit(root, ".github/gates.yml", "    min_tests: 10\n    covers: GraphQL endpoint conformance.",
         "    covers: GraphQL endpoint conformance.")


def m_required_without_evidence(root: Path) -> None:
    """C4 — required Hive gate that cites no green run."""
    edit(root, ".github/gates.yml",
         "  - id: hive-devp2p\n    workflow: hive-devp2p.yml\n"
         "    context: Hive · devp2p\n    tier: informational",
         "  - id: hive-devp2p\n    workflow: hive-devp2p.yml\n"
         "    context: Hive · devp2p\n    tier: required")


def m_expired_waiver(root: Path) -> None:
    """C6 — a waiver past its expiry date."""
    edit(root, ".github/gates.yml",
         "    expires: 2026-12-31\n\n  - id: sync-client-fukuii-from-nethermind",
         "    expires: 2020-01-01\n\n  - id: sync-client-fukuii-from-nethermind")


def m_incomplete_waiver(root: Path) -> None:
    """C6 — a waiver missing its owner."""
    edit(root, ".github/gates.yml",
         "  - id: sync-server-geth-from-fukuii\n"
         "    gate: hive-sync\n"
         "    pattern: sync go-ethereum from fukuii\n"
         "    owner: realcodywburns\n",
         "  - id: sync-server-geth-from-fukuii\n"
         "    gate: hive-sync\n"
         "    pattern: sync go-ethereum from fukuii\n")


def m_undeclared_exclusion(root: Path) -> None:
    """C7 — a workflow excludes a test no waiver declares."""
    edit(root, ".github/workflows/hive-sync.yml",
         "gate_exclude: 'sync go-ethereum from fukuii|sync fukuii from nethermind'",
         "gate_exclude: 'sync go-ethereum from fukuii|sync fukuii from nethermind|sync fukuii from besu'")


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
