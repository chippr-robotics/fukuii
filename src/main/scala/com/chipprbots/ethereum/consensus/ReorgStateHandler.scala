package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

/** What a reorganisation tells the state storage about the blocks it moves in and out of the canonical chain, as
  * `(height, block hash)` pairs. Implemented by `BlockchainImpl`, over the node's
  * [[com.chipprbots.ethereum.db.storage.StateStorage]].
  *
  * INVARIANT (what every caller maintains): under reference-counted state a block's `bd` record is applied exactly
  * while the block is canonical, i.e. named by the number→hash index or just executed on top of the head. A block that
  * is executed and does NOT become canonical (an Engine API side payload, a payload rejected after it executed) is
  * undone straight away; a head move undoes what the index drops and puts back what it adopts. go-ethereum never
  * persists a non-canonical block's state at all (pathdb diff layers are dropped for free); a reference count has no
  * layering, so this is the same rule expressed as "counts reflect the canonical chain only".
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
