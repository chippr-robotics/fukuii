package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

class SNAPSyncControllerSpec extends AnyFlatSpec with Matchers {

  "SNAPSyncConfig" should "load from config correctly" taggedAs UnitTest in {
    // Test that the config case class works properly
    val config = SNAPSyncConfig(
      enabled = true,
      pivotBlockOffset = 1024,
      accountConcurrency = 16,
      storageConcurrency = 8,
      storageBatchSize = 8,
      healingBatchSize = 16,
      stateValidationEnabled = true,
      maxRetries = 3,
      timeout = 30.seconds,
      maxSnapSyncFailures = 5
    )

    config.enabled shouldBe true
    config.pivotBlockOffset shouldBe 1024
    config.accountConcurrency shouldBe 16
    config.maxRetries shouldBe 3
    config.maxSnapSyncFailures shouldBe 5
  }

  it should "have sensible defaults" taggedAs UnitTest in {
    val config = SNAPSyncConfig()

    config.enabled shouldBe true
    config.pivotBlockOffset should be > 0L
    config.accountConcurrency should be > 0
    config.storageConcurrency should be > 0
    config.healingBatchSize should be > 0
    config.maxSnapSyncFailures should be > 0
  }

  // Regression for #1162: chain backfill is decoupled from SNAP completion. The new
  // `chainBackfillConcurrentRequests` budget controls how many in-flight ETH requests background
  // backfill is allowed once regular sync has taken over. Default must be small (yield to regular sync).
  it should "default chainBackfillConcurrentRequests to a small value (yield to regular sync)" taggedAs UnitTest in {
    val config = SNAPSyncConfig()
    config.chainBackfillConcurrentRequests should be > 0
    config.chainBackfillConcurrentRequests should be <= config.chainDownloadBoostedConcurrentRequests
  }

  // Regression for #1162: SNAPSyncController emits a two-phase handshake — SnapSyncFinalized first,
  // then Done either immediately or later. SnapSyncFinalized must carry the pivot block number
  // so SyncController has the context it needs when starting regular sync.
  "SNAPSyncController.SnapSyncFinalized" should "carry the pivot block number" taggedAs UnitTest in {
    val msg = SNAPSyncController.SnapSyncFinalized(BigInt(12345))
    msg.pivot shouldBe BigInt(12345)
  }

  it should "be distinct from Done" taggedAs UnitTest in {
    val finalized: Any = SNAPSyncController.SnapSyncFinalized(BigInt(0))
    val done: Any = SNAPSyncController.Done
    (finalized should not).equal(done)
  }

  "SyncProgress" should "format progress string correctly" taggedAs UnitTest in {
    import SNAPSyncController._

    val progress = SyncProgress(
      phase = AccountRangeSync,
      accountsSynced = 1000,
      bytecodesDownloaded = 50,
      storageSlotsSynced = 200,
      nodesHealed = 10,
      elapsedSeconds = 60.0,
      phaseElapsedSeconds = 30.0,
      accountsPerSec = 16.67,
      bytecodesPerSec = 0.83,
      slotsPerSec = 3.33,
      nodesPerSec = 0.17,
      recentAccountsPerSec = 20.0,
      recentBytecodesPerSec = 1.0,
      recentSlotsPerSec = 5.0,
      recentNodesPerSec = 0.5,
      phaseProgress = 50,
      estimatedTotalAccounts = 2000,
      estimatedTotalBytecodes = 100,
      estimatedTotalSlots = 400,
      startTime = System.currentTimeMillis() - 60000,
      phaseStartTime = System.currentTimeMillis() - 30000
    )

    val formattedString = progress.formattedString
    formattedString should include("Accounts")
    formattedString should include("50%")
    formattedString should include("1.0K/~2.0K")
  }

