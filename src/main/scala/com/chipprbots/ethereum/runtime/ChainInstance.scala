package com.chipprbots.ethereum.runtime

import com.chipprbots.ethereum.consensus.mining.StdMiningBuilder
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.nodebuilder.BaseNode
import com.chipprbots.ethereum.utils.InstanceConfig
import com.chipprbots.ethereum.utils.Logger

/** A single chain instance within the Fukuii multi-network runtime.
  *
  * Each ChainInstance encapsulates its own:
  *   - InstanceConfig (chain params, ports, DB path)
  *   - ActorSystem (named "fukuii_<instanceId>")
  *   - RocksDB data directory
  *   - P2P networking
  *   - JSON-RPC and Engine API servers
  *   - Metrics endpoint
  *
  * @param instanceId
  *   unique identifier for this instance (e.g., "etc", "mordor", "sepolia")
  * @param instanceConfig
  *   the per-instance configuration
  */
class ChainInstance(val instanceId: String, override val instanceConfig: InstanceConfig)
    extends BaseNode
    with StdMiningBuilder
    with Logger {

  def blockchainReaderRef: BlockchainReader = blockchainReader

  override def toString: String =
    s"ChainInstance($instanceId, network=${instanceConfig.blockchains.network})"
}
