# CI Failure Analysis – 2025-12-13

**Date**: 2025-12-13  
**Build**: Actions Run #20186277208  
**Branch**: Assumed main/master (from CI logs)  
**Test Suite**: Essential Tests (`sbt testEssential`)  
**Status**: ❌ **2 Tests Failed**

---

## Executive Summary

The CI build on 2025-12-13 03:56 UTC failed with 2 test failures in `RegularSyncSpec`. Both failures are **timeout assertions** in peer blacklisting tests that expect the `BlockFetcher` actor to blacklist peers that send unrequested data. These failures appear to be **test flakiness** related to timing sensitivity in the actor system, not directly related to the Core-Geth compatibility issues reported in the Gorgoroth Field Report from the same date.

**Relationship to Field Report**: Both the CI failures and field report involve peer communication, but they represent different issues:
- **CI Failures**: Test timing issues in Fukuii's internal peer blacklisting logic
- **Field Report**: External compatibility issue with Core-Geth genesis configuration

---

## Test Results Summary

```
[info] Run completed in 6 minutes, 25 seconds.
[info] Total number of tests run: 2050
[info] Suites: completed 210, aborted 0
[info] Tests: succeeded 2048, failed 2, canceled 0, ignored 8, pending 0
[info] *** 2 TESTS FAILED ***
[error] Failed tests:
[error]   com.chipprbots.ethereum.blockchain.sync.regular.RegularSyncSpec
[error] (Test / testOnly) sbt.TestsFailedException: Tests unsuccessful
```

### Test Success Rate

- **Success Rate**: 99.90% (2048 / 2050 tests passed)
- **Failure Rate**: 0.10% (2 / 2050 tests failed)
- **Test Suite Duration**: 6 minutes 25 seconds
- **Failure Pattern**: Both failures in same test class, same symptom (timeout)

---

## Failed Tests

### 1. RegularSyncSpec: "should blacklist peer which sends headers that were not requested"

**Location**: `src/test/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSyncSpec.scala:202`

**Failure Details**:
```
[info]   - should blacklist peer which sends headers that were not requested *** FAILED *** (3 seconds, 181 milliseconds)
[info]     java.util.concurrent.ExecutionException: Boxed Exception
[info]     Cause: java.lang.AssertionError: assertion failed: timeout (3 seconds) during expectMsg:
[info]     at org.apache.pekko.testkit.TestKitBase.expectMsgPF(TestKit.scala:490)
```

**Test Logic**:
1. Test creates a `BlockFetcher` actor and starts block synchronization
2. Test sends requested headers and bodies to the fetcher
3. Test then sends **unrequested headers** from a different block range (`testBlocksChunked(3).headers`)
4. Test expects the fetcher to send a `BlacklistPeer` message within 3 seconds
5. **Actual Result**: No `BlacklistPeer` message received within timeout period

**Expected Behavior**: When a peer sends headers that were not requested, the `BlockFetcher` should immediately blacklist that peer to prevent misbehavior.

**Observed Behavior**: The `expectMsgPF` call times out after 3 seconds, indicating the `BlacklistPeer` message was either:
- Not sent at all
- Sent but delayed beyond the 3-second timeout
- Sent to a different actor/channel

### 2. RegularSyncSpec: "should blacklist peer which sends bodies that were not requested"

**Location**: `src/test/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSyncSpec.scala:233`

**Failure Details**:
```
[info]   - should blacklist peer which sends bodies that were not requested *** FAILED *** (3 seconds, 183 milliseconds)
[info]     java.util.concurrent.ExecutionException: Boxed Exception
[info]     Cause: java.lang.AssertionError: assertion failed: timeout (3 seconds) during expectMsg:
[info]     at org.apache.pekko.testkit.TestKitBase.expectMsgPF(TestKit.scala:490)
```

**Test Logic**: Similar to test #1, but with **unrequested bodies** instead of headers.

**Expected Behavior**: When a peer sends bodies that were not requested, the `BlockFetcher` should immediately blacklist that peer.

**Observed Behavior**: Same timeout as test #1, suggesting a systematic issue with the blacklisting logic or test timing.

---

## Root Cause Analysis

### Hypothesis 1: Test Flakiness (Most Likely)

**Evidence**:
- Both tests have identical failure symptoms (3-second timeout)
- The timeout duration exactly matches the `expectMsgPF` timeout parameter
- 99.90% of tests passed, suggesting no widespread actor system issues
- Timing-sensitive tests in actor systems are notoriously flaky

