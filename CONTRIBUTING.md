# Contributing to Fukuii

Thank you for your interest in contributing to Fukuii! This document provides guidelines and instructions to help you contribute effectively.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Workflow](#development-workflow)
- [Code Quality Standards](#code-quality-standards)
- [Pre-commit Hooks](#pre-commit-hooks)
- [Testing](#testing)
- [Submitting Changes](#submitting-changes)
- [Guidelines for LLM Agents](#guidelines-for-llm-agents)

## Code of Conduct

We are committed to providing a welcoming and inclusive environment for all contributors. Please be respectful and professional in all interactions.

## Getting Started

### Prerequisites

To contribute to Fukuii, you'll need:

- **JDK 17** - Required for building and running the project
- **sbt** - Scala build tool (version 1.5.4 or higher)
- **Git** - For version control
- **Optional**: Python (for auxiliary scripts)

### Setting Up Your Development Environment

1. **Fork and clone the repository:**
   ```bash
   git clone https://github.com/YOUR-USERNAME/fukuii.git
   cd fukuii
   ```

2. **Update submodules:**
   ```bash
   git submodule update --init --recursive
   ```

3. **Verify your setup:**
   ```bash
   sbt compile
   ```

### Quick Start with GitHub Codespaces

For the fastest setup, use GitHub Codespaces which provides a pre-configured development environment. See [.devcontainer/README.md](.devcontainer/README.md) for details.

## Development Workflow

1. **Create a feature branch:**
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Make your changes** following our [Code Quality Standards](#code-quality-standards)

3. **Test your changes** thoroughly

4. **Run pre-commit checks** (see below)

5. **Commit your changes** with clear, descriptive commit messages

6. **Push and create a Pull Request**

## Code Quality Standards

Fukuii uses several tools to maintain code quality and consistency:

### Code Formatting with Scalafmt

We use [Scalafmt](https://scalameta.org/scalafmt/) for consistent code formatting. Configuration is in `.scalafmt.conf`.

**Format your code:**
```bash
sbt scalafmtAll
```

**Check formatting without changes:**
```bash
sbt scalafmtCheckAll
```

### Static Analysis with Scalafix

We use [Scalafix](https://scalacenter.github.io/scalafix/) for automated code refactoring and linting. Configuration is in `.scalafix.conf`.

**Apply Scalafix rules:**
```bash
sbt scalafixAll
```

**Check Scalafix rules without changes:**
```bash
sbt scalafixAll --check
```

### Static Bug Detection with Scapegoat

We use [Scapegoat](https://github.com/scapegoat-scala/scapegoat) for static code analysis to detect common bugs, anti-patterns, and code smells. Configuration is in `build.sbt`.

**Run Scapegoat analysis:**
```bash
sbt runScapegoat
```

This generates both XML and HTML reports in `target/scala-2.13/scapegoat-report/`. The HTML report is especially useful for reviewing findings in a browser.

**Note**: Scapegoat automatically excludes generated code (protobuf files, BuildInfo, etc.) from analysis.

### Code Coverage with Scoverage

We use [Scoverage](https://github.com/scoverage/sbt-scoverage) for measuring code coverage during test execution. Configuration is in `build.sbt`.

**Run tests with coverage:**
```bash
sbt testCoverage
```

This will:
1. Enable coverage instrumentation
2. Run all tests across all modules
3. Generate coverage reports in `target/scala-2.13/scoverage-report/`
4. Aggregate coverage across all modules

**Coverage reports locations:**
- HTML report: `target/scala-2.13/scoverage-report/index.html`
- XML report: `target/scala-2.13/scoverage-report/cobertura.xml`

**Coverage thresholds:**
- Minimum statement coverage: 70%
- Coverage check will fail if minimum is not met

**Note**: Scoverage automatically excludes:
- Generated protobuf code
- BuildInfo generated code
- All managed sources

### Combined Commands

**Format and fix all code (recommended before committing):**
```bash
sbt formatAll
```

**Check all formatting and style (runs in CI):**
```bash
sbt formatCheck
```

**Prepare for PR submission (format, style, and test):**
```bash
sbt pp
```

## Pre-commit Hooks

To ensure code quality, we strongly recommend setting up pre-commit hooks that automatically check your code before each commit.

### Option 1: Manual Git Hook (Recommended)

Create a pre-commit hook that runs formatting and style checks:

1. **Create the hook file:**
   ```bash
   cat > .git/hooks/pre-commit << 'EOF'
   #!/bin/bash
   
   echo "Running pre-commit checks..."
   
   # Run scalafmt check
   echo "Checking code formatting with scalafmt..."
   sbt scalafmtCheckAll
   if [ $? -ne 0 ]; then
     echo "❌ Code formatting check failed. Run 'sbt scalafmtAll' to fix."
     exit 1
   fi
   
   # Run scalafix check
   echo "Checking code with scalafix..."
   sbt "scalafixAll --check"
   if [ $? -ne 0 ]; then
     echo "❌ Scalafix check failed. Run 'sbt scalafixAll' to fix."
     exit 1
   fi
   
   echo "✅ All pre-commit checks passed!"
   EOF
   ```

2. **Make it executable:**
   ```bash
   chmod +x .git/hooks/pre-commit
   ```

### Option 2: Auto-fix Pre-commit Hook

This variant automatically fixes formatting issues before committing:

```bash
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/bash

echo "Running pre-commit auto-fix..."

# Auto-format code
echo "Auto-formatting with scalafmt..."
sbt scalafmtAll

# Auto-fix with scalafix
echo "Auto-fixing with scalafix..."
sbt scalafixAll

# Add any formatted files back to the commit
git add -u

echo "✅ Pre-commit auto-fix complete!"
EOF

chmod +x .git/hooks/pre-commit
```

### Option 3: Quick Check Hook (Faster)

For a faster pre-commit check that only validates changed files:

```bash
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/bash

echo "Running quick pre-commit checks..."

# Get list of staged Scala files
STAGED_SCALA_FILES=$(git diff --cached --name-only --diff-filter=ACM | grep '\.scala$')

if [ -z "$STAGED_SCALA_FILES" ]; then
  echo "No Scala files to check."
  exit 0
fi

echo "Checking formatting of staged files..."
for file in $STAGED_SCALA_FILES; do
  if [ -f "$file" ]; then
    # Check if file is formatted (scalafmt will exit non-zero if formatting would change it)
    if ! sbt "scalafmt --test $file" > /dev/null 2>&1; then
      echo "❌ $file is not formatted. Run 'sbt scalafmtAll' to fix."
      exit 1
    fi
  fi
done

echo "✅ Quick pre-commit checks passed!"
EOF

chmod +x .git/hooks/pre-commit
```

### Bypassing Pre-commit Hooks

If you need to bypass the pre-commit hook in an emergency (not recommended):
```bash
git commit --no-verify -m "Your commit message"
```

### IDE Integration

Most IDEs support automatic formatting on save:

#### IntelliJ IDEA
1. Install the Scalafmt plugin
2. Go to `Settings → Editor → Code Style → Scala`
3. Select "Scalafmt" as the formatter
4. Enable "Reformat on file save"

#### VS Code
1. Install the Metals extension
2. Enable format on save in settings:
   ```json
   {
     "editor.formatOnSave": true,
     "[scala]": {
       "editor.defaultFormatter": "scalameta.metals"
     }
   }
   ```

## Testing

Always run tests before submitting your changes:

**Run all tests:**
```bash
sbt testAll
```

**Run specific module tests:**
```bash
sbt bytes/test
sbt crypto/test
sbt rlp/test
sbt test
```

**Run integration tests:**
```bash
sbt "IntegrationTest / test"
```

## Submitting Changes

1. **Ensure all checks pass:**
   ```bash
   sbt pp  # Runs format, style checks, and tests
   ```

2. **Commit your changes:**
   - Use clear, descriptive commit messages
   - Reference relevant issue numbers (e.g., "Fix #123: Description")
   - Keep commits focused and atomic

3. **Push your branch:**
   ```bash
   git push origin feature/your-feature-name
   ```

4. **Create a Pull Request:**
   - Provide a clear description of your changes
   - Reference any related issues
   - Ensure all CI checks pass
   - Be responsive to review feedback

### Pull Request Guidelines

- **Title**: Clear and descriptive (e.g., "Add support for EIP-1559" or "Fix memory leak in RPC handler")
- **Description**: Explain what changes were made and why
- **Testing**: Describe how you tested your changes
- **Documentation**: Update relevant documentation if needed
- **Breaking Changes**: Clearly mark any breaking changes

### Continuous Integration

Our CI pipeline automatically runs:
- ✅ Compilation (`compile-all`)
- ✅ Code formatting checks (`formatCheck` - includes scalafmt + scalafix)
- ✅ Static bug detection (`runScapegoat`)
- ✅ Test suite with code coverage (`testCoverage`)
- ✅ Coverage reports (published as artifacts)
- ✅ Build artifacts (`assembly`, `dist`)

All checks must pass before a PR can be merged.

## Guidelines for LLM Agents

This section provides rules, reminders, and prompts for LLM agents (AI coding assistants) working on this codebase to ensure consistency and quality.

### Core Principles

1. **Keep Documentation Essential**: Focus on clarity and brevity. Avoid unnecessary verbosity or redundant explanations.
2. **Consistency Over Innovation**: Follow existing patterns in the codebase rather than introducing new approaches.
3. **Minimal Changes**: Make the smallest possible changes to achieve the goal. Don't refactor unrelated code.

### Rules

1. **Code Style**
   - Always run `sbt formatAll` before committing
   - Follow existing Scala idioms and patterns in the codebase
   - Use the same naming conventions as surrounding code
   - Keep line length under 120 characters (configured in `.scalafmt.conf`)

2. **Testing**
   - Write tests that match the existing test structure and style
   - Run `sbt testAll` to verify all tests pass
   - Don't modify unrelated tests unless fixing a bug
   - Integration tests go in `src/it/`, unit tests in `src/test/`

3. **Documentation**
   - Update documentation when changing public APIs
   - Keep comments concise and focused on "why" not "what"
   - Don't add comments for self-explanatory code
   - Update README.md for user-facing changes

4. **Package Structure**
   - All code uses package prefix `com.chipprbots.ethereum`
   - Previously used `io.iohk.ethereum` (from Mantis project) - update if found
   - Configuration paths use `.fukuii/` not `.mantis/`

5. **Dependencies**
   - Don't add dependencies without justification
   - Check for security vulnerabilities before adding dependencies
   - Prefer libraries already in use in the project

### Reminders

- **JDK Compatibility**: Code must work on JDK 17
- **Logging**: Use structured logging with appropriate levels (DEBUG, INFO, WARN, ERROR)
- **Logger Configuration**: Update logback configurations when adding new packages
- **Rebranding**: This is a rebrand from "Mantis" to "Fukuii" - update any remaining "mantis" or "io.iohk" references
- **Commit Messages**: Use clear, descriptive commit messages in imperative mood
- **Git Hygiene**: Don't commit build artifacts, IDE files, or temporary files

### Prompts for Common Tasks

**When fixing tests:**
```
1. Identify the root cause of the failure
2. Check if it's related to rebranding (mantis→fukuii, io.iohk→com.chipprbots)
3. Check logger configurations in src/test/resources/ and src/it/resources/
4. Run the specific test to verify the fix
5. Run full test suite to ensure no regressions
```

**When adding new features:**
```
1. Follow existing patterns in similar features
2. Add comprehensive tests (unit + integration if needed)
3. Update documentation (README, scaladoc)
4. Run formatCheck and linters
5. Ensure JDK 17 compatibility
```

**When refactoring:**
```
1. Keep changes minimal and focused
2. Don't mix refactoring with feature work
3. Ensure all tests pass before and after
4. Maintain backward compatibility unless breaking changes are approved
```

**When updating dependencies:**
```
1. Check the GitHub Advisory Database for vulnerabilities
2. Test thoroughly on JDK 17
3. Update version numbers in build.sbt
4. Document any breaking changes or migration steps
```

### Quality Checklist

Before submitting a PR, verify:
- [ ] `sbt formatCheck` passes
- [ ] `sbt compile-all` succeeds
- [ ] `sbt testAll` passes (on JDK 17)
- [ ] `sbt "IntegrationTest / test"` passes for integration tests
- [ ] No new compiler warnings introduced
- [ ] Documentation updated for user-facing changes
- [ ] Commit messages are clear and descriptive
- [ ] No debugging code or print statements left in

## Additional Resources

- [GitHub Workflow Documentation](.github/workflows/README.md)
- [Quick Start Guide](.github/QUICKSTART.md)
- [Branch Protection Setup](.github/BRANCH_PROTECTION.md)
- [Scalafmt Documentation](https://scalameta.org/scalafmt/)
- [Scalafix Documentation](https://scalacenter.github.io/scalafix/)

## Questions or Issues?

If you have questions or run into issues:
1. Check the [GitHub Issues](https://github.com/chippr-robotics/fukuii/issues)
2. Review existing discussions
3. Open a new issue with a clear description of your question or problem

Thank you for contributing to Fukuii! 🚀