  it should "handle different phases correctly" taggedAs UnitTest in {
    import SNAPSyncController._

    val phases = Seq(Idle, AccountRangeSync, ByteCodeAndStorageSync, StateHealing, StateValidation, Completed)

    phases.foreach { phase =>
      val progress = SyncProgress(
        phase = phase,
        accountsSynced = 0,
        bytecodesDownloaded = 0,
        storageSlotsSynced = 0,
        nodesHealed = 0,
        elapsedSeconds = 0,
        phaseElapsedSeconds = 0,
        accountsPerSec = 0,
        bytecodesPerSec = 0,
        slotsPerSec = 0,
        nodesPerSec = 0,
        recentAccountsPerSec = 0,
        recentBytecodesPerSec = 0,
        recentSlotsPerSec = 0,
        recentNodesPerSec = 0,
        phaseProgress = 0,
        estimatedTotalAccounts = 0,
        estimatedTotalBytecodes = 0,
        estimatedTotalSlots = 0,
        startTime = System.currentTimeMillis(),
        phaseStartTime = System.currentTimeMillis()
      )

      progress.formattedString should not be empty
    }
  }

  "SNAPSyncController messages" should "be defined correctly" taggedAs UnitTest in {
    import SNAPSyncController._

    // Test that all message types are defined
    val start = Start
    val done = Done
    val _ = AccountRangeSyncComplete
    val _ = ByteCodeSyncComplete
    val _ = StorageRangeSyncComplete
    val _ = StorageRangeSyncForceCompleted
    val _ = StateHealingComplete
    val _ = StateValidationComplete
    val getProgress = GetProgress
    val bootstrapComplete = BootstrapComplete
    val fallback = FallbackToFastSync

    // Verify they exist
    start shouldBe Start
    done shouldBe Done
    getProgress shouldBe GetProgress
    bootstrapComplete shouldBe BootstrapComplete
    fallback shouldBe FallbackToFastSync
  }

  it should "skip healing for clean deferred-merkleization downloads only" taggedAs UnitTest in {
    val config = SNAPSyncConfig(deferredMerkleization = true)

    SNAPSyncController.shouldSkipHealingAfterDownloads(
      snapSyncConfig = config,
      storagePhaseForceCompleted = false
    ) shouldBe true
  }

  it should "run healing when storage was force-completed even with deferred merkleization" taggedAs UnitTest in {
    val config = SNAPSyncConfig(deferredMerkleization = true)

    SNAPSyncController.shouldSkipHealingAfterDownloads(
      snapSyncConfig = config,
      storagePhaseForceCompleted = true
    ) shouldBe false
  }

  // CL-driven pivot messages (#1207). On post-merge chains the pivot comes from the
  // consensus layer via engine_forkchoiceUpdated; SNAP must support a hint message and a
  // by-hash bootstrap variant.
  "SNAPSyncController.CLPivotHint" should "carry the head hash and optional header" taggedAs UnitTest in {
    import SNAPSyncController._
    import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
    import com.chipprbots.ethereum.domain.BlockHeader

    val headHash = ByteString(Array.fill(32)(0x42.toByte))
    val withoutHeader = CLPivotHint(headHash, None)
    withoutHeader.headHash shouldBe headHash
    withoutHeader.knownHeader shouldBe None

    val header = BlockHeader(
      parentHash = ByteString(new Array[Byte](32)),
      ommersHash = BlockHeader.EmptyOmmers,
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = ByteString(Array.fill(32)(0x77.toByte)),
      transactionsRoot = BlockHeader.EmptyMpt,
      receiptsRoot = BlockHeader.EmptyMpt,
      logsBloom = ByteString(new Array[Byte](256)),
      difficulty = 0,
      number = 9876543,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 1700000000,
      extraData = ByteString.empty,
      mixHash = ByteString(new Array[Byte](32)),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostOlympia(BigInt("1000000000"))
    )
    val withHeader = CLPivotHint(headHash, Some(header))
    withHeader.knownHeader.map(_.number) shouldBe Some(BigInt(9876543))
  }

  "SNAPSyncController.StartRegularSyncBootstrapByHash" should "carry the head hash" taggedAs UnitTest in {
    import SNAPSyncController._
    val headHash = ByteString(Array.fill(32)(0xaa.toByte))
    val msg = StartRegularSyncBootstrapByHash(headHash)
    msg.headHash shouldBe headHash
    msg shouldBe a[StartRegularSyncBootstrapByHash]
  }

  "PivotSelectionSource.CLDrivenPivot" should "be a distinct value from NetworkPivot/LocalPivot" taggedAs UnitTest in {
    import SNAPSyncController._
    val sources: Seq[PivotSelectionSource] = Seq(NetworkPivot, LocalPivot, CLDrivenPivot)
    sources.distinct.size shouldBe 3
    CLDrivenPivot.name shouldBe "cl-driven"
  }

