package com.chipprbots.ethereum.blockchain.data

import java.io.File

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.blockchain.sync.regular.BlockEnqueued
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportStateFault
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.blockchain.sync.regular.DuplicateBlock
import com.chipprbots.ethereum.blockchain.sync.regular.ImportedPartially
import com.chipprbots.ethereum.consensus.Consensus
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.ChainInsertLock
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Logger

/** Imports a [[ChainFile]] — go-ethereum's chain format, as written by `geth export`, `admin_exportChain` and hive's
  * chain.rlp — into the blockchain, validating and executing every block.
  *
  * Two modes: [[importChainFile]] continues past invalid blocks (hive and ethereum/tests fixtures, the startup
  * `import-chain-file`), [[importChainFileStrict]] stops at the first problem (operator restores: the `import` launcher
  * command and `admin_importChain`). Both are synchronous and must not run while the node imports from peers or a
  * consensus client.
  */
class ChainImporter(
    blockchainReader: BlockchainReader,
    blockExecution: BlockExecution,
    // The node's own fork choice for a block that does NOT extend the current head: queue it as a side branch and
    // reorganise once that branch outweighs the head (ConsensusAdapter.evaluateBranchBlock -> BlockQueue ->
    // ConsensusImpl). `None` keeps the old last-block-wins behaviour and exists only for direct test construction.
    forkChoice: Option[(Block, BlockchainConfig) => IO[BlockImportResult]] = None,
    // On the unified state layout (spec 018 C4), the adoption of a post-merge block that is not built on the head: it is
    // executed on its parent's state, on layers, and made the head by one move when it is valid
    // ([[ChainImporter.adoptingOnLayers]]). `None` refuses such a block, and exists only for direct test construction.
    adoptOnLayers: Option[(Block, BlockchainConfig) => Consensus.ConsensusResult] = None
) extends Logger:

  /** Import all blocks from an RLP chain file, continuing past invalid blocks — the hive / ethereum/tests loader.
    *
    * hive's consensus simulator concatenates every block file under `/blocks` of a test into one file
    * (hive/fukuii/fukuii.sh), and those fixtures deliberately include invalid blocks and side chains: the client must
    * reject the bad ones, keep the rest, and end on the right head. go-ethereum gets there by importing each fixture
    * file separately; this mode gets there by counting a failed block and moving on, with side-chain blocks going
    * through the node's fork choice. An operator restoring a chain wants the opposite — stop at the first problem —
    * which is [[importChainFileStrict]].
    *
    * A decode error ends the import; the blocks before it stay imported.
    *
    * @param filePath
    *   path to the chain file (see [[ChainFile]]; `.gz` is gunzipped)
    * @return
    *   (imported, skipped, failed) counts
    */
  def importChainFile(filePath: String)(implicit blockchainConfig: BlockchainConfig): (Int, Int, Int) =
    val file = new File(filePath)
    if !file.exists() then
      log.warn(s"Chain import file not found: $filePath")
      (0, 0, 0)
    else
      log.info(s"Chain import: reading ${file.length()} bytes from $filePath")
      scala.util.Try(ChainFile.openInput(file.toPath)) match
        case scala.util.Failure(e) =>
          log.error(s"Chain import: cannot read $filePath — ${e.getClass.getSimpleName}: ${e.getMessage}")
          (0, 0, 0)
        case scala.util.Success(in) => importLenientStream(in, filePath)

  private def importLenientStream(in: java.io.InputStream, filePath: String)(implicit
      blockchainConfig: BlockchainConfig
  ): (Int, Int, Int) =
    try
      val reader = new ChainFile.Reader(in, filePath)

      var imported = 0
      var skipped = 0
      var failed = 0
      var first = true
      var done = false
      sideBlocksRefused = 0
      orphanBlocksRefused = 0

      while !done do
        reader.next() match
          case Left(err) =>
            log.error(s"Chain import: RLP decode error — ${err.describe}; stopping")
            done = true
          case Right(None) =>
            done = true
          case Right(Some(block)) =>
            if first then
              first = false
              logGenesisDiagnostics(block)
            importLenient(block) match
              case LenientOutcome.Imported(n) =>
                imported += n
                if imported % 10 == 0 then log.info(s"Chain import: block ${block.header.number} imported ($imported)")
              case LenientOutcome.Skipped => skipped += 1
              case LenientOutcome.Failed  => failed += 1

      log.info(s"Chain import: decoded ${reader.blocksRead} blocks")
      // On the unified state layout a block that is not built on the head is refused, which is no fault of the block:
      // said apart, so that the count of failed blocks can be read.
      val refused =
        if sideBlocksRefused + orphanBlocksRefused == 0 then ""
        else
          s" ($sideBlocksRefused of them side blocks and $orphanBlocksRefused with no parent here, not taken by " +
            "the unified state layout)"
      log.info(s"Chain import complete: $imported imported, $skipped skipped, $failed failed$refused")
      (imported, skipped, failed)
    finally in.close()

  // Log genesis vs first block's expected parent for debugging genesis mismatches
  private def logGenesisDiagnostics(firstBlock: Block): Unit =
    val genesisOpt = blockchainReader.getBlockHeaderByNumber(0)
    genesisOpt.foreach { genesis =>
      log.error(s"Chain import: genesis hash=${genesis.hashAsHexString}")
      log.error(
        s"Chain import: genesis stateRoot=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(genesis.stateRoot.value)}"
      )
      log.error(
        s"Chain import: genesis difficulty=${genesis.difficulty}, gasLimit=${genesis.gasLimit}, extraData=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(genesis.extraData)}"
      )
    }
    log.error(
      s"Chain import: block ${firstBlock.header.number} expects parent=${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(firstBlock.header.parentHash.value)}"
    )

  private enum LenientOutcome:
    case Imported(count: Int)
    case Skipped
    case Failed

  private def importLenient(block: Block)(implicit blockchainConfig: BlockchainConfig): LenientOutcome =
    val blockNum = block.header.number
    val blockHash = block.header.hash

    // Skip if already imported
    val alreadyExists = blockchainReader.getBlockHeaderByNumber(blockNum.value) match
      case Some(existing) if existing.hash == blockHash => true
      case Some(existing) =>
        log.warn(
          s"Chain import: block $blockNum hash mismatch — " +
            s"existing=${existing.hashAsHexString}, importing=${block.header.hashAsHexString}"
        )
        false
      case None => false

    val bestHash = BlockHash(blockchainReader.getBestBlockHeader.map(_.hash.value).getOrElse(ByteString.empty))
    val extendsHead = block.header.parentHash == bestHash
    // core-geth ForkChoice.ReorgNeeded: once the terminal total difficulty is reached, every imported block is
    // accepted as head ("all the headers after the transition come from the trusted consensus layer"). There
    // is no TD to compare post-merge (difficulty 0 everywhere), so the TD fork choice below must not run: it
    // would keep the first chain in the file on an equal-weight tie. ethereum/tests
    // bcMultiChainTest/UncleFromSideChain_{Cancun,Prague} expects the last VALID block of the file as head.
    val postMerge = blockchainConfig.terminalTotalDifficulty.exists { ttd =>
      blockchainReader
        .getChainWeightByHash(block.header.parentHash)
        .getOrElse(ChainWeight.zero)
        .increase(block.header)
        .totalDifficulty
        .value >= ttd
    }

    if alreadyExists then LenientOutcome.Skipped
    // Before the merge a block that is not built on the head goes to the node's fork choice on both layouts: on the
    // unified one it is queued and executed on layers, and the head moves to its branch once that outweighs the head
    // (ConsensusImpl.reorganiseOnLayers, C4e), as core-geth's InsertChain keeps a side chain until it is heavier.
    else if blockchainReader.holdsStateInPlace && (extendsHead || postMerge || forkChoice.isEmpty) then
      importInPlace(block)
    else if !extendsHead && !postMerge && forkChoice.isDefined then
      // A side-chain block. Saving it as best — what this loop did for every block — made the LAST block of
      // the file the head whatever its total difficulty, so every ethereum/tests fork/uncle-race vector
      // (lotsOfLeafs, sideChainWithMoreTransactions, uncleBlockAtBlock3afterBlock4, ...) ended on the wrong
      // head. core-geth's `import` goes through InsertChain -> writeBlockAndSetHead, which moves the head only
      // when forker.ReorgNeeded says the block is heavier. The node's own fork choice does exactly that.
      // A partial import (B-338) is answered as it was before: the extension imported, a reorganisation failed
      val chosen = forkChoice.get(block, blockchainConfig).unsafeRunSync()(using IORuntime.global) match
        case partial: ImportedPartially => partial.answeredAsBefore
        case other                      => other
      chosen match
        case BlockImportedToTop(data) =>
          LenientOutcome.Imported(data.size)
        case ChainReorganised(_, newBranch, _, _) =>
          log.info(s"Chain import: block $blockNum reorganised to a heavier branch of ${newBranch.size} block(s)")
          LenientOutcome.Imported(newBranch.size)
        case BlockEnqueued | DuplicateBlock =>
          LenientOutcome.Skipped // kept as a side block (lighter or not yet rooted), exactly as core-geth writes a side chain
        case BlockImportStateFault(fault, _) =>
          // Not a verdict on the block: this node's state could not take it (on the unified layout, a branch that leaves
          // the chain deeper than its changeset records reach, for one). Counted as failed: it was neither imported nor
          // kept. Never met on the hash layout, where no such fault is thrown.
          log.error(s"Chain import: block $blockNum was not executed on this node's own state: ${fault.getMessage}")
          LenientOutcome.Failed
        case other =>
          log.error(s"Chain import: block $blockNum rejected by fork choice — $other")
          LenientOutcome.Failed
    else
      // B-455: the node's own import of one block onto the head, as on the unified layout and as an operator's restore:
      // the same checks before and after execution as before, and the block's state, its code, its receipts, weight
      // and access list and the head in ONE batch, written only once the block is valid. A block that fails leaves
      // nothing behind (an archive store's trie nodes are written straight through, by design: inert there). Executed
      // on its parent's state wherever that is: after the merge the last valid block of the file is the head.
      // Blob sidecars take no part in the state transition: the hive consensus / consume-engine fixtures expect the
      // head to advance over blob blocks.
      // Under the head's lock (B-511): the block is committed as the head, with its index entry, and a fork choice is
      // not between its parent's weight read here and that commit. Taken before the chain insert lock the execution
      // takes, as every holder of both takes them. The fork choice's arm above is not under it: the import it runs
      // takes it itself, on another thread.
      val adopted = CanonicalHeadLock.held(s"the lenient import of block $blockNum") {
        val parentWeight = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
        blockExecution.executeAndAdoptBlockIf(block, parentWeight, () => None)
      }
      adopted match
        case Right(_) => LenientOutcome.Imported(1)
        case Left(error) =>
          log.error(s"Chain import: block $blockNum failed — ${error.describe}")
          LenientOutcome.Failed

  /** Blocks refused on the unified state layout in the import that is running because they are not built on the head
    * and nothing here could take them: side blocks with no fork choice or adoption given (direct test construction
    * only), and blocks whose parent this node does not have. The first of each is said loudly, the rest quietly, and
    * the import's last line names both counts.
    */
  private[data] var sideBlocksRefused = 0
  private[data] var orphanBlocksRefused = 0

  /** [[importLenient]] on the unified state layout (spec 018 Track C), for a block built on the head, a block after the
    * merge, or any block when no fork choice was given.
    *
    * A block built on the head goes through the node's own import of one block onto the head, the one an operator's
    * restore uses ([[BlockExecution.executeAndAdoptBlockIf]]): the same validation before and after execution as the
    * hash layout's arm, the same requests-hash and access-list checks, and one batch for the block, its receipts, its
    * weight, the head and the state. The whole of it is under the exclusive chain insert lock, and the head is looked
    * at again just before the commit.
    *
    * A block after the merge that is built on another block this node has is adopted, as on the hash layout and in
    * core-geth, whose `ForkChoice.ReorgNeeded` takes every block at or past the terminal total difficulty as the head:
    * executed on its parent's state, on layers, and made the head by one move when it is valid; an invalid one leaves
    * the head where it was ([[ChainImporter.adoptingOnLayers]]; B-220). Its parent's state is reached through the
    * changeset records, so a fork deeper than they reach is refused as a fault of this node's state, and counted as
    * failed, as the hash layout fails on a parent whose state was pruned.
    *
    * A block whose parent this node does not have is refused, as the hash layout's arm fails it.
    *
    * A fault of the node's own state met on the head is thrown, as anything thrown by the arm below is: it ends the
    * import, and every block after it would meet it too.
    */
  private def importInPlace(block: Block)(implicit blockchainConfig: BlockchainConfig): LenientOutcome =
    val header = block.header
    def onHead: Boolean = blockchainReader.getBestBlockHeader.exists(_.hash == header.parentHash)
    val parentKnown = blockchainReader.getBlockHeaderByHash(header.parentHash).isDefined
    adoptOnLayers match
      case Some(adopt) if !onHead && parentKnown => adoptedOnLayers(block, adopt)
      case _ =>
        ChainInsertLock.exclusive {
          if !onHead then
            val parent = ByteStringUtils.hash2string(header.parentHash.value)
            val head =
              blockchainReader.getBestBlockHeader.map(h => s"#${h.number} (${h.hashAsHexString})").getOrElse("none")
            val sideBlock = blockchainReader.getBlockHeaderByHash(header.parentHash).isDefined
            val what =
              if sideBlock then
                s"is built on $parent, which is not the head $head: a side block, and this importer was given no " +
                  "fork choice to take it to"
              else s"has parent $parent, which this node does not have (head $head): the chain before it is broken"
            val message =
              s"Chain import: block ${header.number} (${header.hashAsHexString}) $what. Not taken on the unified " +
                "state layout. This says nothing about the block."
            val earlier = if sideBlock then sideBlocksRefused else orphanBlocksRefused
            if earlier == 0 then log.error(message) else log.debug(message)
            if sideBlock then sideBlocksRefused += 1 else orphanBlocksRefused += 1
            LenientOutcome.Failed
          else if !blockchainReader.isHeldInPlace(header.parentHash.value) then
            // the head and the state on disk are written in one batch: a head whose state is not the one held is a
            // fault of this datadir, and no block can be judged against it
            throw new com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable(
              s"the head ${ByteStringUtils.hash2string(header.parentHash.value)} is not the block whose state is held; " +
                s"block ${header.number} cannot be imported"
            )
          else
            val parentWeight = blockchainReader.getChainWeightByHash(header.parentHash).getOrElse(ChainWeight.zero)
            val stillOnHead =
              () => Option.unless(onHead)(BlockExecutionError.ValidationAfterExecError(ChainImporter.HeadMoved))
            blockExecution.executeAndAdoptBlockIf(block, parentWeight, stillOnHead) match
              case Right(_) => LenientOutcome.Imported(1)
              case Left(error) =>
                log.error(s"Chain import: block ${header.number} failed — ${error.describe}")
                LenientOutcome.Failed
        }

  /** A block after the merge that is not built on the head, adopted on the unified layout: what the adoption answered,
    * as the import counts it. Not under the exclusive chain insert lock: the adoption executes and moves the head on
    * threads of its own, under the state lock, behind any move under way.
    */
  private def adoptedOnLayers(
      block: Block,
      adopt: (Block, BlockchainConfig) => Consensus.ConsensusResult
  )(implicit blockchainConfig: BlockchainConfig): LenientOutcome =
    val number = block.header.number
    adopt(block, blockchainConfig) match
      case Consensus.SelectedNewBestBranch(oldBranch, _, _, _) =>
        log.info(
          s"Chain import: block $number (${block.header.hashAsHexString}) adopted as the head after the merge, " +
            s"leaving ${oldBranch.size} block(s)"
        )
        LenientOutcome.Imported(1)
      // the head became its parent meanwhile: it was imported onto it
      case Consensus.ExtendedCurrentBestBranch(data) => LenientOutcome.Imported(data.size)
      case fault: Consensus.ConsensusStateFault      =>
        // Not a verdict on the block: this node's state could not take it (a fork deeper than the records reach)
        log.error(
          s"Chain import: block $number was not executed on this node's own state: ${fault.fault.getMessage}"
        )
        LenientOutcome.Failed
      case Consensus.BranchExecutionFailure(_, _, error) =>
        log.error(s"Chain import: block $number failed — $error")
        LenientOutcome.Failed
      case other =>
        log.error(s"Chain import: block $number was not adopted — $other")
        LenientOutcome.Failed

  /** Import a chain file the way go-ethereum's `geth import` / `admin_importChain` does, stopping at the first problem.
    *
    * Every block goes through the node's own import path, [[BlockExecution.executeAndValidateBlocks]]: header and body
    * validation, execution, then gas used, receipts root, state root, logs bloom, EIP-7685 requests hash and EIP-7928
    * access-list hash. Its state writes are staged and committed in ONE atomic write with the block, its receipts,
    * weight, access list and the best-block pointer, so a block that fails validation, or a process killed mid-block,
    * leaves nothing behind; the pruning bookkeeping (`saveBlockState`) runs as for a synced block. Nothing in the file
    * is trusted.
    *
    *   - A genesis block in the file (number 0) is not imported; it must be this chain's genesis.
    *   - A block already on the local canonical chain, with its body and receipts, is skipped (geth `missingBlocks`),
    *     so re-importing a file, or a file overlapping the local chain, is safe.
    *   - Every other block must be the child of the local head. A file that does not continue the local chain, skips a
    *     block, or forks from it is refused at that block, with nothing after it imported.
    *   - The first invalid or undecodable block ends the import with an error naming it. Blocks before it stay
    *     imported, as in geth.
    *
    * Each block is checked and imported under [[ChainInsertLock.exclusive]], which every other block-executing path
    * takes shared, so a running node's sync or Engine API cannot execute or commit a block in between (geth holds its
    * chain insert lock the same way).
    */
  def importChainFileStrict(
      filePath: String
  )(implicit blockchainConfig: BlockchainConfig): Either[ChainImporter.ImportFailure, ChainImporter.ImportSummary] =
    import ChainImporter.*
    val path = java.nio.file.Paths.get(filePath)
    val progress = new StrictProgress
    if !java.nio.file.Files.isRegularFile(path) then
      Left(ImportFailure(s"chain file not found: $filePath", progress.summary))
    else if !strictImportRunning.compareAndSet(false, true) then
      Left(ImportFailure("another chain import is already running", progress.summary))
    else
      try
        val in = ChainFile.openInput(path)
        try importStrict(new ChainFile.Reader(in, filePath), filePath, progress)
        finally in.close()
      catch
        // refused, with nothing of the block written, because another thread's adoption holds the head (B-294): no
        // fault of this node's state and no verdict on the block. Carried by its type, for the caller to answer it as
        // one to ask again (B-462), and said at WARN
        case busy: com.chipprbots.ethereum.db.storage.pathstate.StateBusy =>
          val failure = ImportFailure(
            s"chain import of $filePath stopped: ${busy.getMessage}",
            progress.summary,
            Some(busy)
          )
          log.warn(s"[STATE-BUSY] Chain import (strict): ${failure.describe}")
          Left(failure)
        case scala.util.control.NonFatal(e) =>
          val failure =
            ImportFailure(
              s"chain import of $filePath failed: ${e.getClass.getSimpleName}: ${e.getMessage}",
              progress.summary,
              Some(e).collect { case fault: com.chipprbots.ethereum.db.storage.pathstate.StateFault => fault }
            )
          log.error(s"Chain import (strict): ${failure.describe}")
          Left(failure)
      finally strictImportRunning.set(false)

  private def importStrict(reader: ChainFile.Reader, source: String, progress: ChainImporter.StrictProgress)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[ChainImporter.ImportFailure, ChainImporter.ImportSummary] =
    import ChainImporter.*
    log.info(s"Chain import (strict): importing $source onto local head #${blockchainReader.getBestBlockNumber}")
    val startNanos = System.nanoTime()
    var reportedNanos = startNanos
    var previousInFile: Option[BlockHeader] = None
    var outcome: Option[Either[ImportFailure, ImportSummary]] = None

    def fail(message: String): Unit =
      val failure = ImportFailure(message, progress.summary)
      log.error(s"Chain import (strict): ${failure.describe}")
      outcome = Some(Left(failure))

    while outcome.isEmpty do
      reader.next() match
        case Left(err) => fail(err.describe)
        case Right(None) =>
          outcome = Some(Right(progress.summary))
        case Right(Some(block)) =>
          val header = block.header
          val number = header.number.value
          previousInFile match
            case Some(prev) if header.parentHash != prev.hash || number != prev.number.value + 1 =>
              fail(
                s"block #$number (${header.hashAsHexString}) does not follow the previous block in the file, " +
                  s"#${prev.number} (${prev.hashAsHexString}): the file is not one contiguous chain"
              )
            case _ =>
              previousInFile = Some(header)
              if number == 0 then
                if blockchainReader.genesisHeader.hash != header.hash then
                  fail(
                    s"the file's genesis ${header.hashAsHexString} is not this chain's genesis " +
                      s"${blockchainReader.genesisHeader.hashAsHexString}: the file is for a different network"
                  )
              else
                strictlyUnderTheLocks(block) match
                  case Left(message) => fail(message)
                  case Right(true)   => progress.skipped += 1
                  case Right(false) =>
                    progress.imported += 1
                    progress.head = Some(header)
                    val now = System.nanoTime()
                    if now - reportedNanos >= ProgressIntervalNanos then
                      val rate = progress.imported * 1e9 / (now - startNanos)
                      log.info(
                        f"Chain import (strict): imported=${progress.imported} skipped=${progress.skipped} " +
                          f"head=#$number ($rate%.1f blocks/s)"
                      )
                      reportedNanos = now

    outcome.get.foreach { s =>
      log.info(
        s"Chain import (strict) complete: ${s.imported} imported, ${s.skipped} already present, " +
          s"head #${blockchainReader.getBestBlockNumber}"
      )
    }
    outcome.get

  /** One block of a strict import under the chain insert lock, and on the hash layout under the head's lock first
    * (B-511): its look at the head and its commit as the head are then one step that a fork choice is not between. The
    * head's lock is taken before the chain insert lock, as every holder of both takes them.
    */
  private def strictlyUnderTheLocks(
      block: Block
  )(implicit blockchainConfig: BlockchainConfig): Either[String, Boolean] =
    if blockchainReader.holdsStateInPlace then ChainInsertLock.exclusive(importOneStrict(block))
    else
      CanonicalHeadLock.held(s"the strict import of block ${block.header.number.value}")(
        ChainInsertLock.exclusive(importOneStrict(block))
      )

  /** One block of a strict import, under the chain insert lock. `Right(true)`: already present, skipped;
    * `Right(false)`: imported as the new head; `Left`: why it was refused.
    */
  private def importOneStrict(block: Block)(implicit blockchainConfig: BlockchainConfig): Either[String, Boolean] =
    val header = block.header
    val number = header.number.value
    if isPresent(header) then Right(true)
    else
      val best = blockchainReader.getBestBlockHeader
      if !best.exists(_.hash == header.parentHash) then
        val headDesc = best.map(h => s"#${h.number} (${h.hashAsHexString})").getOrElse("none")
        val parentHex = ByteStringUtils.hash2string(header.parentHash.value)
        Left(
          if blockchainReader.getBlockHeaderByHash(header.parentHash).isDefined then
            s"block #$number (${header.hashAsHexString}) builds on $parentHex, which is not the local head " +
              s"$headDesc: the file forks from the local chain"
          else
            s"block #$number (${header.hashAsHexString}) has parent $parentHex, which is not in the local chain " +
              s"(local head $headDesc): the file does not continue this node's chain — import the blocks before it first"
        )
      else
        val parentWeight = blockchainReader.getChainWeightByHash(header.parentHash).getOrElse(ChainWeight.zero)
        // Head writers outside the chain insert lock (a consensus client's forkchoiceUpdated, a miner's or a branch
        // import's head settle) can move the head while this block executes. Checked again immediately before the
        // commit: a block whose parent is no longer the head must not be made the head.
        var headMoved = false
        val stillOnHead = () =>
          if blockchainReader.getBestBlockHeader.exists(_.hash == header.parentHash) then None
          else
            headMoved = true
            Some(BlockExecutionError.ValidationAfterExecError(ChainImporter.HeadMoved))
        blockExecution.executeAndAdoptBlockIf(block, parentWeight, stillOnHead) match
          case Right(_) => Right(false)
          case Left(_) if headMoved =>
            val now = blockchainReader.getBestBlockHeader.map(h => s"#${h.number} (${h.hashAsHexString})")
            Left(
              s"block #$number (${header.hashAsHexString}): ${ChainImporter.HeadMoved} (head is now " +
                s"${now.getOrElse("none")}) — a consensus client or miner is active; stop it or use the offline " +
                "`import` command"
            )
          case Left(error) =>
            Left(s"block #$number (${header.hashAsHexString}) is invalid: ${error.describe}")

  /** On the local canonical chain with everything a later block or an RPC needs: body and receipts, and — for the head,
    * the only block whose state an import builds on — its state root. A block left half-written by an older version
    * does not count as present.
    */
  private def isPresent(header: BlockHeader): Boolean =
    blockchainReader.getBlockHeaderByNumber(header.number.value).exists(_.hash == header.hash) &&
      blockchainReader.getBlockBodyByHash(header.hash).isDefined &&
      blockchainReader.getReceiptsByHash(header.hash).isDefined &&
      (blockchainReader.getBestBlockNumber != header.number.value || blockchainReader.holdsStateOf(header))

