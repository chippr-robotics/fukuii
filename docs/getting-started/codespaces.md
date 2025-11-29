# GitHub Codespaces

Fukuii includes a preconfigured GitHub Codespaces environment for development.

## Quick Start

1. Navigate to the [Fukuii repository](https://github.com/chippr-robotics/fukuii)
2. Click the green **Code** button
3. Select **Codespaces** → **Create codespace on develop**
4. Wait for the environment to initialize (first time takes a few minutes)

## What's Included

The devcontainer configuration sets up a complete Scala development environment with:

- **JDK 21** (Temurin distribution)
- **sbt 1.10.7+** — Scala Build Tool
- **Scala 3.3.4 LTS** — Primary Scala version
- **Metals** — Scala Language Server for VS Code
- **Git submodules** — Automatically initialized

## Environment Variables

The following environment variables are pre-configured:

| Variable | Value | Purpose |
|----------|-------|---------|
| `FUKUII_DEV` | `true` | Enables developer-friendly settings |
| `JAVA_OPTS` | Memory settings | Optimized for build process |

## Building and Testing

Once your Codespace is ready:

```bash
# Compile all modules
sbt compile-all

# Run tests
sbt testAll

# Build distribution
sbt dist

# Prepare for PR (format, lint, test)
sbt pp
```

## VS Code Extensions

These extensions are automatically installed:

- **Metals** — Scala language support with IntelliSense
- **Scala Syntax** — Syntax highlighting
- **TypeScript** — For tooling support

## Cache Directories

The following directories persist across container rebuilds:

- `.ivy2` — Ivy2 dependency cache
- `.sbt` — SBT cache

This makes subsequent builds much faster.

## Troubleshooting

### Metals Not Working

If the Metals language server doesn't start:

1. Open Command Palette (++cmd+shift+p++ or ++ctrl+shift+p++)
2. Run **Metals: Import build**
3. Wait for the import to complete

### Out of Memory Errors

If you encounter OOM errors:

1. The JVM is configured to use up to 4GB heap
2. Increase the Codespace machine size in GitHub settings

### Build Failures

Ensure git submodules are initialized:

```bash
git submodule update --init --recursive
```

## More Information

- [Contributing Guide](contributing.md)
- [Repository Structure](REPOSITORY_STRUCTURE.md)
- [GitHub Codespaces Documentation](https://docs.github.com/en/codespaces)