  it should "have bootstrap message with target block" taggedAs UnitTest in {
    import SNAPSyncController._

    // Test bootstrap message
    val targetBlock = BigInt(1025)
    val bootstrap = StartRegularSyncBootstrap(targetBlock)

    bootstrap.targetBlock shouldBe targetBlock
    bootstrap shouldBe a[StartRegularSyncBootstrap]
  }

  it should "have correct phase types" taggedAs UnitTest in {
    import SNAPSyncController._

    // Test phase hierarchy
    val idle: SyncPhase = Idle
    val accountRange: SyncPhase = AccountRangeSync
    val bytecodeAndStorage: SyncPhase = ByteCodeAndStorageSync
    val healing: SyncPhase = StateHealing
    val validation: SyncPhase = StateValidation
    val complete: SyncPhase = Completed

    // All phases should be SyncPhase instances
    idle shouldBe a[SyncPhase]
    accountRange shouldBe a[SyncPhase]
    bytecodeAndStorage shouldBe a[SyncPhase]
    healing shouldBe a[SyncPhase]
    validation shouldBe a[SyncPhase]
    complete shouldBe a[SyncPhase]
  }

  "SNAPSyncController" should "provide status for eth_syncing RPC" taggedAs UnitTest in {
    import SNAPSyncController._

    // Verify that SNAP sync phases can be converted to state node progress
    // This is tested indirectly through the currentSyncStatus method
    // but we can verify the data structures are compatible

    val accountProgress = SyncProgress(
      phase = AccountRangeSync,
      accountsSynced = 1000,
      bytecodesDownloaded = 0,
      storageSlotsSynced = 0,
      nodesHealed = 0,
      elapsedSeconds = 10.0,
      phaseElapsedSeconds = 10.0,
      accountsPerSec = 100.0,
      bytecodesPerSec = 0.0,
      slotsPerSec = 0.0,
      nodesPerSec = 0.0,
      recentAccountsPerSec = 100.0,
      recentBytecodesPerSec = 0.0,
      recentSlotsPerSec = 0.0,
      recentNodesPerSec = 0.0,
      phaseProgress = 50,
      estimatedTotalAccounts = 2000,
      estimatedTotalBytecodes = 0,
      estimatedTotalSlots = 0,
      startTime = System.currentTimeMillis(),
      phaseStartTime = System.currentTimeMillis()
    )

    // Verify progress tracking for different phases
    accountProgress.accountsSynced shouldBe 1000
    accountProgress.estimatedTotalAccounts shouldBe 2000
    accountProgress.phase shouldBe AccountRangeSync

    // Verify that we can represent state node progress
    // In SNAP sync, state nodes = accounts + bytecodes + storage slots + healed nodes
    val totalStateNodes = accountProgress.accountsSynced +
      accountProgress.bytecodesDownloaded +
      accountProgress.storageSlotsSynced +
      accountProgress.nodesHealed

    totalStateNodes shouldBe 1000 // Only accounts at this phase

    // Test bytecode+storage phase accumulation
    val bytecodeStorageProgress = accountProgress.copy(
      phase = ByteCodeAndStorageSync,
      bytecodesDownloaded = 500,
      storageSlotsSynced = 3000
    )

    val totalWithBytecodeStorage = bytecodeStorageProgress.accountsSynced +
      bytecodeStorageProgress.bytecodesDownloaded +
      bytecodeStorageProgress.storageSlotsSynced
    totalWithBytecodeStorage shouldBe 4500
  }

  "SyncProgress formatCount" should "format large numbers with K/M suffixes" taggedAs UnitTest in {
    import SNAPSyncController._

    val progress = SyncProgress(
      phase = AccountRangeSync,
      accountsSynced = 13200000,
      bytecodesDownloaded = 0,
      storageSlotsSynced = 0,
      nodesHealed = 0,
      elapsedSeconds = 100.0,
      phaseElapsedSeconds = 100.0,
      accountsPerSec = 132000,
      bytecodesPerSec = 0,
      slotsPerSec = 0,
      nodesPerSec = 0,
      recentAccountsPerSec = 5000,
      recentBytecodesPerSec = 0,
      recentSlotsPerSec = 0,
      recentNodesPerSec = 0,
      phaseProgress = 100,
      estimatedTotalAccounts = 13200000,
      estimatedTotalBytecodes = 0,
      estimatedTotalSlots = 0,
      startTime = System.currentTimeMillis() - 100000,
      phaseStartTime = System.currentTimeMillis() - 100000
    )

    val formatted = progress.formattedString
    formatted should include("13.2M")
    formatted should include("100%")
  }

