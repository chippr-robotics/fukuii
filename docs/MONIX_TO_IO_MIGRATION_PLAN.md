# Monix to Cats Effect 3 IO Migration Plan

**Date**: October 27, 2025  
**Repository**: chippr-robotics/fukuii  
**Current**: Monix 3.4.1 (Task, Observable, Scheduler)  
**Target**: Cats Effect 3.5.4 (IO, fs2 Stream, IORuntime)  
**Estimated Effort**: 4-6 weeks (1-2 senior developers)

---

## Executive Summary

This document provides a detailed plan for migrating from Monix 3.4.1 to Cats Effect 3 IO ecosystem. This migration is **required** to unblock the Cats Effect 3 upgrade (see [CATS_EFFECT_3_MIGRATION.md](./CATS_EFFECT_3_MIGRATION.md) and [MONIX_CE3_COMPATIBILITY_ANALYSIS.md](./MONIX_CE3_COMPATIBILITY_ANALYSIS.md)).

**Migration Scope**:
- **85 files** using `monix.eval.Task`
- **16 files** using `monix.reactive.Observable`
- **59 files** using `monix.execution.Scheduler`
- **Total affected**: ~100+ unique Scala files

**Key Components**:
1. Replace `Task[A]` with `IO[A]`
2. Replace `Observable[A]` with `fs2.Stream[IO, A]`
3. Replace `Scheduler` with `IORuntime`
4. Update actor integration patterns
5. Migrate test infrastructure

---

## Table of Contents

