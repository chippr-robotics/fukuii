# Flaky Tests - GitHub Issues

This document contains templates for creating GitHub issues for each flaky test identified in the test audit. Each test uses `Thread.sleep()` which causes timing-dependent failures.

**Instructions:**
1. Create one GitHub issue per test file using the templates below
2. Label each issue with: `test`, `flaky`, `technical-debt`, `good-first-issue`
3. Assign to milestone: "Test Quality Improvements"
4. Link to this PR: #<PR_NUMBER>

---

## Issue 1: Fix flaky test - RetryStrategySpec

**Title:** Fix flaky test in RetryStrategySpec (uses Thread.sleep)

**Description:**
### Problem
`RetryStrategySpec` contains timing-dependent test code that uses `Thread.sleep(100)`, making the test flaky and unreliable in CI environments.

**Location:** `src/test/scala/com/chipprbots/ethereum/blockchain/sync/RetryStrategySpec.scala:180`

**Current Code:**
```scala
Thread.sleep(100)
```

### Solution
Replace `Thread.sleep` with ScalaTest's `eventually` construct:

```scala
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Seconds, Span}

eventually(timeout(Span(5, Seconds))) {
  // assertions here
}
```

### Acceptance Criteria
- [ ] Replace `Thread.sleep` with `eventually`
- [ ] Test passes consistently (10/10 runs)
- [ ] Add timeout configuration to prevent hanging
- [ ] Update test documentation if needed

**Labels:** `test`, `flaky`, `technical-debt`, `good-first-issue`  
**System:** Synchronization  
**Priority:** High (blocks reliable CI)

---

## Issue 2: Fix flaky test - CachedNodeStorageSpec

**Title:** Fix flaky test in CachedNodeStorageSpec (uses Thread.sleep)

**Description:**
### Problem
`CachedNodeStorageSpec` contains timing-dependent test code that uses `Thread.sleep(1.second.toMillis)`, making the test flaky.

**Location:** `src/test/scala/com/chipprbots/ethereum/db/storage/CachedNodeStorageSpec.scala:60`

**Current Code:**
```scala
Thread.sleep(1.second.toMillis)
```

### Solution
Replace with eventual consistency check:

```scala
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Seconds, Span}

eventually(timeout(Span(5, Seconds))) {
  // verify cache state
}
```

### Acceptance Criteria
- [ ] Replace `Thread.sleep` with `eventually`
- [ ] Test passes consistently (10/10 runs)
- [ ] Verify cache eviction behavior works correctly
- [ ] Consider using test-specific cache configuration

**Labels:** `test`, `flaky`, `technical-debt`, `good-first-issue`  
**System:** Database & Storage  
**Priority:** High

---

## Issue 3: Fix flaky test - RegularSyncSpec

**Title:** Fix flaky test in RegularSyncSpec (uses Thread.sleep)

**Description:**
### Problem
`RegularSyncSpec` contains multiple timing-dependent test codes using `Thread.sleep`, making tests flaky.

**Locations:**
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSyncSpec.scala:554`
- `src/test/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSyncSpec.scala:636`

**Current Code:**
```scala
Thread.sleep(remainingOrDefault.toMillis)
Thread.sleep(remainingOrDefault.toMillis / 2)
```

### Solution
Use akka-testkit's `expectMsg` with timeout or ScalaTest's `eventually`:

```scala
import akka.testkit.TestProbe
import scala.concurrent.duration._

// For actor message waiting:
probe.expectMsg(5.seconds, expectedMessage)