object ChainImporter:

  /** The adoption of a block that is not built on the head, on the unified state layout (spec 018 C4; B-220): the
    * node's own reorganisation on layers ([[com.chipprbots.ethereum.consensus.ConsensusImpl]], the consensus layer's
    * arm of C4e part 5), with the block itself named as the head to lead to. So the block is executed on its parent's
    * state, kept as a forward layer, and made the head by one move of the state, the best block and the index when it
    * is valid; an invalid block leaves the head exactly where it was, and a fork deeper than the changeset records
    * reach is refused before anything is executed
    * ([[com.chipprbots.ethereum.consensus.Consensus.ConsensusStateFault]]).
    *
    * For an import that takes the block as the head whatever its weight: the chain file after the merge, where
    * core-geth's `ForkChoice.ReorgNeeded` takes every block as the head, and the sequential ethereum/tests runner,
    * which executes each block on its parent. A fresh `ConsensusImpl` for each block: what it names and what it
    * remembers of a refusal are that block's alone. No consensus layer is bound to it and no invalid block is reported.
    * Inert on the hash layout, where neither caller asks it.
    */
  def adoptingOnLayers(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      blockExecution: BlockExecution,
      reorgState: com.chipprbots.ethereum.consensus.ReorgStateHandler
  ): (Block, BlockchainConfig) => Consensus.ConsensusResult =
    (block, blockchainConfig) =>
      val named = Some(block.hash.value)
      val consensus = new com.chipprbots.ethereum.consensus.ConsensusImpl(
        blockchainReader,
        blockchainWriter,
        blockExecution,
        designatedHead = Some(com.chipprbots.ethereum.consensus.engine.DesignatedHead(() => named)),
        reorgState = reorgState
      )
      consensus
        .evaluateBranch(cats.data.NonEmptyList.one(block))(using IORuntime.global, blockchainConfig)
        .unsafeRunSync()(using IORuntime.global)

  /** Counters a strict import keeps outside its `try`, so a failure part-way reports what was really imported. */
  final private[data] class StrictProgress:
    var imported = 0L
    var skipped = 0L
    var head: Option[BlockHeader] = None
    def summary: ImportSummary = ImportSummary(imported, skipped, head)

  /** What a strict import did before it finished or stopped. `head` is the last block it imported. */
  final case class ImportSummary(imported: Long, skipped: Long, head: Option[BlockHeader])

  /** Why a strict import stopped, and what it did before. `fault` is the fault of this node's state that stopped it, if
    * one did, by its type: a caller answers a [[com.chipprbots.ethereum.db.storage.pathstate.StateBusy]] as one to ask
    * again, not as a failed import (B-462).
    */
  final case class ImportFailure(
      reason: String,
      progress: ImportSummary,
      fault: Option[com.chipprbots.ethereum.db.storage.pathstate.StateFault] = None
  ):
    def describe: String =
      s"$reason (imported ${progress.imported}, skipped ${progress.skipped} before stopping" +
        progress.head.map(h => s"; last imported #${h.number}").getOrElse("") + ")"

  private val ProgressIntervalNanos: Long = 8_000_000_000L

  private[data] val HeadMoved = "head moved during import"

  /** One strict import at a time per JVM: two would race on the head. */
  private val strictImportRunning = new java.util.concurrent.atomic.AtomicBoolean(false)
