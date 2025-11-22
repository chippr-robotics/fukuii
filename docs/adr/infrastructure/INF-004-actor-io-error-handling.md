# ADR-INF-004: Actor IO Error Handling Pattern with Cats Effect

**Status**: Accepted

**Date**: November 2025

**Context**: PR fixing flaky PeerDiscoveryManager tests

## Background

During testing of the `PeerDiscoveryManager` actor, we encountered flaky test failures related to error handling when IO tasks were piped to actor recipients. The root cause was non-deterministic error propagation when using `IO.onError().unsafeToFuture().pipeTo()` pattern.

### The Problem

The original error handling pattern in `PeerDiscoveryManager.pipeToRecipient`:

```scala
task
  .onError(ex => IO(log.error(ex, "Failed to relay result to recipient.")))
  .unsafeToFuture()
  .pipeTo(recipient)
```

This approach had several issues:

1. **Non-deterministic behavior**: `IO.onError` runs a callback on errors but rethrows the original error. When combined with `unsafeToFuture().pipeTo()`, the timing of logging vs. message delivery was unpredictable.

2. **Race conditions**: Actor state transitions could race with error handling, leading to inconsistent actor state.

3. **Flaky tests**: Tests that simulated IO failures would sometimes pass and sometimes fail due to timing issues.

4. **Unclear error delivery**: Recipients would receive Scala `Failure` messages, but the conversion wasn't explicit in the code, making the error handling contract unclear.

### Evidence from CI/Testing

- Job 56121089316 showed failing tests with logs: "Failed to start peer discovery." and "Failed to relay result to recipient."
- Tests like "keep serving the known peers if the service fails to start" and "propagate any error from the service to the caller" exhibited intermittent failures.
- The error log "Failed to relay result to recipient." appeared even when tests passed, indicating error handling was executing but in a non-deterministic way.

## Decision

**We adopt an explicit error handling pattern for all IO tasks piped to actors:**

```scala
private def pipeToRecipient[T](recipient: ActorRef)(task: IO[T]): Unit = {
  implicit val ec = context.dispatcher
  
  // Convert IO[T] into a Future[Either[Throwable, T]] so we can explicitly handle errors
  val attemptedF = task.attempt.unsafeToFuture()
  
  // Map Left(ex) -> Status.Failure(ex) so recipients get a clear Failure message
  val mappedF = attemptedF.map {
    case Right(value) => value
    case Left(ex)     => Status.Failure(ex)
  }
  
  mappedF.pipeTo(recipient)
}
```

### Key Principles

1. **Use `IO.attempt`**: Convert `IO[T]` to `IO[Either[Throwable, T]]` to make error handling explicit.

2. **Map to `Status.Failure`**: Convert `Left(ex)` to `org.apache.pekko.actor.Status.Failure(ex)` before piping to recipients.

3. **Deterministic delivery**: Recipients always receive either:
   - The expected message type `T` on success
   - `Status.Failure(ex)` on error

4. **No side-effects in error path**: Avoid callbacks like `onError` that introduce timing dependencies.

5. **Self-piping requires failure handlers**: When piping to `self`, the actor's receive method must handle `Status.Failure` messages.

## Implementation

### Files Updated

1. **PeerDiscoveryManager.scala**:
   - Added import: `org.apache.pekko.actor.Status`
   - Updated `pipeToRecipient` method to use explicit error handling
   - All tests passing, no more "Failed to relay result to recipient" errors

2. **PeerManagerActor.scala**:
   - Added `pipeToRecipient` helper method (same pattern)
   - Updated `GetPeers` handler to use `pipeToRecipient(sender())(getPeers(...))`
   - Updated `SchedulePruneIncomingPeers` handler to use `pipeToRecipient(self)(...)`
   - Added `Status.Failure` handler in `handlePruning` to gracefully handle pruning errors

### Pattern for Piping to External Actors

When piping IO results to external actors (e.g., `sender()` from an ask):

```scala
case GetSomething =>
  pipeToRecipient(sender())(fetchSomething())
```

The caller will receive either:
- The result on success
- `Status.Failure(ex)` on error (which causes `Future` from `ask` to fail with the exception)

### Pattern for Piping to Self

When piping IO results to `self`:

```scala
case StartAsyncOperation =>
  pipeToRecipient(self)(performOperation())

case Status.Failure(ex) =>
  log.warning("Async operation failed: {}", ex.getMessage)
  // Handle failure appropriately (retry, fallback, etc.)
```

