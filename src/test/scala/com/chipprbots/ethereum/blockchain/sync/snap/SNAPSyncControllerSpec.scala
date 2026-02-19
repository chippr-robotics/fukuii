package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

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
    formattedString should include("AccountRange")
    formattedString should include("(50%)")
    formattedString should include("accounts=1.0K/~2.0K")
  }

  it should "handle different phases correctly" taggedAs UnitTest in {
    import SNAPSyncController._

    val phases = Seq(Idle, AccountRangeSync, ByteCodeSync, StorageRangeSync, StateHealing, StateValidation, Completed)

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
    val accountComplete = AccountRangeSyncComplete
    val bytecodeComplete = ByteCodeSyncComplete
    val storageComplete = StorageRangeSyncComplete
    val healingComplete = StateHealingComplete
    val validationComplete = StateValidationComplete
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
    val bytecode: SyncPhase = ByteCodeSync
    val storage: SyncPhase = StorageRangeSync
    val healing: SyncPhase = StateHealing
    val validation: SyncPhase = StateValidation
    val complete: SyncPhase = Completed

    // All phases should be SyncPhase instances
    idle shouldBe a[SyncPhase]
    accountRange shouldBe a[SyncPhase]
    bytecode shouldBe a[SyncPhase]
    storage shouldBe a[SyncPhase]
    healing shouldBe a[SyncPhase]
    validation shouldBe a[SyncPhase]
    complete shouldBe a[SyncPhase]
  }

  "SNAPSyncController" should "provide status for eth_syncing RPC" taggedAs UnitTest in {
    import SNAPSyncController._
    import com.chipprbots.ethereum.blockchain.sync.SyncProtocol

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
    
    // Test bytecode phase accumulation
    val bytecodeProgress = accountProgress.copy(
      phase = ByteCodeSync,
      bytecodesDownloaded = 500
    )
    
    val totalWithBytecodes = bytecodeProgress.accountsSynced + 
                            bytecodeProgress.bytecodesDownloaded
    totalWithBytecodes shouldBe 1500
    
    // Test storage phase accumulation
    val storageProgress = bytecodeProgress.copy(
      phase = StorageRangeSync,
      storageSlotsSynced = 3000
    )
    
    val totalWithStorage = storageProgress.accountsSynced + 
                          storageProgress.bytecodesDownloaded + 
                          storageProgress.storageSlotsSynced
    totalWithStorage shouldBe 4500
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
    formatted should include("(100%)")
  }

  it should "show ByteCode phase with total and percentage" taggedAs UnitTest in {
    import SNAPSyncController._

    val progress = SyncProgress(
      phase = ByteCodeSync,
      accountsSynced = 2700000,
      bytecodesDownloaded = 95200,
      storageSlotsSynced = 0,
      nodesHealed = 0,
      elapsedSeconds = 300.0,
      phaseElapsedSeconds = 60.0,
      accountsPerSec = 9000,
      bytecodesPerSec = 1586,
      slotsPerSec = 0,
      nodesPerSec = 0,
      recentAccountsPerSec = 0,
      recentBytecodesPerSec = 593,
      recentSlotsPerSec = 0,
      recentNodesPerSec = 0,
      phaseProgress = 65,
      estimatedTotalAccounts = 2700000,
      estimatedTotalBytecodes = 146000,
      estimatedTotalSlots = 0,
      startTime = System.currentTimeMillis() - 300000,
      phaseStartTime = System.currentTimeMillis() - 60000
    )

    val formatted = progress.formattedString
    formatted should include("ByteCode")
    formatted should include("(65%)")
    formatted should include("95.2K/146.0K")
  }

  it should "show Storage phase with contracts progress" taggedAs UnitTest in {
    import SNAPSyncController._

    val progress = SyncProgress(
      phase = StorageRangeSync,
      accountsSynced = 2700000,
      bytecodesDownloaded = 146000,
      storageSlotsSynced = 44500,
      nodesHealed = 0,
      elapsedSeconds = 600.0,
      phaseElapsedSeconds = 120.0,
      accountsPerSec = 4500,
      bytecodesPerSec = 243,
      slotsPerSec = 370,
      nodesPerSec = 0,
      recentAccountsPerSec = 0,
      recentBytecodesPerSec = 0,
      recentSlotsPerSec = 370,
      recentNodesPerSec = 0,
      phaseProgress = 12,
      estimatedTotalAccounts = 2700000,
      estimatedTotalBytecodes = 146000,
      estimatedTotalSlots = 370000,
      startTime = System.currentTimeMillis() - 600000,
      phaseStartTime = System.currentTimeMillis() - 120000,
      storageContractsCompleted = 1823,
      storageContractsTotal = 15234
    )

    val formatted = progress.formattedString
    formatted should include("Storage")
    formatted should include("(12%)")
    formatted should include("44.5K")
    formatted should include("contracts=1823/15234")
  }
}
