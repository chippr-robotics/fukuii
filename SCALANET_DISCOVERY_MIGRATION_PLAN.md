# ScalanetDiscovery Module - Monix to Cats Effect 3 Migration Plan

## Overview
The scalanetDiscovery module has **120 compilation errors** due to incomplete Monix → Cats Effect 3 migration.
This module contains the Kademlia DHT implementation for peer discovery.

## Current Status
- **Total files**: 45 Scala files
- **Main problem files**: KRouter.scala, KNetwork.scala (contain most errors)
- **Error types**: Observable, Consumer, Task references that don't exist in CE3

## Migration Strategy

### Phase 1: Core API Replacements (Estimated: 2-3 hours)

#### 1.1 Observable → fs2.Stream
**Files affected**: KRouter.scala, KNetwork.scala
```scala
// OLD (Monix):
Observable.intervalWithFixedDelay(duration, duration)
  .consumeWith(Consumer.foreachTask { ... })

// NEW (CE3 + fs2):
Stream.awakeEvery[IO](duration)
  .evalMap { _ => ... }
  .compile.drain
```

#### 1.2 Task → IO  
**Files affected**: KRouter.scala, KNetwork.scala
```scala
// OLD:
Task(logger.debug(...))
Task.now(value)
Task.eval(...)

// NEW:
IO(logger.debug(...))
IO.pure(value)
IO.delay(...)
```

#### 1.3 Consumer → fs2 compilation/evaluation
**Files affected**: KRouter.scala
```scala
// OLD:
Consumer.foreachTask { x => ... }
Consumer.foreachParallelIO[A](parallelism = 4) { ... }

// NEW:
stream.evalMap { x => ... }.compile.drain
stream.parEvalMap(4) { x => ... }.compile.drain  // for parallel
```

### Phase 2: Stream Operations (Estimated: 1-2 hours)

#### 2.1 headL → head/headOption
**Files affected**: KNetwork.scala
```scala
// OLD:
observable.headL

// NEW:
stream.head.compile.lastOrError  // or
stream.head.compile.last  // returns Option
```

#### 2.2 timeout → timeout with Duration
**Files affected**: KNetwork.scala
```scala
// OLD:
task.timeout(duration)

// NEW:
io.timeout(duration)  // Same API in CE3
```

#### 2.3 onErrorHandleWith → handleErrorWith
**Files affected**: KNetwork.scala
```scala
// OLD:
task.onErrorHandleWith { case ex => ... }

// NEW:
io.handleErrorWith { case ex => ... }
```

#### 2.4 guarantee → guarantee
**Files affected**: KNetwork.scala
```scala
// OLD:
task.guarantee(cleanup)

// NEW:
io.guarantee(cleanup)  // Same API in CE3
```

### Phase 3: Fiber & Concurrency (Estimated: 1 hour)

#### 3.1 startAndForget → start.void or unsafeRunAndForget
**Files affected**: KRouter.scala
```scala
// OLD:
task.startAndForget

// NEW (proper):
io.start.void

// NEW (unsafe, for fire-and-forget):
io.unsafeRunAndForget()(runtime)
```

#### 3.2 Parallel execution with parEvalMap
**Files affected**: KRouter.scala
```scala
// OLD:
Consumer.foreachParallelIO[A](parallelism = 4) { case x => ... }

// NEW:
stream.parEvalMap(4) { case x => ... }.compile.drain
```

### Phase 4: Stream Construction (Estimated: 1 hour)

#### 4.1 Stream.fromTask → Stream.eval
**Files affected**: KNetwork.scala
```scala
// OLD:
Stream.fromTask(task)

// NEW:
Stream.eval(io)
```

#### 4.2 mergeMap → flatMap or parJoin
**Files affected**: KNetwork.scala
```scala
// OLD:
stream.mergeMap { x => ... }

// NEW:
stream.flatMap { x => ... }  // sequential
// OR
stream.parJoin(maxOpen) { x => ... }  // parallel
```

### Phase 5: Type Pattern Matching Warnings (Estimated: 30 min)

#### 5.1 Unchecked type warnings
**Files affected**: KNetwork.scala
```scala
// Issue:
case (channel: Channel[A, KMessage[A]], release: IO[Unit])  // IO[Unit] unchecked

// Fix: Add @unchecked annotation or use wildcard
case (channel: Channel[A, KMessage[A]], release) => 
  val typedRelease: IO[Unit] = release
```

#### 5.2 Abstract type erasure warnings
```scala
// Issue:
case MessageReceived(req: KRequest[A])  // A unchecked

// Fix: Accept limitation or use ClassTag
case MessageReceived(req: KRequest[_])  // if type doesn't matter
```

## File-by-File Breakdown

### High Priority Files (Core Logic)

1. **KRouter.scala** (~40 errors)
   - Replace Observable with Stream
   - Replace Consumer with stream compilation
   - Replace Task with IO
   - Fix startAndForget

2. **KNetwork.scala** (~30 errors)
   - Replace Observable with Stream  
   - Replace Task with IO
   - Fix headL → head.compile
   - Fix Stream.fromTask

### Medium Priority Files (Support Code)

3. **Other kademlia files** (~50 errors)
   - Mostly cascading type errors from KRouter/KNetwork
   - Should resolve automatically after fixing core files

## Dependencies Check

Ensure these are in build.sbt:
```scala
libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "3.5.4",
  "co.fs2" %% "fs2-core" % "3.9.3",
  "co.fs2" %% "fs2-io" % "3.9.3"
)
```

## Testing Strategy

1. **After Phase 1**: Compile and fix syntax errors
2. **After Phase 2**: Compile and verify stream operations
3. **After Phase 3**: Compile and check for remaining errors
4. **After Phase 4**: Full compilation of scalanetDiscovery module
5. **After Phase 5**: Address any remaining warnings

## Expected Outcomes

- ✅ All 120 compilation errors resolved
- ✅ ScalanetDiscovery module compiles successfully
- ✅ Consistent use of CE3 APIs throughout
- ✅ Proper fs2.Stream usage for reactive streams
- ✅ No Monix dependencies remaining

## Notes

- KRouter and KNetwork are the core files - fix these first
- Many errors will cascade once core files are fixed
- fs2.Stream is the standard streaming library for CE3
- Some APIs are similar (timeout, guarantee) but imports matter
- Test incrementally to catch issues early

## Success Criteria

1. `sbt scalanetDiscovery/compile` succeeds with 0 errors
2. No Monix imports remain in the codebase
3. All streams use fs2.Stream
4. All effects use cats.effect.IO
5. Code follows CE3 best practices