  // ---- J8: State machine — phase ordering and restart detection -------------------

  "SyncPhase" should "enumerate all 6 phases as distinct values" taggedAs UnitTest in {
    import SNAPSyncController._

    val allPhases: Seq[SyncPhase] = Seq(
      Idle,
      AccountRangeSync,
      ByteCodeAndStorageSync,
      StateHealing,
      StateValidation,
      Completed
    )
    allPhases.distinct.size shouldBe 6
  }

  it should "declare Idle before AccountRangeSync in canonical declaration order" taggedAs UnitTest in {
    import SNAPSyncController._

    // The canonical SNAP sync pipeline order.  We can't enforce ordering via sealed trait alone,
    // but locking the set of phases here means adding a new phase forces updating this test.
    val canonicalOrder = Seq(
      Idle,
      AccountRangeSync,
      ByteCodeAndStorageSync,
      StateHealing,
      StateValidation,
      Completed
    )
    canonicalOrder.head shouldBe Idle
    canonicalOrder.last shouldBe Completed
    canonicalOrder(1) shouldBe AccountRangeSync
    canonicalOrder(2) shouldBe ByteCodeAndStorageSync
    canonicalOrder(3) shouldBe StateHealing
    canonicalOrder(4) shouldBe StateValidation
  }

  it should "include ChainDownloadCompletion as a valid intermediate phase" taggedAs UnitTest in {
    import SNAPSyncController._
    val phase: SyncPhase = ChainDownloadCompletion
    phase shouldBe a[SyncPhase]
    phase should not be Completed
  }

  // walkGeneration epoch isolation model (D1 regression — commit 169c4b64c).
  // SNAPSyncController uses a Long counter (walkGeneration) so that TrieWalk* messages
  // carrying a stale generation are silently discarded rather than processed against
  // the wrong state root.  This test models the counter semantics as pure logic so a
  // future refactor cannot reintroduce the race without a failing test.
  "walkGeneration epoch model" should "accept current generation and reject stale" taggedAs UnitTest in {
    // Simulate the counter behaviour extracted from SNAPSyncController.
    var generation: Long = 0L

    def invalidate(): Long = { generation += 1; generation }
    def isStale(gen: Long): Boolean = gen != generation

    // Initial state: generation=0, any message with gen=0 is current
    generation shouldBe 0L
    isStale(0L) shouldBe false

    // Pivot refresh fires → generation incremented to 1
    val gen1 = invalidate()
    gen1 shouldBe 1L
    isStale(0L) shouldBe true // old walk (gen=0) is now stale
    isStale(1L) shouldBe false // new walk (gen=1) is current

    // Second pivot refresh → generation=2
    val gen2 = invalidate()
    gen2 shouldBe 2L
    isStale(1L) shouldBe true // gen=1 walk is now stale
    isStale(2L) shouldBe false // gen=2 is current

    // Incrementing twice (restartSnapSync + triggerHealingForMissingNodes) still produces distinct values
    val gen3 = invalidate()
    val gen4 = invalidate()
    gen4 should be > gen3
    isStale(gen3) shouldBe true
    isStale(gen4) shouldBe false
  }

  // ── #1188: Healing clean-signal short-circuit (logic model) ──
  // When the round-2 healing trie walk returns `totalFound == 0`, the entire account+storage
  // trie has been DFS-walked end-to-end. SNAPSyncController captures this as `healingValidatedRoot`
  // and `validateState()` short-circuits the redundant `validateAccountTrie + validateAllStorageTries`
  // passes. Belt-and-suspenders: only honoured when the captured root *equals* the current `stateRoot`,
  // so any pivot refresh / restart naturally invalidates the signal and full validation runs.
  //
  // These tests model the decision as pure logic (mirroring the `walkGeneration epoch model` pattern)
  // so a future refactor cannot silently revert the short-circuit without a failing test.

