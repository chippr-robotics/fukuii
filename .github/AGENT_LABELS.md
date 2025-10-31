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

### Automatic vs Manual Labeling

Agent labels serve two purposes:
1. **Automatic labeling** for specific critical domains
2. **Manual labeling** to assign agents to issues or PRs

#### Automatically Applied Labels

Only **`agent: forge 🔨`** is automatically applied when PRs modify consensus-critical code:
- VM execution code (`src/main/scala/**/vm/**/*`)
- Consensus logic (`src/main/scala/**/consensus/**/*`)
- Mining code (`src/main/scala/**/mining/**/*`)
- Cryptographic operations (`crypto/**/*`)

This automatic labeling ensures that changes to the most critical parts of the codebase receive specialized review from the forge agent's domain expertise.

#### Manually Applied Labels

The other agent labels are typically applied manually:
- **`agent: wraith 👻`** - Manually add when fixing compilation errors or Scala 3 migration issues
- **`agent: mithril ✨`** - Manually add when refactoring code to use modern Scala 3 features
- **`agent: ICE 🧊`** - Manually add for large-scale migration planning and documentation tasks
- **`agent: eye 👁️`** - Manually add when the focus is on testing, validation, or quality assurance

### When to Apply Agent Labels Manually

Maintainers and contributors should manually add agent labels to:
- **Issues**: To indicate which specialized agent should handle the issue
- **PRs**: To request review from a specific agent domain expert
- **Planning**: To organize work by agent specialization

For example:
- An issue about fixing 50 compilation errors → Add `agent: wraith 👻`
- An issue about improving test coverage → Add `agent: eye 👁️`
- A PR that modernizes implicit syntax → Add `agent: mithril ✨`

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

## Creating Labels in GitHub

Before the agent labels can be used, they must first be created in the GitHub repository. See [CREATE_LABELS.md](CREATE_LABELS.md) for detailed instructions on:
- Creating labels via GitHub UI
- Creating labels via GitHub CLI
- Creating labels via API
- Recommended colors for each label

## Label Configuration

The agent label patterns are defined in `.github/labeler.yml`. If you need to update the patterns or add new agents, edit that file and update this documentation accordingly.

## Related Documentation

- [Creating Agent Labels](CREATE_LABELS.md): Step-by-step guide to create labels
- [Agent Definitions](.github/agents/): Individual agent instruction files
- [Workflow Documentation](.github/workflows/README.md): GitHub Actions workflows
- [PR Management Workflow](.github/workflows/pr-management.yml): Auto-labeling implementation
