# GitHub Codespaces Configuration for Fukuii

This directory contains the configuration for GitHub Codespaces development environment for the Fukuii Ethereum Client.

## What's Included

The devcontainer configuration sets up a complete Scala development environment with:

- **JDK 17** (Temurin distribution) - Required for building Fukuii
- **SBT 1.5.4+** - Scala Build Tool for compiling and testing
- **Scala 3.3.4** (LTS) - Primary Scala version used by the project
- **Metals** - Scala Language Server for VS Code
- **Git submodules** - Automatically initialized on container creation

## Environment Variables

The following environment variables are pre-configured:

- `FUKUII_DEV=true` - Enables developer-friendly settings (disables fatal warnings, etc.)
- `JAVA_OPTS` - JVM memory settings optimized for the build process

## Getting Started

1. Open this repository in GitHub Codespaces (click the green "Code" button and select "Open with Codespaces")
2. Wait for the container to build and initialize (first time may take a few minutes)
3. Once ready, you can start building:

```bash
# Compile all modules
sbt compile-all

# Run tests
sbt testAll

# Build distribution
sbt dist

# Format and check code (prepare for PR)
sbt pp
```

## VS Code Extensions

The following extensions are automatically installed:

- **Metals** - Scala language support with IntelliSense, refactoring, and more
- **Scala Syntax** - Syntax highlighting for Scala
- **TypeScript** - For any TypeScript tooling support

## Cache Directories

The following directories are mounted as volumes to speed up subsequent builds:

- `.ivy2` - Ivy2 dependency cache
- `.sbt` - SBT cache

These caches persist across container rebuilds, making subsequent builds much faster.

## Troubleshooting

### Metals not working

If the Metals language server doesn't start automatically:
1. Open the Command Palette (Cmd/Ctrl + Shift + P)
2. Run "Metals: Import build"
3. Wait for the import to complete

### Out of Memory Errors

If you encounter OOM errors during build:
1. The JVM options are already set to use up to 4GB of heap
2. You may need to increase the Codespace machine size in GitHub settings

### Build Failures

Make sure git submodules are initialized:
```bash
git submodule update --init --recursive
```

## More Information

- [Fukuii Quick Start Guide](../.github/QUICKSTART.md)
- [Main README](../README.md)
- [GitHub Codespaces Documentation](https://docs.github.com/en/codespaces)
