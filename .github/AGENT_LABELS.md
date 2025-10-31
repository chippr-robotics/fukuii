# Agent Labels

This document describes the automated agent labels used in the fukuii project. When agents work on PRs or issues, they automatically receive labels with fun emojis that represent their domain expertise.

## Agent Label Reference

### 👻 agent: wraith
**Description:** Nazgûl-like agent that relentlessly hunts down and eliminates Scala 3 compile errors

**Domain:** Compilation errors, Scala 3 migration, syntax fixes

**Applied When:**
- Working on Scala source files (`**/*.scala`)
- Fixing build configuration (`build.sbt`, `project/**/*`)
- Hunting down and eliminating compilation errors

**Expertise:**
- New Scala 3 keywords (enum, export, given, then)
- Procedure syntax removal
- Wildcard imports (`_` → `*`)
- Lambda captures and type inference
- Implicit system changes

---

### ✨ agent: mithril
**Description:** Like the precious metal of legend, transforms code to be stronger and lighter using Scala 3's power

**Domain:** Code modernization, Scala 3 features, refactoring

**Applied When:**
- Transforming Scala code to idiomatic Scala 3
- Applying modern language features
- Improving code patterns

**Expertise:**
- Given/using contextual abstractions
- Extension methods
- Opaque types for type safety
- Enums for sealed hierarchies
- Union types for error handling
- Top-level definitions

---

### 🧊 agent: ICE
**Description:** Abstract Methodology for Large-Scale Code Migration Tasks

**Domain:** Large-scale migrations, systematic transformations, strategic planning

**Applied When:**
- Working on comprehensive migration tasks
- Creating migration documentation
- Systematic code transformations across multiple files

**Expertise:**
- Discovery and assessment
- Build configuration
- Systematic migration phases
- Code review and refinement
- Verification and documentation
- High-level strategic planning

---

### 👁️ agent: eye
**Description:** Like Sauron's gaze from Barad-dûr, sees all bugs, validates all code, ensures perfect migration quality

**Domain:** Testing, validation, quality assurance, consensus testing

**Applied When:**
- Working on test files (`**/test/**/*`, `**/*Spec.scala`, `**/*Test.scala`)
- Validating code changes
- Ensuring quality and correctness

**Expertise:**
- Unit testing
- Integration testing
- Consensus testing
- Performance testing
- Regression testing
- Property-based testing
- ETC (Ethereum Classic) specification compliance

---

### 🔨 agent: forge
**Description:** Master smith forged in Mount Doom, handles consensus-critical Ethereum Classic code with ancient wisdom

**Domain:** Consensus-critical code, EVM, mining, blockchain core, cryptography

**Applied When:**
- Working on VM execution code (`src/main/scala/**/vm/**/*`)
- Modifying consensus logic (`src/main/scala/**/consensus/**/*`)
- Updating mining code (`src/main/scala/**/mining/**/*`)
- Changing cryptographic operations (`crypto/**/*`)

**Expertise:**
- EVM opcode execution
- Ethash PoW mining
- ETC consensus rules
- State management
- Cryptographic operations
- Block validation
- Deterministic execution

---

## How Agent Labels Work

### Automatic Labeling

Agent labels are automatically applied by the GitHub Actions workflow (`.github/workflows/pr-management.yml`) using the configuration in `.github/labeler.yml`.

When a PR is opened or updated, the workflow:
1. Analyzes which files were changed
2. Matches file patterns to agent domains
3. Applies appropriate agent labels

### Multiple Agents

A PR can receive multiple agent labels if it touches multiple domains. For example:
- A PR that modifies both test files and VM code would get both `agent: eye 👁️` and `agent: forge 🔨`
- A PR that fixes Scala 3 compilation errors across multiple modules would get `agent: wraith 👻`

### Manual Labeling

While labels are typically applied automatically, maintainers can also manually add agent labels to issues or PRs to indicate which agent should review or work on them.

## Agent Label Guidelines

### For Contributors

When you see agent labels on a PR:
- The label indicates the domain of expertise required for review
- Multiple agent labels mean the change affects multiple critical areas
- Pay special attention to PRs with `agent: forge 🔨` as these affect consensus-critical code

### For Reviewers

When reviewing PRs with agent labels:
- **agent: wraith 👻**: Verify compilation succeeds and Scala 3 compatibility
- **agent: mithril ✨**: Check that refactoring maintains functionality and improves code quality
- **agent: ICE 🧊**: Ensure migration strategy is sound and documentation is complete
- **agent: eye 👁️**: Verify all tests pass and new tests are added for new functionality
- **agent: forge 🔨**: Extra scrutiny required - verify deterministic behavior and consensus compatibility

### Priority and Risk

Agent labels also indicate risk level:

**Highest Risk:**
- 🔨 **forge**: Consensus-critical code requires extensive validation

**High Risk:**
- 👁️ **eye**: Testing changes affect quality assurance
- 👻 **wraith**: Compilation fixes must not introduce regressions

**Medium Risk:**
- 🧊 **ICE**: Large-scale migrations need careful planning
- ✨ **mithril**: Refactoring must preserve functionality

## Label Configuration

The agent label patterns are defined in `.github/labeler.yml`. If you need to update the patterns or add new agents, edit that file and update this documentation accordingly.

## Related Documentation

- [Agent Definitions](.github/agents/): Individual agent instruction files
- [Workflow Documentation](.github/workflows/README.md): GitHub Actions workflows
- [PR Management Workflow](.github/workflows/pr-management.yml): Auto-labeling implementation
