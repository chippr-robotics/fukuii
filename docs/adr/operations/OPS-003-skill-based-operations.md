# OPS-003: Migration from CLI Tools to Claude Code Skills

**Status**: Accepted

**Date**: March 2026

**Deciders**: Chippr Robotics LLC Engineering Team

## Context

Fukuii operations have been managed through a collection of Bash CLI tools (`ops/tools/fukuii-cli.sh`, `validate-build.sh`, `check-docker.sh`, `build-all-images.sh`) totaling ~75KB of shell scripts. These tools handle node lifecycle management, network deployment, sync operations, peer management, health checks, log collection, and test orchestration across three deployment environments (Barad-dur, Cirith Ungol, Gorgoroth).

### Problems with the CLI Approach

1. **Rigid command structure**: Every new operational workflow requires writing and maintaining Bash functions, argument parsing, help text, and error handling. Adding a "check if the node is stalled and diagnose why" command requires hundreds of lines of scripting.

2. **No reasoning capability**: CLI scripts execute fixed sequences. When a peer count drops, a script can report the number but cannot analyze whether it's a discovery issue, a blacklist cascade, a port misconfiguration, or a network-wide event. Operators must interpret raw output themselves.

3. **Maintenance burden**: 59KB of `fukuii-cli.sh` has grown organically across three environments with overlapping but divergent code paths. Docker compose paths, port mappings, container names, and config file locations are hardcoded throughout, creating a brittle coupling that breaks when deployment topology changes.

4. **Poor discoverability**: New operators must read documentation or source code to learn available commands. There's no interactive help, no tab completion for node-specific context, and no way to ask "what's wrong with my node?"

5. **Static analysis only**: Scripts can check if a container is running, but cannot correlate a rising blacklist rate with a recent config change, trace an actor name collision to its root cause, or recommend increasing the per-peer budget based on observed throughput patterns.

6. **Duplication**: Functionality overlaps between `fukuii-cli.sh`, the Gorgoroth test scripts, Barad-dur setup scripts, and ad-hoc Docker commands that operators run manually. The same "check sync status" logic exists in at least four places.

### The Claude Code Skill Alternative

Claude Code skills provide a natural language interface to operational tooling. A skill is a structured prompt document (`SKILL.md`) that gives Claude Code domain-specific knowledge about the system: container names, port mappings, config file locations, RPC methods, metrics, and troubleshooting procedures. When invoked, Claude Code uses this knowledge to execute the appropriate Docker, RPC, or system commands — with reasoning about the results.

This approach was validated during alpha development: all Barad-dur node management, sync monitoring, bug diagnosis, configuration changes, and deployment operations have been performed through Claude Code conversations since March 2026. The operator has not used `fukuii-cli.sh` during this period.

## Decision

Replace the CLI-based operations model with a Claude Code skill (`/fukuii`) as the primary interface for Fukuii node operations.

### What the Skill Provides

- **Node lifecycle**: Start, stop, restart, deploy across all environments
- **Sync monitoring**: Phase tracking, throughput analysis, ETA estimation, bottleneck diagnosis
- **Blockchain queries**: JSON-RPC via `docker exec` with result interpretation
- **Peer management**: Connection analysis, GeoIP lookup, capability checks
- **Health diagnosis**: Correlate logs, metrics, and config to identify root causes
- **Build & deploy**: Assembly JAR, Docker image build, tag, force-recreate workflow
- **Test orchestration**: Stop containers, run tests, restart — with awareness of the "tests freeze machine with active nodes" constraint

### What the Skill Does NOT Replace

- **Gorgoroth test scripts**: Multi-client integration tests (`test-ecip1111-basefee-redirect.sh`, etc.) are automated CI/CD artifacts that must run headlessly. These remain as Bash scripts.
- **`validate-build.sh`**: CI/CD build validation runs in environments without Claude Code. Stays as standalone script.
- **`build-all-images.sh`**: Multi-repo image builds for all three clients (Fukuii, Core-Geth, Besu) with branch targeting. Stays as standalone script.
- **Docker Compose files**: Infrastructure-as-code definitions remain as YAML.

### Migration Path

1. **Phase 1 (Complete)**: Skill created with full operational knowledge. CLI tools remain but are not actively maintained.
2. **Phase 2**: Gorgoroth test scripts updated to use consistent patterns but remain as standalone Bash.
3. **Phase 3**: `fukuii-cli.sh` marked as deprecated in favor of `/fukuii` skill. Script preserved for reference.

## Consequences

### Positive

- **Adaptive operations**: Operators describe intent ("check if the node is healthy", "deploy latest code to primary") rather than remembering exact commands and flags.
- **Contextual reasoning**: Claude Code can correlate a "no incoming peers" observation with a port mapping mismatch in Docker compose — something a CLI script cannot do.
- **Zero maintenance for new workflows**: Adding a new operational capability (e.g., "analyze peer geographic distribution") requires no code changes. The skill's reference data and Claude Code's tool access handle it.
- **Self-documenting**: The skill's `SKILL.md` and `REFERENCE.md` serve as living documentation of operational procedures, updated alongside the system.
- **Faster onboarding**: New operators interact naturally instead of learning a custom CLI vocabulary.
- **Error recovery**: When a command fails, Claude Code adapts (tries different ports, checks container status, reads logs) rather than exiting with an error code.

### Negative

- **Requires Claude Code**: Operations depend on Claude Code availability. For air-gapped or offline environments, Bash scripts remain necessary.
- **Non-deterministic**: The same question may produce slightly different command sequences across invocations. For reproducible CI/CD pipelines, deterministic scripts are still required.
- **Latency**: Interactive Claude Code conversations have higher latency than direct CLI commands for simple operations (e.g., `docker ps`). Operators who know the exact command may prefer running it directly.
- **Skill drift**: If deployment topology changes but the skill isn't updated, Claude Code may issue incorrect commands. Mitigated by the skill reading live system state (Docker, configs) rather than relying solely on static documentation.

### Neutral

- **Bash scripts remain available**: The CLI tools are not deleted, only deprecated as the primary interface. Operators can always fall back to direct Docker/Bash commands.
- **Hybrid model**: Complex multi-step operations (deploy, test, analyze) use the skill. Simple one-liners (`docker logs fukuii-primary --since 5m`) can be run directly.

## Alternatives Considered

### 1. Maintain and Improve CLI Tools
Rejected. The 59KB `fukuii-cli.sh` would grow linearly with every new operational capability. The reasoning and diagnosis capabilities that make Claude Code valuable cannot be replicated in Bash without essentially building an expert system.

### 2. Replace with Python CLI (Click/Typer)
Considered. A Python CLI would provide better structure, type safety, and testing than Bash. However, it still lacks reasoning capability and would require maintaining a parallel operational knowledge base. The skill approach achieves the same organizational benefit with zero code maintenance.

### 3. Web-based Operations Dashboard
Partially implemented via Grafana dashboards. Dashboards provide monitoring but not interactive operations (can't deploy, can't diagnose, can't modify configuration). The skill complements dashboards rather than replacing them.

## References

- `~/.claude/skills/fukuii/SKILL.md` — Skill definition
- `~/.claude/skills/fukuii/REFERENCE.md` — Metrics, RPC, and configuration reference
- `/chipprbots/blockchain/fukuii/ops/tools/fukuii-cli.sh` — Deprecated CLI tool (preserved for reference)
- [OPS-001: Console UI](OPS-001-console-ui.md) — Previous decision on TUI approach
