# Quick Start Guide: GitHub Actions & Project Hygiene

This guide helps you get started with the GitHub Actions workflows configured for this project.

## 🚀 For Developers

### Before You Push

Always run these commands locally before pushing:

```bash
# Format your code
sbt scalafmtAll

# Check everything (format, style, tests)
sbt pp
```

### Creating a Pull Request

1. **Create a feature branch:**
   ```bash
   git checkout -b feature/my-awesome-feature
   ```

2. **Make your changes and commit:**
   ```bash
   git add .
   git commit -m "Add awesome feature"
   ```

3. **Push and create PR:**
   ```bash
   git push origin feature/my-awesome-feature
   ```

4. **In the PR description, link related issues:**
   ```
   Fixes #123
   Closes #456
   ```

5. **Assign to a milestone** (for tracking features/releases)

6. **Wait for CI checks to pass** ✅
   - Code compilation
   - Formatting checks
   - Style checks  
   - All tests
   - Docker builds (optional)

7. **Get approval and merge!**

### Understanding PR Labels

Labels are automatically applied based on your changes:

| Label | Files Changed |
|-------|---------------|
| 🔧 `build` | `build.sbt`, project files |
| 📦 `dependencies` | Dependency files |
| 🐳 `docker` | Docker-related files |
| 📝 `documentation` | Markdown, README files |
| 🧪 `tests` | Test files (*Spec.scala) |
| 🔐 `crypto` | Crypto module |
| 📊 `bytes` | Bytes module |
| 📋 `rlp` | RLP module |

You can also add manual labels like `bug`, `enhancement`, `breaking-change`, etc.

---

## 🎯 For Maintainers

### Managing Milestones

**Create a milestone for each release or sprint:**

1. Go to **Issues** → **Milestones** → **New milestone**
2. Title: `v1.0.0` (for releases) or `Sprint 1` (for sprints)
3. Set a due date
4. Assign issues and PRs to track progress
5. The release workflow will auto-close it when you tag a release

### Creating a Release

**For version `1.0.0`:**

```bash
# 1. Update version in version.sbt (if used)
echo 'version in ThisBuild := "1.0.0"' > version.sbt

# 2. Commit the version bump
git add version.sbt
git commit -m "Bump version to 1.0.0"
git push origin main

# 3. Create and push the tag
git tag -a v1.0.0 -m "Release version 1.0.0"
git push origin v1.0.0

# 4. The Release workflow will automatically:
#    - Build the distribution
#    - Create GitHub release
#    - Attach artifacts
#    - Close milestone v1.0.0
```

**For pre-releases (alpha/beta/RC):**

```bash
git tag -a v1.0.0-beta1 -m "Beta 1 for version 1.0.0"
git push origin v1.0.0-beta1
# This will be marked as a pre-release automatically
```

### Setting Up Branch Protection

**One-time setup for the main branch:**

1. Go to **Settings** → **Branches**
2. Click **Add branch protection rule**
3. Branch name: `main`
4. Enable these options:
   - ✅ Require a pull request before merging
     - Required approvals: 1
   - ✅ Require status checks to pass
     - Search and select: `Test and Build`
     - Search and select: `Build Docker Images`
   - ✅ Require conversation resolution before merging
   - ✅ Do not allow bypassing (applies to admins too)
5. Click **Create**

Now all changes must go through PRs and pass CI checks!

---

## 🐳 Docker Images

### Automatic Builds

Docker images are built automatically on:
- Push to `main`, `master`, or `develop` branches
- Push of version tags (e.g., `v1.0.0`)
- Pull requests (build only, not pushed)

### Published Images

All images are published to GitHub Container Registry:

```bash
# Pull the latest production image
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:latest

# Pull a specific version
docker pull ghcr.io/chippr-robotics/chordodes_fukuii:1.0.0

# Pull dev environment
docker pull ghcr.io/chippr-robotics/chordodes_fukuii-dev:latest
```

### Running Locally

```bash
# Run the latest version
docker run -it ghcr.io/chippr-robotics/chordodes_fukuii:latest

# Or build locally
cd docker
./build.sh
```

