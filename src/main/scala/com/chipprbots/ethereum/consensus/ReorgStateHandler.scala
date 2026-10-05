package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

/** What a reorganisation tells the state storage about the blocks it moves in and out of the canonical chain, as
  * `(height, block hash)` pairs. Implemented by `BlockchainImpl`, over the node's
  * [[com.chipprbots.ethereum.db.storage.StateStorage]].
  */
trait ReorgStateHandler:

  /** Blocks that were executed and committed and then stopped being canonical. */
  def abandonBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit

  /** Blocks that became canonical again without being executed again. */
  def readoptBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit

object ReorgStateHandler:
  /** For callers that have no reference-counted state to correct (test doubles with no storage). */
  val NoOp: ReorgStateHandler = new ReorgStateHandler:
    override def abandonBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = ()
    override def readoptBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = ()
