# Branch Protection and GitHub Actions Setup

This document describes the GitHub Actions workflows and branch protection rules configured for this project.

## GitHub Actions Workflows

### CI Workflow (`.github/workflows/ci.yml`)

Runs on every push to main branches and pull requests. This workflow:

1. **Compiles all modules** - Ensures all Scala code compiles successfully
2. **Checks code formatting** - Validates code style with scalafmt and scalafix
3. **Runs scalastyle** - Checks code quality and style
4. **Runs tests** - Executes all test suites across modules (bytes, crypto, rlp, node)
5. **Builds distribution** - Creates the distributable zip package
6. **Uploads artifacts** - Saves build artifacts for download

### Docker Build Workflow (`.github/workflows/docker.yml`)

Builds and publishes Docker images:

1. **Base image** (`fukuii-base`) - Foundation image with dependencies
2. **Dev image** (`fukuii-dev`) - Development environment
3. **Main image** (`fukuii`) - Production-ready application image

Images are pushed to GitHub Container Registry (ghcr.io) on:
- Push to main/master/develop branches
- Creation of version tags (v*)

### Release Workflow (`.github/workflows/release.yml`)

Triggered when a version tag is pushed (e.g., `v1.0.0`):

1. **Builds distribution** - Creates optimized production build
2. **Creates GitHub Release** - Generates release notes and attaches artifacts
3. **Closes milestone** - Automatically closes the matching milestone

### PR Management Workflow (`.github/workflows/pr-management.yml`)

Helps maintain project hygiene:

1. **Auto-labels PRs** - Applies labels based on changed files
2. **Checks milestone assignment** - Warns if PR has no milestone
3. **Checks issue linking** - Reminds to link issues in PR description

## Setting Up Branch Protection Rules

To ensure good project hygiene, configure the following branch protection rules for your main branch:

### Recommended Settings for `main` or `master` branch

1. **Navigate to Repository Settings** → **Branches** → **Add branch protection rule**

2. **Branch name pattern**: `main` (or `master`)

3. **Enable the following settings**:

   ☑️ **Require a pull request before merging**
   - Require approvals: 1 (adjust based on team size)
   - Dismiss stale pull request approvals when new commits are pushed

   ☑️ **Require status checks to pass before merging**
   - Require branches to be up to date before merging
   - Status checks to require:
     - `Test and Build` (from CI workflow)
     - `Build Docker Images` (from Docker workflow)

   ☑️ **Require conversation resolution before merging**
   - Ensures all review comments are addressed

   ☑️ **Require linear history** (optional)
   - Prevents merge commits, enforces rebase or squash

   ☑️ **Do not allow bypassing the above settings**
   - Applies rules to administrators as well

   ☑️ **Restrict who can push to matching branches** (optional)
   - Limit direct pushes to specific teams/users

### Quick Setup via GitHub CLI

If you have the GitHub CLI installed, you can configure branch protection with:

```bash
# Install GitHub CLI first if needed
# https://cli.github.com/

gh api repos/{owner}/{repo}/branches/main/protection \
  --method PUT \
  --field required_status_checks='{"strict":true,"contexts":["Test and Build","Build Docker Images"]}' \
  --field enforce_admins=true \
  --field required_pull_request_reviews='{"required_approving_review_count":1,"dismiss_stale_reviews":true}' \
  --field required_conversation_resolution=true \
  --field restrictions=null
```

Replace `{owner}` and `{repo}` with your repository details.

## Creating Milestones

Milestones help track features and releases:

1. **Navigate to Issues** → **Milestones** → **New milestone**
2. **Create milestone** with version number (e.g., "v1.0.0" or "Sprint 1")
3. **Assign issues and PRs** to milestones as you work
4. **Release workflow** will automatically close milestones when matching version is tagged

### Milestone Naming Convention

- For version releases: `v1.0.0`, `v1.1.0`, etc.
- For sprints/iterations: `Sprint 1`, `Q4 2024`, etc.
- For features: `Feature: Authentication`, `Feature: API v2`, etc.

## Using Labels

The PR Management workflow automatically applies labels based on file changes:

- `documentation` - Changes to markdown files or docs
- `dependencies` - Updates to build dependencies
- `docker` - Docker-related changes
- `ci/cd` - CI/CD pipeline changes
- `tests` - Test file changes
- `crypto`, `bytes`, `rlp`, `core` - Module-specific changes
- `configuration` - Config file changes
- `build` - Build system changes

You can also manually add labels like:
- `bug` - Bug fixes
- `enhancement` - New features
- `breaking-change` - Breaking API changes
- `good-first-issue` - Good for newcomers

## Creating a Release

To create a new release:

1. **Update version** in `version.sbt` (if applicable)
2. **Commit and push** changes
3. **Create and push a tag**:
   ```bash
   git tag -a v1.0.0 -m "Release version 1.0.0"
   git push origin v1.0.0
   ```
4. **Release workflow** will automatically:
   - Build the distribution
   - Create GitHub release with notes
   - Attach build artifacts
   - Close matching milestone

## Running Checks Locally

Before pushing, you can run the same checks locally:

```bash
# Compile all modules
sbt compile-all

# Check formatting
sbt formatCheck

# Run scalastyle
sbt bytes/scalastyle crypto/scalastyle rlp/scalastyle scalastyle

# Run all tests
sbt testAll

# Build distribution
sbt dist
```

Or use the combined alias:

```bash
# Run all checks (format, style, tests)
sbt pp
```

## Troubleshooting

### CI Workflow Fails

- Check the workflow logs in the Actions tab
- Ensure all dependencies are properly defined
- Run checks locally first: `sbt pp`

### Docker Build Fails

- Verify Dockerfiles are valid
- Check that base images exist
- Ensure proper build context

### Release Workflow Doesn't Close Milestone

- Verify milestone name matches tag (e.g., tag `v1.0.0` → milestone `v1.0.0` or `1.0.0`)
- Check workflow permissions in repository settings

## Additional Resources

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Branch Protection Rules](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/defining-the-mergeability-of-pull-requests/about-protected-branches)
- [GitHub Milestones](https://docs.github.com/en/issues/using-labels-and-milestones-to-track-work/about-milestones)
