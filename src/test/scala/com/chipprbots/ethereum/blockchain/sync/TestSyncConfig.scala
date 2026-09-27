package com.chipprbots.ethereum.blockchain.sync

import scala.concurrent.duration.*

import com.chipprbots.ethereum.nodebuilder.SyncConfigBuilder
import com.chipprbots.ethereum.utils.Config.SyncConfig

trait TestSyncConfig extends SyncConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
  def defaultSyncConfig: SyncConfig = SyncConfig(
    doSnapSync = false,
    peersScanInterval = 1.hour,
    blacklistDuration = 5.seconds,
    syncRetryInterval = 1.second,
    peerResponseTimeout = 5.seconds,
    printStatusInterval = 1.second,
    blockHeadersPerRequest = 2,
    blockBodiesPerRequest = 5,
    receiptsPerRequest = 10,
    peersToFetchFrom = 5,
    blocksBatchSize = 5,
    maxFetcherQueueSize = 100,
    checkForNewBlockInterval = 1.milli,
    branchResolutionRequestSize = 30,
    maxQueuedBlockNumberAhead = 10,
    maxQueuedBlockNumberBehind = 10,
    maxNewBlockHashAge = 20,
    maxNewHashes = 64,
    redownloadMissingStateNodes = true,
    maxRetryDelay = 30.seconds,
    maxBodyFetchRetries = 10,
    useBootstrapCheckpoints = false,
    bootstrapCheckpoints = Seq.empty,
    engineApiRequired = false,
    clWaitTimeout = 5.minutes,
    checkpointSyncFile = None,
    checkpointSyncUrl = None
  )

  override lazy val syncConfig: SyncConfig = defaultSyncConfig