// For state verification:
eventually(timeout(Span(5, Seconds))) {
  // verify sync state
}
```

### Acceptance Criteria
- [ ] Replace all `Thread.sleep` calls with proper async waiting
- [ ] Tests pass consistently (10/10 runs)
- [ ] Use akka-testkit patterns for actor synchronization
- [ ] Document async testing pattern for future tests

**Labels:** `test`, `flaky`, `technical-debt`  
**System:** Synchronization  
**Priority:** High

---

## Issue 4: Fix flaky test - EthMiningServiceSpec

**Title:** Fix flaky test in EthMiningServiceSpec (uses Thread.sleep)

**Description:**
### Problem
`EthMiningServiceSpec` contains timing-dependent test code with multiple `Thread.sleep` calls.

**Locations:**
- `src/test/scala/com/chipprbots/ethereum/jsonrpc/EthMiningServiceSpec.scala:157`
- `src/test/scala/com/chipprbots/ethereum/jsonrpc/EthMiningServiceSpec.scala:284`
- `src/test/scala/com/chipprbots/ethereum/jsonrpc/EthMiningServiceSpec.scala:294`

**Current Code:**
```scala
Thread.sleep(jsonRpcConfig.minerActiveTimeout.toMillis + 1000)
Thread.sleep(jsonRpcConfig.minerActiveTimeout.toMillis / 2)
Thread.sleep(jsonRpcConfig.minerActiveTimeout.toMillis / 2 + 1000)
```

### Solution
Use akka-testkit patterns for timeout verification:

```scala
import akka.testkit.TestProbe
import org.scalatest.concurrent.Eventually._

// For timeout testing:
probe.expectNoMessage(minerActiveTimeout)

// For state verification:
eventually(timeout(minerActiveTimeout + 2.seconds)) {
  // verify miner state
}
```

### Acceptance Criteria
- [ ] Replace all `Thread.sleep` with proper async patterns
- [ ] Tests pass consistently (10/10 runs)
- [ ] Timeout logic properly tested without sleep
- [ ] Mining service state transitions verified correctly

**Labels:** `test`, `flaky`, `technical-debt`  
**System:** JSON-RPC API  
**Priority:** High

---

## Issue 5: Fix flaky test - KeyStoreImplSpec

**Title:** Fix flaky test in KeyStoreImplSpec (uses Thread.sleep)

**Description:**
### Problem
`KeyStoreImplSpec` contains timing-dependent test code with `Thread.sleep` calls.

**Locations:**
- `src/test/scala/com/chipprbots/ethereum/keystore/KeyStoreImplSpec.scala:40`
- `src/test/scala/com/chipprbots/ethereum/keystore/KeyStoreImplSpec.scala:42`

**Current Code:**
```scala
Thread.sleep(1005)
Thread.sleep(1005)
```

### Solution
Use `eventually` to wait for keystore operations:

```scala
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.{Seconds, Span}

eventually(timeout(Span(5, Seconds))) {
  // verify keystore state
}
```

### Acceptance Criteria
- [ ] Replace `Thread.sleep` with `eventually`
- [ ] Tests pass consistently (10/10 runs)
- [ ] Keystore lock/unlock timing tested properly
- [ ] Consider using test clock for time-based operations

**Labels:** `test`, `flaky`, `technical-debt`, `good-first-issue`  
**System:** Utilities  
**Priority:** Medium

---

## Issue 6: Fix flaky test - PendingTransactionsManagerSpec

**Title:** Fix flaky test in PendingTransactionsManagerSpec (uses Thread.sleep)

**Description:**
### Problem
`PendingTransactionsManagerSpec` contains multiple timing-dependent test codes using `Thread.sleep`.

**Locations:**
- `src/test/scala/com/chipprbots/ethereum/transactions/PendingTransactionsManagerSpec.scala:47`
- `src/test/scala/com/chipprbots/ethereum/transactions/PendingTransactionsManagerSpec.scala:59`
- `src/test/scala/com/chipprbots/ethereum/transactions/PendingTransactionsManagerSpec.scala:127`
- `src/test/scala/com/chipprbots/ethereum/transactions/PendingTransactionsManagerSpec.scala:148`
- `src/test/scala/com/chipprbots/ethereum/transactions/PendingTransactionsManagerSpec.scala:153`
- `src/test/scala/com/chipprbots/ethereum/transactions/PendingTransactionsManagerSpec.scala:158`
- `src/test/scala/com/chipprbots/ethereum/transactions/PendingTransactionsManagerSpec.scala:208`

**Current Code:**
```scala
Thread.sleep(Timeouts.normalTimeout.toMillis)
Thread.sleep(Timeouts.shortTimeout.toMillis)
Thread.sleep(550)
```

### Solution
Use akka-testkit for actor message timing:

```scala
import akka.testkit.TestProbe
import org.scalatest.concurrent.Eventually._