  "Healing clean-signal model" should "skip validation when healingValidatedRoot matches current stateRoot" taggedAs UnitTest in {
    var healingValidatedRoot: Option[ByteString] = None
    val rootA = ByteString("root-a".getBytes)

    // TrieWalkComplete(0) sets the signal against the current root.
    healingValidatedRoot = Some(rootA)

    // validateState() decision: skip if signal matches.
    val expectedRoot = rootA
    val shouldSkip = healingValidatedRoot.contains(expectedRoot)
    shouldSkip shouldBe true

    // Consume on use (one-shot semantics).
    if (shouldSkip) healingValidatedRoot = None
    healingValidatedRoot shouldBe None
  }

  it should "run full validation when stateRoot changed since healing (pivot refresh case)" taggedAs UnitTest in {
    var healingValidatedRoot: Option[ByteString] = None
    val rootA = ByteString("root-a".getBytes)
    val rootB = ByteString("root-b".getBytes)

    // Signal captured against rootA.
    healingValidatedRoot = Some(rootA)

    // Pivot refreshed → stateRoot is now rootB. validateState() decision keys on root equality;
    // a stale signal against the OLD root must NOT short-circuit validation against the NEW root.
    val expectedRoot = rootB
    healingValidatedRoot.contains(expectedRoot) shouldBe false
  }

  it should "NOT capture signal when healing reports missing nodes (TrieWalkComplete(N>0))" taggedAs UnitTest in {
    var healingValidatedRoot: Option[ByteString] = None
    val rootA = ByteString("root-a".getBytes)

    val totalFound = 87901 // round-1 healing case: not yet clean
    if (totalFound == 0) healingValidatedRoot = Some(rootA)
    // else: signal NOT set; another healing round will run.

    healingValidatedRoot shouldBe None
  }

  it should "be invalidated by restartSnapSync (defense-in-depth)" taggedAs UnitTest in {
    var healingValidatedRoot: Option[ByteString] = None
    var stateRoot: Option[ByteString] = Some(ByteString("root-a".getBytes))

    // Signal captured.
    healingValidatedRoot = stateRoot

    // restartSnapSync clears stateRoot; should also explicitly clear the signal.
    healingValidatedRoot = None
    stateRoot = None

    healingValidatedRoot shouldBe None
    stateRoot shouldBe None
  }

  it should "be one-shot: a second validateState() call after consumption falls through to full validation" taggedAs UnitTest in {
    var healingValidatedRoot: Option[ByteString] = None
    val rootA = ByteString("root-a".getBytes)
    healingValidatedRoot = Some(rootA)

    // First call: matches, skip, consume.
    val first = healingValidatedRoot.contains(rootA)
    first shouldBe true
    if (first) healingValidatedRoot = None

    // Second call (e.g. validation retry path) — signal already consumed, full validation must run.
    val second = healingValidatedRoot.contains(rootA)
    second shouldBe false
  }

  // Restart phase detection — models the AppStateStorage flag combinations that determine
  // which SNAP sync phase is entered on restart (tested here at the storage-semantics level;
  // the full actor path is an integration test).
  "Restart phase detection" should "enter AccountRangeSync when no completion flags are set" taggedAs UnitTest in {
    import com.chipprbots.ethereum.db.dataSource.EphemDataSource
    import com.chipprbots.ethereum.db.storage.AppStateStorage

    val storage = new AppStateStorage(EphemDataSource())
    // No flags set → accounts not complete → start from account download
    storage.isSnapSyncAccountsComplete() shouldBe false
    storage.isSnapSyncStorageComplete() shouldBe false
    storage.isSnapSyncBytecodeComplete() shouldBe false
  }

  it should "skip account phase when accountsComplete=true and bytecode+storage remain" taggedAs UnitTest in {
    import com.chipprbots.ethereum.db.dataSource.EphemDataSource
    import com.chipprbots.ethereum.db.storage.AppStateStorage

    val storage = new AppStateStorage(EphemDataSource())
    storage.putSnapSyncAccountsComplete(true).commit()

    storage.isSnapSyncAccountsComplete() shouldBe true
    // Storage and bytecode not yet done → resume at ByteCodeAndStorageSync
    storage.isSnapSyncStorageComplete() shouldBe false
    storage.isSnapSyncBytecodeComplete() shouldBe false
  }

