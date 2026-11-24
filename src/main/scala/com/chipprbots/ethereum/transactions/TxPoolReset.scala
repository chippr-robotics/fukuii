package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.utils.Logger

/** What the transaction pool is told when the head moves (B-253): go-ethereum's `LegacyPool.reset(oldHead, newHead)`
  * (core/txpool/legacypool/legacypool.go; core-geth's `TxPool.reset` is the same).
  *
  *   - '''Included''': the transactions of every block the head moved ONTO — the new chain from the common ancestor up
  *     to the new head — leave the pool. go-ethereum's pool drops them by nonce against the new head's state; this pool
  *     keeps no such demotion and drops by hash, so the blocks are told one by one.
  *   - '''Reinjected''': the transactions of every block the head moved OFF that the new chain does not include
  *     (`types.TxDifference(discarded, included)`) go back to the pool, through its ordinary admission, which validates
  *     each against the new head's state as any other add does (go-ethereum's `addTxsLocked(reinject)`). Blob
  *     transactions do not: a block holds no sidecar ([[difference]]).
  *
  * The pool takes both in one [[PendingTransactionsManager.HeadReset]], which also rebuilds its nonce gate for the
  * senders concerned (go-ethereum's reset makes a new noncer from the new head's state).
  *
  * '''Bounded as go-ethereum bounds it''': no reinjection when the two heads are more than [[MaxReorgDepth]] blocks
  * apart (`depth := |oldNum - newNum|; depth > 64` → "Skipping deep transaction reorg"), nor when either chain is not
  * stored back to the common ancestor ("Unrooted old/new chain seen by tx pool"); the new head's newest
  * [[MaxReorgDepth]] blocks still leave the pool then, as this pool has no other way to drop them. One bound is this
  * node's own: go-ethereum walks two chains of equal height back to their common ancestor without limit; here that walk
  * stops after [[MaxReorgDepth]] steps and reinjects nothing, so that no move of the head reads more than a few hundred
  * blocks.
  *
  * Pool hygiene and RPC visibility only (eth_getTransactionByHash of a pending tx, txpool_content): a build never
  * depends on it, as the proposer filters what is not executable at the parent.
  */