// For message waiting:
probe.expectMsg(normalTimeout, expectedMessage)
probe.expectNoMessage(shortTimeout)

// For state verification:
eventually(timeout(normalTimeout)) {
  // verify transaction manager state
}
```

### Acceptance Criteria
- [ ] Replace all `Thread.sleep` with akka-testkit patterns
- [ ] Tests pass consistently (10/10 runs)
- [ ] Transaction timeout behavior tested correctly
- [ ] Document proper actor testing patterns

**Labels:** `test`, `flaky`, `technical-debt`  
**System:** Transactions  
**Priority:** High

---

## Issue 7: Fix flaky test - IORuntimeInitializationSpec

**Title:** Fix flaky test in IORuntimeInitializationSpec (uses Thread.sleep)

**Description:**
### Problem
`IORuntimeInitializationSpec` contains test code using `Thread.sleep` to simulate work.

**Location:** `src/test/scala/com/chipprbots/ethereum/nodebuilder/IORuntimeInitializationSpec.scala:124`

**Current Code:**
```scala
Thread.sleep(10) // Simulate some initialization work
```

### Solution
Replace with proper async initialization testing:

```scala
import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.concurrent.Eventually._

// Use cats-effect testing:
IO.sleep(10.millis) *> // Simulated work
  IO(assertions).asserting(_ => succeed)

// Or use eventually:
eventually(timeout(Span(5, Seconds))) {
  // verify initialization completed
}
```

### Acceptance Criteria
- [ ] Replace `Thread.sleep` with cats-effect `IO.sleep` or `eventually`
- [ ] Tests pass consistently (10/10 runs)
- [ ] Use proper IO testing patterns
- [ ] Consider using TestControl for time manipulation

**Labels:** `test`, `flaky`, `technical-debt`, `good-first-issue`  
**System:** Utilities  
**Priority:** Medium

---

## Issue 8: Fix flaky test - PortForwardingBuilderSpec

**Title:** Fix flaky test in PortForwardingBuilderSpec (uses Thread.sleep)

**Description:**
### Problem
`PortForwardingBuilderSpec` contains test code using `Thread.sleep` to simulate delays.

**Location:** `src/test/scala/com/chipprbots/ethereum/nodebuilder/PortForwardingBuilderSpec.scala:252`

**Current Code:**
```scala
Thread.sleep(simulateDelay)
```

### Solution
Use cats-effect IO for delay simulation:

```scala
import cats.effect.IO
import scala.concurrent.duration._

// Proper async delay:
IO.sleep(simulateDelay.millis) *>
  IO(assertions).asserting(_ => succeed)
```

### Acceptance Criteria
- [ ] Replace `Thread.sleep` with `IO.sleep`
- [ ] Tests pass consistently (10/10 runs)
- [ ] Port forwarding timing tested properly
- [ ] Use cats-effect testing best practices

**Labels:** `test`, `flaky`, `technical-debt`, `good-first-issue`  
**System:** Utilities  
**Priority:** Medium

---

## Summary

**Total Flaky Tests:** 8 test files  
**Total Thread.sleep locations:** 18+ instances

**Priority Distribution:**
- High: 5 tests (RetryStrategy, CachedNodeStorage, RegularSync, EthMiningService, PendingTransactionsManager)
- Medium: 3 tests (KeyStoreImpl, IORuntimeInitialization, PortForwardingBuilder)

**Recommended Order:**
1. PendingTransactionsManagerSpec (7 instances, high priority)
2. EthMiningServiceSpec (3 instances, high priority)
3. RegularSyncSpec (2 instances, high priority)
4. KeyStoreImplSpec (2 instances, good first issue)
5. RetryStrategySpec (1 instance, good first issue)
6. CachedNodeStorageSpec (1 instance, good first issue)
7. IORuntimeInitializationSpec (1 instance, good first issue)
8. PortForwardingBuilderSpec (1 instance, good first issue)

**Testing Pattern Resources:**
- ScalaTest Eventually: https://www.scalatest.org/user_guide/async_testing#usingEventually
- Akka TestKit: https://doc.akka.io/docs/akka/current/testing.html
- Cats Effect Testing: https://github.com/typelevel/cats-effect-testing