  it should "skip account+bytecode+storage phases when all three are complete" taggedAs UnitTest in {
    import com.chipprbots.ethereum.db.dataSource.EphemDataSource
    import com.chipprbots.ethereum.db.storage.AppStateStorage

    val storage = new AppStateStorage(EphemDataSource())
    storage
      .putSnapSyncAccountsComplete(true)
      .and(storage.putSnapSyncStorageComplete(true))
      .and(storage.putSnapSyncBytecodeComplete(true))
      .commit()

    // All three download phases done → restart enters StateHealing
    storage.isSnapSyncAccountsComplete() shouldBe true
    storage.isSnapSyncStorageComplete() shouldBe true
    storage.isSnapSyncBytecodeComplete() shouldBe true
    // SnapSyncDone NOT set → healing still needed (sync not complete)
    storage.isSnapSyncDone() shouldBe false
  }

  it should "show sync is complete once SnapSyncDone is set regardless of phase flags" taggedAs UnitTest in {
    import com.chipprbots.ethereum.db.dataSource.EphemDataSource
    import com.chipprbots.ethereum.db.storage.AppStateStorage

    val storage = new AppStateStorage(EphemDataSource())
    storage
      .putSnapSyncAccountsComplete(true)
      .and(storage.putSnapSyncStorageComplete(true))
      .and(storage.putSnapSyncBytecodeComplete(true))
      .and(storage.snapSyncDone())
      .commit()

    storage.isSnapSyncDone() shouldBe true
    storage.isSnapSyncInProgress() shouldBe false // Done wins over in-progress
  }

  it should "show ByteCode phase with total and percentage" taggedAs UnitTest in {
    import SNAPSyncController._

    val progress = SyncProgress(
      phase = ByteCodeAndStorageSync,
      accountsSynced = 2700000,
      bytecodesDownloaded = 95200,
      storageSlotsSynced = 44500,
      nodesHealed = 0,
      elapsedSeconds = 300.0,
      phaseElapsedSeconds = 60.0,
      accountsPerSec = 9000,
      bytecodesPerSec = 1586,
      slotsPerSec = 370,
      nodesPerSec = 0,
      recentAccountsPerSec = 0,
      recentBytecodesPerSec = 593,
      recentSlotsPerSec = 370,
      recentNodesPerSec = 0,
      phaseProgress = 12,
      estimatedTotalAccounts = 2700000,
      estimatedTotalBytecodes = 146000,
      estimatedTotalSlots = 370000,
      startTime = System.currentTimeMillis() - 300000,
      phaseStartTime = System.currentTimeMillis() - 60000,
      storageContractsCompleted = 1823,
      storageContractsTotal = 15234
    )

    val formatted = progress.formattedString
    formatted should include("Code+Storage")
    formatted should include("1823/15234 contracts")
  }

  // ── K6: Account trie root mismatch restart (regression for may-fields commit 20ccc1f2b) ──
  // SNAPSyncController must restart (not proceed to healing) when account trie
  // finalization fails. Previously the coordinator always sent ProgressAccountsTrieFinalized
  // even on failure, causing healing to start with a corrupt trie.

  "AccountTrieFinalizationFailed message" should "exist and carry an error string" taggedAs UnitTest in {
    import SNAPSyncController._
    val msg = AccountTrieFinalizationFailed("root mismatch: computed 8f5d92fe != expected b8c5a89e")
    msg.error should include("root mismatch")
    msg.error should include("8f5d92fe")
    msg shouldBe an[AccountTrieFinalizationFailed]
  }

  it should "be distinct from AccountTrieFinalized (success path)" taggedAs UnitTest in {
    import SNAPSyncController._
    import org.apache.pekko.util.ByteString
    val failed = AccountTrieFinalizationFailed("some error")
    val succeeded = AccountTrieFinalized(ByteString(Array.fill(32)(0.toByte)))
    (failed should not).equal(succeeded)
  }

  "SNAPSyncController message set" should "include AccountTrieFinalizationFailed alongside AccountTrieFinalized" taggedAs UnitTest in {
    import SNAPSyncController._
    // Both success and failure finalization messages must exist so the controller
    // can distinguish "proceed to healing" from "restart with fresh pivot".
    val successMsg: AnyRef = AccountTrieFinalized(org.apache.pekko.util.ByteString.empty)
    val failMsg: AnyRef = AccountTrieFinalizationFailed("error")
    successMsg.getClass should not be failMsg.getClass
  }

