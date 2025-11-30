# ADR-001a: Netty Channel Lifecycle with Cats Effect IO

**Status**: Accepted

**Date**: November 2025

**Parent ADR**: [INF-001: Scala 3 Migration](INF-001-scala-3-migration.md)

**Deciders**: Chippr Robotics LLC Engineering Team

## Context

During the Scala 3 and Cats Effect 3 migration (ADR-001), we encountered a critical issue with the vendored scalanet library's UDP channel management. Channels were reporting as "CLOSED" during peer enrollment despite successful bind operations, preventing peer discovery from functioning.

### Original Implementation (IOHK scalanet with Monix Task)

The original IOHK scalanet library (v0.8.0) used Monix Task and followed this pattern:

```scala
// Original Monix Task pattern
private lazy val serverBinding: ChannelFuture = 
  new Bootstrap().bind(localAddress)

override def client(to: Address): Resource[Task, Channel] = {
  for {
    _ <- Resource.liftF(raiseIfShutdown)
    remoteAddress = to.inetSocketAddress
    channel <- Resource {
      ChannelImpl(
        nettyChannel = serverBinding.channel,  // Direct access to lazy val
        localAddress = localAddress,
        remoteAddress = remoteAddress,
        ...
      ).allocated
    }
  } yield channel
}

private def initialize: Task[Unit] =
  toTask(serverBinding)  // Wait for bind to complete
    .onErrorRecoverWith { ... }
```

**Key Characteristics:**
- `serverBinding` is a lazy val that creates and caches the ChannelFuture
- `initialize()` waits for the bind operation to complete via `toTask()`
- Client channels access the Netty channel directly via `serverBinding.channel`
- No intermediate caching of the channel reference

### Migrated Implementation (Initial Cats Effect IO Attempt)

The initial migration to Cats Effect IO introduced an optimization:

```scala
// Initial CE3 migration with boundChannelRef
class StaticUDPPeerGroup[M] private (
    ...
    boundChannelRef: Ref[IO, Option[io.netty.channel.Channel]]
)

private def initialize: IO[Unit] =
  for {
    _ <- toTask(serverBinding)
    channel = serverBinding.channel()
    _ <- boundChannelRef.set(Some(channel))  // Cache channel reference
  } yield ()

override def client(to: Address): Resource[IO, Channel] = {
  for {
    nettyChannel <- Resource.eval(boundChannelRef.get.flatMap {
      case Some(ch) => IO.pure(ch)
      case None => IO.raiseError(...)
    })
    channel <- Resource { ... }
  } yield channel
}
```

**Problems Introduced:**
1. **Race Condition**: The channel reference was cached in `boundChannelRef` before Netty's async initialization completed
2. **State Staleness**: Accessing the cached reference could return a channel in an intermediate state
3. **Thread Safety**: The channel state was being inspected from different threads than Netty's event loop
4. **Lazy Val Semantics**: The lazy val `serverBinding` evaluation timing differed between Task and IO contexts

## Investigation Findings

### Netty Channel Lifecycle

Understanding Netty's channel lifecycle was critical:

```
1. Bootstrap.bind() called
   ↓
2. Channel created (NEW state)
   ↓  
3. Channel registered with EventLoopGroup (REGISTERED)
   ↓
4. Bind operation initiated (BINDING)
   ↓
5. Bind completes, ChannelFuture fires (BOUND)
   ↓
6. Channel becomes active (ACTIVE)
   ↓
7. Channel ready for I/O operations
```

**Critical Insight**: The ChannelFuture returned by `bind()` completes at step 5, but the channel may not be in ACTIVE state (step 6) immediately. The cached channel reference at step 5 could be inspected before step 6 completes.

### Monix Task vs Cats Effect IO Differences

| Aspect | Monix Task | Cats Effect IO |
|--------|-----------|----------------|
| Evaluation | Lazy by default | Depends on context (eager/lazy) |
| Thread Pool | Scheduler-based | Work-stealing executor |
| Future Integration | Direct `Task.fromFuture` | `IO.async` with callbacks |
| Lazy Val Interaction | Predictable sequencing | Can vary with fiber scheduling |
| Blocking Operations | Explicit `.executeOn` | `IO.blocking` shift |

**Key Discovery**: When `serverBinding` (a lazy val containing a ChannelFuture) is evaluated in an IO context, the timing of when downstream operations see the channel state can vary based on fiber scheduling. Monix Task's scheduler had more predictable sequencing.

### Root Cause Analysis

The bug manifested as:
```
ERROR - Netty channel is CLOSED when trying to send
Channel: NioDatagramChannel, isActive: false, isRegistered: false
```

Root causes:
1. **Premature Caching**: `boundChannelRef.set(Some(channel))` happened before the channel was fully active
2. **Async Completion**: The bind future completing doesn't guarantee channel activation
3. **Cross-Thread Access**: Checking `channel.isActive` from IO fiber vs Netty event loop thread
4. **Resource Cleanup**: If initialization checks failed, the EventLoopGroup shut down, closing all channels

## Decision

We decided to revert to the original IOHK scalanet pattern:

```scala
// Corrected CE3 pattern (matches original)
class StaticUDPPeerGroup[M] private (
    ...
    // boundChannelRef removed
)

private lazy val serverBinding: io.netty.channel.ChannelFuture =
  new Bootstrap()
    .group(workerGroup)
    .channel(classOf[NioDatagramChannel])
    .bind(localAddress)

private def initialize: IO[Unit] =
  for {
    _ <- toTask(serverBinding).handleErrorWith { ... }
    _ <- IO(logger.info(s"Server bound to address ${config.bindAddress}"))
  } yield ()

override def client(to: Address): Resource[IO, Channel] = {
  for {
    _ <- Resource.eval(raiseIfShutdown)
    remoteAddress = to.inetSocketAddress
    nettyChannel = serverBinding.channel()  // Direct access, no caching
    channel <- Resource { ... }
  } yield channel
}
```

