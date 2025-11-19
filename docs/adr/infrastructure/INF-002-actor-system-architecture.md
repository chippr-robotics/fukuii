# ADR-009: Actor System Architecture - Untyped vs Typed Actors

**Status**: Accepted (Documenting Current State)

**Date**: November 2025

**Context**: PR #302 (Fix NumberFormatException during network sync)

## Background

During PR #302, a discussion arose about the use of untyped vs typed actors in the codebase. The `ConsoleUIUpdater` class was updated to use untyped `ActorSystem` instead of typed `ActorSystem[_]`, which raised questions about whether this is intentional or a deviation from best practices.

## Current State Analysis

### Inherited from Mantis

The Fukuii codebase is a fork of Mantis, which was originally built entirely on **untyped (classic) Akka actors**. During the migration documented in ADR-001, the codebase was migrated from Akka to Apache Pekko, but the actor model remained predominantly untyped.

**Evidence:**
- The core `Node` trait extends `ActorSystemBuilder` which defines: `implicit lazy val system: ActorSystem` (untyped)
- 15+ core components import `org.apache.pekko.actor.ActorSystem` (untyped)
- Only 1 file imports `org.apache.pekko.actor.typed.ActorSystem` (StdNode.scala)
- The entire networking, consensus, and blockchain sync infrastructure uses untyped actors

### Partial Typed Actor Adoption

Some newer components DO use typed actors:
- `BlockFetcher`, `BodiesFetcher`, `StateNodeFetcher`, `HeadersFetcher` (sync components)
- `PoWMiningCoordinator` and related mining protocols
- `PeriodicConsistencyCheck`

These appear to be isolated typed actor implementations that coexist with the untyped system.

### The Specific Case: ConsoleUIUpdater

The `ConsoleUIUpdater` class initially tried to reference:
- `ActorRef[PeerManagerActor.PeerManagementCommand]`
- `ActorRef[SyncProtocol.Command]`

However, these types don't exist in the codebase. The core actor references (`peerManager`, `syncController`) are untyped `ActorRef` objects. The change to `Option[Any]` and untyped `ActorSystem` was necessary for compilation and is consistent with the actual usage patterns.

## Decision

**We accept the current hybrid approach** where:

1. **The core system remains untyped** - This includes:
   - Node infrastructure and actor system initialization
   - Network layer (PeerManager, ServerActor, etc.)
   - JSON-RPC servers
   - Consensus and blockchain core

2. **New isolated components MAY use typed actors** where:
   - They are self-contained subsystems
   - They don't need to integrate deeply with legacy untyped components
   - The team has bandwidth to implement them properly

3. **The ConsoleUIUpdater uses untyped actors** because:
   - It integrates with untyped core components (PeerManagerActor, SyncController)
   - It's a UI/monitoring component, not a critical path
   - The actor references are currently unused (placeholder for future functionality)

## Rationale

### Why Not Migrate Everything to Typed Actors?

**Effort vs Benefit Analysis:**
- **Scope**: Would require rewriting 50+ actor classes and 200+ actor interactions
- **Risk**: High risk of introducing bugs in consensus-critical code
- **Testing**: Would require extensive integration testing and validation
- **Timeline**: Estimated 6-8 weeks of full-time engineering effort
- **Value**: Limited immediate benefit - the untyped system works reliably

**Pekko Documentation Position:**
- Apache Pekko maintains both classic (untyped) and typed APIs
- Classic actors are not deprecated and continue to receive support
- Migration is recommended but not required
- Interoperability patterns exist for hybrid systems

### Why Keep the Hybrid Approach?

1. **Pragmatism**: Allows new features to use typed actors without blocking on a complete migration
2. **Risk Management**: Avoids touching battle-tested consensus and networking code
3. **Incremental Progress**: New components can adopt typed actors as appropriate
4. **Compatibility**: Pekko provides adapters for typed/untyped interop

## Consequences

### Positive

1. **Stability**: Core consensus and networking code remains unchanged and stable
2. **Flexibility**: New components can choose typed actors when beneficial
3. **Reduced Risk**: No large-scale refactoring of critical code paths
4. **Clear Documentation**: This ADR provides context for future maintainers

### Negative

1. **Inconsistency**: Mixed actor models in the codebase
2. **Learning Curve**: Developers need to understand both paradigms
3. **Technical Debt**: Eventually may want to migrate entirely to typed actors
4. **Interop Complexity**: Bridging typed/untyped requires adapters in some cases

## Future Considerations

### When to Use Typed Actors

Use typed actors for:
- New, isolated subsystems
- Components with complex message protocols
- Code that benefits from compile-time message type checking
- Non-critical path features

### When to Use Untyped Actors

Continue using untyped actors for:
- Core infrastructure (networking, consensus, blockchain)
- Integration with existing untyped components
- UI/monitoring components that interact with untyped core
- Any changes where migration risk outweighs benefits

### Potential Future Migration

A full migration to typed actors could be considered when:
1. Team bandwidth allows for multi-week refactoring effort
2. Comprehensive test coverage is in place (integration & property tests)
3. Business value justifies the engineering investment
4. A clear migration plan with rollback strategy exists

Such a migration would be tracked in a separate ADR if undertaken.

## References

- [Apache Pekko Classic Actors](https://pekko.apache.org/docs/pekko/current/actors.html)
- [Apache Pekko Typed Actors](https://pekko.apache.org/docs/pekko/current/typed/index.html)
- [Coexistence Between Classic and Typed](https://pekko.apache.org/docs/pekko/current/typed/coexisting.html)
- ADR-001: Migration to Scala 3 and JDK 21
- PR #302: Fix NumberFormatException during network sync
- Original Mantis codebase (untyped actors throughout)

## Related Issues

- PR #302 - ConsoleUIUpdater actor system type discussion
- Future: Consider typed actor migration for new features only
