# Monix to Cats Effect 3 IO Migration - Action Plan

**Date Created**: October 27, 2025  
**Repository**: chippr-robotics/fukuii  
**Status**: Ready for Execution  
**Related Documents**: 
- [MONIX_TO_IO_MIGRATION_PLAN.md](./MONIX_TO_IO_MIGRATION_PLAN.md) - Technical migration guide
- [MONIX_MIGRATION_PUNCH_LIST.md](./MONIX_MIGRATION_PUNCH_LIST.md) - Detailed task checklist
- [CATS_EFFECT_3_MIGRATION.md](./CATS_EFFECT_3_MIGRATION.md) - CE3 upgrade analysis

---

## Executive Summary

This document provides an **executable action plan** for migrating the fukuii codebase from Monix 3.4.1 to Cats Effect 3 IO. This migration is **required** to complete the Cats Effect 3 upgrade and enable full Scala 3 compatibility.

**Key Deliverables**:
1. Replace ~85 files using `monix.eval.Task` with `cats.effect.IO`
2. Replace ~16 files using `monix.reactive.Observable` with `fs2.Stream[IO, _]`
3. Replace ~59 files using `monix.execution.Scheduler` with `cats.effect.unsafe.IORuntime`
4. Remove Monix dependency entirely
5. Complete Cats Effect 3 upgrade
6. Update all documentation

**Timeline**: 6-8 weeks (2 senior Scala developers)  
**Estimated Effort**: ~15 person-weeks

---

## Table of Contents

