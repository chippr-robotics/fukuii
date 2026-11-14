package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockAccessListStorage
import com.chipprbots.ethereum.db.storage.BlockBodiesStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.ChainWeightStorage
import com.chipprbots.ethereum.db.storage.ReceiptStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage.TransactionLocation
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.FinalizedBlock
import com.chipprbots.ethereum.db.storage.pathstate.HeadMovePlan
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.utils.Logger

class BlockchainWriter(
    blockHeadersStorage: BlockHeadersStorage,
    blockBodiesStorage: BlockBodiesStorage,
    blockNumberMappingStorage: BlockNumberMappingStorage,
    transactionMappingStorage: TransactionMappingStorage,
    receiptStorage: ReceiptStorage,
    chainWeightStorage: ChainWeightStorage,
    appStateStorage: AppStateStorage,
    blockAccessListStorage: BlockAccessListStorage,
    // The state on the unified layout (spec 018 Track C), where this node holds it there: the best block and the index
    // of blocks by number then move only with the state, in a block's own commit or in a move of its head
    // (moveHeadInPlace), and the writers below that move them alone refuse.
    pathState: Option[PathStateStorage] = None
) extends Logger:

  /** Whether this node holds its state on the unified layout, where the head moves with the state or not at all. */
  def holdsStateInPlace: Boolean = pathState.isDefined

  /** Refuses, on the unified layout, a write of the best block or the index of blocks by number that does not move the
    * state with it: the state anchor and the best block are one block there (spec 018 data-model 5.1, I1), and only a
    * move of the head ([[com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage.moveHead]], with
    * [[canonicalHeadUpdate]] as its rows) or a block's own commit moves both (plan.md "C4b part 3").
    */
  private def refuseOnTheUnifiedLayout(what: String): Unit =
    if holdsStateInPlace then
      throw new IllegalStateException(
        s"$what would move the best block or the index of blocks by number without the state, on the unified state " +
          "layout, where the two move together or not at all"
      )

  def save(block: Block, receipts: Seq[Receipt], weight: ChainWeight, saveAsBestBlock: Boolean): Unit =
    saveBatch(block, receipts, weight, saveAsBestBlock).commit()

  /** [[save]] without committing, so a caller can add to the same atomic write (the block's staged state, its access
    * list): a block's state must exist if and only if the block does.
    */
  def saveBatch(
      block: Block,
      receipts: Seq[Receipt],
      weight: ChainWeight,
      saveAsBestBlock: Boolean
  ): DataSourceBatchUpdate =
    val updateBestBlocks = if saveAsBestBlock then
      log.debug(
        "New best known block number - {}",
        block.header.number
      )
      appStateStorage.putBestBlockInfo(BlockInfo(block.header.hash.value, block.header.number.value))
    else appStateStorage.emptyBatchUpdate

    log.debug("Saving new block {} to database", block.idTag)
    storeBlock(block)
      .and(storeReceipts(block.header.hash, receipts))
      .and(storeChainWeight(block.header.hash, weight))
      .and(updateBestBlocks)

  def storeReceipts(blockHash: BlockHash, receipts: Seq[Receipt]): DataSourceBatchUpdate =
    receiptStorage.put(blockHash.value, receipts)

  def storeChainWeight(blockHash: BlockHash, weight: ChainWeight): DataSourceBatchUpdate =
    chainWeightStorage.put(blockHash.value, weight)

  /** Many weights in one update (`ChainWeightReconcile`, #1654): one storage update, not one `and` per weight. */
  def storeChainWeights(weights: Seq[(BlockHash, ChainWeight)]): DataSourceBatchUpdate =
    chainWeightStorage.update(Nil, weights.map { case (hash, weight) => hash.value -> weight })

  /** EIP-7928: keeps the access list of an Amsterdam block that validated against it, as its canonical RLP (see
    * [[com.chipprbots.ethereum.db.storage.BlockAccessListStorage]]). Written by every path that accepts an Amsterdam
    * block after executing it; read back through `BlockchainReader.getBlockAccessListByHash`.
    */
  def storeBlockAccessList(blockHash: BlockHash, accessList: BlockAccessList): DataSourceBatchUpdate =
    blockAccessListStorage.put(blockHash.value, accessList.toBytes)

  /** Persists a block in the underlying Blockchain Database Note: all store* do not update the database immediately,
    * rather they create a [[com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate]] which then has to be
    * committed (atomic operation)
    *
    * @param block
    *   Block to be saved
    */
  def storeBlock(block: Block): DataSourceBatchUpdate =
    storeBlockHeader(block.header).and(storeBlockBody(block.header.hash, block.body))

  /** Store block by hash only (no number→hash mapping). Used for optimistic/accepted blocks that shouldn't appear in
    * eth_getBlockByNumber until fully validated.
    */
  def storeBlockByHashOnly(block: Block): DataSourceBatchUpdate =
    blockHeadersStorage
      .put(block.header.hash.value, block.header)
      .and(blockBodiesStorage.put(block.header.hash.value, block.body))

  /** Remove block header and body stored by hash. Inverse of storeBlockByHashOnly. Idempotent — no-op if the hash
    * doesn't exist in storage.
    */
  def removeBlockByHash(blockHash: BlockHash): DataSourceBatchUpdate =
    blockHeadersStorage
      .remove(blockHash.value)
      .and(blockBodiesStorage.remove(blockHash.value))

  def storeBlockHeader(blockHeader: BlockHeader): DataSourceBatchUpdate =
    val hash = blockHeader.hash
    blockHeadersStorage.put(hash.value, blockHeader).and(saveBlockNumberMapping(blockHeader.number.value, hash))

  /** Store a header by hash only (no number→hash mapping, no body), as [[storeBlockByHashOnly]] stores a block: a
    * header that is known by its hash but must not name its height in the canonical index.
    */
  def storeBlockHeaderByHashOnly(blockHeader: BlockHeader): DataSourceBatchUpdate =
    blockHeadersStorage.put(blockHeader.hash.value, blockHeader)

  /** Startup consistency repair for the number→hash index at the best block's height.
    *
    * An earlier SyncController start-up path re-stored the best block's header with a rewritten `stateRoot`.
    * `storeBlockHeader` writes the number→hash index, so the best block's height then named a forged header (new hash,
    * no body) while best-block info still named the real block. This puts the index back, and only that:
    *
    *   - no best-block hash recorded, mapping absent, or mapping equal to the best hash: nothing to do
    *     ([[BestMappingRepair.Consistent]]);
    *   - mapping differs, and the best block's header AND body are both stored under the best hash: the mapping is
    *     rewritten to the best hash ([[BestMappingRepair.Restored]]). The old target is removed too when it is a
    *     header-only orphan at the same height (no body, not the best hash), which is the forged header's shape;
    *   - mapping differs and the best header or body is missing: nothing is touched ([[BestMappingRepair.Refused]]).
    *     This does not guess which block is canonical.
    *
    * Idempotent: a second call finds the mapping consistent. A best block with a missing mapping is not "damaged by
    * this bug" and is left to the existing paths.
    */
  def repairBestBlockNumberMapping(reader: BlockchainReader): BestMappingRepair =
    val best = appStateStorage.getBestBlockInfo()
    val bestHash = BlockHash(best.hash)
    blockNumberMappingStorage.get(best.number) match
      case _ if best.hash.isEmpty              => BestMappingRepair.Consistent // no best-block hash was ever recorded
      case None                                => BestMappingRepair.Consistent
      case Some(mapped) if mapped == best.hash => BestMappingRepair.Consistent
      case Some(mapped) =>
        val bestHeader = reader.getBlockHeaderByHash(bestHash)
        val bestBody = reader.getBlockBodyByHash(bestHash)
        def refuse(reason: String) =
          BestMappingRepair.Refused(best.number, bestHash, mapped, bestHeader.isDefined, bestBody.isDefined, reason)
        (bestHeader, bestBody) match
          case (Some(bh), Some(_)) =>
            val mappedHash = BlockHash(mapped)
            reader.getBlockHeaderByHash(mappedHash) match
              // The forged-sibling shape: same height AND same parent as the best block. A crash mid-reorg leaves a
              // fully stored new-branch block at this height, but there the neighbouring heights name the new branch
              // too, so rewriting one entry would break index linearity; such a block need not share the parent.
              case Some(mh) if mh.number == bh.number && mh.parentHash == bh.parentHash =>
                val orphan = reader.getBlockBodyByHash(mappedHash).isEmpty
                val restore = blockNumberMappingStorage.put(best.number, best.hash)
                (if orphan then restore.and(blockHeadersStorage.remove(mapped)) else restore).commit()
                BestMappingRepair.Restored(best.number, bestHash, mappedHash, removedOrphan = orphan)
              case Some(_) =>
                refuse("mapped block is not a same-parent sibling of the best block (possible interrupted reorg)")
              case None => refuse("mapped header is not stored")
          case _ => refuse("best block is not fully stored")

  def storeBlockBody(blockHash: BlockHash, blockBody: BlockBody): DataSourceBatchUpdate =
    blockBodiesStorage.put(blockHash.value, blockBody).and(saveTxsLocations(blockHash, blockBody))

  /** `storeBlockBody` for a body built or chosen locally rather than one a validating path just accepted: refuses
    * (throws `IllegalStateException`) a body whose shape contradicts `header` — empty lists under non-empty roots, or a
    * withdrawals field whose presence disagrees with the header (`BlockBody.shapeMismatch`, O(1)). Fails loud because
    * the alternative is serving a body that does not hash to its header, to RPC and to peers, forever (CQ-020-07).
    */
  def storeCheckedBlockBody(header: BlockHeader, blockBody: BlockBody): DataSourceBatchUpdate =
    BlockBody.shapeMismatch(header, blockBody).foreach { reason =>
      throw new IllegalStateException(
        s"Refusing to store a body that contradicts its header: block ${header.number.value} " +
          s"${header.hashAsHexString}: $reason"
      )
    }
    storeBlockBody(header.hash, blockBody)

  /** Removes the body stored under `blockHash`, leaving its header and number→hash mapping. Used to drop a body that
    * does not belong to its header so the backfill fetches the real one (a body is fetched only when absent). Tx
    * locations the wrong body wrote are left; the refetched body's own overwrite them.
    */
  def removeBlockBody(blockHash: BlockHash): DataSourceBatchUpdate =
    blockBodiesStorage.remove(blockHash.value)

  def saveBestKnownBlocks(
      bestBlockHash: BlockHash,
      bestBlockNumber: BigInt
  ): Unit =
    refuseOnTheUnifiedLayout(s"saveBestKnownBlocks to block $bestBlockNumber")
    appStateStorage.putBestBlockInfo(BlockInfo(bestBlockHash.value, bestBlockNumber)).commit()

  /** Roll back the canonical chain index to `targetNumber`, removing number→hash entries for all blocks above
    * `targetNumber`. Used by fork recovery (SYNC-FORK) to truncate stale canonical chain entries before re-syncing from
    * the fork point.
    *
    * Only the number→hash index is modified — block headers/bodies/receipts are kept in storage. Orphaned header
    * entries are benign in RocksDB and will be compacted away in time.
    *
    * No-op if `currentBest <= targetNumber`.
    */
  def setCanonicalChainHead(targetNumber: BigInt, targetHash: BlockHash, currentBest: BigInt): Unit =
    refuseOnTheUnifiedLayout(s"setCanonicalChainHead to block $targetNumber")
    if currentBest > targetNumber then
      val batch = ((targetNumber + 1) to currentBest).foldLeft(blockNumberMappingStorage.emptyBatchUpdate) { (acc, n) =>
        acc.and(blockNumberMappingStorage.remove(n))
      }
      batch.and(appStateStorage.putBestBlockInfo(BlockInfo(targetHash.value, targetNumber))).commit()

  /** Rewrite part of the canonical number→hash index in ONE atomic batch: write `put` (and re-point the transaction
    * locations of those blocks at them), delete the `remove` heights, and move the best block if `newBest` is given.
    *
    * The index half of core-geth's `reorg()` + `writeHeadBlock` (core/blockchain.go): after it, the index is exactly
    * the head's ancestry — no entry from another chain below the head, none at all above it. `ConsensusImpl.reorganise`
    * uses it both to adopt a new head and to put the old head's entries back when it keeps the old head.
    */
  def rewriteCanonicalIndex(
      put: Seq[(BigInt, BlockHash)],
      remove: Seq[BigInt],
      newBest: Option[(BlockHash, BigInt)],
      reader: BlockchainReader
  ): Unit =
    refuseOnTheUnifiedLayout("rewriteCanonicalIndex")
    canonicalIndexUpdate(put, remove, newBest, reader).commit()

  /** The batch [[rewriteCanonicalIndex]] commits, uncommitted, so a caller can add to the same atomic write. */
  private def canonicalIndexUpdate(
      put: Seq[(BigInt, BlockHash)],
      remove: Seq[BigInt],
      newBest: Option[(BlockHash, BigInt)],
      reader: BlockchainReader
  ): DataSourceBatchUpdate =
    val withPuts = put.foldLeft(blockNumberMappingStorage.emptyBatchUpdate) { case (acc, (number, hash)) =>
      val withNumber = acc.and(blockNumberMappingStorage.put(number, hash.value))
      reader.getBlockBodyByHash(hash).fold(withNumber)(body => withNumber.and(saveTxsLocations(hash, body)))
    }
    val withRemoves = remove.foldLeft(withPuts)((acc, number) => acc.and(blockNumberMappingStorage.remove(number)))
    newBest.fold(withRemoves) { case (hash, number) =>
      withRemoves.and(appStateStorage.putBestBlockInfo(BlockInfo(hash.value, number)))
    }

  /** Make `head` the canonical head, in ONE atomic batch: afterwards the number→hash index is exactly `head`'s ancestry
    * — every height up to `head` names `head`'s ancestor, and no height above `head` has an entry — and the best block
    * is `head`. go-ethereum's `BlockChain.SetCanonical` (core/blockchain.go: `reorg`, then `writeHeadBlock`). The
    * post-merge fork choice's writer; `ForkChoiceManager.applyForkChoiceState` is its caller.
    *
    * THE BRANCH. Walk back from `head` along parent hashes to the first height AT OR BELOW the current best block whose
    * entry already names the walked block. Up to the best block the index is the best block's ancestry, so that is
    * where the two chains meet and nothing below it needs rewriting. Above the best block nothing guarantees the index
    * — `engine_newPayload` writes entries ahead of the head, and the p2p import path's designated-head arm moves the
    * best block without clearing what lies above it — so a match up there is not trusted: a forkchoiceUpdated back to a
    * longer chain used to stop at such a leftover entry and keep the other branch's hashes below it. Every walked block
    * gets its entry and its transaction locations rewritten; without the latter, eth_getTransactionReceipt answers from
    * the block a side chain re-pointed the transaction at (hive 'Transaction Re-Org, Re-Org to Different Block'). The
    * walk also ends at a missing header, and at genesis.
    *
    * ABOVE THE HEAD. Every height from `head + 1` up to the old best block, and on up while the index has an entry
    * there, is deleted: go-ethereum `reorg` "Delete all hash markers that are not part of the new canonical chain". A
    * head that moves DOWN — a forkchoiceUpdated to an ancestor, or to a shorter side chain — used to leave the old
    * chain's entries above it.
    *
    * TRANSACTION LOOKUPS. The blocks this drops from the index — the ones it deletes above the head and the ones it
    * replaces below it — lose the lookups of every transaction the new branch does not re-include: go-ethereum `reorg`,
    * `types.HashDifference(deletedTxs, rebirthTxs)`. eth_getTransactionByHash follows a lookup to its block by hash
    * without asking whether that block is still canonical, so it used to report such a transaction as included in a
    * block that had left the chain.
    *
    * Only the index, the lookups and the best-block pointer move; headers, bodies and receipts stay where they are.
    */
  def promoteToCanonicalHead(head: BlockHeader, reader: BlockchainReader): CanonicalMove =
    refuseOnTheUnifiedLayout(s"promoteToCanonicalHead to block ${head.number.value}")
    val update = canonicalHeadUpdate(head, reader)
    // A plain extension rewrites only heights above the old best and clears nothing: not worth a line.
    if update.reorganises then
      log.info(
        "Canonical head moved: head=#{} {} previousBest=#{} rewritten={} clearedAboveHead={} droppedTxLookups={}",
        head.number.value,
        head.hash.toHexString,
        update.previousBest,
        update.move.adopted.size,
        update.cleared,
        update.droppedTxLookups
      )
    update.batch.commit()
    update.move

  /** The batch [[promoteToCanonicalHead]] commits, uncommitted, and what it does: for a caller that writes it with its
    * own rows in one atomic batch, as a move of the state's head does (spec 018 C4b), and for that caller only. It
    * reads the index and the best block as they are when it is called: the batch is of that moment.
    */
  def canonicalHeadUpdate(head: BlockHeader, reader: BlockchainReader): CanonicalHeadUpdate =
    val oldBest = reader.getBestBlockNumber
    val headNumber = head.number.value

    @tailrec
    def branch(header: BlockHeader, above: List[(BigInt, BlockHash)]): List[(BigInt, BlockHash)] =
      val number = header.number.value
      if number <= oldBest && reader.getCanonicalHashByNumber(number).contains(header.hash) then above
      else
        val withHeader = (number, header.hash) :: above
        if number == 0 then withHeader
        else
          reader.getBlockHeaderByHash(header.parentHash) match
            case Some(parent) => branch(parent, withHeader)
            case None         => withHeader

    val put = branch(head, Nil)
    val remove =
      ((headNumber + 1) to oldBest).toList ++
        LazyList
          .iterate(headNumber.max(oldBest) + 1)(_ + 1)
          .takeWhile(n => reader.getCanonicalHashByNumber(n).isDefined)
          .toList

    // Read before the batch: the blocks the index names now at the heights it is about to replace or delete.
    val droppedAt: Seq[(BigInt, BlockHash)] =
      put.flatMap { case (number, hash) =>
        reader.getCanonicalHashByNumber(number).filterNot(_ == hash).map(number -> _)
      } ++ remove.flatMap(n => reader.getCanonicalHashByNumber(n).map(n -> _))
    val dropped: Seq[BlockHash] = droppedAt.map(_._2)
    def txHashes(blocks: Seq[BlockHash]): Set[ByteString] =
      blocks.flatMap(reader.getBlockBodyByHash).flatMap(_.transactionList.map(_.hash.value)).toSet
    val staleLookups = txHashes(dropped) -- txHashes(put.map(_._2))

    val batch = canonicalIndexUpdate(put, remove, Some((head.hash, headNumber)), reader)
      .and(staleLookups.foldLeft(transactionMappingStorage.emptyBatchUpdate) { (acc, tx) =>
        acc.and(transactionMappingStorage.remove(tx))
      })
    CanonicalHeadUpdate(
      batch = batch,
      move = CanonicalMove(adopted = put, dropped = droppedAt),
      previousBest = oldBest,
      cleared = remove.size,
      droppedTxLookups = staleLookups.size,
      reorganises = remove.nonEmpty || put.exists(_._1 <= oldBest)
    )

  /** '''Moves the head, and the state with it, to `target`''' on the unified layout: with a block's own commit, the one
    * writer of the best block and the index of blocks by number there (spec 018 data-model 5.1, I1; plan.md "C4b part
    * 3b"). Each batch of the move ([[PathStateStorage.moveHead]]) carries [[canonicalHeadUpdate]] for the block it ends
    * on, built when that batch is asked for, so after every batch, and after a crash between two, the anchor, the best
    * block and the index name one chain. A target that is the head already is a move of nothing, written as one batch
    * under the state lock: what a caller that confirms the head writes, so that no commit lands between its look at the
    * head and its write.
    *
    * Headers, bodies, receipts and the changeset records stay: a block the head leaves is still known by its hash.
    *
    * @param refuse
    *   why a batch of the move is not to be written, when it is not. Asked for each batch before its rows are built; a
    *   refusal ends the move there with a [[StateUnavailable]], and the batches before it stand
    * @param finalized
    *   the finalized block the fork choice behind this move named: written in the batch that ends on `target`, and the
    *   store's from then on, only once that batch was written (spec 018 C4d part 1)
    * @param admit
    *   why the move as planned is not to be made ([[PathStateStorage.moveHead]]'s `admit`): asked before anything is
    *   written
    * @throws IllegalStateException
    *   on the hash layout, which holds the state of every block and has no head of the state to move
    * @throws com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
    *   when the move is refused: nothing was changed
    * @throws StateUnavailable
    *   as [[PathStateStorage.moveHead]] throws it, and for a refused batch
    */
  def moveHeadInPlace(
      target: BlockHeader,
      reader: BlockchainReader,
      refuse: HeadMovePlan.Chunk => Option[String] = _ => None,
      finalized: Option[FinalizedBlock] = None,
      admit: HeadMovePlan => Option[String] = _ => None
  ): PathStateStorage.MoveOutcome =
    val state = pathState.getOrElse(
      throw new IllegalStateException(
        s"the head is moved to block ${target.number.value} with the state on the unified state layout only"
      )
    )
    state.moveHead(
      BlockRef(target.hash.value, target.number.value, target.stateRoot.value),
      reader.headerLink,
      chunk =>
        refuse(chunk).foreach(why => throw new StateUnavailable(why))
        val head =
          if chunk.head.hash == target.hash.value then target
          else
            reader
              .getBlockHeaderByHash(BlockHash(chunk.head.hash))
              .getOrElse(
                throw new IllegalStateException(
                  s"the header of block ${chunk.head.show}, which a move of the head was planned through, is gone"
                )
              )
        Some(canonicalHeadUpdate(head, reader).batch)
      ,
      finalized,
      admit
    )

  /** '''START only''': the batch that makes the index of blocks by number the ancestry of `held`, the block whose state
    * the disk holds, and the best block `held` (spec 018 data-model 5.1, I1 rule 4 and I6; plan.md "C4b part 4"). For
    * the reconciliation at start ([[StateReconciliation]]) and for it alone: at runtime the head moves with the state,
    * in a move ([[moveHeadInPlace]]) or a block's own commit, never the other way round.
    *
    * '''Below `held`''': its ancestry by parent hashes, never by the index, for `window` blocks or down to the genesis
    * or a header that is not stored. Every height whose entry is not the ancestor is rewritten, at whatever depth: the
    * walk does not stop at the first entry that matches, as [[canonicalHeadUpdate]]'s does, since an entry below one
    * that matches can still name another block.
    *
    * '''Above `held`''': every entry is deleted, up to the old best block and `gapScan` heights beyond the highest
    * entry found, and never more than `scanAbove` heights above `held`: the old best block may be any number. A scan
    * that ends at that cap says so (`capped`).
    *
    * The transaction lookups of the blocks it drops go, but for those of the transactions the ancestry includes, as
    * [[canonicalHeadUpdate]] does. Nothing else is written: no header, body, receipt, weight, state row or record.
    */
  def heldBlockIndex(
      held: BlockHeader,
      reader: BlockchainReader,
      window: Int,
      scanAbove: Int,
      gapScan: Int = BlockchainWriter.IndexGapScan
  ): HeldBlockIndex =
    val heldNumber = held.number.value
    val oldBest = appStateStorage.getBestBlockInfo()

    @tailrec
    def ancestry(at: BlockHeader, left: Int, acc: List[(BigInt, BlockHash)]): List[(BigInt, BlockHash)] =
      val withAt = (at.number.value, at.hash) :: acc
      if left <= 1 || at.number.value == 0 then withAt
      else
        reader.getBlockHeaderByHash(at.parentHash).filter(_.hash == at.parentHash) match
          case Some(parent) => ancestry(parent, left - 1, withAt)
          case None         => withAt
    val chain = ancestry(held, window.max(1), Nil)
    val put = chain.filterNot((number, hash) => reader.getCanonicalHashByNumber(number).contains(hash))

    val ceiling = heldNumber + scanAbove
    @tailrec
    def above(height: BigInt, highest: BigInt, acc: List[(BigInt, BlockHash)]): (List[(BigInt, BlockHash)], Boolean) =
      if height > ceiling then (acc.reverse, true)
      else if height > highest + gapScan then (acc.reverse, false)
      else
        reader.getCanonicalHashByNumber(height) match
          case Some(hash) => above(height + 1, height, (height, hash) :: acc)
          case None       => above(height + 1, highest, acc)
    val (removed, capped) = above(heldNumber + 1, oldBest.number.max(heldNumber), Nil)

    val replaced: Seq[(BigInt, BlockHash)] =
      put.flatMap((number, hash) => reader.getCanonicalHashByNumber(number).filterNot(_ == hash).map(number -> _))
    // The ancestry from the lowest height rewritten up: a transaction a dropped block shared with any of these blocks
    // keeps its lookup, and is pointed at the ancestor that includes it (beacon), not only at a rewritten one.
    val reincluded: Seq[(BigInt, BlockHash)] =
      put.headOption.fold(Seq.empty[(BigInt, BlockHash)])((lowest, _) => chain.filter(_._1 >= lowest))
    def txHashes(blocks: Seq[BlockHash]): Set[ByteString] =
      blocks.flatMap(reader.getBlockBodyByHash).flatMap(_.transactionList.map(_.hash.value)).toSet
    val staleLookups = txHashes((replaced ++ removed).map(_._2)) -- txHashes(reincluded.map(_._2))
    val putHashes = put.map(_._2).toSet
    val relocated =
      reincluded.filterNot((_, hash) => putHashes(hash)).foldLeft(transactionMappingStorage.emptyBatchUpdate) {
        case (acc, (_, hash)) =>
          reader.getBlockBodyByHash(hash).fold(acc)(body => acc.and(saveTxsLocations(hash, body)))
      }
    val bestMoves = oldBest.hash != held.hash.value || oldBest.number != heldNumber

    val batch = canonicalIndexUpdate(
      put,
      removed.map(_._1),
      Option.when(bestMoves)((held.hash, heldNumber)),
      reader
    ).and(staleLookups.foldLeft(transactionMappingStorage.emptyBatchUpdate) { (acc, tx) =>
      acc.and(transactionMappingStorage.remove(tx))
    }).and(relocated)
    HeldBlockIndex(batch, oldBest, put, replaced, removed, staleLookups.size, bestMoves, capped)

  /** The state on the unified layout takes no further write ([[PathStateStorage.failClosed]]): for a caller that found
    * this datadir's own changeset records corrupt. Nothing on the hash layout.
    */
  def failStateClosed(why: String): Unit = pathState.foreach(_.failClosed(why))

  /** Drops the forward layer of `blockHash` on the unified layout, with the layers built on it
    * ([[PathStateStorage.dropLayer]]): the block was found invalid after it was executed, and no fork choice may move
    * the head to it. Nothing on the hash layout.
    */
  def dropForwardLayer(blockHash: ByteString): Unit = pathState.foreach(_.dropLayer(blockHash))

  /** The finalized block the state on the unified layout is held to ([[PathStateStorage.finalizedBlock]]). */
  def finalizedOfTheState: Option[FinalizedBlock] = pathState.flatMap(_.finalizedBlock)

  private def saveBlockNumberMapping(number: BigInt, hash: BlockHash): DataSourceBatchUpdate =
    blockNumberMappingStorage.put(number, hash.value)

  private def saveTxsLocations(blockHash: BlockHash, blockBody: BlockBody): DataSourceBatchUpdate =
    blockBody.transactionList.zipWithIndex.foldLeft(transactionMappingStorage.emptyBatchUpdate) {
      case (updates, (tx, index)) =>
        updates.and(transactionMappingStorage.put(tx.hash.value, TransactionLocation(blockHash.value, index)))
    }

