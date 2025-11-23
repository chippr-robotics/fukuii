package com.chipprbots.ethereum.blockchain.data

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps
import com.chipprbots.ethereum.utils.Logger

/** Loads bootstrap checkpoints into the blockchain to provide a starting point for syncing without waiting for peers.
  *
  * Bootstrap checkpoints are trusted block hashes at known heights that allow the node to skip the initial peer
  * discovery phase and start syncing immediately.
  */
class BootstrapCheckpointLoader(
    blockchainReader: BlockchainReader,
    appStateStorage: AppStateStorage
) extends Logger {

  /** Load bootstrap checkpoints if enabled and the database is empty (only genesis block exists).
    *
    * This method will load the highest trusted checkpoint block number into the app state, allowing the sync process to
    * use it as a starting reference point.
    *
    * @param blockchainConfig
    *   The blockchain configuration containing bootstrap checkpoints
    * @return
    *   true if checkpoints were loaded, false otherwise
    */
  def loadBootstrapCheckpoints()(implicit blockchainConfig: BlockchainConfig): Boolean = {
    if (!blockchainConfig.useBootstrapCheckpoints) {
      log.info("Bootstrap checkpoints disabled")
      return false
    }

    if (blockchainConfig.bootstrapCheckpoints.isEmpty) {
      log.info("No bootstrap checkpoints configured for this network")
      return false
    }

    val bestBlockNumber = blockchainReader.getBestBlockNumber()

    // Only load checkpoints if we have just the genesis block
    if (bestBlockNumber > 0) {
      log.info("Blockchain already has blocks (best block: {}), skipping bootstrap checkpoint loading", bestBlockNumber)
      return false
    }

    val sortedCheckpoints = blockchainConfig.bootstrapCheckpoints.sortBy(_.blockNumber)
    // Safe to use .last because we check for emptiness at line 34
    val highestCheckpoint = sortedCheckpoints.last

    log.info(
      "Loading bootstrap checkpoints for network. Highest checkpoint: block {} with hash {}",
      highestCheckpoint.blockNumber,
      highestCheckpoint.blockHash.toHex
    )

    // Log all checkpoints
    sortedCheckpoints.foreach { checkpoint =>
      log.debug("Bootstrap checkpoint: block {} -> {}", checkpoint.blockNumber, checkpoint.blockHash.toHex)
    }

    // Note: We don't actually insert checkpoint blocks into the database because we don't have the full block data.
    // Instead, we use these checkpoints as trusted reference points for the sync process.
    // We store the highest checkpoint as a "bootstrap pivot block" so that Status messages can report this
    // height instead of genesis, avoiding fork ID incompatibility issues with peers.

    // Store the highest checkpoint as the bootstrap pivot block
    appStateStorage.putBootstrapPivotBlock(highestCheckpoint.blockNumber, highestCheckpoint.blockHash).commit()

    log.info(
      "Bootstrap checkpoints loaded. {} checkpoints available. Highest checkpoint at block {} stored as pivot block.",
      sortedCheckpoints.size,
      highestCheckpoint.blockNumber
    )
    
    log.info(
      "Status messages will report block {} (bootstrap pivot) instead of genesis to avoid peer disconnections.",
      highestCheckpoint.blockNumber
    )

    true
  }
}