1. [Migration Scope](#migration-scope)
2. [Migration Order](#migration-order)
3. [Phase 0: Pre-Migration Setup](#phase-0-pre-migration-setup)
4. [Phase 1: Foundation Modules](#phase-1-foundation-modules)
5. [Phase 2: Database Layer](#phase-2-database-layer)
6. [Phase 3: Transaction Processing](#phase-3-transaction-processing)
7. [Phase 4: Blockchain Sync](#phase-4-blockchain-sync)
8. [Phase 5: Network & Consensus](#phase-5-network--consensus)
9. [Phase 6: Integration & Finalization](#phase-6-integration--finalization)
10. [Phase 7: Cats Effect 3 Upgrade](#phase-7-cats-effect-3-upgrade)
11. [Phase 8: Documentation & Closure](#phase-8-documentation--closure)
12. [Success Criteria](#success-criteria)
13. [Risk Mitigation](#risk-mitigation)
14. [Resource Requirements](#resource-requirements)

---

## Migration Scope

### Current State Analysis

**Monix Usage Statistics**:
- 184 files with Monix imports
- ~85 files using `monix.eval.Task`
- ~16 files using `monix.reactive.Observable`
- ~59 files using `monix.execution.Scheduler`
- 0 files currently using `cats.effect.IO`

**Dependencies**:
- ✅ Cats Effect 3.5.4 already installed
- ⚠️ Monix 3.4.1 still present
- ✅ fs2 3.9.3 ready to be added

**Module Breakdown**:
| Module | Monix Usage | Complexity | Priority |
|--------|-------------|------------|----------|
| bytes | None | None | ✅ Complete |
| rlp | Minimal | Low | High |
| crypto | Moderate | Medium | High |
| node (main) | Heavy | High | Critical |

---

## Migration Order

**Dependency-Based Migration Sequence**:

```
Phase 1: Foundation (Week 1-2)
├── bytes ✅ (no Monix - already complete)
├── rlp (minimal Monix usage)
└── crypto (moderate Monix usage)

Phase 2: Core Infrastructure (Week 2-3)
├── Database layer (RocksDB, Storage abstractions)
├── Test infrastructure (SpecBase, ResourceFixtures)
└── Utility modules

Phase 3: Business Logic (Week 3-5)
├── Transaction processing
├── Blockchain sync
├── Network layer
└── Consensus and mining

Phase 4-6: Integration (Week 5-6)
├── Actor integration patterns
├── Full system testing
└── Performance validation

Phase 7: CE3 Upgrade (Week 6)
├── Finalize CE3 dependencies
└── Validation

Phase 8: Documentation (Week 6)
└── Update all docs
```

**Rationale**: This order minimizes integration issues by migrating lower-level dependencies first.

---

## Phase 0: Pre-Migration Setup

**Duration**: 2 days  
**Owner**: Lead developer

### Tasks

#### 0.1 Environment Setup
- [ ] Create migration branch: `git checkout -b feature/monix-to-io-migration`
- [ ] Verify build environment (JDK 17, sbt 1.10.7+)
- [ ] Run baseline tests: `sbt testAll`
- [ ] Document baseline metrics

**Acceptance Criteria**:
- ✅ Branch created and pushed
- ✅ All tests pass in baseline
- ✅ Build succeeds cleanly

#### 0.2 Add fs2 Dependencies
- [ ] Add fs2-core 3.9.3 to `project/Dependencies.scala`
- [ ] Add fs2-io 3.9.3 to `project/Dependencies.scala`
- [ ] Add fs2-reactive-streams 3.9.3 (for interop if needed)
- [ ] Verify dependencies resolve: `sbt update`

**Code Changes**:
```scala
// project/Dependencies.scala
val fs2: Seq[ModuleID] = {
  val fs2Version = "3.9.3"
  Seq(
    "co.fs2" %% "fs2-core" % fs2Version,
    "co.fs2" %% "fs2-io" % fs2Version,
    "co.fs2" %% "fs2-reactive-streams" % fs2Version
  )
}
```

**Acceptance Criteria**:
- ✅ `sbt update` succeeds
- ✅ fs2 available on classpath
- ✅ No dependency conflicts

#### 0.3 Team Training
- [ ] Conduct 2-day fs2 workshop
- [ ] Review CE3 IO patterns
- [ ] Establish pair programming schedule

**Acceptance Criteria**:
- ✅ Team familiar with fs2 concepts
- ✅ Migration patterns documented

#### 0.4 Establish Performance Baselines
- [ ] Run and record performance benchmarks
- [ ] Document current metrics for comparison

**Acceptance Criteria**:
- ✅ Baseline metrics documented
- ✅ Benchmark scripts ready

---

## Phase 1: Foundation Modules

**Duration**: 3-5 days  
**Owner**: Senior developer #1

### Module: rlp (Minimal Monix)

#### 1.1 Migrate rlp Module
- [ ] Scan for Monix usage: `grep -r "import monix" rlp/`
- [ ] Replace `Task` with `IO` in all files
- [ ] Update imports: `import cats.effect.IO`
- [ ] Run tests: `sbt rlp/test`

**Migration Pattern**:
```scala
// BEFORE
import monix.eval.Task

def encode(value: RLPValue): Task[ByteString] = 
  Task.eval(encodeImpl(value))

// AFTER
import cats.effect.IO

def encode(value: RLPValue): IO[ByteString] = 
  IO(encodeImpl(value))
```

**Acceptance Criteria**:
- ✅ All Monix imports removed from rlp/
- ✅ `sbt rlp/test` passes
- ✅ No performance regression

### Module: crypto (Moderate Monix)

#### 1.2 Migrate crypto Module
- [ ] Scan for Monix usage: `grep -r "import monix" crypto/`
- [ ] Replace `Task` with `IO`
- [ ] Update `Resource[Task, _]` to `Resource[IO, _]`
- [ ] Run tests: `sbt crypto/test`

**Migration Pattern**:
```scala
// BEFORE
import monix.eval.Task
import cats.effect.Resource

def openKeyStore: Resource[Task, KeyStore] =
  Resource.make(Task(load()))(ks => Task(ks.close()))

// AFTER
import cats.effect.{IO, Resource}

def openKeyStore: Resource[IO, KeyStore] =
  Resource.make(IO(load()))(ks => IO(ks.close()))
```

**Acceptance Criteria**:
- ✅ All Monix imports removed from crypto/
- ✅ `sbt crypto/test` passes
- ✅ Resource cleanup works correctly

### Test Infrastructure

#### 1.3 Migrate SpecBase.scala
- [ ] Update `Effect[Task]` to `Async[IO]`
- [ ] Replace `Scheduler` with `IORuntime`
- [ ] Update test helper methods

**File**: `src/test/scala/com/chipprbots/ethereum/SpecBase.scala`

**Migration Pattern**:
```scala
// BEFORE
import monix.eval.Task
import monix.execution.Scheduler
import cats.effect.Effect

trait SpecBase {
  implicit val scheduler: Scheduler = Scheduler.global
  
  def runTest[A](task: Task[A]): Future[A] =
    task.runToFuture
}

// AFTER
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.effect.Async

trait SpecBase {
  implicit val runtime: IORuntime = IORuntime.global
  
  def runTest[A](io: IO[A]): Future[A] =
    io.unsafeToFuture()
}
```

**Acceptance Criteria**:
- ✅ Test infrastructure compiles
- ✅ Sample tests pass with new infrastructure

#### 1.4 Migrate Test Fixtures
- [ ] Update `ResourceFixtures.scala`
- [ ] Update `FastSyncItSpecUtils.scala`
- [ ] Update `RegularSyncItSpecUtils.scala`

**Acceptance Criteria**:
- ✅ All test utilities compile
- ✅ Integration test utilities work

---

## Phase 2: Database Layer

**Duration**: 5-7 days  
**Owner**: Senior developer #2

### High Priority: Observable → Stream Migration

#### 2.1 Migrate RocksDbDataSource.scala
- [ ] Convert `Observable` iteration to `fs2.Stream`
- [ ] Update iterator methods
- [ ] Performance test iteration

**File**: `src/main/scala/com/chipprbots/ethereum/db/dataSource/RocksDbDataSource.scala`

**Migration Pattern**:
```scala
// BEFORE
import monix.reactive.Observable

def iterate(namespace: Namespace): Observable[(Key, Value)] =
  Observable.fromIterator(Task(iterator(namespace)))

// AFTER
import fs2.Stream
import cats.effect.IO

def iterate(namespace: Namespace): Stream[IO, (Key, Value)] =
  Stream.fromIterator[IO](IO(iterator(namespace)), chunkSize = 1024)
```

**Acceptance Criteria**:
- ✅ Database iteration uses fs2.Stream
- ✅ Performance within 10% of baseline
- ✅ Tests pass

#### 2.2 Migrate Storage Abstractions
- [ ] Migrate `KeyValueStorage.scala`
- [ ] Migrate `NodeStorage.scala`
- [ ] Migrate `EvmCodeStorage.scala`
- [ ] Migrate `TransactionalKeyValueStorage.scala`
- [ ] Migrate `EphemDataSource.scala`

**Acceptance Criteria**:
- ✅ All storage classes use IO
- ✅ Observable removed from storage layer
- ✅ Storage tests pass

#### 2.3 Update Database Tests
- [ ] Update `RockDbIteratorSpec.scala`
- [ ] Update all storage tests

**Acceptance Criteria**:
- ✅ All database tests pass
- ✅ Integration tests pass

---

## Phase 3: Transaction Processing

**Duration**: 5-7 days  
**Owner**: Senior developer #1

### Complex Streaming Logic

#### 3.1 Migrate TransactionHistoryService.scala
- [ ] Analyze complex Observable streaming patterns
- [ ] Design fs2.Stream equivalent
- [ ] Implement parallel streaming with `parEvalMap`
- [ ] Test transaction queries

**File**: `src/main/scala/com/chipprbots/ethereum/transactions/TransactionHistoryService.scala`

**Migration Pattern**:
```scala
// BEFORE
Observable
  .from(blocks)
  .mapParallelOrdered(10)(blockNr => Task(fetchBlock(blockNr)))
  .collect { case Some(block) => block }
  .concatMap { block =>
    Observable.from(block.transactions)
      .collect(matchingTxs)
      .mapEval(enrichTx)
  }
  .toListL

// AFTER
Stream
  .emits(blocks.toSeq)
  .parEvalMap(10)(blockNr => IO(fetchBlock(blockNr)))
  .collect { case Some(block) => block }
  .flatMap { block =>
    Stream.emits(block.transactions.toSeq)
      .collect(matchingTxs)
      .evalMap(enrichTx)
  }
  .compile.toList
```

**Acceptance Criteria**:
- ✅ Transaction history queries work
- ✅ Performance comparable to baseline
- ✅ Tests pass

#### 3.2 Migrate PendingTransactionsManager.scala
- [ ] Update transaction pool with IO
- [ ] Test transaction handling

**Acceptance Criteria**:
- ✅ Transaction pool works correctly
- ✅ Tests pass

#### 3.3 Update Transaction Tests
- [ ] Update all transaction-related tests

**Acceptance Criteria**:
- ✅ All transaction tests pass

---

## Phase 4: Blockchain Sync

**Duration**: 5-7 days  
**Owner**: Senior developer #2

### Critical Sync Components

#### 4.1 Migrate BlockImporter.scala
- [ ] Replace Task with IO in block import pipeline
- [ ] Maintain scheduler handling
- [ ] Test block import

**Acceptance Criteria**:
- ✅ Block import works correctly
- ✅ Sync tests pass

#### 4.2 Migrate Sync Components
- [ ] Migrate `HeadersFetcher.scala`
- [ ] Migrate `FetchRequest.scala`
- [ ] Migrate `StateStorageActor.scala`
- [ ] Migrate `SyncStateScheduler.scala` (Observable usage)
- [ ] Migrate `SyncStateSchedulerActor.scala`
- [ ] Migrate `LoadableBloomFilter.scala`

**Acceptance Criteria**:
- ✅ All sync components use IO
- ✅ Observable removed from sync layer
- ✅ Sync tests pass

#### 4.3 Update Sync Tests
- [ ] Update `RegularSyncSpec.scala`
- [ ] Update `FastSyncBranchResolverActorSpec.scala`
- [ ] Update `EtcPeerManagerFake.scala`

**Acceptance Criteria**:
- ✅ All sync tests pass

---

## Phase 5: Network & Consensus

**Duration**: 5-7 days  
**Owner**: Senior developer #1

### Network Layer

#### 5.1 Migrate PeerDiscoveryManager.scala
- [ ] Convert Observable peer discovery to fs2.Stream
- [ ] Test peer discovery

**Acceptance Criteria**:
- ✅ Peer discovery works
- ✅ Tests pass

#### 5.2 Migrate Network Components
- [ ] Migrate `DiscoveryServiceBuilder.scala`
- [ ] Migrate `PortForwarder.scala` (Resource[Task])
- [ ] Migrate `EthNodeStatus64ExchangeState.scala`
- [ ] Update `PeerDiscoveryManagerSpec.scala`

**Acceptance Criteria**:
- ✅ All network components use IO
- ✅ Network tests pass

### Consensus Layer

#### 5.3 Migrate Consensus Components
- [ ] Migrate `ConsensusAdapter.scala`
- [ ] Migrate `Mining.scala`
- [ ] Migrate `ForkIdValidator.scala`

**Acceptance Criteria**:
- ✅ Consensus operations work
- ✅ Mining works correctly
- ✅ Tests pass

---

## Phase 6: Integration & Finalization

**Duration**: 5-7 days  
**Owner**: Both developers

### Integration Testing

#### 6.1 Run Full Test Suite
- [ ] Run all unit tests: `sbt test`
- [ ] Run all integration tests: `sbt "IntegrationTest / test"`
- [ ] Verify no Monix imports remain

**Acceptance Criteria**:
- ✅ All unit tests pass
- ✅ All integration tests pass
- ✅ No Monix imports found

#### 6.2 Performance Testing
- [ ] Run performance benchmarks
- [ ] Compare to baseline metrics
- [ ] Document performance changes

**Metrics to Check**:
- Block import rate (blocks/sec)
- Transaction query time
- Database iteration speed
- Sync throughput

**Acceptance Criteria**:
- ✅ Performance within 10% of baseline (or better)

#### 6.3 Resource Leak Testing
- [ ] Run extended tests (24 hours)
- [ ] Monitor memory usage
- [ ] Monitor file handles

**Acceptance Criteria**:
- ✅ No memory leaks
- ✅ No resource leaks

### Cleanup

#### 6.4 Remove Monix Dependency
- [ ] Remove from `project/Dependencies.scala`
- [ ] Verify build: `sbt clean compile`

**Code Changes**:
```scala
// project/Dependencies.scala
// DELETE:
val monix = Seq(
  "io.monix" %% "monix" % "3.4.1"
)
```

**Acceptance Criteria**:
- ✅ Build succeeds without Monix
- ✅ No Monix references remain

#### 6.5 Verify No Monix Imports
- [ ] Search codebase: `grep -r "import monix" src`
- [ ] Verify result is empty

**Acceptance Criteria**:
- ✅ Zero Monix imports found

---

## Phase 7: Cats Effect 3 Upgrade

**Duration**: 1-2 days  
**Owner**: Lead developer

### CE3 Finalization

#### 7.1 Update Dependencies
- [ ] Verify cats-effect 3.5.4 in `project/Dependencies.scala`
- [ ] Verify log4cats 2.6.0
- [ ] Update any remaining CE2 dependencies

**Acceptance Criteria**:
- ✅ All dependencies CE3-compatible
- ✅ Dependencies resolve correctly

#### 7.2 Update Import Paths
- [ ] Change `cats.effect.concurrent._` to `cats.effect._`
- [ ] Update all affected files

**Files to Update** (if not already done):
- `src/it/scala/com/chipprbots/ethereum/db/RockDbIteratorSpec.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/EtcPeerManagerFake.scala`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/fast/FastSyncBranchResolverActorSpec.scala`

**Acceptance Criteria**:
- ✅ All imports updated
- ✅ No CE2-specific imports remain

#### 7.3 Validation
- [ ] Compile: `sbt clean compile`
- [ ] Run tests: `sbt testAll`
- [ ] Run integration tests: `sbt "IntegrationTest / test"`

**Acceptance Criteria**:
- ✅ Clean compilation
- ✅ All tests pass
- ✅ No CE3-related errors

---

## Phase 8: Documentation & Closure

**Duration**: 2-3 days  
**Owner**: Lead developer

### Documentation Updates

#### 8.1 Update README.md
- [ ] Update dependencies section
- [ ] Mention CE3 and removal of Monix
- [ ] Update technology stack description

**Acceptance Criteria**:
- ✅ README accurate and current

#### 8.2 Update CONTRIBUTING.md
- [ ] Update with CE3 IO patterns
- [ ] Remove Monix references
- [ ] Update test examples

**Acceptance Criteria**:
- ✅ Contributing guide accurate

#### 8.3 Update Migration Reports
- [ ] Update `SCALA_3_MIGRATION_REPORT.md` - mark CE3 complete
- [ ] Update `DEPENDENCY_UPDATE_REPORT.md` - document versions
- [ ] Update `CATS_EFFECT_3_MIGRATION.md` - mark complete

**Acceptance Criteria**:
- ✅ All migration docs current

#### 8.4 Create Completion Summary
- [ ] Create `MONIX_MIGRATION_COMPLETE.md`
- [ ] Document what was changed
- [ ] Document lessons learned
- [ ] Document performance impact

**Acceptance Criteria**:
- ✅ Summary document created
- ✅ Lessons learned documented

### Code Quality

#### 8.5 Format and Lint
- [ ] Run scalafmt: `sbt scalafmtAll`
- [ ] Run scalafix: `sbt scalafixAll`
- [ ] Verify no violations

**Acceptance Criteria**:
- ✅ All files formatted
- ✅ No style violations

#### 8.6 Code Review
- [ ] Request code review from team
- [ ] Address all feedback
- [ ] Get approval

**Acceptance Criteria**:
- ✅ Code review approved

### PR Closure

#### 8.7 Final Validation
- [ ] Run full test suite one more time
- [ ] Verify documentation is complete
- [ ] Verify no regressions

**Acceptance Criteria**:
- ✅ All tests pass
- ✅ Documentation complete
- ✅ No regressions

#### 8.8 Merge and Deploy
- [ ] Merge PR to main
- [ ] Tag release if applicable
- [ ] Deploy to staging
- [ ] Monitor for issues

**Acceptance Criteria**:
- ✅ PR merged
- ✅ Deployed successfully
- ✅ No production issues

---

## Success Criteria

### Technical Criteria

- ✅ Zero compilation errors
- ✅ All unit tests pass (100%)
- ✅ All integration tests pass (100%)
- ✅ Performance within 10% of baseline (or better)
- ✅ No resource leaks detected
- ✅ No memory leaks detected
- ✅ Error handling preserved
- ✅ Cancellation works correctly
- ✅ Zero Monix imports remain
- ✅ Zero Monix dependencies

### Code Quality Criteria

- ✅ Code review approved
- ✅ No new compiler warnings
- ✅ Documentation updated
- ✅ Migration summary created
- ✅ Consistent style across codebase

### Business Criteria

- ✅ Unblocks Cats Effect 3 upgrade
- ✅ Enables full Scala 3 migration
- ✅ Improves maintainability
- ✅ Reduces technical debt
- ✅ No production issues introduced

---

## Risk Mitigation

### Technical Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| fs2 learning curve | High | Medium | Pre-migration training, pair programming |
| Observable→Stream semantic differences | Medium | High | Thorough testing, reference implementations |
| Performance regressions | Low | High | Continuous benchmarking, optimization pass |
| Actor integration issues | Medium | Medium | Early Akka interop testing |
| Resource leak introduction | Low | High | Resource leak testing, code review focus |

### Process Risks

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Timeline slippage | Medium | Medium | Weekly checkpoints, buffer time |
| Scope creep | Low | Medium | Stick to migration plan, defer improvements |
| Team capacity issues | Low | High | Have backup developers identified |

### Rollback Strategy

**If Critical Issues Arise**:

1. **Per-module rollback**:
   - Each module migrated independently
   - Can revert individual modules
   - Use feature flags if needed

2. **Full rollback**:
   - Revert to Monix 3.4.1
   - All code compiles immediately
   - Tests should pass

3. **Partial migration**:
   - Can keep some modules on IO, others on Task
   - Not ideal, but possible escape hatch

---

## Resource Requirements

### Personnel

**2 Senior Scala Developers** (full-time, 6 weeks)
- Deep Scala expertise
- Cats Effect and fs2 experience
- Monix knowledge
- Functional programming background

**1 DevOps Engineer** (part-time, 20%)
- CI/CD pipeline updates
- Performance monitoring setup
- Infrastructure support

**1 QA Engineer** (part-time, 50% weeks 4-6)
- Integration testing
- Performance validation
- Regression testing

### Skills Matrix

| Skill | Required Level | Team Members |
|-------|---------------|--------------|
| Scala (advanced) | Expert | 2 |
| Cats Effect 3 | Advanced | 2 |
| fs2 | Intermediate-Advanced | 1-2 |
| Monix | Intermediate | 2 |
| Akka | Intermediate | 1 |
| Performance testing | Intermediate | 1 |

### Training

- fs2 workshop (2 days) before starting
- Weekly code reviews to share knowledge
- Pair programming on complex migrations

### Timeline Summary

| Phase | Duration | Week |
|-------|----------|------|
| Phase 0: Pre-Migration Setup | 2 days | Week 1 |
| Phase 1: Foundation Modules | 3-5 days | Week 1-2 |
| Phase 2: Database Layer | 5-7 days | Week 2-3 |
| Phase 3: Transaction Processing | 5-7 days | Week 3-4 |
| Phase 4: Blockchain Sync | 5-7 days | Week 4-5 |
| Phase 5: Network & Consensus | 5-7 days | Week 5 |
| Phase 6: Integration & Finalization | 5-7 days | Week 5-6 |
| Phase 7: Cats Effect 3 Upgrade | 1-2 days | Week 6 |
| Phase 8: Documentation & Closure | 2-3 days | Week 6 |

**Total**: 6-8 weeks (including buffer)

---

## Weekly Checkpoints

### Week 1 Checkpoint
- ✅ Environment setup complete
- ✅ fs2 dependencies added
- ✅ rlp and crypto modules migrated
- ✅ Test infrastructure migrated
- ✅ Foundation tests pass

### Week 2 Checkpoint
- ✅ Database layer migrated
- ✅ Storage abstractions use fs2.Stream
- ✅ Database tests pass
- ✅ Performance benchmarks acceptable

### Week 3 Checkpoint
- ✅ Transaction processing migrated
- ✅ Complex streaming logic working
- ✅ Transaction tests pass

### Week 4 Checkpoint
- ✅ Blockchain sync migrated
- ✅ All sync components use IO
- ✅ Sync tests pass

### Week 5 Checkpoint
- ✅ Network layer migrated
- ✅ Consensus layer migrated
- ✅ All component tests pass

### Week 6 Checkpoint
- ✅ Full integration tests pass
- ✅ Performance validation complete
- ✅ Monix removed
- ✅ CE3 upgrade complete
- ✅ Documentation updated
- ✅ PR ready for merge

---

## Tracking Progress

**Use this template for daily updates**:

```markdown
### [Date]
**Today's Goals**: [What we plan to accomplish]
**Completed**: [What was completed]
**Blocked**: [Any blockers]
**Tomorrow**: [Next steps]
```

**Example**:
```markdown
### 2025-10-28
**Today's Goals**: Migrate rlp module, start crypto module
**Completed**: 
- ✅ rlp module fully migrated
- ✅ All rlp tests passing
- ✅ Started crypto module migration (60% complete)
**Blocked**: None
**Tomorrow**: Complete crypto module, start test infrastructure
```

---

## Communication Plan

### Daily Standups
- 15-minute daily standup
- Share progress, blockers, next steps
- Rotate facilitator

### Weekly Reviews
- 1-hour weekly review meeting
- Demo completed work
- Adjust plan as needed
- Update timeline

### Code Reviews
- All changes reviewed by second developer
- Focus on fs2 patterns and resource safety
- Maintain review checklist

### Stakeholder Updates
- Weekly email update to stakeholders
- Highlight progress and risks
- Request decisions as needed

---

## Next Steps

### Immediate Actions (This Week)

1. **Review and Approve This Plan**
   - Team review meeting
   - Stakeholder approval
   - Resource allocation

2. **Schedule Training**
   - Book 2-day fs2 workshop
   - Schedule pairing sessions
   - Set up knowledge sharing

3. **Begin Phase 0**
   - Create migration branch
   - Add fs2 dependencies
   - Run baseline tests

### Decision Points

**Decision Required**: When to start?
- **Recommended**: Start Week of [TBD]
- **Prerequisites**: Team training complete, resources allocated

**Decision Required**: Team composition?
- **Recommended**: 2 senior Scala developers full-time
- **Alternative**: 1 full-time + 1 part-time (extends timeline)

---

## References

### Technical Documentation
- [Monix to IO Migration Plan](./MONIX_TO_IO_MIGRATION_PLAN.md)
- [Monix Migration Punch List](./MONIX_MIGRATION_PUNCH_LIST.md)
- [Cats Effect 3 Migration](./CATS_EFFECT_3_MIGRATION.md)
- [fs2 Official Guide](https://fs2.io/#/guide)
- [Cats Effect 3 Documentation](https://typelevel.org/cats-effect/)

### Migration Examples
- [http4s Monix to CE3 migration](https://github.com/http4s/http4s)
- [Doobie CE3 migration](https://github.com/tpolecat/doobie)

---

## Conclusion

This action plan provides a clear, executable roadmap for migrating from Monix to Cats Effect 3 IO. With proper planning, team training, and incremental execution, this migration is achievable in 6-8 weeks.

**Key Success Factors**:
1. Strong fs2 knowledge in team
2. Incremental, module-by-module approach
3. Comprehensive testing at each step
4. Performance monitoring throughout
5. Clear rollback strategy

**Ready to Execute**: This plan is ready for team approval and execution.

---

**Document Control**:
- **Version**: 1.0
- **Date**: October 27, 2025
- **Author**: GitHub Copilot Agent
- **Status**: Ready for Approval
- **Next Review**: Weekly during migration