1. [Monix Usage Analysis](#1-monix-usage-analysis)
2. [Migration Mapping](#2-migration-mapping)
3. [fs2-streams Adoption Plan](#3-fs2-streams-adoption-plan)
4. [Module-by-Module Strategy](#4-module-by-module-strategy)
5. [Pattern-by-Pattern Migration](#5-pattern-by-pattern-migration)
6. [Testing Strategy](#6-testing-strategy)
7. [Timeline and Resources](#7-timeline-and-resources)
8. [Risk Mitigation](#8-risk-mitigation)
9. [Implementation Checklist](#9-implementation-checklist)

---

## 1. Monix Usage Analysis

### 1.1 Monix Components Inventory

| Component | Usage Count | Complexity | Priority |
|-----------|-------------|------------|----------|
| `monix.eval.Task` | 85 files | High | Critical |
| `monix.execution.Scheduler` | 59 files | Medium | Critical |
| `monix.reactive.Observable` | 16 files | High | Critical |
| `monix.tail.Iterant` | 1 file | Low | Medium |
| `monix.catnap` | 1 file | Low | Low |

### 1.2 Key Areas of Usage

#### Production Code (src/main)

**Database Layer** (Heavy Observable usage):
- `RocksDbDataSource.scala` - Iterator over database keys
- `EvmCodeStorage.scala` - Storage iteration
- `KeyValueStorage.scala` - Key-value iterations
- `NodeStorage.scala` - Node data streaming

**Blockchain Sync** (Heavy Task usage):
- `BlockImporter.scala` - Block import pipeline
- `HeadersFetcher.scala` - Header fetching
- `StateStorageActor.scala` - State sync
- `SyncStateScheduler.scala` - Sync scheduling with Observable

**Network Layer**:
- `PeerDiscoveryManager.scala` - Peer discovery with Observable
- `PortForwarder.scala` - Resource management with Task

**Transaction Processing**:
- `TransactionHistoryService.scala` - Heavy Observable usage for tx history
- `PendingTransactionsManager.scala` - Transaction pool management

**Consensus**:
- `ConsensusAdapter.scala` - Consensus integration
- `Mining.scala` - Mining operations

#### Test Code (src/test, src/it)

**Test Infrastructure**:
- `SpecBase.scala` - Base test utilities
- `ResourceFixtures.scala` - Resource management for tests
- `FastSyncItSpecUtils.scala` - Integration test utilities
- `EtcPeerManagerFake.scala` - Mock implementations

**Integration Tests**:
- `RockDbIteratorSpec.scala` - Database iteration tests
- `RegularSyncSpec.scala` - Sync testing
- Various actor specs with Task-based testing

### 1.3 Complexity Analysis by Pattern

| Pattern | Count | Migration Complexity | Notes |
|---------|-------|---------------------|-------|
| Simple Task.pure/Task.eval | ~40 | Low | Straightforward IO conversion |
| Task.flatMap chains | ~30 | Low-Medium | Similar IO flatMap |
| Task with Scheduler | ~25 | Medium | Requires IORuntime migration |
| Observable streaming | ~15 | High | Requires fs2 Stream rewrite |
| Observable.concat/merge | ~5 | High | fs2 combinators different |
| Task <-> Akka Future | ~20 | Medium | Different interop pattern |
| Resource[Task, _] | ~8 | Low | Already CE3 compatible |

---

## 2. Migration Mapping

### 2.1 Monix Task → Cats Effect IO

| Monix Task | Cats Effect IO | Complexity |
|------------|----------------|------------|
| `Task.pure(a)` | `IO.pure(a)` | Trivial |
| `Task.eval(expr)` | `IO.delay(expr)` | Trivial |
| `Task(expr)` | `IO(expr)` | Trivial |
| `Task.defer(task)` | `IO.defer(io)` | Trivial |
| `Task.now(a)` | `IO.pure(a)` | Trivial |
| `Task.raiseError(e)` | `IO.raiseError(e)` | Trivial |
| `task.flatMap(f)` | `io.flatMap(f)` | Trivial |
| `task.map(f)` | `io.map(f)` | Trivial |
| `task.attempt` | `io.attempt` | Trivial |
| `task.handleError(f)` | `io.handleError(f)` | Trivial |
| `task.onErrorHandle(f)` | `io.handleError(f)` | Trivial |
| `task.guarantee(fin)` | `io.guarantee(fin)` | Trivial |
| `task.bracket(use)(release)` | `io.bracket(use)(release)` | Trivial |
| `task.memoize` | `io.memoize` | Trivial |
| `task.timeout(d)` | `io.timeout(d)` | Trivial |
| `task.runAsync(cb)` | `io.unsafeRunAsync(cb)` | Low |
| `task.runSyncUnsafe()` | `io.unsafeRunSync()` | Low |
| `task.runToFuture` | `io.unsafeToFuture()` | Low |
| `Task.parSequence(list)` | `list.parSequence` | Low |
| `Task.parTraverse(list)(f)` | `list.parTraverse(f)` | Low |
| `Task.race(t1, t2)` | `IO.race(io1, io2)` | Low |
| `Task.sleep(duration)` | `IO.sleep(duration)` | Low |
| `task.executeOn(scheduler)` | `io.evalOn(ec)` | Medium |
| `task.asyncBoundary` | `IO.cede` | Medium |

**Key Difference**: CE3 IO has built-in fiber management, no need for explicit `ContextShift`.

### 2.2 Monix Scheduler → IORuntime

| Monix Scheduler | Cats Effect IORuntime | Notes |
|----------------|----------------------|-------|
| `implicit val scheduler: Scheduler` | `implicit val runtime: IORuntime` | Runtime provides scheduler |
| `Scheduler(ec)` | `IORuntime.builder().setCompute(ec).build()` | Custom runtime |
| `scheduler.execute(r)` | `runtime.compute.execute(r)` | Access underlying EC |
| `Task.executeOn(scheduler)` | `IO.evalOn(runtime.compute)` | Shift computation |
| Global scheduler | `IORuntime.global` | Default runtime |

**Migration Pattern**:
```scala
// BEFORE (Monix)
implicit val scheduler: Scheduler = Scheduler(context.dispatcher)
task.runToFuture

// AFTER (CE3 IO)
implicit val runtime: IORuntime = IORuntime.global
// Or for Akka integration:
implicit val runtime: IORuntime = {
  val ec = context.dispatcher
  IORuntime.builder()
    .setCompute(ec)
    .setBlocking(ec)
    .build()
}
io.unsafeToFuture()
```

### 2.3 Monix Observable → fs2 Stream

| Monix Observable | fs2 Stream | Complexity |
|------------------|-----------|------------|
| `Observable.pure(a)` | `Stream.emit(a)` | Low |
| `Observable(a, b, c)` | `Stream(a, b, c)` | Low |
| `Observable.from(iterable)` | `Stream.emits(iterable)` | Low |
| `Observable.eval(task)` | `Stream.eval(io)` | Low |
| `obs.map(f)` | `stream.map(f)` | Low |
| `obs.flatMap(f)` | `stream.flatMap(f)` | Low |
| `obs.filter(p)` | `stream.filter(p)` | Low |
| `obs.collect(pf)` | `stream.collect(pf)` | Low |
| `obs.take(n)` | `stream.take(n)` | Low |
| `obs.drop(n)` | `stream.drop(n)` | Low |
| `obs.concat(other)` | `stream ++ other` | Low |
| `obs.merge(other)` | `stream.merge(other)` | Medium |
| `obs.mapParallelOrdered(n)(f)` | `stream.parEvalMap(n)(f)` | Medium |
| `obs.mapParallelUnordered(n)(f)` | `stream.parEvalMapUnordered(n)(f)` | Medium |
| `obs.consumeWith(consumer)` | `stream.compile.to(collector)` | Medium |
| `obs.toListL` | `stream.compile.toList` | Low |
| `obs.foreachL(f)` | `stream.evalMap(f).compile.drain` | Low |
| `obs.foldLeftL(z)(f)` | `stream.fold(z)(f).compile.lastOrError` | Low |
| `Observable.interval(d)` | `Stream.awakeEvery[IO](d)` | Low |
| Overflow strategies | Backpressure built-in | Medium |

**Key Differences**:
- fs2 Stream is pull-based (vs Observable push-based)
- fs2 has built-in backpressure
- fs2 Stream integrates seamlessly with IO
- Different concurrency primitives

---

## 3. fs2-streams Adoption Plan

### 3.1 fs2 Dependency

Add to `project/Dependencies.scala`:
```scala
val fs2Version = "3.9.3" // Latest stable with CE3

val fs2: Seq[ModuleID] = Seq(
  "co.fs2" %% "fs2-core" % fs2Version,
  "co.fs2" %% "fs2-io" % fs2Version,
  "co.fs2" %% "fs2-reactive-streams" % fs2Version // For interop if needed
)
```

### 3.2 Key Files Requiring fs2 Stream Migration

#### High Priority (Heavy Observable Usage)

**1. TransactionHistoryService.scala** (Complex streaming logic)
```scala
// BEFORE (Monix Observable)
val txnsFromBlocks = Observable
  .from(fromBlocks.reverse)
  .mapParallelOrdered(10)(blockNr => Task(blockchainReader.getBlockByNumber(...)))
  .collect { case Some(block) => block }
  .concatMap { block => Observable.from(block.body.transactionList) ... }
  .toListL

// AFTER (fs2 Stream)
val txnsFromBlocks = Stream
  .emits(fromBlocks.reverse.toSeq)
  .parEvalMap(10)(blockNr => IO(blockchainReader.getBlockByNumber(...)))
  .collect { case Some(block) => block }
  .flatMap { block => Stream.emits(block.body.transactionList.toSeq) ... }
  .compile.toList
```

**2. RocksDbDataSource.scala** (Database iteration)
```scala
// BEFORE (Monix Observable)
def iterate(namespace: Namespace): Observable[(Key, Value)]

// AFTER (fs2 Stream)
def iterate(namespace: Namespace): Stream[IO, (Key, Value)]
```

**3. PeerDiscoveryManager.scala** (Network streams)
```scala
// BEFORE (Monix Observable)
randomNodes.consumeWithConfig(...)

// AFTER (fs2 Stream)
randomNodes.through(pipe).compile.drain
```

#### Medium Priority (Simpler Observable Usage)

- `SyncStateScheduler.scala` - State sync iteration
- `LoadableBloomFilter.scala` - Bloom filter operations
- Storage layer abstractions

### 3.3 fs2 Learning Resources

**Official Documentation**:
- [fs2 Guide](https://fs2.io/#/guide)
- [fs2 Concurrency Primitives](https://fs2.io/#/concurrency-primitives)
- [Migration from Akka Streams](https://fs2.io/#/migration-guide)

**Key Concepts**:
- Pull-based streaming
- Chunks for efficiency
- Stream composition with `++`, `merge`, `parJoin`
- Resource safety with `Stream.bracket`
- Concurrency with `parEvalMap`, `parJoin`

---

## 4. Module-by-Module Strategy

### 4.1 Migration Order

Migrate in dependency order to minimize integration issues:

```
Phase 1: Foundation (Week 1-2)
├── bytes module (no Monix usage - already done)
├── rlp module (minimal Monix)
└── crypto module (minimal Monix)

Phase 2: Core Infrastructure (Week 2-3)
├── Database layer (RocksDbDataSource, Storage abstractions)
├── Test infrastructure (SpecBase, ResourceFixtures)
└── Utility modules

Phase 3: Business Logic (Week 3-5)
├── Transaction processing (TransactionHistoryService)
├── Blockchain sync (BlockImporter, HeadersFetcher)
├── Network layer (PeerDiscoveryManager)
└── Consensus and Mining

Phase 4: Integration (Week 5-6)
├── Actor integration patterns
├── RPC handlers
├── Full system testing
└── Performance validation
```

### 4.2 Per-Module Checklist

For each module:
- [ ] Identify all Monix usage in module
- [ ] Create migration branch
- [ ] Update imports
- [ ] Replace Task with IO
- [ ] Replace Observable with Stream (if used)
- [ ] Replace Scheduler with IORuntime
- [ ] Update tests
- [ ] Run module tests
- [ ] Performance benchmark (if critical path)
- [ ] Code review
- [ ] Merge to main branch

---

## 5. Pattern-by-Pattern Migration

### 5.1 Simple Task Operations

**Pattern**: Pure computation with Task

```scala
// BEFORE
import monix.eval.Task

def calculateHash(data: ByteString): Task[Hash] = 
  Task.eval(kec256(data))

// AFTER
import cats.effect.IO

def calculateHash(data: ByteString): IO[Hash] = 
  IO(kec256(data))
```

**Files Affected**: ~40 files  
**Effort**: 1-2 days  
**Risk**: Low

### 5.2 Task with Resource Management

**Pattern**: Resource acquisition and cleanup

```scala
// BEFORE
import cats.effect.Resource
import monix.eval.Task

def openDatabase: Resource[Task, Database] =
  Resource.make(Task(openDb()))(db => Task(db.close()))

// AFTER
import cats.effect.{IO, Resource}

def openDatabase: Resource[IO, Database] =
  Resource.make(IO(openDb()))(db => IO(db.close()))
```

**Files Affected**: ~8 files  
**Effort**: 0.5 days  
**Risk**: Low (Resource API is CE3 compatible)

### 5.3 Task with Scheduler/Async Boundaries

**Pattern**: Scheduling and execution control

```scala
// BEFORE
import monix.eval.Task
import monix.execution.Scheduler

def processAsync(data: Data)(implicit s: Scheduler): Task[Result] =
  Task(heavyComputation(data))
    .executeOn(s)
    .asyncBoundary

// AFTER
import cats.effect.IO

def processAsync(data: Data)(implicit runtime: IORuntime): IO[Result] =
  IO(heavyComputation(data))
    .evalOn(runtime.compute)
    .flatMap(_ => IO.cede)
```

**Files Affected**: ~25 files  
**Effort**: 2-3 days  
**Risk**: Medium (needs careful testing of execution behavior)

### 5.4 Observable Streaming

**Pattern**: Data streaming with Observable

```scala
// BEFORE
import monix.reactive.Observable

def streamBlocks(numbers: Seq[BigInt]): Observable[Block] =
  Observable
    .from(numbers)
    .mapParallelOrdered(10)(nr => Task(fetchBlock(nr)))
    .collect { case Some(block) => block }

// AFTER
import fs2.Stream
import cats.effect.IO

def streamBlocks(numbers: Seq[BigInt]): Stream[IO, Block] =
  Stream
    .emits(numbers)
    .parEvalMap(10)(nr => IO(fetchBlock(nr)))
    .collect { case Some(block) => block }
```

**Files Affected**: ~16 files  
**Effort**: 5-7 days  
**Risk**: High (different streaming semantics)

### 5.5 Observable with Complex Streaming Logic

**Pattern**: TransactionHistoryService-style streaming

```scala
// BEFORE (Monix)
Observable
  .from(blocks)
  .mapParallelOrdered(10)(fetchBlock)
  .concatMap { block =>
    Observable
      .from(block.transactions)
      .collect(matchingTxs)
      .mapEval(enrichTx)
  }
  .toListL

// AFTER (fs2)
Stream
  .emits(blocks.toSeq)
  .parEvalMap(10)(fetchBlock)
  .flatMap { block =>
    Stream
      .emits(block.transactions.toSeq)
      .collect(matchingTxs)
      .evalMap(enrichTx)
  }
  .compile.toList
```

**Files Affected**: 3-4 complex files  
**Effort**: 3-4 days  
**Risk**: High (requires fs2 expertise)

### 5.6 Task-Akka Future Interop

**Pattern**: Integration with Akka actors

```scala
// BEFORE
import monix.eval.Task
import akka.pattern.ask

def askActor(actor: ActorRef, msg: Msg)
    (implicit timeout: Timeout, s: Scheduler): Task[Response] =
  Task.deferFuture((actor ? msg).mapTo[Response])

// AFTER
import cats.effect.IO
import akka.pattern.ask

def askActor(actor: ActorRef, msg: Msg)
    (implicit timeout: Timeout, runtime: IORuntime): IO[Response] =
  IO.fromFuture(IO((actor ? msg).mapTo[Response]))
```

**Files Affected**: ~20 files  
**Effort**: 2-3 days  
**Risk**: Medium (different Future interop)

### 5.7 Test Infrastructure

**Pattern**: Test base traits with Task

```scala
// BEFORE
trait SpecBase {
  implicit val scheduler: Scheduler = Scheduler.global
  
  def testCaseM[M[_]: Async](test: => M[Assertion]): Future[Assertion] =
    test.toIO.unsafeToFuture()
}

// AFTER
trait SpecBase {
  implicit val runtime: IORuntime = IORuntime.global
  
  def testCaseM[M[_]: Async](test: => M[Assertion]): Future[Assertion] =
    test.toIO.unsafeToFuture()(runtime)
}
```

**Files Affected**: ~10 test infrastructure files  
**Effort**: 1-2 days  
**Risk**: Medium (affects all tests)

---

## 6. Testing Strategy

### 6.1 Unit Testing Approach

**For Each Migrated Module**:

1. **Compilation Test**
   ```bash
   sbt <module>/compile
   ```
   Ensure no type errors

2. **Unit Tests**
   ```bash
   sbt <module>/test
   ```
   All existing tests should pass

3. **Behavior Equivalence**
   - Verify same outputs for same inputs
   - Check error handling behavior
   - Validate resource cleanup

### 6.2 Integration Testing

**Full System Tests**:
```bash
sbt "IntegrationTest / test"
```

**Key Integration Tests**:
- Database iteration (RocksDB streaming)
- Blockchain sync (block import pipeline)
- Transaction history (complex streaming)
- Peer discovery (network streaming)
- Actor integration (message passing)

### 6.3 Performance Testing

**Benchmark Critical Paths**:

| Component | Metric | Target |
|-----------|--------|--------|
| Block import | Blocks/sec | Within 10% of baseline |
| Transaction history | Query time | Within 10% of baseline |
| Database iteration | Keys/sec | Within 10% of baseline |
| Sync throughput | MB/sec | Within 10% of baseline |

**Benchmarking Approach**:
1. Establish Monix baseline
2. Compare IO implementation
3. Optimize if needed (IO is usually faster)
4. Document findings

### 6.4 Regression Testing

**Test Matrix**:
- ✅ All unit tests pass
- ✅ All integration tests pass
- ✅ No memory leaks (run for extended period)
- ✅ No resource leaks (file handles, connections)
- ✅ Error handling works correctly
- ✅ Cancellation works correctly
- ✅ Backpressure works correctly (for streams)

---

## 7. Timeline and Resources

### 7.1 Detailed Timeline (6 weeks)

**Week 1: Foundation & Setup**
- Days 1-2: Setup fs2 dependencies, update build configuration
- Days 3-4: Migrate test infrastructure (SpecBase, ResourceFixtures)
- Day 5: Migrate crypto and rlp modules (minimal Monix)

**Week 2: Database Layer**
- Days 1-2: Migrate RocksDbDataSource (Observable → Stream)
- Days 3-4: Migrate storage abstractions (KeyValueStorage, NodeStorage)
- Day 5: Test database layer, performance benchmarks

**Week 3: Transaction Processing**
- Days 1-2: Migrate TransactionHistoryService (complex streaming)
- Days 3-4: Migrate PendingTransactionsManager
- Day 5: Transaction tests and validation

**Week 4: Blockchain Sync**
- Days 1-2: Migrate BlockImporter and HeadersFetcher
- Days 3-4: Migrate StateStorageActor and sync schedulers
- Day 5: Sync integration tests

**Week 5: Network & Consensus**
- Days 1-2: Migrate PeerDiscoveryManager (Observable → Stream)
- Days 2-3: Migrate consensus adapters and mining
- Day 4-5: Network and consensus tests

**Week 6: Integration & Validation**
- Days 1-2: Full integration testing
- Days 3-4: Performance testing and optimization
- Day 5: Documentation and code review

**Buffer**: Add 1-2 weeks for unexpected issues

### 7.2 Resource Requirements

**Personnel**:
- **2 Senior Scala Developers** (full-time, 6 weeks)
  - Deep Scala expertise
  - Cats Effect and fs2 experience
  - Monix knowledge
  - Functional programming background

- **1 DevOps Engineer** (part-time, 20%)
  - CI/CD pipeline updates
  - Performance monitoring setup
  - Infrastructure support

- **1 QA Engineer** (part-time, 50% weeks 4-6)
  - Integration testing
  - Performance validation
  - Regression testing

**Skills Matrix**:
| Skill | Required Level | Team Members |
|-------|---------------|--------------|
| Scala (advanced) | Expert | 2 |
| Cats Effect 3 | Advanced | 2 |
| fs2 | Intermediate-Advanced | 1-2 |
| Monix | Intermediate | 2 |
| Akka | Intermediate | 1 |
| Performance testing | Intermediate | 1 |

**Training**:
- fs2 workshop (2 days) before starting
- Weekly code reviews to share knowledge
- Pair programming on complex migrations

### 7.3 Cost Estimation

**Development Time**:
- Senior developers: 2 × 6 weeks = 12 person-weeks
- DevOps: 0.2 × 6 weeks = 1.2 person-weeks
- QA: 0.5 × 3 weeks = 1.5 person-weeks
- **Total**: ~15 person-weeks

**Additional Costs**:
- Training materials and workshops
- Performance testing infrastructure
- Code review time from leads

---

## 8. Risk Mitigation

### 8.1 Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| fs2 learning curve | High | Medium | Pre-migration training, pair programming |
| Observable→Stream semantic differences | Medium | High | Thorough testing, reference implementations |
| Performance regressions | Low | High | Continuous benchmarking, optimization pass |
| Actor integration issues | Medium | Medium | Early Akka interop testing |
| Resource leak introduction | Low | High | Resource leak testing, code review focus |
| Test infrastructure breaks | Medium | Medium | Migrate tests incrementally |

### 8.2 Process Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Timeline slippage | Medium | Medium | Weekly checkpoints, buffer time |
| Scope creep | Low | Medium | Stick to migration plan, defer improvements |
| Team capacity issues | Low | High | Have backup developers identified |
| Knowledge silos | Medium | Medium | Pair programming, documentation |

### 8.3 Rollback Strategy

**If Critical Issues Arise**:

1. **Per-module rollback**:
   - Each module migrated independently
   - Can revert individual modules without affecting others
   - Use feature flags if needed

2. **Full rollback**:
   - Revert to Monix 3.4.1
   - All code compiles immediately
   - Tests should pass (if properly maintained)

3. **Partial migration**:
   - Can keep some modules on IO, others on Task
   - Use compatibility layer if needed
   - Not ideal, but possible escape hatch

---

## 9. Implementation Checklist

### 9.1 Pre-Migration Setup

- [ ] Create migration branch
- [ ] Set up fs2 dependencies
- [ ] Update build configuration
- [ ] Create migration tracking document
- [ ] Set up performance benchmarking
- [ ] Brief team on migration plan
- [ ] Conduct fs2 training session
- [ ] Establish code review process

### 9.2 Per-Module Checklist

For each module, track:

**Crypto Module** (Minimal Monix):
- [ ] Identify Monix usage
- [ ] Replace Task with IO
- [ ] Update tests
- [ ] Run tests ✓/✗
- [ ] Performance check ✓/✗
- [ ] Code review ✓/✗
- [ ] Merge ✓/✗

**Database Layer** (Heavy Observable):
- [ ] Identify Observable usage
- [ ] Design fs2 Stream replacement
- [ ] Implement RocksDbDataSource.iterate
- [ ] Implement storage abstractions
- [ ] Update tests
- [ ] Run tests ✓/✗
- [ ] Performance check ✓/✗
- [ ] Code review ✓/✗
- [ ] Merge ✓/✗

**Transaction Processing** (Complex Streaming):
- [ ] Analyze TransactionHistoryService streaming
- [ ] Design fs2 Stream equivalent
- [ ] Implement parallel streaming
- [ ] Implement transaction filtering
- [ ] Update tests
- [ ] Run tests ✓/✗
- [ ] Performance check ✓/✗
- [ ] Code review ✓/✗
- [ ] Merge ✓/✗

**Blockchain Sync** (Actor Integration):
- [ ] Identify Task-Actor patterns
- [ ] Implement IO-Actor interop
- [ ] Update BlockImporter
- [ ] Update sync schedulers
- [ ] Update tests
- [ ] Run tests ✓/✗
- [ ] Performance check ✓/✗
- [ ] Code review ✓/✗
- [ ] Merge ✓/✗

**Network Layer** (Observable + Actor):
- [ ] Analyze PeerDiscoveryManager
- [ ] Implement fs2 Stream peer discovery
- [ ] Update network protocols
- [ ] Update tests
- [ ] Run tests ✓/✗
- [ ] Performance check ✓/✗
- [ ] Code review ✓/✗
- [ ] Merge ✓/✗

### 9.3 Post-Migration Validation

- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] Performance benchmarks acceptable
- [ ] No resource leaks detected
- [ ] Documentation updated
- [ ] Team training on CE3 IO patterns
- [ ] Monitor production for issues
- [ ] Conduct retrospective

---

## 10. Example Migrations

### 10.1 Complete Example: Simple Service

```scala
// ============================================================
// BEFORE: Monix Version
// ============================================================
package com.chipprbots.ethereum.example

import monix.eval.Task
import monix.execution.Scheduler
import cats.effect.Resource

class DataService(db: Database)(implicit s: Scheduler) {
  
  def fetchData(id: String): Task[Data] =
    Task.eval(db.get(id))
  
  def processData(data: Data): Task[Result] =
    Task {
      // Heavy computation
      compute(data)
    }.executeOn(s)
  
  def fetchAndProcess(id: String): Task[Result] =
    for {
      data <- fetchData(id)
      result <- processData(data)
    } yield result
  
  def batchProcess(ids: List[String]): Task[List[Result]] =
    Task.parTraverse(ids)(fetchAndProcess)
}

object DataService {
  def resource(dbConfig: DbConfig)
      (implicit s: Scheduler): Resource[Task, DataService] =
    Resource.make(
      Task(new Database(dbConfig))
    )(db => Task(db.close()))
    .map(db => new DataService(db))
}

// ============================================================
// AFTER: Cats Effect IO Version
// ============================================================
package com.chipprbots.ethereum.example

import cats.effect.{IO, Resource}
import cats.effect.unsafe.IORuntime
import cats.syntax.parallel._

class DataService(db: Database)(implicit runtime: IORuntime) {
  
  def fetchData(id: String): IO[Data] =
    IO(db.get(id))
  
  def processData(data: Data): IO[Result] =
    IO {
      // Heavy computation
      compute(data)
    }.evalOn(runtime.compute)
  
  def fetchAndProcess(id: String): IO[Result] =
    for {
      data <- fetchData(id)
      result <- processData(data)
    } yield result
  
  def batchProcess(ids: List[String]): IO[List[Result]] =
    ids.parTraverse(fetchAndProcess)
}

object DataService {
  def resource(dbConfig: DbConfig)
      (implicit runtime: IORuntime): Resource[IO, DataService] =
    Resource.make(
      IO(new Database(dbConfig))
    )(db => IO(db.close()))
    .map(db => new DataService(db))
}
```

### 10.2 Complete Example: Streaming Service

```scala
// ============================================================
// BEFORE: Monix Observable Version
// ============================================================
package com.chipprbots.ethereum.example

import monix.eval.Task
import monix.reactive.Observable
import monix.reactive.OverflowStrategy

class StreamingService {
  
  def streamData(ids: Seq[Long]): Observable[Data] =
    Observable
      .from(ids)
      .mapParallelOrdered(10) { id =>
        Task(fetchFromDb(id))
      }(OverflowStrategy.Unbounded)
      .collect { case Some(data) => data }
  
  def processStream(data: Observable[Data]): Task[Summary] =
    data
      .mapEval(transform)
      .foldLeftL(Summary.empty)(_ + _)
  
  def pipeline(ids: Seq[Long]): Task[Summary] =
    processStream(streamData(ids))
}

// ============================================================
// AFTER: fs2 Stream Version
// ============================================================
package com.chipprbots.ethereum.example

import cats.effect.IO
import fs2.Stream

class StreamingService {
  
  def streamData(ids: Seq[Long]): Stream[IO, Data] =
    Stream
      .emits(ids)
      .parEvalMap(10) { id =>
        IO(fetchFromDb(id))
      }
      .collect { case Some(data) => data }
  
  def processStream(data: Stream[IO, Data]): IO[Summary] =
    data
      .evalMap(transform)
      .fold(Summary.empty)(_ + _)
      .compile.lastOrError
  
  def pipeline(ids: Seq[Long]): IO[Summary] =
    processStream(streamData(ids))
}
```

---

## 11. Success Criteria

### 11.1 Technical Criteria

- ✅ Zero compilation errors
- ✅ All unit tests pass (100%)
- ✅ All integration tests pass (100%)
- ✅ Performance within 10% of baseline (or better)
- ✅ No resource leaks detected
- ✅ No memory leaks detected
- ✅ Error handling preserved
- ✅ Cancellation works correctly

### 11.2 Code Quality Criteria

- ✅ Code review approved
- ✅ No new compiler warnings
- ✅ Documentation updated
- ✅ Migration guide created
- ✅ Team trained on new patterns
- ✅ Consistent style across codebase

### 11.3 Business Criteria

- ✅ Unblocks Cats Effect 3 upgrade
- ✅ Enables Scala 3 migration
- ✅ Improves maintainability
- ✅ Reduces technical debt
- ✅ No production issues introduced

---

## 12. Post-Migration

### 12.1 Immediate Actions

1. **Remove Monix dependency** from build.sbt
2. **Update documentation** (README, CONTRIBUTING)
3. **Team training** on CE3 IO patterns
4. **Monitor production** for any issues
5. **Conduct retrospective** on migration process

### 12.2 Follow-up Tasks

1. **Cats Effect 3 Upgrade** (now unblocked - 1 week)
2. **Optimize hot paths** based on profiling
3. **Adopt new CE3 features** where beneficial
4. **Scala 3 Migration** (per existing plan)

### 12.3 Documentation Updates

- [ ] Update README with CE3 IO patterns
- [ ] Update CONTRIBUTING with IO best practices
- [ ] Update SCALA_3_MIGRATION_REPORT
- [ ] Create CE3 IO usage guide
- [ ] Document common patterns
- [ ] Update architecture diagrams

---

## 13. References

### 13.1 Cats Effect 3 Resources

- [Cats Effect 3 Documentation](https://typelevel.org/cats-effect/)
- [Cats Effect 3 Migration Guide](https://typelevel.org/cats-effect/docs/migration-guide)
- [IO Documentation](https://typelevel.org/cats-effect/docs/core/io)

### 13.2 fs2 Resources

- [fs2 Official Guide](https://fs2.io/#/guide)
- [fs2 API Documentation](https://fs2.io/api/3.x/)
- [fs2 Concurrency Guide](https://fs2.io/#/concurrency-primitives)
- [Migrating from Monix Observable](https://github.com/functional-streams-for-scala/fs2/discussions)

### 13.3 Migration Examples

- [http4s Monix to CE3 migration](https://github.com/http4s/http4s)
- [Doobie CE3 migration](https://github.com/tpolecat/doobie)
- [Community migration stories](https://typelevel.org/blog/)

---

## Conclusion

This migration is substantial but well-scoped. With proper planning, team training, and incremental execution, the Monix → CE3 IO migration is achievable in 6-8 weeks.

**Key Success Factors**:
1. Strong fs2 knowledge in team
2. Incremental, module-by-module approach
3. Comprehensive testing at each step
4. Performance monitoring throughout
5. Clear rollback strategy

**Next Steps**:
1. Review and approve this plan
2. Allocate team resources
3. Conduct fs2 training
4. Begin Week 1 (Foundation & Setup)

---

**Document Control**:
- **Version**: 1.0
- **Date**: October 27, 2025
- **Author**: GitHub Copilot Agent
- **Status**: Ready for Review
- **Approved by**: [Pending]
- **Next Review**: After Week 1 completion
