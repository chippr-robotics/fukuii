#!/usr/bin/env python3
"""Gate Integrity — the meta-check for Fukuii's CI gate matrix.

Spec: specs/008-ci-gate-integrity/spec.md  (issues #1402, #1403, #1404)

This is the check that makes `.github/gates.yml` load-bearing instead of
decorative. It answers, mechanically, the question the v0.8.0 release could not:
*is every claim this repository makes backed by a check that can actually fail?*

It runs offline in seconds (FR-030) — no network, no Docker, no sbt — so it can
be a required status check (FR-029) without lengthening the merge path.

Checks, in order:
  C1  every workflow invoking _hive-sim.yml is declared in the matrix
  C2  every declared gate points at a workflow file that exists
  C3  a `required` gate must have a mechanism that can fail it
  C4  a `required` Hive gate must cite evidence of a green run
  C5  non-required gates must carry `issue` + `promote_by`; overdue = fail
  C6  waivers must be complete (owner/issue/reason/expires) and unexpired
  C7  every gate_exclude branch in every workflow must be a declared waiver
  C8  every constitution principle must appear in the enforcement map
  C9  every `enforced` mapping must name a gate that exists and can fail
  C10 one versioning scheme only (FR-019)
  C11 README badges must point at workflows that exist (FR-027)
  C12 docs must not claim verification for a non-required suite (FR-026)
  C13 the generated status doc must be current (FR-025)

Exit 0 = clean (warnings allowed). Exit 1 = at least one failure.
"""

from __future__ import annotations

import datetime as _dt
import os
import re
import subprocess
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - environment guard
    print("::error::PyYAML is required. pip install pyyaml", file=sys.stderr)
    raise SystemExit(1)

REPO = Path(__file__).resolve().parents[2]
GATES = REPO / ".github" / "gates.yml"
WORKFLOWS = REPO / ".github" / "workflows"
CONSTITUTION = REPO / ".specify" / "memory" / "constitution.md"
ENFORCEMENT = REPO / "docs" / "governance" / "constitution-enforcement.md"
STATUS_DOC = REPO / "docs" / "STATUS.md"
README = REPO / "README.md"

FAILURES: list[str] = []
WARNINGS: list[str] = []


def fail(check: str, msg: str) -> None:
    FAILURES.append(f"[{check}] {msg}")


def warn(check: str, msg: str) -> None:
    WARNINGS.append(f"[{check}] {msg}")


def today() -> _dt.date:
    # Overridable so the suite can test expiry behaviour deterministically.
    override = os.environ.get("GATE_INTEGRITY_TODAY")
    if override:
        return _dt.date.fromisoformat(override)
    return _dt.date.today()


def parse_date(value: object, ctx: str, check: str) -> _dt.date | None:
    if isinstance(value, _dt.date):
        return value
    if isinstance(value, str):
        try:
            return _dt.date.fromisoformat(value)
        except ValueError:
            pass
    fail(check, f"{ctx}: `{value!r}` is not an ISO date (YYYY-MM-DD).")
    return None


# ---------------------------------------------------------------------------
# Workflow inspection (text-level; we care about literal inputs, not templating)
# ---------------------------------------------------------------------------

def workflow_text(name: str) -> str:
    path = WORKFLOWS / name
    return path.read_text(encoding="utf-8") if path.exists() else ""


def hive_callers() -> set[str]:
    """Workflow filenames that invoke the reusable _hive-sim.yml."""
    found = set()
    for wf in sorted(WORKFLOWS.glob("*.yml")):
        if wf.name == "_hive-sim.yml":
            continue
        if "_hive-sim.yml" in wf.read_text(encoding="utf-8"):
            found.add(wf.name)
    return found


def scalar_input(text: str, key: str) -> str | None:
    """Value of `key: <scalar>` as written in a caller workflow, if present."""
    m = re.search(rf"^\s*{re.escape(key)}:\s*(.+?)\s*$", text, re.MULTILINE)
    if not m:
        return None
    return m.group(1).strip().strip("'\"")


def gate_exclude_branches(text: str) -> list[str]:
    raw = scalar_input(text, "gate_exclude")
    if not raw:
        return []
    return [b.strip() for b in raw.split("|") if b.strip()]


# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

