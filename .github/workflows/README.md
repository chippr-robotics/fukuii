# GitHub Actions Workflows

This directory contains the GitHub Actions workflows for continuous integration, deployment, and project management.

## Workflows Overview

### 📖 Documentation Workflow (`gh-pages.yml`)

**Triggers:** Push to main/master/develop branches (docs changes), Pull Requests (build only), Manual dispatch

**Purpose:** Deploys documentation from the `docs/` folder to GitHub Pages

**URL:** [https://chippr-robotics.github.io/fukuii/](https://chippr-robotics.github.io/fukuii/)

**Steps:**
1. Checks out code
2. Configures GitHub Pages
3. Builds documentation with Jekyll
4. Deploys to GitHub Pages (on push only, not PRs)

**Notes:**
- Documentation is kept in the same branch as code for AI agents to consume
- Pull requests only build documentation (for validation) but do not deploy
- Uses the GitHub Pages deployment API for modern, artifact-based deployment
- Jekyll configuration is in `docs/_config.yml`

---

### 🧪 CI Workflow (`ci.yml`)

**Triggers:** Push to main/master/develop branches, Pull Requests

**Purpose:** Ensures code quality and tests pass before merging

**Matrix Build:**
- **JDK Version:** 21
- **Operating System:** ubuntu-latest
- **Caching:** Coursier, Ivy, and SBT for faster builds

**Steps:**
1. Checks out code with submodules
2. Sets up Java (21) with Temurin distribution
3. Configures Coursier and Ivy caching
4. Installs SBT
5. Compiles all modules (bytes, crypto, rlp, node)
6. Checks code formatting (scalafmt/scalafix)
7. Runs scalastyle checks
8. Executes all tests
9. Builds assembly artifacts
10. Builds distribution package
11. Uploads test results and build artifacts

**Artifacts Published:**
- Test results
- Distribution packages
- Assembly JARs

**Required Status Check:** Yes - Must pass before merging to protected branches

---

### ⚡ Fast Distro Workflow (`fast-distro.yml`)

**Triggers:** Nightly schedule (2 AM UTC), Manual dispatch

**Purpose:** Creates distribution packages quickly without running the full test suite, suitable for nightly releases

**Steps:**
1. Compiles production code only (bytes, crypto, rlp, node) - skips test compilation
2. Builds assembly JAR (standalone executable)
3. Builds distribution package (ZIP)
4. Creates timestamped artifacts
5. Uploads artifacts with 30-day retention
6. Creates nightly pre-release on GitHub (for scheduled runs)

**Artifacts Published:**
- Distribution ZIP with nightly version timestamp
- Assembly JAR with nightly version timestamp

**Use Cases:**
- Nightly builds for testing and development
- Quick distribution builds without waiting for full test suite
- Intermediate builds for stakeholders

**Note:** This workflow intentionally skips the full test suite and test compilation for faster builds. Uses `FUKUII_DEV: true` to speed up compilation by disabling production optimizations and fatal warnings. The full test suite has some tests that are excluded in `build.sbt`. This workflow is suitable for development and testing purposes only. For production releases, use the standard release workflow (`release.yml`).

**Manual Trigger:**
```bash
# Via GitHub UI: Actions → Fast Distro → Run workflow
# Or use GitHub CLI:
gh workflow run fast-distro.yml
```

---

### 🐳 Docker Build Workflow (`docker.yml`)

**Triggers:** Push to main branches, version tags, Pull Requests

**Purpose:** Builds and publishes development Docker images to GitHub Container Registry

**Images Built:**
- `fukuii-base`: Base OS and dependencies
- `fukuii-dev`: Development environment
- `fukuii`: Production application image

**Registry:** `ghcr.io/chippr-robotics/fukuii` (Development builds)

**Tags:**
- Branch name (e.g., `main`, `develop`)
- Pull request number (e.g., `pr-123`)
- Semantic version (e.g., `1.0.0`, `1.0`) - from tags
- Git SHA (e.g., `sha-abc123`)
- `latest` (default branch only)

**Note:** Development images built by this workflow are **not signed** and do **not include provenance attestations**. For production deployments, use release images from `ghcr.io/chippr-robotics/chordodes_fukuii` which are built by `release.yml` with full security features.

---

### 🚀 Release Workflow (`release.yml`)

**Triggers:** Git tags starting with `v` (e.g., `v1.0.0`), Manual dispatch

**Purpose:** Creates GitHub releases with full traceability, builds artifacts, generates CHANGELOG, and publishes signed container images

**Steps:**
1. Builds optimized production distribution (ZIP)
2. Builds assembly JAR (standalone executable)
3. Extracts version from tag
4. Generates SBOM (Software Bill of Materials) in CycloneDX format
5. Generates CHANGELOG.md from commits since last release
6. Creates GitHub release with all artifacts
7. Builds and publishes Docker image to `ghcr.io/chippr-robotics/chordodes_fukuii`
8. Signs image with Cosign (keyless, using GitHub OIDC)
9. Generates SLSA Level 3 provenance attestations
10. Logs immutable image digest and tags
11. Closes matching milestone (for stable releases)

**Release Artifacts:**
- ✅ **Distribution ZIP:** Complete package with scripts, configs, and dependencies
- ✅ **Assembly JAR:** Standalone executable JAR file
- ✅ **SBOM:** Software Bill of Materials in CycloneDX JSON format
- ✅ **CHANGELOG:** Automatically generated from commit history
- ✅ **Docker Image:** Signed container image with SBOM and provenance

**Container Security Features:**
- ✅ **Image Signing:** Uses [Cosign](https://docs.sigstore.dev/cosign/overview/) with keyless signing (GitHub OIDC)
- ✅ **SLSA Provenance:** Generates [SLSA Level 3](https://slsa.dev/spec/v1.0/levels) attestations for build integrity
- ✅ **SBOM:** Includes Software Bill of Materials in SPDX format
- ✅ **Immutable Digests:** Outputs `sha256` digest for tamper-proof image references

**Image Tags:**
- `v1.0.0` - Full semantic version
- `1.0` - Major.minor version
- `1` - Major version (not applied to v0.x releases)
- `latest` - Latest stable release (excludes alpha/beta/rc)

**Pre-release Detection:** Tags containing `alpha`, `beta`, or `rc` are marked as pre-releases

**Verification Example:**
```bash
# Pull and verify a signed release image
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0

cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/chordodes_fukuii:v1.0.0
```

**Usage:**
```bash
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin v1.0.0
```

---

### 📦 Launchpad PPA Workflow (`launchpad-ppa.yml`)

**Triggers:** Release published, Manual dispatch

**Purpose:** Publishes Fukuii packages to Ubuntu's Launchpad Personal Package Archive (PPA) for easy installation via `apt`

**Steps:**
1. Triggered automatically when a GitHub release is published
2. Imports GPG signing key from repository secrets
3. Updates debian/changelog for target Ubuntu series
4. Builds source package with dpkg-buildpackage
5. Signs package with GPG
6. Uploads to Launchpad PPA via dput
7. Launchpad builds and publishes the package

**Target Ubuntu Series:**
- Ubuntu 22.04 LTS (Jammy Jellyfish)
- Ubuntu 24.04 LTS (Noble Numbat)

**Required Secrets:**
- `LAUNCHPAD_GPG_PRIVATE_KEY` - GPG private key for signing packages
- `LAUNCHPAD_GPG_PASSPHRASE` - Passphrase for the GPG key

**Installation (for end users):**
```bash
sudo add-apt-repository ppa:chippr-robotics/fukuii
sudo apt-get update
sudo apt-get install fukuii
```

**Manual Trigger:**
```bash
# Via GitHub UI: Actions → Launchpad PPA → Run workflow
# Specify version (e.g., 0.1.71)
```

**Notes:**
- Packages are built on Launchpad's infrastructure after upload
- Build status can be monitored at https://launchpad.net/~chippr-robotics/+archive/ubuntu/fukuii
- The PPA must be created manually on Launchpad before first use (see setup instructions below)

---

### 📝 Release Drafter Workflow (`release-drafter.yml`)

**Triggers:** Push to main/master/develop branches, Pull Request updates

**Purpose:** Automatically generates and maintains draft releases with categorized changelog

**Features:**
1. **Auto-categorization:** Groups changes by type (Features, Bug Fixes, Security, etc.)
2. **Draft Releases:** Creates and updates draft releases as PRs are merged
3. **Version Management:** Suggests next version based on labels (major, minor, patch)
4. **Contributor Attribution:** Automatically lists all contributors

**Categories:**
- 🚀 Features
- 🐛 Bug Fixes
- 🔒 Security
- 📚 Documentation
- 🏗️ Build & CI/CD
- 🔧 Maintenance
- ⚡ Performance
- 🧪 Testing

**Label-based Versioning:**
- Labels `major` or `breaking` → Major version bump (1.0.0 → 2.0.0)
- Labels `minor`, `feature`, or `milestone` → Minor version bump (1.0.0 → 1.1.0)
- Default → Patch version bump (1.0.0 → 1.0.1)

**Usage:** Simply merge PRs to main/master/develop. Release Drafter will automatically update the draft release. When ready to publish, create and push a version tag.

---

### 🏷️ PR Management Workflow (`pr-management.yml`)

**Triggers:** Pull Request events

**Purpose:** Automates PR labeling and ensures project hygiene

**Features:**
1. **Auto-labeling:** Labels PRs based on changed files
2. **Milestone check:** Warns if PR has no milestone
3. **Issue linking:** Reminds to link issues in PR description

**Labels Applied:**

**Agent Labels:** (see [AGENT_LABELS.md](../AGENT_LABELS.md) for details)
- `agent: wraith 👻` - Compilation errors and Scala 3 migration
- `agent: mithril ✨` - Code modernization and Scala 3 features
- `agent: ICE 🧊` - Large-scale migrations and strategic planning
- `agent: eye 👁️` - Testing, validation, and quality assurance
- `agent: forge 🔨` - Consensus-critical code (EVM, mining, crypto)

**Standard Labels:**
- `documentation` - Markdown and doc changes
- `dependencies` - Dependency updates
- `docker` - Docker-related changes
- `ci/cd` - CI/CD pipeline changes
- `tests` - Test file changes
- `crypto`, `bytes`, `rlp`, `core` - Module-specific changes
- `configuration` - Config file changes
- `build` - Build system changes

---

### 📦 Dependency Check Workflow (`dependency-check.yml`)

**Triggers:** Weekly (Mondays at 9 AM UTC), Manual dispatch, Dependency file changes in PRs

**Purpose:** Monitors and reports on project dependencies

**Steps:**
1. Generates dependency tree report
2. Uploads report as artifact
3. Comments on PRs with dependency checklist

**Artifacts:** Dependency reports are retained for 30 days

---

## Setting Up Branch Protection

To enforce these workflows, configure branch protection rules:

1. Go to **Settings** → **Branches** → **Add branch protection rule**
2. Branch name pattern: `main` (or `master`)
3. Enable:
   - ✅ Require a pull request before merging
   - ✅ Require status checks to pass before merging
     - Select: `Test and Build`
     - Select: `Build Docker Images` (optional)
   - ✅ Require conversation resolution before merging
   - ✅ Do not allow bypassing the above settings

See [BRANCH_PROTECTION.md](BRANCH_PROTECTION.md) for detailed instructions.

---

## Local Development

Before pushing changes, run these checks locally:

```bash
# Compile everything
sbt compile-all

# Check formatting
sbt formatCheck

# Run style checks
sbt "scalastyle ; Test / scalastyle"

# Run all tests
sbt testAll

# Or use the convenience alias that does all of the above
sbt pp
```

---

## Milestones and Releases

### One-Click Release Process

Fukuii uses an automated release process with full traceability:

1. **Development:** Work on features and bug fixes in feature branches
2. **Pull Requests:** Create PRs with appropriate labels (feature, bug, security, etc.)
3. **Auto-Draft:** Release Drafter automatically updates draft releases as PRs are merged
4. **Ready to Release:** When ready to publish:
   ```bash
   # Version is managed in version.sbt
   git tag -a v1.0.0 -m "Release 1.0.0"
   git push origin v1.0.0
   ```
5. **Automatic Build:** Release workflow automatically:
   - Builds distribution ZIP and assembly JAR
   - Generates CHANGELOG from commits since last release
   - Creates SBOM (Software Bill of Materials)
   - Publishes GitHub release with all artifacts
   - Builds and signs Docker images
   - Closes matching milestone

### Release Artifacts

Each release automatically includes:
- ✅ **Distribution ZIP** - Full package with scripts and configs
- ✅ **Assembly JAR** - Standalone executable JAR
- ✅ **CHANGELOG.md** - Auto-generated from commit history
- ✅ **SBOM** - Software Bill of Materials (CycloneDX JSON)
- ✅ **Docker Image** - Signed with Cosign, includes provenance
- ✅ **Ubuntu Package** - Published to Launchpad PPA (see [Launchpad PPA Setup](#launchpad-ppa-setup))

### Creating a Milestone

1. Go to **Issues** → **Milestones** → **New milestone**
2. Title: Use semantic versioning (e.g., `v1.0.0`) or feature names
3. Description: Describe the goals and scope
4. Due date: Set target completion date
5. Assign issues and PRs to the milestone

### Release Notes and Changelog

**Automatic Generation:** Release notes and CHANGELOG are automatically generated from commit messages. Follow these best practices:

**Good commit message format:**
- `feat: Add support for EIP-1559 transactions`
- `fix: Resolve memory leak in block processing`
- `security: Patch vulnerability in RPC handler`
- `docs: Update installation guide`

**Commit prefixes for categorization:**
- `feat:` / `add:` → Features section
- `fix:` / `bug:` → Bug Fixes section
- `security:` / `vuln:` → Security section
- `change:` / `update:` / `refactor:` → Changed section

**Label your PRs:** Use labels to help Release Drafter categorize changes:
- `feature`, `enhancement` → Features
- `bug`, `fix` → Bug Fixes
- `security` → Security
- `documentation` → Documentation
- `ci/cd`, `build` → Build & CI/CD
- `major`, `breaking` → Major version bump
- `minor`, `milestone` → Minor version bump

### Making a Release

1. Ensure all milestone issues/PRs are closed
2. Review the draft release created by Release Drafter
3. Update version in `version.sbt` if needed
4. Commit and push changes
5. Create and push a version tag:
   ```bash
   git tag -a v1.0.0 -m "Release version 1.0.0"
   git push origin v1.0.0
   ```
6. The release workflow will automatically:
   - Build the distribution ZIP
   - Build the assembly JAR
   - Generate CHANGELOG from commits
   - Generate SBOM (Software Bill of Materials)
   - Create a GitHub release with all artifacts
   - Build and sign Docker images
   - Close the matching milestone

### Release Notes

Release notes are automatically generated from commit messages. Write clear, descriptive commit messages:

```bash
# Good commit messages
git commit -m "feat: Add support for EIP-1559 transactions"
git commit -m "fix: Memory leak in block processing"
git commit -m "security: Patch RPC handler vulnerability"
git commit -m "docs: Improve RPC response performance by 20%"

# Less helpful commit messages (avoid these)
git commit -m "fix bug"
git commit -m "updates"
git commit -m "WIP"
```

---

## Workflow Maintenance

### Updating Workflows

1. Edit workflow files in `.github/workflows/`
2. Test changes in a feature branch
3. Validate YAML syntax:
   ```bash
   python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"
   ```
4. Create a PR to review changes
5. Monitor the first run after merging

### Secrets and Variables

Some workflows may require secrets:

- `GITHUB_TOKEN` - Automatically provided by GitHub
- `LAUNCHPAD_GPG_PRIVATE_KEY` - GPG private key for signing Launchpad packages
- `LAUNCHPAD_GPG_PASSPHRASE` - Passphrase for the GPG key
- `DOCKERHUB_USERNAME` - Docker Hub username for image publishing
- `DOCKERHUB_TOKEN` - Docker Hub access token for image publishing
- Additional secrets can be added in **Settings** → **Secrets and variables** → **Actions**

### Workflow Permissions

Workflows use the following permissions:
- `contents: read/write` - Read code, create releases
- `packages: write` - Push Docker images
- `pull-requests: write` - Comment on PRs, add labels

---

## Launchpad PPA Setup

To enable Launchpad PPA publishing, follow these one-time setup steps:

### 1. Create Launchpad Account and PPA

1. Create an account on [Launchpad](https://launchpad.net/)
2. Go to your profile and click "Create a new PPA"
3. Name the PPA `fukuii` (to match the workflow configuration)
4. The PPA URL will be: `ppa:chippr-robotics/fukuii`

### 2. Generate GPG Key

Generate a GPG key for signing packages:

```bash
# Generate a new GPG key
gpg --full-generate-key
# Choose: RSA and RSA, 4096 bits, does not expire
# Use email: dev@chipprbots.com

# Export the public key
gpg --armor --export YOUR_KEY_ID > fukuii-ppa-public.asc

# Export the private key (store securely!)
gpg --armor --export-secret-keys YOUR_KEY_ID > fukuii-ppa-private.asc
```

### 3. Upload Public Key to Launchpad

1. Go to your Launchpad profile → "OpenPGP keys"
2. Click "Import an OpenPGP key"
3. Paste the contents of `fukuii-ppa-public.asc`
4. Verify the key via email confirmation

### 4. Configure GitHub Secrets

Add the following secrets to the repository:

1. Go to **Settings** → **Secrets and variables** → **Actions**
2. Add secret `LAUNCHPAD_GPG_PRIVATE_KEY`:
   - Paste the entire contents of `fukuii-ppa-private.asc`
3. Add secret `LAUNCHPAD_GPG_PASSPHRASE`:
   - The passphrase used when generating the GPG key

### 5. Test the Workflow

After setup, create a test release to verify the workflow:

```bash
git tag -a v0.1.72 -m "Test Launchpad PPA"
git push origin v0.1.72
```

Monitor the workflow run in GitHub Actions and check the Launchpad PPA page for build status.

---

## Troubleshooting

### CI Fails with "sbt: command not found"

The workflow installs SBT automatically. If this fails, check the Ubuntu package repository availability.

### Docker Build Fails

Docker builds depend on each other (base → dev → main). If a base image build fails, subsequent builds will also fail.

### Release Doesn't Close Milestone

Ensure the milestone name matches the tag version (e.g., tag `v1.0.0` → milestone `v1.0.0` or `1.0.0`).

### Workflow Not Triggering

Check:
- Branch name matches trigger patterns
- Workflow file syntax is valid
- Repository Actions are enabled in Settings

---

## Contributing

When modifying workflows:

1. Test in a feature branch first
2. Document any new secrets or requirements
3. Update this README with workflow changes
4. Validate YAML syntax before committing
5. Monitor the first run after merging

---

## Resources

- [GitHub Actions Documentation](https://docs.github.com/en/actions)
- [Workflow Syntax Reference](https://docs.github.com/en/actions/reference/workflow-syntax-for-github-actions)
- [SBT Documentation](https://www.scala-sbt.org/documentation.html)
- [Docker Build Reference](https://docs.docker.com/engine/reference/builder/)
