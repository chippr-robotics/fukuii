#!/usr/bin/env python3
"""Self-test for scripts/changelog/assemble.py. Each case works in a temporary directory, never on the repository's
CHANGELOG.md or changelog.d/.

    python3 -I -m unittest discover -s scripts/changelog -v
"""
from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))  # `python3 -I` drops the script directory from sys.path
import assemble  # noqa: E402

BASE = """# Changelog

Intro.

## [Unreleased]

### Security
- Old security entry.
### Changed
- Old changed entry,
  wrapped.

### Fixed
- Old fixed entry.

## [0.10.0] - 2026-10-20

### Fixed
- A released fix.
"""


class AssembleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(self.tmp))
        self.changelog = self.tmp / "CHANGELOG.md"
        self.changelog.write_text(BASE, encoding="utf-8")
        self.frags = self.tmp / "changelog.d"
        self.frags.mkdir()
        (self.frags / "README.md").write_text("# not a fragment\n", encoding="utf-8")
        (self.frags / ".gitkeep").write_text("", encoding="utf-8")

    def frag(self, name: str, text: str) -> Path:
        p = self.frags / name
        p.write_text(text, encoding="utf-8")
        return p

    def run_main(self, *args: str) -> tuple[int, str, str]:
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = assemble.main(["--changelog", str(self.changelog), "--fragments", str(self.frags), *args])
        return code, out.getvalue(), err.getvalue()

    def unreleased(self) -> str:
        text = self.changelog.read_text(encoding="utf-8")
        return text.split("## [Unreleased]\n", 1)[1].split("\n## [", 1)[0]

    # --- merge -----------------------------------------------------------------------------------------------------

    def test_appends_under_existing_heading_and_deletes_fragments(self) -> None:
        a = self.frag("B-144.changed.md", "- New changed entry (B-144).\n")
        b = self.frag("1750.fixed.md", "- New fix,\n  wrapped (#1750).\n\n")
        code, _, err = self.run_main()
        self.assertEqual(code, 0, err)
        self.assertEqual(
            self.unreleased(),
            "\n### Security\n- Old security entry.\n### Changed\n- Old changed entry,\n  wrapped.\n"
            "- New changed entry (B-144).\n\n### Fixed\n- Old fixed entry.\n- New fix,\n  wrapped (#1750).\n",
        )
        self.assertFalse(a.exists() or b.exists())
        self.assertTrue((self.frags / "README.md").exists())
        self.assertIn("## [0.10.0] - 2026-10-20\n\n### Fixed\n- A released fix.\n", self.changelog.read_text())

    def test_entry_directly_before_next_heading(self) -> None:
        # Security has no blank line before ### Changed; the entry still lands inside Security.
        self.frag("B-1.security.md", "- New security entry.")
        self.assertEqual(self.run_main()[0], 0)
        self.assertIn("- Old security entry.\n- New security entry.\n### Changed\n", self.unreleased())

    def test_stable_natural_order_by_id(self) -> None:
        for name in ("B-144.fixed.md", "B-99.fixed.md", "1750.fixed.md", "a-slug.fixed.md"):
            self.frag(name, f"- from {name}\n")
        self.assertEqual(self.run_main()[0], 0)
        fixed = self.unreleased().split("### Fixed\n", 1)[1]
        self.assertEqual(
            fixed,
            "- Old fixed entry.\n- from 1750.fixed.md\n- from a-slug.fixed.md\n- from B-99.fixed.md\n"
            "- from B-144.fixed.md\n",
        )

    def test_missing_heading_created_in_canonical_order(self) -> None:
        self.frag("B-2.added.md", "- New added entry.\n")  # between Changed and Fixed
        self.frag("B-3.known-issues.md", "- New known issue.\n")  # after Fixed, the last heading
        self.frag("B-4.breaking.md", "- New breaking entry.\n")  # between Security and Changed
        self.assertEqual(self.run_main()[0], 0)
        u = self.unreleased()
        order = [u.index(h) for h in ("### Security", "### Breaking", "### Changed", "### Added", "### Fixed",
                                      "### Known issues")]
        self.assertEqual(order, sorted(order))
        self.assertIn("### Added\n- New added entry.\n\n### Fixed\n", u)
        self.assertIn("### Breaking\n- New breaking entry.\n\n### Changed\n", u)
        self.assertTrue(u.endswith("- Old fixed entry.\n\n### Known issues\n- New known issue.\n"))

    def test_no_fragments_is_a_no_op(self) -> None:
        self.assertEqual(self.run_main()[0], 0)
        self.assertEqual(self.changelog.read_text(encoding="utf-8"), BASE)

    # --- release ---------------------------------------------------------------------------------------------------

    def test_version_cuts_release_section(self) -> None:
        self.frag("B-144.added.md", "- Fragments.\n")
        code, out, err = self.run_main("--version", "0.13.0", "--date", "2026-12-01")
        self.assertEqual(code, 0, err)
        text = self.changelog.read_text(encoding="utf-8")
        self.assertIn("## [Unreleased]\n\n## [0.13.0] - 2026-12-01\n\n### Security\n", text)
        self.assertIn("### Added\n- Fragments.\n\n### Fixed\n", text)
        self.assertIn("cut 0.13.0", out)

    def test_version_refuses_duplicate_and_bad_input(self) -> None:
        for args in (("--version", "0.10.0"), ("--version", "1.0"), ("--version", "1.0.0", "--date", "26-12-01")):
            code, _, err = self.run_main(*args)
            self.assertEqual(code, 1, args)
            self.assertIn("error:", err)
        self.assertEqual(self.changelog.read_text(encoding="utf-8"), BASE)

    def test_version_refuses_empty_unreleased(self) -> None:
        self.changelog.write_text("# Changelog\n\n## [Unreleased]\n\n## [0.1.0] - 2020-01-01\n- x\n")
        code, _, err = self.run_main("--version", "0.2.0", "--date", "2026-12-01")
        self.assertEqual(code, 1)
        self.assertIn("empty", err)

    # --- validation ------------------------------------------------------------------------------------------------

    def test_check_accepts_good_fragments_and_writes_nothing(self) -> None:
        p = self.frag("B-144.changed.md", "- Fine.\n")
        code, out, _ = self.run_main("--check")
        self.assertEqual(code, 0)
        self.assertIn("1 fragment(s) OK", out)
        self.assertTrue(p.exists())
        self.assertEqual(self.changelog.read_text(encoding="utf-8"), BASE)

    def test_bad_fragments_fail_loudly_and_nothing_is_written(self) -> None:
        good = self.frag("B-1.fixed.md", "- Good.\n")
        cases = {
            "B-2.features.md": "unknown section 'features'",
            "B-3.md": "name must be",
            "B 4.fixed.md": "name must be",
            "B-5.fixed.txt": "name must be",
            "B-6.fixed.md": "empty",
            "B-7.fixed.md": "must start with a Markdown bullet",
            "B-8.fixed.md": "must not contain a heading",
        }
        content = {"B-6.fixed.md": "\n  \n", "B-7.fixed.md": "Not a bullet.\n", "B-8.fixed.md": "- x\n### Fixed\n"}
        for name in cases:
            self.frag(name, content.get(name, "- x\n"))
        for args in ((), ("--check",), ("--dry-run",)):
            code, _, err = self.run_main(*args)
            self.assertEqual(code, 1, args)
            for name, msg in cases.items():
                self.assertIn(f"{name}: {msg}", err)
        self.assertTrue(good.exists())
        self.assertEqual(self.changelog.read_text(encoding="utf-8"), BASE)

    def test_changelog_without_unreleased_fails(self) -> None:
        self.changelog.write_text("# Changelog\n\n## [0.1.0] - 2020-01-01\n- x\n")
        p = self.frag("B-1.fixed.md", "- Good.\n")
        code, _, err = self.run_main("--check")
        self.assertEqual(code, 1)
        self.assertIn("exactly one '## [Unreleased]'", err)
        self.assertTrue(p.exists())

    def test_dry_run_prints_diff_and_writes_nothing(self) -> None:
        p = self.frag("B-144.changed.md", "- New changed entry (B-144).\n")
        code, out, _ = self.run_main("--dry-run")
        self.assertEqual(code, 0)
        self.assertIn("+- New changed entry (B-144).", out)
        self.assertTrue(p.exists())
        self.assertEqual(self.changelog.read_text(encoding="utf-8"), BASE)

    def test_section_map_covers_every_heading_in_use(self) -> None:
        # Every ### heading the repository's CHANGELOG uses must be reachable from a fragment.
        real = (HERE.parents[1] / "CHANGELOG.md").read_text(encoding="utf-8")
        used = {line[4:].strip() for line in real.splitlines() if line.startswith("### ")}
        self.assertLessEqual(used, set(assemble.SECTIONS.values()))

    def test_repository_fragments_are_valid(self) -> None:
        _, errors = assemble.load_fragments(assemble.FRAGMENTS)
        self.assertEqual(errors, [])


if __name__ == "__main__":
    unittest.main()