def check_gates(doc: dict) -> dict[str, dict]:
    gates = {g["id"]: g for g in doc.get("gates", [])}
    declared_workflows = {g.get("workflow") for g in gates.values()}

    # C1 — no undeclared Hive caller may exist.
    for wf in sorted(hive_callers() - declared_workflows):
        fail("C1", f"workflow `{wf}` invokes _hive-sim.yml but is not declared in gates.yml. "
                   "Every check is declared or it does not run.")

    for gid, g in gates.items():
        wf = g.get("workflow")
        tier = g.get("tier")

        # C2 — a gate must point at something real.
        if wf and not (WORKFLOWS / wf).exists():
            fail("C2", f"gate `{gid}` points at `{wf}`, which does not exist.")
            continue

        for field in ("owner", "covers", "tier"):
            if not g.get(field):
                fail("C2", f"gate `{gid}` is missing required field `{field}`.")

        if tier not in ("required", "informational", "quarantined"):
            fail("C2", f"gate `{gid}` has unknown tier `{tier}`.")
            continue

        text = workflow_text(wf) if wf else ""
        is_hive = wf in hive_callers() if wf else False

        if tier == "required":
            # C3 — a required check must be structurally able to fail.
            if is_hive:
                has_mechanism = (
                    (g.get("min_tests") or 0) > 0
                    or (g.get("pass_threshold") or 0) > 0
                    or bool(scalar_input(text, "gate_pattern"))
                )
                if not has_mechanism:
                    fail("C3", f"gate `{gid}` is declared required but has no failing mechanism "
                               "(min_tests / pass_threshold / gate_pattern). A required check "
                               "that cannot fail is the v0.8.0 defect.")
                # C4 — required Hive gates must cite a green run.
                if not g.get("evidence"):
                    fail("C4", f"gate `{gid}` is declared required without `evidence:` — a URL of "
                               "a green run of this workflow on `main`. Promotion needs proof, "
                               "not intent.")
        else:
            # C5 — informational/quarantined is a staging area with a deadline.
            if not g.get("issue"):
                fail("C5", f"gate `{gid}` is `{tier}` without a tracking `issue`.")
            due = g.get("promote_by")
            if not due:
                fail("C5", f"gate `{gid}` is `{tier}` without `promote_by`. The non-required tier "
                           "is a staging area with a deadline, not a parking lot.")
            else:
                d = parse_date(due, f"gate `{gid}`.promote_by", "C5")
                if d:
                    delta = (d - today()).days
                    if delta < 0:
                        fail("C5", f"gate `{gid}` was due for promotion or re-scoping on {d} "
                                   f"({-delta} days ago, issue #{g.get('issue')}). Promote it, "
                                   "or edit this entry in a reviewed PR with a new date.")
                    elif delta <= doc.get("meta", {}).get("warn_window_days", 14):
                        warn("C5", f"gate `{gid}` promotion deadline {d} is {delta} days away "
                                   f"(issue #{g.get('issue')}).")
    return gates


def check_waivers(doc: dict, gates: dict[str, dict]) -> None:
    waivers = {w["id"]: w for w in doc.get("waivers", [])}
    window = doc.get("meta", {}).get("warn_window_days", 14)

    # C6 — completeness and expiry.
    for wid, w in waivers.items():
        for field in ("gate", "pattern", "owner", "issue", "reason", "expires"):
            if not w.get(field):
                fail("C6", f"waiver `{wid}` is missing `{field}`. Incomplete waivers are rejected "
                           "at authoring time, not discovered after a bad release.")
        if w.get("gate") and w["gate"] not in gates:
            fail("C6", f"waiver `{wid}` references unknown gate `{w['gate']}`.")
        exp = w.get("expires")
        if exp:
            d = parse_date(exp, f"waiver `{wid}`.expires", "C6")
            if d:
                delta = (d - today()).days
                if delta < 0:
                    fail("C6", f"waiver `{wid}` EXPIRED on {d} ({-delta} days ago). "
                               f"owner: {w.get('owner')}, issue: #{w.get('issue')}. "
                               "Fix the test or re-authorize it in a reviewed PR with a new date. "
                               "There is no bypass.")
                elif delta <= window:
                    warn("C6", f"waiver `{wid}` expires {d} ({delta} days) — "
                               f"owner {w.get('owner')}, issue #{w.get('issue')}.")

    # C7 — every exclusion in every workflow must be declared here.
    declared_patterns = {(w.get("gate"), w.get("pattern")) for w in waivers.values()}
    for gid, g in gates.items():
        wf = g.get("workflow")
        if not wf:
            continue
        for branch in gate_exclude_branches(workflow_text(wf)):
            if (gid, branch) not in declared_patterns:
                fail("C7", f"workflow `{wf}` excludes `{branch}` from its gate, but no waiver "
                           f"declares it for gate `{gid}`. Silent, undated exclusions are how "
                           "v0.8.0 shipped red.")
    # And the reverse: a declared waiver whose pattern is no longer excluded is stale.
    for wid, w in waivers.items():
        g = gates.get(w.get("gate", ""))
        if not g or not g.get("workflow"):
            continue
        if w.get("pattern") not in gate_exclude_branches(workflow_text(g["workflow"])):
            warn("C7", f"waiver `{wid}` declares pattern `{w.get('pattern')}` but the workflow no "
                       "longer excludes it — the waiver is stale and can be deleted.")


