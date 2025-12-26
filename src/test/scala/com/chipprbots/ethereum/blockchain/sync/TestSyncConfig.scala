package com.chipprbots.ethereum.blockchain.sync

import scala.concurrent.duration._

import com.chipprbots.ethereum.nodebuilder.SyncConfigBuilder
import com.chipprbots.ethereum.utils.Config.SyncConfig

trait TestSyncConfig extends SyncConfigBuilder {
  def defaultSyncConfig: SyncConfig = SyncConfig(
    doFastSync = false,
    doSnapSync = false,
    fastSyncRestartCooloff = 10.minutes,
    peersScanInterval = 1.hour,
    blacklistDuration = 5.seconds,
    criticalBlacklistDuration = 10.seconds,
    startRetryInterval = 500.milliseconds,
    syncRetryInterval = 1.second,
    syncSwitchDelay = 0.5.second,
    peerResponseTimeout = 5.seconds,
    printStatusInterval = 1.second,
    maxConcurrentRequests = 10,
    blockHeadersPerRequest = 2,
    blockBodiesPerRequest = 5,
    receiptsPerRequest = 10,
    nodesPerRequest = 10,
    minPeersToChoosePivotBlock = 2,
    peersToChoosePivotBlockMargin = 0,
    peersToFetchFrom = 5,
    pivotBlockOffset = 500,
    pivotBlockMaxTotalSelectionAttempts = 20,
    persistStateSnapshotInterval = 2.seconds,
    blocksBatchSize = 5,
    maxFetcherQueueSize = 100,
    checkForNewBlockInterval = 1.milli,
    branchResolutionRequestSize = 30,
    blockChainOnlyPeersPoolSize = 100,
    fastSyncThrottle = 100.milliseconds,
    maxQueuedBlockNumberAhead = 10,
    maxQueuedBlockNumberBehind = 10,
    maxNewBlockHashAge = 20,
    maxNewHashes = 64,
    redownloadMissingStateNodes = true,
    fastSyncBlockValidationK = 100,
    fastSyncBlockValidationN = 2048,
    fastSyncBlockValidationX = 50,
    maxTargetDifference = 5,
    maximumTargetUpdateFailures = 1,
    stateSyncBloomFilterSize = 1000,
    stateSyncPersistBatchSize = 1000,
    pivotBlockReScheduleInterval = 1.second,
    maxPivotBlockAge = 96,
    fastSyncMaxBatchRetries = 3,
    maxPivotBlockFailuresCount = 3
  )

  override lazy val syncConfig: SyncConfig = defaultSyncConfig
}