/** What [[BlockchainWriter.promoteToCanonicalHead]] changed in the number→hash index, as `(height, block hash)` pairs:
  * the blocks it wrote (`adopted`, ascending, including any the index already named above the old best block) and the
  * ones it displaced or deleted (`dropped`). The caller owns the reference-count side of this (see
  * `ReorgStateHandler`), which the index write cannot do atomically.
  */
final case class CanonicalMove(adopted: Seq[(BigInt, BlockHash)], dropped: Seq[(BigInt, BlockHash)])

/** The batch that makes a block the canonical head ([[BlockchainWriter.canonicalHeadUpdate]]), not yet written.
  *
  * @param previousBest
  *   the best block's number when it was built
  * @param cleared
  *   heights whose index entry it deletes
  * @param droppedTxLookups
  *   transaction lookups it deletes
  * @param reorganises
  *   it rewrites a height at or below the previous best block, or deletes one: more than an extension
  */
final case class CanonicalHeadUpdate(
    batch: DataSourceBatchUpdate,
    move: CanonicalMove,
    previousBest: BigInt,
    cleared: Int,
    droppedTxLookups: Int,
    reorganises: Boolean
)

/** The batch [[BlockchainWriter.heldBlockIndex]] builds, not yet written, and what it does.
  *
  * @param previousBest
  *   the best block as it was when it was built
  * @param put
  *   the heights it writes with the held block's ancestor (ascending)
  * @param replaced
  *   what the index named at those heights before, where it named a block
  * @param removed
  *   the entries above the held block it deletes
  * @param capped
  *   the scan above the held block stopped at its cap: heights above it were not looked at
  */