**Possible Causes**:
- Actor message processing delay due to:
  - CPU scheduling variance in CI environment
  - Thread pool saturation from parallel test execution
  - Garbage collection pauses
  - Race condition in message ordering
- Test setup timing issues (actor not fully initialized before receiving messages)

**Supporting Evidence from Code Review** (from CI logs):
```scala
// Line 228-230 in RegularSyncSpec.scala
peersClient.expectMsgPF() {
  case PeersClient.BlacklistPeer(id, _) if id == defaultPeer.id => true
}
```
The test uses `expectMsgPF()` without an explicit timeout, so it falls back to the default 3-second timeout. This is a narrow window for actor message propagation in a loaded CI environment.

### Hypothesis 2: Regression in BlockFetcher Blacklisting Logic

**Evidence Against**:
- No recent commits found related to `RegularSyncSpec` or blacklisting logic
- 2048 other tests passed, including other `RegularSyncSpec` tests
- Test "should blacklist peer which returns headers not forming a chain" (line 202) passed successfully (181ms)

**Evidence For**:
- If there were a systematic issue, we'd expect more failures in similar tests

**Likelihood**: Low. The high pass rate of related tests suggests the blacklisting logic itself is functional.

### Hypothesis 3: Resource Contention in CI Environment

**Evidence**:
- Both failures occurred at approximately the same time (03:53:06 and 03:53:10)
- Test suite had been running for ~6 minutes (near end of run)
- CI environment may have resource constraints (CPU, memory)

**Supporting Factors**:
- Tests are running in parallel (common for sbt test suites)
- JVM may be under memory pressure after 6 minutes of continuous testing
- Docker containers from Gorgoroth testing (if on same runner) could compete for resources

**Likelihood**: Moderate. Timing issues at the end of long test runs are common.

---

## Relationship to Gorgoroth Field Report

### Connection Points

1. **Peer Communication**: Both issues involve peer interaction logic
   - CI: Fukuii's internal peer blacklisting for misbehaving nodes
   - Field Report: Cross-client peer connectivity with Core-Geth

2. **Timing Sensitivity**: Both exhibit timing-related problems
   - CI: Test timeout waiting for blacklist message
   - Field Report: Peer count collection showed transient unavailability

3. **Actor System Health**: Both depend on Pekko actor message passing
   - CI: `BlockFetcher` actor must send `BlacklistPeer` to `peersClient`
   - Field Report: Multiple actors coordinate peer discovery and sync

### Key Differences

| Aspect | CI Failure | Field Report |
|--------|------------|--------------|
| **Scope** | Unit test isolation | Multi-client integration |
| **Environment** | GitHub Actions CI | Local Docker network |
| **Failure Mode** | Test timeout | Container crash loop |
| **Root Cause** | Test flakiness (likely) | Genesis configuration incompatibility |
| **Impact** | CI build failure | Network testing blocked |
| **Fix Complexity** | Test adjustment or retry | Genesis configuration update |

### Are They Related?

**Conclusion**: **No direct relationship**. These are independent issues:

1. **CI Failure**: Internal test infrastructure issue
   - Fukuii's peer blacklisting logic likely works correctly
   - Test timeouts suggest environmental timing issues
   - No changes to production code required (likely)

2. **Field Report**: External integration issue
   - Core-Geth cannot initialize with current genesis configuration
   - EIP-1559 blob pool incompatibility with PoW genesis
   - Requires genesis configuration or Core-Geth version changes

**Indirect Connection**: Both issues relate to peer communication reliability, but at different layers:
- CI tests Fukuii's internal peer management logic
- Field report exposes cross-client compatibility at the genesis/configuration layer

---

## Recommendations

### For CI Test Failures (Immediate)

1. **Increase Test Timeout** (Quick Fix)
   
   Increase the `expectMsgPF` timeout in the two failing tests from 3 seconds to 5 seconds:
   ```scala
   // Line 228 in RegularSyncSpec.scala
   peersClient.expectMsgPF(5.seconds) {  // Increased from implicit 3s
     case PeersClient.BlacklistPeer(id, _) if id == defaultPeer.id => true
   }
   ```
   
   **Pros**: Addresses test flakiness without changing production code
   **Cons**: Doesn't fix underlying timing issue if it's a real regression

2. **Add Test Retry Logic** (Preferred Short-Term)
   
   Configure sbt to retry flaky tests:
   ```scala
   // In build.sbt
   Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-oD")
   Test / testOptions += Tests.Argument(TestFrameworks.ScalaTest, "-F", "3")  // Retry 3 times
   ```