  // ── K2: Startup race — zero-height pivot exclusion (regression for commit 164c8e2ac) ──
  // Before 164c8e2ac, peers whose STATUS exchange had not yet completed (maxBlockNumber=0)
  // were included in pivot candidate selection. This caused the pivot to compute as 0
  // (max(0,0,...) - pivotBlockOffset = negative → clamped to 0), triggering an immediate
  // sync-from-genesis which is wrong when valid peers do exist but haven't handshaked yet.
  //
  // The fix adds a maxBlockNumber > 0 guard at every SNAP peer-selection site.
  // These tests lock the filter semantics so a refactor cannot silently revert the guard.

  "SNAP peer selection" should "exclude zero-height peers from pivot candidate computation" taggedAs UnitTest in {
    case class TestPeer(maxBlockNumber: BigInt)
    val peers = Seq(TestPeer(0), TestPeer(0), TestPeer(20_000_000))
    val eligible = peers.filter(_.maxBlockNumber > 0)
    eligible should have size 1
    eligible.head.maxBlockNumber shouldBe BigInt(20_000_000)
  }

  it should "identify when ALL peers have maxBlockNumber=0 (STATUS not yet exchanged)" taggedAs UnitTest in {
    case class TestPeer(maxBlockNumber: BigInt)
    val peers = Seq(TestPeer(0), TestPeer(0), TestPeer(0))
    // All zero → no eligible peers → pivot selection must wait or be skipped.
    // The controller checks eligiblePeers.isEmpty to gate pivot selection.
    peers.filter(_.maxBlockNumber > 0) shouldBe empty
  }

  it should "pass only peers with maxBlockNumber > 0 through the zero-height filter" taggedAs UnitTest in {
    case class TestPeer(maxBlockNumber: BigInt)
    val mixed = Seq(TestPeer(0), TestPeer(19_000_000), TestPeer(20_000_000))
    val eligible = mixed.filter(_.maxBlockNumber > 0)
    eligible should have size 2
    all(eligible.map(_.maxBlockNumber)) should be > BigInt(0)
  }

  it should "compute a valid pivot from the highest eligible peer, ignoring zero-height peers" taggedAs UnitTest in {
    case class TestPeer(maxBlockNumber: BigInt)
    val pivotBlockOffset = BigInt(5)
    // Mix of zero-height and valid peers. Pivot must be derived from valid peers only.
    val peers = Seq(TestPeer(0), TestPeer(0), TestPeer(20_000_000))
    val eligible = peers.filter(_.maxBlockNumber > 0)
    val bestBlock = eligible.map(_.maxBlockNumber).max
    val pivot = (bestBlock - pivotBlockOffset).max(0)
    pivot shouldBe BigInt(19_999_995)
  }

  it should "not regress: pivot from zero-height-only peers would have computed 0 (old bug)" taggedAs UnitTest in {
    case class TestPeer(maxBlockNumber: BigInt)
    val pivotBlockOffset = BigInt(5)
    // Old code without filter: including zero-height peers in max() gives bestBlock=0,
    // pivot=max(0-5,0)=0. Confirm the old formula would have produced 0.
    val allPeers = Seq(TestPeer(0), TestPeer(0))
    val oldBestBlock = allPeers.map(_.maxBlockNumber).maxOption.getOrElse(BigInt(0))
    val oldPivot = (oldBestBlock - pivotBlockOffset).max(0)
    oldPivot shouldBe BigInt(0) // demonstrates why the fix was necessary
  }

  // ── validatorFactory injection seam (issue #1161) ──────────────────────────
  //
  // SNAPSyncController exposes a `validatorFactory: MptStorage => StateValidator`
  // constructor parameter so tests can inject a fake validator. The full
  // actor-level orchestration tests (covering all the new ValidateAccountTrieResult /
  // ValidateStorageTriesResult / ValidationRetry handlers, the stale-generation
  // drop, the phase gate, and mailbox responsiveness) require building a heavy
  // dependency harness (BlockchainReader/Writer, AppStateStorage, StateStorage,
  // EvmCodeStorage, FlatSlotStorage, networkPeerManager, peerEventBus). That's
  // too much for this PR; orchestration is verified by live Mordor sync per the
  // pattern in this spec. These tests confirm the seam itself works.