final case class HeldBlockIndex(
    batch: DataSourceBatchUpdate,
    previousBest: BlockInfo,
    put: Seq[(BigInt, BlockHash)],
    replaced: Seq[(BigInt, BlockHash)],
    removed: Seq[(BigInt, BlockHash)],
    droppedTxLookups: Int,
    movesBest: Boolean,
    capped: Boolean
):
  /** Whether it writes anything at all: a datadir whose index and best block are the held block's takes no write. */
  def writes: Boolean = put.nonEmpty || removed.nonEmpty || movesBest || droppedTxLookups > 0

object BlockchainWriter:

  /** How many heights above the highest entry found the reconciliation at start still looks for another: entries left
    * above a gap (a removal that stopped at the first height with no entry) are found too.
    */
  val IndexGapScan: Int = 1024

  def apply(storages: BlockchainStorages): BlockchainWriter =
    new BlockchainWriter(
      storages.blockHeadersStorage,
      storages.blockBodiesStorage,
      storages.blockNumberMappingStorage,
      storages.transactionMappingStorage,
      storages.receiptStorage,
      storages.chainWeightStorage,
      storages.appStateStorage,
      storages.blockAccessListStorage,
      pathState = storages.pathStateStorage
    )

/** Outcome of [[BlockchainWriter.repairBestBlockNumberMapping]]. */
sealed trait BestMappingRepair
object BestMappingRepair:
  case object Consistent extends BestMappingRepair
  final case class Restored(number: BigInt, bestHash: BlockHash, wasMappedTo: BlockHash, removedOrphan: Boolean)
      extends BestMappingRepair
  final case class Refused(
      number: BigInt,
      bestHash: BlockHash,
      mappedTo: ByteString,
      bestHeaderPresent: Boolean,
      bestBodyPresent: Boolean,
      reason: String
  ) extends BestMappingRepair