---

## 📊 Monitoring

### Check CI Status

**For a PR:**
- Look at the bottom of the PR page for status checks
- Click "Details" to see logs if something fails

**For the main branch:**
- Go to the **Actions** tab
- See all workflow runs and their status

### View Artifacts

Build artifacts (distribution zips, reports) are available:

1. Go to **Actions** tab
2. Click on a workflow run
3. Scroll to **Artifacts** section
4. Download what you need

Artifacts are kept for 7-30 days depending on type.

---

## 🔧 Troubleshooting

### ❌ CI Check Failed

**Problem:** Tests fail, code doesn't compile, style checks fail

**Solution:**
1. Check the workflow logs in the Actions tab
2. Run the same checks locally (use the same commands as CI):
   ```bash
   sbt compile-all        # Compile all modules
   sbt formatCheck        # Check code formatting
   sbt bytes/scalastyle crypto/scalastyle rlp/scalastyle scalastyle  # Style checks
   sbt testAll            # Run all tests
   ```
3. Fix the issues and push again

### ❌ Docker Build Failed

**Problem:** Docker image build fails

**Solution:**
1. Check the workflow logs for error details
2. Test Docker build locally:
   ```bash
   cd docker
   docker build -t fukuii-base:latest -f Dockerfile-base .
   docker build -t fukuii-dev:latest -f Dockerfile-dev .
   docker build -t fukuii:latest -f Dockerfile .
   ```
3. Fix Dockerfile issues and push again

### ⚠️ PR Has No Milestone

**Problem:** Warning that PR isn't assigned to a milestone

**Solution:**
- This is just a reminder, not a blocker
- Assign the PR to a relevant milestone if tracking features/releases
- Or ignore if not using milestones for this PR

### ⚠️ PR Has No Linked Issue

**Problem:** Comment asking to link an issue

**Solution:**
- Add to PR description: `Fixes #123` or `Closes #456`
- Or ignore if this is a standalone PR without an issue

---

## 📚 Common Tasks

### Update Dependencies

```bash
# Edit dependencies in project/Dependencies.scala or build.sbt
# Use your preferred editor (vim, nano, emacs, VS Code, etc.)
# Example with vim:
vim project/Dependencies.scala

# Or with nano:
# nano project/Dependencies.scala

# Test changes
sbt compile-all
sbt testAll

# Commit and push
git add project/Dependencies.scala
git commit -m "Update dependency X to version Y"
git push origin feature/update-deps
```

The dependency check workflow will run and report on changes.

### Fix Formatting Issues

```bash
# Auto-format all code
sbt scalafmtAll

# Run scalafix
sbt scalafixAll

# Commit formatted code
git add .
git commit -m "Format code"
```

### Add New Tests

```bash
# Add test file in appropriate module
# e.g., bytes/src/test/scala/.../*Spec.scala

# Run tests
sbt bytes/test

# Or run all tests
sbt testAll
```

Tests are automatically picked up by CI.

---

## 🎓 Best Practices

### Commit Messages

Write clear, descriptive commit messages:

✅ **Good:**
```
Add support for EIP-1559 transactions
Fix memory leak in block synchronization
Improve RPC response time by caching headers
```

❌ **Bad:**
```
fix
update
changes
wip
```

### PR Descriptions

Include:
- What changed and why
- Related issues (`Fixes #123`)
- Testing notes
- Breaking changes (if any)
- Screenshots (for UI changes)

### Code Reviews

- Be respectful and constructive
- Ask questions if something is unclear
- Suggest improvements
- Approve when satisfied
- Resolve all conversations before merging

---

## 📞 Getting Help

- **Workflow Issues:** Check `.github/workflows/README.md`
- **Branch Protection:** See `.github/BRANCH_PROTECTION.md`
- **Build Issues:** See project `README.md`
- **Questions:** Open a GitHub issue or discussion

---

## 🎉 That's It!

You're now ready to contribute with full GitHub Actions support. The workflows will help ensure code quality and automate releases. Happy coding! 🚀
