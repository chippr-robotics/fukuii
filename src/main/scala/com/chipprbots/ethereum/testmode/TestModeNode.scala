package com.chipprbots.ethereum.testmode

import java.util.concurrent.atomic.AtomicReference

import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.utils.BlockchainConfig

/** What test mode's services take from the node they run in, and no more: the chain configuration and the seal engine a
  * test sets, the queue of blocks that are not on the head, the node's genesis loader, and its position store when it
  * is on the unified state layout.
  *
  * [[com.chipprbots.ethereum.nodebuilder.TestNode]] is it. It is a trait of its own so that the services can be given a
  * node that is not a whole one: a spec's, in memory, on either state layout.
  */
trait TestModeNode:
  def currentBlockchainConfig: AtomicReference[BlockchainConfig]
  implicit def blockchainConfig: BlockchainConfig
  def currentSealEngine: AtomicReference[SealEngineType]
  def sealEngine: SealEngineType
  def blockQueue: BlockQueue

  /** The node's own genesis loader: the one that commits a genesis to the layout the node is on. */
  def genesisDataLoader: GenesisDataLoader

  /** The position store, on the unified state layout; `None` on the hash store. */
  def pathStateStorage: Option[PathStateStorage]