def check_constitution(gates: dict[str, dict]) -> None:
    if not CONSTITUTION.exists():
        fail("C8", f"{CONSTITUTION.relative_to(REPO)} not found.")
        return
    if not ENFORCEMENT.exists():
        fail("C8", f"{ENFORCEMENT.relative_to(REPO)} not found — every principle must map to a "
                   "check or be labelled aspirational.")
        return

    principles = re.findall(r"^### ([IVX]+)\. (.+)$", CONSTITUTION.read_text(encoding="utf-8"),
                            re.MULTILINE)
    emap = ENFORCEMENT.read_text(encoding="utf-8")

    # C8 — coverage.
    for numeral, title in principles:
        short = title.split("(")[0].strip()
        if f"| {numeral} |" not in emap and short not in emap:
            fail("C8", f"constitution principle {numeral} ({short}) has no entry in "
                       f"{ENFORCEMENT.relative_to(REPO)}. Amending the constitution must answer "
                       "the enforcement question in the same PR.")

    # C9 — every `enforced` row must name a gate that exists.
    for row in re.findall(r"^\|.*\|$", emap, re.MULTILINE):
        if "`enforced`" not in row:
            continue
        cited = re.findall(r"`gate:([a-z0-9-]+)`", row)
        if not cited:
            fail("C9", f"enforcement row claims `enforced` but names no gate: {row.strip()[:110]}")
            continue
        for gid in cited:
            if gid not in gates:
                fail("C9", f"enforcement row cites unknown gate `{gid}`.")
            elif gates[gid].get("tier") != "required":
                fail("C9", f"principle row claims `enforced` via gate `{gid}`, but that gate's "
                           f"tier is `{gates[gid].get('tier')}`. A non-required check enforces "
                           "nothing at merge time — label the rule `aspirational` until it is.")


def check_versioning(doc: dict) -> None:
    # C10 — one scheme, one source of truth (FR-019).
    src = doc.get("canonical", {}).get("version_source", "version.sbt")
    if not (REPO / src).exists():
        fail("C10", f"declared version source `{src}` does not exist.")
        return
    rivals = ["VERSION", "VERSION.txt", ".version", "version.txt"]
    for r in rivals:
        if (REPO / r).exists():
            fail("C10", f"`{r}` introduces a second versioning scheme alongside `{src}`. "
                        "One scheme (FR-019).")


def check_badges(gates: dict[str, dict]) -> None:
    if not README.exists():
        return
    text = README.read_text(encoding="utf-8")
    # C11 — no badge may point at a workflow file that does not exist.
    for wf in sorted(set(re.findall(r"actions/workflows/([A-Za-z0-9._-]+\.yml)/badge\.svg", text))):
        if not (WORKFLOWS / wf).exists():
            fail("C11", f"README renders a badge for `{wf}`, which does not exist. A badge for a "
                        "missing workflow shows no status and reads as 'not failing'.")


VERIFY_WORDS = re.compile(r"\b(verified|fully compliant|compliance verified|all tests pass(ing)?)\b",
                          re.IGNORECASE)