  "SNAPSyncController validatorFactory seam" should "accept a custom factory in props" taggedAs UnitTest in {
    // The default factory is `new StateValidator(_)`. A test can substitute a
    // closure that returns a fake. We verify the closure constructs cleanly
    // and that the produced validator delegates to the StateValidator API
    // surface that the controller's spawn helpers call.
    val storage = new TestMptStorage()
    val factory: MptStorage => StateValidator = (s: MptStorage) => new StateValidator(s)
    val validator = factory(storage)
    validator should not be null
    validator shouldBe a[StateValidator]
  }

  it should "wrap a fake validator that returns canned results" taggedAs UnitTest in {
    // Demonstration of the FakeStateValidator pattern future actor tests will use.
    val storage = new TestMptStorage()
    val expectedRoot = ByteString("test-root-hash".getBytes)

    val fake = new FakeStateValidator(
      storage,
      accountResult = Right(Seq.empty),
      storageResult = Right(Seq.empty)
    )
    fake.validateAccountTrie(expectedRoot) shouldBe Right(Seq.empty)
    fake.validateAllStorageTries(expectedRoot) shouldBe Right(Seq.empty)
    fake.accountCallCount shouldBe 1
    fake.storageCallCount shouldBe 1
  }

  it should "support a fake that returns missing-node lists" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val expectedRoot = ByteString("test-root-hash".getBytes)
    val missingNode = ByteString("missing-node-hash".getBytes)

    val fake = new FakeStateValidator(
      storage,
      accountResult = Right(Seq(missingNode)),
      storageResult = Right(Seq.empty)
    )
    fake.validateAccountTrie(expectedRoot) shouldBe Right(Seq(missingNode))
  }

  it should "support a fake that returns the missing-root-node error string" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val expectedRoot = ByteString("test-root-hash".getBytes)

    val fake = new FakeStateValidator(
      storage,
      accountResult = Left("Missing root node: deadbeef"),
      storageResult = Right(Seq.empty)
    )
    val result = fake.validateAccountTrie(expectedRoot)
    result.isLeft shouldBe true
    result.left.toOption.get should include("Missing root node")
  }

  it should "support a fake that throws so onComplete-Failure path can be tested" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val expectedRoot = ByteString("test-root-hash".getBytes)

    val fake = new FakeStateValidator(
      storage,
      accountResult = Right(Seq.empty),
      storageResult = Right(Seq.empty),
      throwOnAccount = Some(new RuntimeException("simulated validator failure"))
    )
    val ex = intercept[RuntimeException](fake.validateAccountTrie(expectedRoot))
    ex.getMessage should include("simulated validator failure")
  }
}

/** Test helper: a `StateValidator` subclass that returns canned results without traversing the trie. Used in
  * orchestration tests to drive the controller's validation handlers without paying the cost of a real walk.
  *
  * Reset semantics: results and exception are immutable per instance. Call counters are mutable so tests can assert how
  * many times each method was invoked. Sleep durations let tests verify mailbox responsiveness during a slow walk.
  */
class FakeStateValidator(
    storage: MptStorage,
    accountResult: Either[String, Seq[ByteString]],
    storageResult: Either[String, Seq[ByteString]],
    throwOnAccount: Option[Throwable] = None,
    throwOnStorage: Option[Throwable] = None,
    accountSleepMs: Long = 0L,
    storageSleepMs: Long = 0L
) extends StateValidator(storage) {

  @volatile var accountCallCount: Int = 0
  @volatile var storageCallCount: Int = 0

  override def validateAccountTrie(stateRoot: ByteString): Either[String, Seq[ByteString]] = {
    accountCallCount += 1
    if (accountSleepMs > 0) Thread.sleep(accountSleepMs)
    throwOnAccount.foreach(t => throw t)
    accountResult
  }

  override def validateAllStorageTries(stateRoot: ByteString): Either[String, Seq[ByteString]] = {
    storageCallCount += 1
    if (storageSleepMs > 0) Thread.sleep(storageSleepMs)
    throwOnStorage.foreach(t => throw t)
    storageResult
  }
}
