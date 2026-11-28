#!/usr/bin/env python3
"""Assemble CHANGELOG.md from the one-file-per-PR fragments in changelog.d/ (B-144).

Parallel PRs that each edited CHANGELOG.md's [Unreleased] section conflicted with one another, and a conflict cost a
replacement PR (force-push is not allowed). A PR now adds a fragment instead:

    changelog.d/<id>.<section>.md

<id> is the backlog id (B-144), the PR number (1750) or a slug (`snap-heal-docs`): letters, digits, `-` and `_`.
<section> is one of the keys of SECTIONS below and picks the `### <Heading>` the entry goes under. The file holds the
entry exactly as it would appear in CHANGELOG.md: a Markdown bullet (`- **Lead sentence (B-144).** ...`), wrapped and
indented as the CHANGELOG is, sub-bullets included.

Usage:
    scripts/changelog/assemble.py --check                    # validate the fragments; writes nothing (CI)
    scripts/changelog/assemble.py --dry-run                  # print the diff the merge would make; writes nothing
    scripts/changelog/assemble.py                            # merge the fragments into [Unreleased], delete them
    scripts/changelog/assemble.py --version 0.13.0 [--date 2026-12-01]
                                                             # same, then [Unreleased] becomes `## [0.13.0] - <date>`
                                                             # under a new, empty [Unreleased] (release prep)

Each fragment is appended after the existing entries of its heading, in natural order of <id>, so the output does not
depend on the order the files were created in. A heading the target section lacks is created at its place in
SECTIONS order. An unknown section, a malformed name, an empty fragment or one that is not a bullet fails the run
before anything is written. Standard library only; run it as `python3 -I`.
"""
from __future__ import annotations

import argparse
import datetime as _dt
import difflib
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CHANGELOG = ROOT / "CHANGELOG.md"
FRAGMENTS = ROOT / "changelog.d"

# Fragment section key -> CHANGELOG heading, in the order headings appear in a version section. The first six are the
# [Unreleased] headings in use; Deprecated and Removed are Keep a Changelog's (0.8.8 has a Removed section).
SECTIONS: dict[str, str] = {
    "security": "Security",
    "breaking": "Breaking",
    "changed": "Changed",
    "added": "Added",
    "deprecated": "Deprecated",
    "removed": "Removed",
    "fixed": "Fixed",
    "known-issues": "Known issues",
}
RANK = {heading: i for i, heading in enumerate(SECTIONS.values())}

NAME = re.compile(r"^(?P<id>[A-Za-z0-9][A-Za-z0-9_-]*)\.(?P<section>[a-z][a-z-]*)\.md$")
IGNORED = {"README.md"}
UNRELEASED = "## [Unreleased]"
VERSION = re.compile(r"^\d+\.\d+\.\d+$")


class AssembleError(Exception):
    pass


@dataclass(frozen=True)
class Fragment:
    path: Path
    id: str
    section: str  # the key in SECTIONS
    text: str  # stripped of trailing whitespace, no final newline

    @property
    def heading(self) -> str:
        return SECTIONS[self.section]


def natural_key(s: str) -> list:
    return [(0, int(p), "") if p.isdigit() else (1, 0, p.lower()) for p in re.split(r"(\d+)", s) if p]


def load_fragments(directory: Path) -> tuple[list[Fragment], list[str]]:
    """Every fragment in `directory`, sorted, and every problem found. Dotfiles and README.md are skipped."""
    fragments: list[Fragment] = []
    errors: list[str] = []
    if not directory.is_dir():
        return fragments, errors
    for path in sorted(directory.iterdir()):
        name = path.name
        if name.startswith(".") or name in IGNORED:
            continue
        if not path.is_file():
            errors.append(f"{name}: not a file")
            continue
        m = NAME.match(name)
        if not m:
            errors.append(f"{name}: name must be <id>.<section>.md, <id> of letters, digits, '-' and '_'")
            continue
        section = m["section"]
        if section not in SECTIONS:
            errors.append(f"{name}: unknown section '{section}'; one of: {', '.join(SECTIONS)}")
            continue
        try:
            raw = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            errors.append(f"{name}: not UTF-8")
            continue
        text = "\n".join(line.rstrip() for line in raw.strip("\n").splitlines()).strip("\n")
        if not text.strip():
            errors.append(f"{name}: empty")
            continue
        if not text.startswith("- "):
            errors.append(f"{name}: must start with a Markdown bullet ('- '), as the CHANGELOG entry would")
            continue
        if re.search(r"^#{1,3} ", text, re.MULTILINE):
            errors.append(f"{name}: must not contain a heading; the section comes from the file name")
            continue
        fragments.append(Fragment(path, m["id"], section, text))
    fragments.sort(key=lambda f: (RANK[f.heading], natural_key(f.id), f.id))
    return fragments, errors