object TxPoolReset extends Logger:

  /** go-ethereum `LegacyPool.reset`: "If the reorg is too deep, avoid doing it (will happen during fast sync)". */
  val MaxReorgDepth: Int = 64

  /** @param included
    *   the transactions of each block moved onto, one entry per block, oldest first: each is one removal
    * @param reinject
    *   the transactions of the blocks moved off that the blocks moved onto do not include, once each
    */
  final case class Reset(included: Seq[Seq[SignedTransaction]], reinject: Seq[SignedTransaction])

  object Reset:
    val empty: Reset = Reset(Nil, Nil)

  /** The reset for a move whose two branches are known (regular sync's import results): `added` the blocks the head
    * moved onto, `removed` those it moved off. The heads are the highest block of each; with nothing added, the head is
    * the block below the lowest one removed.
    */
  def ofBranches(added: Seq[Block], removed: Seq[Block]): Reset =
    val included = added.map(_.body.transactionList).filter(_.nonEmpty)
    if removed.isEmpty then Reset(included, Nil)
    else
      val oldHead = removed.map(_.header.number.value).max
      val newHead =
        if added.isEmpty then removed.map(_.header.number.value).min - 1 else added.map(_.header.number.value).max
      val depth = (oldHead - newHead).abs
      if depth > MaxReorgDepth then
        log.debug("Skipping deep transaction reorg: depth={}", depth)
        Reset(included, Nil)
      else Reset(included, difference(removed.map(_.body.transactionList), included))

  /** The reset for a move of the head from `from`, the head the pool was last reset to, to `to`, read from the chain as
    * go-ethereum's reset reads it. With no `from` (the first move this process tells), or `to` a child of `from`, the
    * head block alone is included.
    */
  def between(from: Option[BlockHeader], to: BlockHeader, reader: BlockchainReader): Reset =
    between(from, to, reader.getBlockHeaderByHash, reader.getBlockBodyByHash)

  def between(
      from: Option[BlockHeader],
      to: BlockHeader,
      headerOf: BlockHash => Option[BlockHeader],
      bodyOf: BlockHash => Option[BlockBody]
  ): Reset =
    def txsOf(header: BlockHeader): Option[Seq[SignedTransaction]] = bodyOf(header.hash).map(_.transactionList)
    def parentOf(header: BlockHeader): Option[BlockHeader] =
      Option.when(header.number.value > 0)(header.parentHash).flatMap(headerOf).filter(_.isParentOf(header))
    def told(included: List[Seq[SignedTransaction]], reinject: Seq[SignedTransaction]): Reset =
      Reset(included.filter(_.nonEmpty), reinject)

    // The txs of the new chain's newest `left` blocks, oldest first: what is dropped when nothing is reinjected
    @tailrec
    def newest(at: Option[BlockHeader], left: Int, acc: List[Seq[SignedTransaction]]): List[Seq[SignedTransaction]] =
      at match
        case Some(header) if left > 0 =>
          txsOf(header) match
            case Some(txs) => newest(parentOf(header), left - 1, txs :: acc)
            case None      => acc
        case _ => acc

    from match
      case Some(old) if old.hash == to.hash => Reset.empty
      case None                             => told(txsOf(to).toList, Nil)
      case Some(old) if to.parentHash == old.hash =>
        told(txsOf(to).toList, Nil)
      case Some(old) =>
        val depth = (old.number.value - to.number.value).abs
        if depth > MaxReorgDepth then
          log.debug("Skipping deep transaction reorg: depth={}", depth)
          told(newest(Some(to), MaxReorgDepth, Nil), Nil)
        else
          walk(old, to, txsOf, parentOf) match
            case Walked.Rooted(discarded, included) => told(included, difference(discarded, included))
            case Walked.Unrooted(why)               =>
              // nothing is taken back, and the new chain's txs leave the pool as on a deep move
              log.debug("Transaction reorg not reinjected: {}", why)
              told(newest(Some(to), MaxReorgDepth, Nil), Nil)

  /** Tell `pool` of `reset`, in one message the pool acts on as one step ([[PendingTransactionsManager.HeadReset]]): a
    * build that asks the pool after it is told sees the whole reset. Nothing when there is nothing to tell.
    */
  def tell(pool: ActorRef[PendingTransactionsManager.Command], reset: Reset): Unit =
    if reset.included.nonEmpty || reset.reinject.nonEmpty then
      pool ! PendingTransactionsManager.HeadReset(reset.included, reset.reinject)

  private enum Walked:
    case Rooted(discarded: List[Seq[SignedTransaction]], included: List[Seq[SignedTransaction]])
    case Unrooted(why: String)

  /** go-ethereum's three loops: the higher head down to the other's height, then both down together until they meet.
    * Each list is oldest first.
    */
  private def walk(
      old: BlockHeader,
      to: BlockHeader,
      txsOf: BlockHeader => Option[Seq[SignedTransaction]],
      parentOf: BlockHeader => Option[BlockHeader]
  ): Walked =
    @tailrec
    def loop(
        rem: BlockHeader,
        add: BlockHeader,
        discarded: List[Seq[SignedTransaction]],
        included: List[Seq[SignedTransaction]],
        together: Int
    ): Walked =
      if rem.hash == add.hash then Walked.Rooted(discarded, included)
      else if together >= MaxReorgDepth then
        Walked.Unrooted(s"the chains do not meet within $MaxReorgDepth blocks of equal height")
      else
        val remHigher = rem.number.value > add.number.value
        val addHigher = add.number.value > rem.number.value
        val stepRem = !addHigher
        val stepAdd = !remHigher
        val remTxs = if stepRem then txsOf(rem) else Some(Nil)
        val addTxs = if stepAdd then txsOf(add) else Some(Nil)
        (remTxs, addTxs) match
          case (None, _) => Walked.Unrooted(s"block ${rem.number.value} of the old chain is not stored")
          case (_, None) => Walked.Unrooted(s"block ${add.number.value} of the new chain is not stored")
          case (Some(r), Some(a)) =>
            val nextDiscarded = if stepRem then r :: discarded else discarded
            val nextIncluded = if stepAdd then a :: included else included
            val nextRem = if stepRem then parentOf(rem) else Some(rem)
            val nextAdd = if stepAdd then parentOf(add) else Some(add)
            (nextRem, nextAdd) match
              case (Some(r2), Some(a2)) =>
                loop(r2, a2, nextDiscarded, nextIncluded, if stepRem && stepAdd then together + 1 else together)
              case (None, _) =>
                Walked.Unrooted(s"the parent of block ${rem.number.value} of the old chain is not stored")
              case (_, None) =>
                Walked.Unrooted(s"the parent of block ${add.number.value} of the new chain is not stored")
    loop(old, to, Nil, Nil, 0)

  /** The transactions of `discarded`, in block order and once each, that `included` does not hold, but for blob
    * transactions: a block carries no sidecar, and a blob transaction pooled without one would be built into a payload
    * whose blobs bundle lacks its blobs, and handed to peers without them. go-ethereum's legacy pool never takes one
    * back (`LegacyPool.Filter` refuses type 3), and its blob pool keeps the sidecars of the transactions it drops.
    */
  private def difference(
      discarded: Seq[Seq[SignedTransaction]],
      included: Seq[Seq[SignedTransaction]]
  ): Seq[SignedTransaction] =
    val inNewChain: Set[ByteString] = included.iterator.flatten.map(_.hash.value).toSet
    discarded.flatten
      .filterNot(tx => inNewChain(tx.hash.value) || tx.tx.isInstanceOf[BlobTransaction])
      .distinctBy(_.hash.value)
