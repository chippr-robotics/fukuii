# Fukuii Documentation

Welcome to the Fukuii Ethereum Classic client documentation! Whether you're running a node, deploying to production, or contributing code, you'll find everything you need here.

> **📖 Hosted Documentation:** Visit [https://chippr-robotics.github.io/fukuii/](https://chippr-robotics.github.io/fukuii/) for an easier browsing experience with search functionality.

## Quick Navigation

### 🚀 Getting Started
- [Main README](../README.md) - Project overview and features
- [First Start Guide](runbooks/first-start.md) - Initial node setup and configuration
- [Contributing Guide](../CONTRIBUTING.md) - How to contribute to the project

### 📚 Documentation by Category

#### 🏗️ [Architecture](architecture/)
Architectural documentation, diagrams, and system design.
- [Architecture Overview](architecture/architecture-overview.md) - High-level system architecture
- [Architecture Diagrams](architecture/ARCHITECTURE_DIAGRAMS.md) - C4 diagrams and visual representations
- [Console UI Design](architecture/console-ui.md) - User interface architecture

#### 🐳 [Deployment](deployment/)
Docker, containers, and deployment guides.
- [Docker Guide](deployment/docker.md) - Comprehensive Docker deployment
- [Kong API Gateway](deployment/kong.md) - API gateway integration
- [Test Network Setup](deployment/test-network.md) - Local test network

#### 💻 [Development](development/)
Developer guides and repository documentation.
- [Repository Structure](development/REPOSITORY_STRUCTURE.md) - Codebase organization
- [Vendored Modules Plan](development/VENDORED_MODULES_INTEGRATION_PLAN.md) - Dependency integration

#### 📖 [Guides](guides/)
User guides and configuration documentation.
- [MESS Configuration](guides/mess-configuration.md) - Peer scoring configuration

#### 🌐 [API Documentation](api/)
JSON-RPC API documentation and tools.
- **[API Documentation Index](api/README.md)** - Complete API documentation hub
- [JSON-RPC API Reference](api/JSON_RPC_API_REFERENCE.md) - Comprehensive endpoint reference (77 methods)
- [JSON-RPC Coverage Analysis](api/JSON_RPC_COVERAGE_ANALYSIS.md) - Gap analysis vs Ethereum specification
- [MCP Integration Guide](api/MCP_INTEGRATION_GUIDE.md) - Model Context Protocol server planning
- [Insomnia Workspace Guide](api/INSOMNIA_WORKSPACE_GUIDE.md) - API testing workspace guide
- [RPC API Analysis](api/INSOMNIA_RPC_ANALYSIS.md) - Implementation comparison with Ethereum execution-apis

#### 📊 [Reports](reports/)
Test reports, implementation summaries, and analysis.
- [Network Test Report](reports/NETWORK_TEST_REPORT.md) - Network testing results
- [Implementation Complete](reports/IMPLEMENTATION_COMPLETE.md) - Testing tags summary
- [Static Analysis Inventory](reports/STATIC_ANALYSIS_INVENTORY.md) - Code quality analysis

#### 📐 [Specifications](specifications/)
Technical specifications and protocol documentation.
- [RLP Integer Encoding](specifications/RLP_INTEGER_ENCODING_SPEC.md) - Encoding specification

#### 🔧 [Troubleshooting](troubleshooting/)
Solutions for common scenarios:
- [Block Sync Guide](troubleshooting/BLOCK_SYNC_TROUBLESHOOTING.md) — Sync solutions ✅
- [Gas Calculation Reference](troubleshooting/GAS_CALCULATION_ISSUES.md) — Gas costs verified ✅

#### 🛠️ [Tools](tools/)
Interactive tools and utilities.
- [Fukuii Configurator](tools/fukuii-configurator.html) - Web-based configuration generator

### 📋 Well-Organized Sections

#### 📝 [Architecture Decision Records (ADRs)](adr/)
Architectural decisions with context and rationale, organized by category:
- [Infrastructure ADRs](adr/infrastructure/) - Platform and runtime decisions
- [VM ADRs](adr/vm/) - EVM and EIP implementations
- [Consensus ADRs](adr/consensus/) - Protocol and networking
- [Testing ADRs](adr/testing/) - Testing strategy
- [Operations ADRs](adr/operations/) - Operational tooling

#### 📚 [Operations Runbooks](runbooks/)
Operational guides for running production nodes:
- [First Start](runbooks/first-start.md) - Initial setup
- [Operating Modes](runbooks/operating-modes.md) - Node types and configurations
- [Node Configuration](runbooks/node-configuration.md) - Configuration options
- [Security](runbooks/security.md) - Security best practices
- [Backup & Restore](runbooks/backup-restore.md) - Data protection
- [Known Issues](runbooks/known-issues.md) - Common problems and solutions

#### 🧪 [Testing Documentation](testing/)
Testing strategies, guides, and results:
- [Test Tagging Guide](testing/TEST_TAGGING_GUIDE.md) - Test organization
- [E2E Testing Guide](testing/E2E_TESTING_GUIDE.md) - End-to-end testing
- [KPI Monitoring Guide](testing/KPI_MONITORING_GUIDE.md) - Performance tracking

#### 📊 [Operations](operations/)
Operational guides and monitoring:
- [Metrics and Monitoring](operations/metrics-and-monitoring.md) - Prometheus and Grafana setup

#### 🔍 [Analysis](analysis/)
Log analysis and system analysis documents:
- [Fast Sync Log Analysis](analysis/fast-sync-log-analysis.md)
- [Sync Process Log Analysis](analysis/sync-process-log-analysis.md)

#### 🕵️ [Investigation](investigation/)
Historical investigations and root cause analyses:
- [FastSync Investigation](investigation/FASTSYNC_TIMEOUT_INVESTIGATION.md) — Resolved ✅
- [Contract Test Analysis](investigation/CONTRACT_TEST_FAILURE_ANALYSIS.md) — Root cause identified ✅
- [Integration Test Classification](investigation/INTEGRATION_TEST_CLASSIFICATION.md)

#### 📜 [Historical](historical/)
Historical documentation and project milestones:
- [Scala 3 Migration](historical/MIGRATION_HISTORY.md) — Successful Scala 3 migration story
- [Ethereum Tests Migration](historical/ETHEREUM_TESTS_MIGRATION.md)

## Documentation by Use Case

### I want to run a Fukuii node
1. [First Start Guide](runbooks/first-start.md)
2. [Operating Modes](runbooks/operating-modes.md)
3. [Node Configuration](runbooks/node-configuration.md)
4. [Configuration Tool](tools/fukuii-configurator.html)

### I want to deploy with Docker
1. [Docker Guide](deployment/docker.md)
2. [Test Network Setup](deployment/test-network.md)
3. [Kong API Gateway](deployment/kong.md)

### I want to contribute code
1. [Contributing Guide](../CONTRIBUTING.md)
2. [Repository Structure](development/REPOSITORY_STRUCTURE.md)
3. [Testing Guide](testing/TEST_TAGGING_GUIDE.md)
4. [ADRs](adr/)

### I'm troubleshooting a scenario
1. [Known Issues & Solutions](runbooks/known-issues.md)
2. [Log Analysis](runbooks/log-triage.md)
3. [Troubleshooting Guides](troubleshooting/)
4. [Historical Investigations](investigation/)

### I want to understand the architecture
1. [Architecture Overview](architecture/architecture-overview.md)
2. [Architecture Diagrams](architecture/ARCHITECTURE_DIAGRAMS.md)
3. [ADRs](adr/)

### I want to monitor my node
1. [Metrics and Monitoring](operations/metrics-and-monitoring.md)
2. [Log Triage](runbooks/log-triage.md)
3. [KPI Monitoring Guide](testing/KPI_MONITORING_GUIDE.md)

## Documentation Standards

### File Naming
- Use kebab-case for multi-word files: `block-sync-troubleshooting.md`
- Use UPPERCASE for major documents: `README.md`, `CONTRIBUTING.md`
- Use descriptive names that indicate content

### Directory Structure
- Each major category has its own subdirectory
- Each subdirectory contains a README.md index
- Related documents are grouped together

### Cross-References
- Use relative links within documentation
- Link to related documentation at the end of each document
- Maintain bidirectional links where appropriate

## Contributing to Documentation

When adding new documentation:
1. Determine the appropriate category
2. Follow the existing file naming conventions
3. Add entry to the category's README.md
4. Update this main index if needed
5. Check all cross-references are correct

**Note:** Documentation changes are automatically deployed to GitHub Pages when merged to main/master/develop branches. The workflow validates documentation builds on PRs before merging.

See [CONTRIBUTING.md](../CONTRIBUTING.md) for more details.

## Questions or Feedback?

We'd love to hear from you!

- [GitHub Issues](https://github.com/chippr-robotics/fukuii/issues) — Report issues or suggest improvements
- [GitHub Discussions](https://github.com/chippr-robotics/fukuii/discussions) — Ask questions and share ideas
