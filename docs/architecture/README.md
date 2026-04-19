# Architecture Documentation

This directory contains architectural documentation for the Fukuii EVM execution layer (EL) client — a Scala 3 Ethereum execution engine that supports PoW (Ethash) for Ethereum Classic and Engine API-driven PoS for post-Merge Ethereum networks under a pluggable consensus architecture.

## Contents

### Architecture Overview
- **[Architecture Overview](architecture-overview.md)** - High-level system architecture and component interactions, including the Engine API / Consensus Layer Integration subsystem
- **[Architecture Diagrams](ARCHITECTURE_DIAGRAMS.md)** - C4 architecture diagrams and visual representations
- **[Pluggable Consensus Vision](pluggable-consensus-vision.md)** - Three-layer `fukuii-core` / `fukuii-env` / consensus-module architecture for multi-network support with Orbita sidechains

### User Interfaces
- **[Console UI](console-ui.md)** - Console user interface design and implementation
- **[Console UI Mockup](console-ui-mockup.txt)** - Text-based UI mockup

## Related Documentation

- [Architecture Decision Records (ADRs)](../adr/README.md) - Detailed architectural decisions with context and rationale
- [Operations Runbooks](../runbooks/README.md) - Operational guides for running nodes
- [Deployment Guides](../deployment/README.md) - Docker and deployment documentation

## See Also

- [Documentation Home](../index.md)
- [Contributing Guide](../development/contributing.md)