# A negation immediately before the verification word turns an assertion into a
# disclaimer ("we do not claim a suite is verified unless a check can fail on
# it"). Flagging those would punish exactly the honest phrasing this check is
# meant to encourage, so we skip them.
#
# Limits, stated plainly: this is a window heuristic, not a parser. It looks
# back a short distance for a negating token. A claim deliberately phrased to
# put a negation nearby would slip through. That is an accepted false-negative
# — the check's job is to catch drift by well-meaning authors, not to defeat
# someone intent on smuggling a claim past review.
NEGATORS = re.compile(r"\b(not|never|no|cannot|can't|don't|doesn't|unless|without|"
                      r"until|isn't|aren't|rather than|instead of)\b", re.IGNORECASE)
NEGATION_WINDOW = 60  # characters before the verification word


def _is_negated(line: str, match: re.Match[str]) -> bool:
    window = line[max(0, match.start() - NEGATION_WINDOW):match.start()]
    return bool(NEGATORS.search(window))


def check_claims(gates: dict[str, dict]) -> None:
    """C12 (FR-026) — no doc may claim verification for a non-required suite."""
    required_ids = {gid for gid, g in gates.items() if g.get("tier") == "required"}
    # Suite nicknames that appear in prose, mapped to their gate id.
    suites = {gid.replace("hive-", ""): gid for gid in gates if gid.startswith("hive-")}
    suites["hive"] = "hive-sync"  # a bare "Hive-verified" claim implicates the suite as a whole

    targets = [README, REPO / "docs" / "index.md"]
    for path in targets:
        if not path.exists():
            continue
        for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            m = VERIFY_WORDS.search(line)
            if not m or _is_negated(line, m):
                continue
            low = line.lower()
            for nickname, gid in suites.items():
                if nickname in low and gid not in required_ids:
                    fail("C12", f"{path.relative_to(REPO)}:{lineno} claims verification for "
                                f"`{gid}`, whose tier is `{gates[gid].get('tier')}`. A public "
                                "claim may not exceed what a check enforces (#1403).")
                    break


def check_status_doc() -> None:
    """C13 (FR-025) — the generated status document must be current."""
    gen = REPO / "scripts" / "ci" / "generate_status.py"
    if not gen.exists():
        fail("C13", "scripts/ci/generate_status.py is missing — the status document must be "
                    "generated from the matrix, not hand-maintained.")
        return
    if not STATUS_DOC.exists():
        fail("C13", f"{STATUS_DOC.relative_to(REPO)} is missing. Run `python3 "
                    "scripts/ci/generate_status.py`.")
        return
    result = subprocess.run([sys.executable, str(gen), "--check"], capture_output=True, text=True)
    if result.returncode != 0:
        fail("C13", f"{STATUS_DOC.relative_to(REPO)} is stale relative to .github/gates.yml. "
                    "Run `python3 scripts/ci/generate_status.py` and commit the result.\n"
                    f"{result.stdout.strip()}{result.stderr.strip()}")


# ---------------------------------------------------------------------------

def main() -> int:
    if not GATES.exists():
        print(f"::error::{GATES} not found.", file=sys.stderr)
        return 1
    doc = yaml.safe_load(GATES.read_text(encoding="utf-8"))

    gates = check_gates(doc)
    check_waivers(doc, gates)
    check_constitution(gates)
    check_versioning(doc)
    check_badges(gates)
    check_claims(gates)
    check_status_doc()

    summary_lines = ["### Gate Integrity", ""]
    tiers: dict[str, int] = {}
    for g in gates.values():
        tiers[g.get("tier", "?")] = tiers.get(g.get("tier", "?"), 0) + 1
    summary_lines.append(
        "Declared gates: "
        + ", ".join(f"**{n}** {t}" for t, n in sorted(tiers.items()))
        + f" · waivers: **{len(doc.get('waivers', []))}**"
    )
    summary_lines.append("")

    for w in WARNINGS:
        print(f"::warning::{w}")
        summary_lines.append(f"- :warning: {w}")
    for f_ in FAILURES:
        print(f"::error::{f_}")
        summary_lines.append(f"- :x: {f_}")

    if not FAILURES:
        summary_lines.append("- :white_check_mark: All gate-integrity checks passed.")

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write("\n".join(summary_lines) + "\n")

    print()
    print(f"Gate integrity: {len(FAILURES)} failure(s), {len(WARNINGS)} warning(s).")
    return 1 if FAILURES else 0


if __name__ == "__main__":
    raise SystemExit(main())