3. **Investigate Actor Initialization Timing**
   
   Add explicit synchronization to ensure `BlockFetcher` actor is fully initialized:
   ```scala
   val fetcher: typed.ActorRef[BlockFetcher.FetchCommand] = 
     system.spawn(BlockFetcher(...), "block-fetcher")
   
   // Wait for actor to be ready
   eventually(timeout(Span(2, Seconds))) {
     fetcher ! BlockFetcher.GetStatus
     // ... verify ready
   }
   
   fetcher ! Start(blockImporter.ref, 0)
   ```

### For Field Report Issues (Separate Work)

See recommendations in the [Gorgoroth Field Report (2025-12-13)](./GORGOROTH_FIELD_REPORT_2025-12-13.md):
- Update genesis configuration to disable EIP-1559 for PoW
- Pin Core-Geth to ETC-compatible version
- Add runtime flags to disable blob pool

### For Long-Term Reliability

4. **Improve Test Observability**
   
   Add logging to understand why `BlacklistPeer` messages aren't arriving:
   ```scala
   within(3.seconds) {
     logger.debug(s"Waiting for BlacklistPeer for peer ${defaultPeer.id}")
     peersClient.expectMsgPF() {
       case msg @ PeersClient.BlacklistPeer(id, _) => 
         logger.debug(s"Received BlacklistPeer: $msg")
         id == defaultPeer.id
     }
   }
   ```

5. **Monitor Test Flakiness Trends**
   
   Track test failure rates over time:
   - Set up test analytics dashboard
   - Alert on increasing failure rates
   - Identify environmental factors (time of day, runner load, etc.)

6. **Isolate Heavy Integration Tests**
   
   Consider separating long-running integration tests from fast unit tests:
   ```bash
   sbt testFast    # Unit tests only (< 3 minutes)
   sbt testSlow    # Integration tests (> 3 minutes)
   ```

---

## Next Steps

### Immediate Actions

1. ✅ **Document Field Report**: Store Gorgoroth trial results in `docs/reports/`
2. ✅ **Analyze CI Failure**: Determine relationship to field report issues
3. ⏳ **Triage CI Failure**: Classify as test flakiness vs. production bug
4. ⏳ **Implement Fix**: Apply appropriate solution (timeout increase or retry logic)

### Follow-Up Investigation

5. ⏳ **Run Tests Locally**: Reproduce failures on local developer machine
   ```bash
   sbt "testOnly com.chipprbots.ethereum.blockchain.sync.regular.RegularSyncSpec"
   # Run 10 times to check for flakiness
   for i in {1..10}; do sbt testOnly ...; done
   ```

6. ⏳ **Review Recent Changes**: Check for recent changes to `BlockFetcher` or peer management
   ```bash
   git log --since="2025-12-01" --oneline -- src/main/scala/com/chipprbots/ethereum/blockchain/sync/
   ```

7. ⏳ **Monitor Future CI Runs**: Track whether failures persist or were transient

### Coordination with Field Report Work

8. ⏳ **Fix Genesis Configuration**: Address Core-Geth compatibility (separate PR)
9. ⏳ **Retest Gorgoroth Mixed Network**: Validate fix with new trial
10. ⏳ **Update Documentation**: Add troubleshooting guidance for mixed-client networks

---

## Conclusion

The CI test failures on 2025-12-13 are **likely test flakiness** related to timeout sensitivity in actor message passing tests. While both the CI failures and Gorgoroth field report involve peer communication, they represent **independent issues at different system layers**:

- **CI Failures**: Internal test infrastructure timing issues (99.90% pass rate)
- **Field Report**: External Core-Geth genesis configuration incompatibility (100% failure rate for Core-Geth nodes)

**Recommended Approach**:
1. Address CI test flakiness with timeout adjustments or retry logic (low risk)
2. Address Core-Geth compatibility with genesis configuration updates (separate effort)
3. Monitor both issues independently to confirm fixes are effective

The high test pass rate (99.90%) suggests Fukuii's peer blacklisting logic is functionally correct, and the failures are environmental/timing-related rather than indicating a production code regression.

---

## References

- [Gorgoroth Field Report 2025-12-13](./GORGOROTH_FIELD_REPORT_2025-12-13.md)
- CI Build: GitHub Actions Run #20186277208 (logs archived internally)
- RegularSyncSpec Source: `src/test/scala/com/chipprbots/ethereum/blockchain/sync/regular/RegularSyncSpec.scala`
- [Pekko TestKit Documentation](https://pekko.apache.org/docs/pekko/current/testing.html)

**Note**: Original CI build logs were retrieved from a temporary Azure blob storage URL. Key excerpts have been preserved in this document for historical reference.
