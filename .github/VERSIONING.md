# Versioning Scheme

This project follows a specific versioning scheme for releases:

## Version Format

Versions follow semantic versioning: `MAJOR.MINOR.PATCH`

## Version Increment Rules

1. **Patch (`0.0.+1`)**: `auto-version.yml` bumps the patch version on every push to `staging` (and
   `develop`). It commits `chore: bump version to X.Y.Z`, tags `vX.Y.Z` and dispatches a patch-tier
   `release.yml` (JAR and zip only).
2. **Minor (`0.+1.0`) and major**: set **on staging, before the promotion to main**, by dispatching
   auto-version by hand:
   ```bash
   gh workflow run auto-version.yml --ref staging -f increment_type=minor   # or major
   ```
   Major bumps are always a deliberate, manual decision.
3. **main never receives a bot commit.** The `Main_Protection` ruleset accepts changes to `main` only
   through pull requests, so a direct version push is rejected (`GH013`; this broke the 0.9.0
   release, run 37559165800). main's version is whatever the promotion PR merged.

## Automation

- **auto-version.yml**
  - Job `auto-version` (every branch except main/master): bumps, commits, tags and dispatches
    `release.yml` as described above.
  - Job `tag-release` (main/master): never commits. On a push it reads `version.sbt`; if `vX.Y.Z` is
    not tagged yet it tags the pushed commit and dispatches a **full-tier** `release.yml` (Docker,
    signing, SBOM). If the version is already tagged it does nothing. `gh workflow run
    auto-version.yml --ref main` re-runs the same check, which is the retry path when a tag or
    dispatch failed.
- **release.yml**: builds the release from the tag (`ref: v<version>`): distribution ZIP and assembly
  JAR, changelog, SBOM, and on the full tier signed Docker images with SLSA provenance.
- **release-drafter.yml**: keeps a draft release updated with categorized changes as PRs merge.

## Releasing a minor version (checklist)

1. Dispatch the minor bump on staging (rule 2 above) and wait for the bump commit.
2. Open the promotion PR `staging` → `main` (label `hive-full` for the full hive pass) and merge it
   with **"Create a merge commit"** once the required checks pass.
3. `tag-release` tags `vX.Y.0` on the merge commit and dispatches the full release. Verify the
   release assets.

## Manual Version Updates

Edit `(ThisBuild / version) := "X.Y.Z"` in `version.sbt` on a branch and merge it through a PR.
The next automatic bump continues from that version. On main, the merged version is tagged and
released by `tag-release` if it has no tag yet.

## Checking Current Version

```bash
# Check version in version.sbt
cat version.sbt

# Or use sbt
sbt "show version"
```

## Version History

All version tags are available in the repository:

```bash
git tag -l "v*"
```

Each tag corresponds to a release in GitHub Releases.
