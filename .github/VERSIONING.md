# Versioning Scheme

This project follows a specific versioning scheme for releases:

## Version Format

Versions follow semantic versioning: `MAJOR.MINOR.PATCH`

## Version Increment Rules

1. **Patch Version (0.0.1)**: Automatically incremented on every commit to `main`, `master`, or `develop` branches
   - Example: `0.1.0` → `0.1.1` → `0.1.2`

2. **Minor Version (0.1.0)**: Incremented at milestones, patch resets to 0
   - To trigger a milestone increment, include the word "milestone" in your commit message or PR title/labels
   - Example: `0.1.5` → `0.2.0`

3. **Major Version (1.0.0)**: Incremented at project completion
   - Manually set when the project reaches version 1.0.0
   - Example: `0.9.5` → `1.0.0`

## Automation

The versioning and release process is fully automated through GitHub Actions:

- **auto-version.yml**: Automatically increments the version in `version.sbt` on every commit/merged PR and creates version tags
- **release.yml**: Creates GitHub releases when version tags are pushed, including:
  - Distribution ZIP and assembly JAR
  - Auto-generated CHANGELOG from commit history
  - SBOM (Software Bill of Materials)
  - Signed Docker images with SLSA provenance
- **release-drafter.yml**: Maintains draft releases with categorized changes as PRs are merged

**How It Works:**
1. When you merge a PR to main/master/develop, `auto-version.yml` automatically:
   - Increments the version (patch by default, minor if "milestone" label/keyword)
   - Creates and pushes a version tag (e.g., `v0.1.1`)
2. The version tag triggers `release.yml` which creates a full release with all artifacts
3. `release-drafter.yml` keeps a draft release updated with categorized changes

**Note:** Every merge to main/master/develop creates a new release. If you want to batch changes, work in feature branches and only merge to main when ready to release.

## Manual Version Updates

If you need to manually update the version:

1. Edit `version.sbt`
2. Update the version string: `(ThisBuild / version) := "X.Y.Z"`
3. Commit and push

The next auto-increment will continue from your manually set version.

## Checking Current Version

```bash
# Check version in version.sbt
cat version.sbt

# Or use sbt
sbt "show version"
```

## Creating a Milestone Release

To mark a commit as a milestone and trigger a minor version increment:

1. **For direct commits**: Include "milestone" in your commit message
   ```bash
   git commit -m "feat: implement feature X [milestone]"
   ```

2. **For Pull Requests**: 
   - Add "milestone" to the PR title, OR
   - Add a "milestone" label to the PR

## Version History

All version tags are available in the repository:

```bash
git tag -l "v*"
```

Each tag corresponds to a release in GitHub Releases.