The actor must explicitly handle `Status.Failure` messages.

## Consequences

### Positive

1. **Deterministic error behavior**: Errors are always delivered as `Status.Failure` messages.

2. **No race conditions**: State transitions and error handling are ordered by the actor mailbox.

3. **Testable**: Tests can reliably assert on error cases without flakiness.

4. **Clear contract**: The error handling contract is explicit in the code.

5. **Consistent pattern**: Same pattern works for all IO-to-actor scenarios.

6. **Better debugging**: `Status.Failure` messages are visible in actor system logs with standard formatting.

### Negative

1. **Boilerplate**: Each actor using IO needs its own `pipeToRecipient` helper or needs to import a shared one.

2. **Learning curve**: Developers need to understand this pattern vs. the simpler but flaky direct `pipeTo`.

3. **Status.Failure handling**: Actors piping to `self` must remember to handle `Status.Failure`.

### Migration Impact

- **Low risk**: The change is localized to error handling paths and doesn't affect success cases.
- **Backward compatible**: External callers see the same behavior (Future fails on error).
- **Test improvements**: Flaky tests become stable.

## Related Patterns

### When NOT to Use This Pattern

1. **Pure actor messages**: When not using Cats Effect IO at all.

2. **context.pipeToSelf**: Pekko's `context.pipeToSelf` has built-in error handling and is preferred when the Future is already constructed.

3. **Synchronous operations**: When the operation is purely synchronous, use regular message sends.

### Alternative Approaches Considered

1. **Domain-level error messages**: Wrap results in ADTs like `Result[T]` or `OperationResult[T]`. 
   - **Rejected**: More boilerplate, and `Status.Failure` is a standard Pekko pattern.

2. **Try[T] instead of Either**: Use `task.attempt.map(_.toTry)`.
   - **Rejected**: `Either` is more composable and explicit in Scala 3.

3. **Supervisor strategy**: Let actors crash and restart on errors.
   - **Rejected**: Not appropriate for expected errors like network timeouts or resource allocation failures.

## Future Considerations

1. **Shared utility**: Consider creating a shared `ActorIOOps` trait with `pipeToRecipient` to reduce boilerplate.

2. **Typed actors**: When/if migrating to Pekko Typed, the equivalent pattern would use typed message protocols with explicit error types.

3. **Monitoring**: Consider adding metrics for `Status.Failure` frequency to detect systemic issues.

4. **Documentation**: Update internal developer docs with this pattern as a best practice.

## Compliance Check

All network and actor code using `unsafeToFuture().pipeTo()` should be reviewed:

- ✅ `PeerDiscoveryManager.pipeToRecipient` - Updated with explicit error handling
- ✅ `PeerManagerActor.pipeToRecipient` - Updated with explicit error handling
- ✅ `PeerManagerActor.handlePruning` - Added `Status.Failure` handler
- ✅ Regular sync actors (`BodiesFetcher`, `StateNodeFetcher`, `HeadersFetcher`) - Use `context.pipeToSelf` with explicit error handling
- ⚠️ `StateStorageActor` - Pipes to `self`, has `case Failure(e) => throw e` handler (rethrows)
- ⚠️ `SyncStateSchedulerActor` - Pipes to `self` but lacks explicit `Status.Failure` handler (future improvement)

### Future Improvements

The following actors should be reviewed and potentially updated in future work:

1. **StateStorageActor**: Currently rethrows failures with `case Failure(e) => throw e`. Consider whether graceful error handling would be more appropriate than crashing the actor.

2. **SyncStateSchedulerActor**: Pipes IO results to `self` but doesn't explicitly handle `Status.Failure`. Should add handler to prevent unhandled messages.

## References

- [Cats Effect IO](https://typelevel.org/cats-effect/docs/core/io)
- [Pekko Actor Error Handling](https://pekko.apache.org/docs/pekko/current/general/supervision.html)
- [Pekko Status.Failure](https://pekko.apache.org/api/pekko/current/org/apache/pekko/actor/Status$$Failure.html)
- Original issue: Fix flaky PeerDiscoveryManager tests
- ADR-INF-002: Actor System Architecture (context on untyped actors)

## Related Issues

- Flaky PeerDiscoveryManager tests (resolved)
- CI job 56121089316 (fixed)
- Future: Apply pattern to other actors as needed
