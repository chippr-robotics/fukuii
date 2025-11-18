# Development Documentation

This directory contains documentation for developers working on the Fukuii codebase.

## Contents

### Repository Structure
- **[Repository Structure](REPOSITORY_STRUCTURE.md)** - Detailed guide to the repository organization and codebase layout

### Development Guides
- **[Addressing Warnings](ADDRESSING_WARNINGS.md)** - Guide to addressing compiler and linter warnings
- **[Vendored Modules Integration Plan](VENDORED_MODULES_INTEGRATION_PLAN.md)** - Plan for integrating vendored dependencies

## Related Documentation

- [Contributing Guide](../../CONTRIBUTING.md) - How to contribute to the project
- [Testing Documentation](../testing/) - Testing strategies and guides
- [ADRs](../adr/) - Architecture Decision Records

## Getting Started

1. Review the [Repository Structure](REPOSITORY_STRUCTURE.md) to understand the codebase layout
2. Follow the [Contributing Guide](../../CONTRIBUTING.md) for development setup
3. Check [ADRs](../adr/) for architectural decisions that affect your work

## Development Workflow

```bash
# Clone with submodules
git clone --recursive https://github.com/chippr-robotics/fukuii.git
cd fukuii

# Build
sbt compile

# Run tests
sbt test

# Format and check code
sbt pp  # "prepare PR" - formats, checks, and tests

# Build distribution
sbt dist
```

See [CONTRIBUTING.md](../../CONTRIBUTING.md) for more details.