def _unreleased_bounds(lines: list[str]) -> tuple[int, int]:
    """[start, end) of the [Unreleased] section: its `##` line up to the next `##` line or the end."""
    starts = [i for i, line in enumerate(lines) if line.rstrip() == UNRELEASED]
    if len(starts) != 1:
        raise AssembleError(f"CHANGELOG must have exactly one '{UNRELEASED}' line, found {len(starts)}")
    start = starts[0]
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    return start, end


def _last_content(lines: list[str], lo: int, hi: int) -> int:
    """Index just past the last non-blank line in lines[lo:hi], or lo if there is none."""
    i = hi
    while i > lo and not lines[i - 1].strip():
        i -= 1
    return i


def merge(changelog: str, fragments: list[Fragment]) -> str:
    """Return `changelog` with every fragment appended under its heading in [Unreleased]."""
    lines = changelog.split("\n")
    for frag in fragments:  # already in heading order, then id order
        start, end = _unreleased_bounds(lines)
        heading_line = f"### {frag.heading}"
        headings = [i for i in range(start + 1, end) if lines[i].startswith("### ")]
        match = [i for i in headings if lines[i].rstrip() == heading_line]
        body = frag.text.split("\n")
        if match:
            h = match[0]
            nxt = next((i for i in headings if i > h), end)
            at = _last_content(lines, h + 1, nxt)
            lines[at:at] = body
            continue
        # Create the heading before the first known heading that ranks after it, else at the end of the section.
        rank = RANK[frag.heading]
        later = [i for i in headings if RANK.get(lines[i][4:].rstrip(), -1) > rank]
        if later:
            at = later[0]
            lines[at:at] = [heading_line, *body, ""]
        else:
            at = _last_content(lines, start + 1, end)
            lines[at:at] = ["", heading_line, *body]
    return "\n".join(lines)


def cut_release(changelog: str, version: str, date: str) -> str:
    """Rename [Unreleased] to `## [version] - date` and open a new, empty [Unreleased] above it."""
    if not VERSION.match(version):
        raise AssembleError(f"--version must be X.Y.Z, got '{version}'")
    try:
        _dt.date.fromisoformat(date)
    except ValueError:
        raise AssembleError(f"--date must be YYYY-MM-DD, got '{date}'") from None
    lines = changelog.split("\n")
    if any(line.startswith(f"## [{version}]") for line in lines):
        raise AssembleError(f"CHANGELOG already has a section for {version}")
    start, end = _unreleased_bounds(lines)
    if _last_content(lines, start + 1, end) == start + 1:
        raise AssembleError("[Unreleased] is empty; nothing to release")
    lines[start : start + 1] = [UNRELEASED, "", f"## [{version}] - {date}"]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="validate the fragments and exit; writes nothing")
    mode.add_argument("--dry-run", action="store_true", help="print the diff the merge would make; writes nothing")
    ap.add_argument("--version", help="release prep: turn [Unreleased] into this version's section (X.Y.Z)")
    ap.add_argument("--date", help="the release date for --version (YYYY-MM-DD, default today, UTC)")
    ap.add_argument("--changelog", type=Path, default=CHANGELOG, help=argparse.SUPPRESS)
    ap.add_argument("--fragments", type=Path, default=FRAGMENTS, help=argparse.SUPPRESS)
    args = ap.parse_args(argv)
    if args.date and not args.version:
        ap.error("--date needs --version")

    fragments, errors = load_fragments(args.fragments)
    for e in errors:
        print(f"::error file=changelog.d/{e.split(':', 1)[0]}::{e}", file=sys.stderr)
    if errors:
        print(f"{len(errors)} bad fragment(s) in {args.fragments}; nothing written", file=sys.stderr)
        return 1

    try:
        before = args.changelog.read_text(encoding="utf-8")
        after = merge(before, fragments)
        if args.version:
            date = args.date or _dt.datetime.now(_dt.timezone.utc).date().isoformat()
            after = cut_release(after, args.version, date)
    except (AssembleError, OSError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    if args.check:
        print(f"{len(fragments)} fragment(s) OK")
        return 0
    if args.dry_run:
        sys.stdout.writelines(
            difflib.unified_diff(
                before.splitlines(keepends=True), after.splitlines(keepends=True), "CHANGELOG.md", "CHANGELOG.md"
            )
        )
        print(f"\n(dry run: {len(fragments)} fragment(s) would be merged and deleted)", file=sys.stderr)
        return 0

    args.changelog.write_text(after, encoding="utf-8")  # write first: a failure here leaves every fragment in place
    for frag in fragments:
        frag.path.unlink()
    print(f"merged and deleted {len(fragments)} fragment(s)" + (f"; cut {args.version}" if args.version else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