**Key Changes:**
1. Removed `boundChannelRef` parameter and all caching
2. Access channel directly from `serverBinding.channel()` like the original
3. Simplified `initialize()` to match original pattern
4. Let Netty's internal synchronization handle channel state

## Consequences

### Positive

1. **Eliminated Race Condition**: No premature caching of channel references
2. **Simpler Code**: Removed complexity of managing `boundChannelRef`
3. **Proven Pattern**: Matches battle-tested original IOHK implementation
4. **Thread Safety**: Let Netty manage its own threading and state
5. **Test Validation**: All 3 unit tests pass reliably; initialization and shutdown work correctly
6. **Robust Shutdown**: Synchronous channel close with error handling prevents shutdown failures

### Negative

1. **Migration Complexity**: Required deep understanding of Netty and effect system differences
2. **Investigation Time**: Significant effort to identify and resolve both initialization and shutdown races

### Neutral

1. **Performance**: No measurable difference (caching would have been premature optimization anyway)
2. **Type Safety**: Both approaches are type-safe; the issues were runtime lifecycle management

## Lessons Learned

### For Future Effect System Migrations

1. **Validate Async Resource Lifecycles**: Don't assume type-level compatibility means behavioral compatibility
2. **Compare Line-by-Line**: When vendoring libraries, compare with original implementation closely
3. **Test Resource Initialization**: Create specific tests for resource lifecycle sequences
4. **Avoid Premature Optimization**: Don't cache async resources unless proven necessary
5. **Thread Awareness**: Be aware of which thread pool/executor is being used for operations
6. **Understand Framework Internals**: Deep understanding of Netty's lifecycle was essential

### Pattern for Netty + Cats Effect Integration

**DO:**
- Let Netty manage its own channel state and threading
- Access channels directly from ChannelFutures when needed
- Wait for bind futures to complete before considering resources ready
- Use `IO.blocking` for operations that might block on Netty event loops
- Use synchronous channel operations (`.syncUninterruptibly()`) in shutdown paths
- Add comprehensive logging during debugging to track state transitions
- Handle errors gracefully in shutdown code to avoid cascading failures

**DON'T:**
- Cache Netty channel references in separate Refs/state holders
- Inspect channel state from threads other than Netty's event loop
- Assume ChannelFuture completion means full resource readiness
- Use async operations in shutdown that schedule on potentially-terminating executors
- Optimize prematurely by introducing intermediate caching
- Skip comparing with original implementations when migrating
- Let shutdown failures propagate without error handling

### Debugging Approach That Worked

1. **Compare with Original**: Looked at IOHK scalanet v0.8.0 source code
2. **Add Detailed Logging**: Tracked channel state through initialization sequence
3. **Check Thread Context**: Logged which thread/executor was running operations
4. **Test Channel State**: Verified `isOpen`, `isActive`, `isRegistered` at each step
5. **Follow Netty Lifecycle**: Understood the channel's state machine
6. **Simplify Incrementally**: Removed complexity until matching original pattern

## Implementation Notes

### Testing Strategy

Unit tests validate:
- Basic initialization works and channel becomes active
- Client channels can be created after initialization
- Multiple peer groups can coexist and shut down cleanly

**Final Resolution (November 2025):**
All three unit tests now pass reliably after fixing the shutdown race condition:

1. **Shutdown Race Fix**: The final issue was in the `shutdown()` method, which used `toTask(channel.close())` to asynchronously close the channel. This scheduled work on Netty's EventLoopGroup, but when multiple peer groups were shutting down in sequence, the executor could already be terminating, causing "event executor terminated" errors.

2. **Solution**: Changed to synchronous close with error handling:
```scala
// Before (async scheduling that could fail):
_ <- toTask(serverBinding.channel().close())

// After (synchronous close with error handling):
_ <- IO {
  val channel = serverBinding.channel()
  if (channel.isOpen) {
    channel.close().syncUninterruptibly()
  }
}.handleErrorWith { error =>
  IO(logger.warn(s"Error closing channel: ${error.getMessage}"))
}
```

This avoids scheduling on the potentially-shutting-down executor and handles errors gracefully.

Integration tests (in production):
- Actual peer discovery and enrollment
- Long-running stability
- Network edge cases (timeouts, unreachable peers, etc.)

### Migration Checklist for Similar Issues

If encountering similar issues elsewhere in the codebase:

- [ ] Compare vendored code with original line-by-line
- [ ] Check for cached references to async resources
- [ ] Validate resource lifecycle timing (creation → ready → cleanup)
- [ ] Test cross-thread state inspection
- [ ] Add lifecycle logging
- [ ] Create unit tests for resource initialization
- [ ] Simplify to match proven patterns
- [ ] Document findings in ADR

## References

- [GitHub Issue #337](https://github.com/chippr-robotics/fukuii/issues/337)
- [PR Fix - Commit 61d2076](https://github.com/chippr-robotics/fukuii/commit/61d2076)
- [Original IOHK scalanet v0.8.0](https://github.com/input-output-hk/scalanet)
- [Netty User Guide - Channel Lifecycle](https://netty.io/wiki/user-guide-for-4.x.html)
- [Cats Effect 3 Documentation - Resource](https://typelevel.org/cats-effect/docs/std/resource)
- [INF-001: Scala 3 Migration](INF-001-scala-3-migration.md)

## Review and Update

This ADR should be reviewed when:
- Additional Netty integration issues are discovered
- Cats Effect releases major version updates
- Performance issues arise in network layer
- Similar patterns are needed elsewhere (HTTP clients, database connections, etc.)
