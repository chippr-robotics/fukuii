# Changelog fragments

Do not edit `CHANGELOG.md`'s `[Unreleased]` section in a PR: parallel PRs conflict there, and a conflict costs a
replacement PR. Add one file here per entry instead; it is merged into `CHANGELOG.md` when a release is prepared.

**Name:** `<id>.<section>.md`

- `<id>`: the backlog id (`B-144`), else the PR number (`1750`), else a short slug (`snap-heal-docs`). Letters,
  digits, `-` and `_`. Two entries for one id use two ids (`B-144`, `B-144-docs`).
- `<section>`: the `###` heading the entry goes under.

| `<section>` | Heading |
|-------------|---------|
| `security` | Security |
| `breaking` | Breaking |
| `changed` | Changed |
| `added` | Added |
| `deprecated` | Deprecated |
| `removed` | Removed |
| `fixed` | Fixed |
| `known-issues` | Known issues |

**Content:** the entry exactly as it would appear in `CHANGELOG.md`: one Markdown bullet (sub-bullets allowed),
wrapped at 120 columns with a two-space continuation indent, the lead in bold with the backlog id, no heading.

```markdown
- **CHANGELOG entries are fragments (B-144).** A PR adds `changelog.d/<id>.<section>.md` ...
```

**Check:** `python3 -I scripts/changelog/assemble.py --check` (the Changelog Fragments workflow runs it on PRs that
touch this directory). `--dry-run` prints the diff the merge would make.

**Release prep:** `python3 -I scripts/changelog/assemble.py --version X.Y.Z` merges every fragment into
`[Unreleased]`, turns that into `## [X.Y.Z] - <date>` under a new, empty `[Unreleased]`, and deletes the fragments
(`.github/VERSIONING.md`, "Releasing a minor version").
