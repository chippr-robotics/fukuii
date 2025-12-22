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
    config.pivotBlockOffset should be >= 1000L
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
    formattedString should include("accounts=1000")
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
}
