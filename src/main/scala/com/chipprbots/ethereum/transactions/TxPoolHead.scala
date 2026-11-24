package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.typed.ActorRef

import com.chipprbots.ethereum.domain.BlockHeader

/** The head the transaction pool was last reset to (B-253, B-515): one per node, shared by every writer that resets the
  * pool, the fork choice's (`EngineApiService`) and regular sync's importer (`BlockImporter`). go-ethereum's pool keeps
  * one `currentHead` whoever moved the chain; with one each, a fork choice after a move the importer made reset the
  * pool from the head the fork choice last set, not from the one the pool stands on, and took back or dropped the
  * transactions of the wrong blocks.
  *
  * Every reset is computed, told and recorded under this object's lock, so that two writers' resets are told to the
  * pool in the order their heads were recorded, and a reset is told before whatever its writer asks the pool next
  * (#2066: the fork choice's reset is told before the payload build's ask).
  */
final class TxPoolHead:
  private var head: Option[BlockHeader] = None

  /** The head the pool was last reset to; None before the first reset this process told. */
  def current: Option[BlockHeader] = synchronized(head)

  /** Reset `pool` onto `to` from the head it was last reset to: `reset` computes what the pool is told from that head
    * (`TxPoolReset.between`). `to` is recorded as the pool's head whatever `reset` returns, an empty reset included.
    */
  def resetOnto(to: BlockHeader, pool: ActorRef[PendingTransactionsManager.Command])(
      reset: Option[BlockHeader] => TxPoolReset.Reset
  ): Unit = synchronized {
    val told = reset(head)
    head = Some(to)
    TxPoolReset.tell(pool, told)
  }

  /** Tell `pool` of `reset`, a move whose branches the writer knows (`TxPoolReset.ofBranches`), and record `to`, the
    * head that move left, as the pool's. With no `to` (a move whose head is not known) the recorded head is kept.
    */
  def told(
      to: Option[BlockHeader],
      pool: ActorRef[PendingTransactionsManager.Command],
      reset: TxPoolReset.Reset
  ): Unit =
    synchronized {
      to.foreach(header => head = Some(header))
      TxPoolReset.tell(pool, reset)
    }
