package com.chipprbots.ethereum.blockchain.data

import java.io.File

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.chipprbots.ethereum.blockchain.sync.regular.BlockEnqueued
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportResult
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.blockchain.sync.regular.DuplicateBlock
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockValidation
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
    blockchainWriter: BlockchainWriter,
    blockExecution: BlockExecution,
    blockValidation: BlockValidation,
    // The node's own fork choice for a block that does NOT extend the current head: queue it as a side branch and
    // reorganise once that branch outweighs the head (ConsensusAdapter.evaluateBranchBlock -> BlockQueue ->
    // ConsensusImpl). `None` keeps the old last-block-wins behaviour and exists only for direct test construction.
    forkChoice: Option[(Block, BlockchainConfig) => IO[BlockImportResult]] = None
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
      val in = ChainFile.openInput(file.toPath)
      try
        val reader = new ChainFile.Reader(in, filePath)

        var imported = 0
        var skipped = 0
        var failed = 0
        var first = true
        var done = false

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
                  if imported % 10 == 0 then
                    log.info(s"Chain import: block ${block.header.number} imported ($imported)")
                case LenientOutcome.Skipped => skipped += 1
                case LenientOutcome.Failed  => failed += 1

        log.info(s"Chain import: decoded ${reader.blocksRead} blocks")
        log.info(s"Chain import complete: $imported imported, $skipped skipped, $failed failed")
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
    else if !extendsHead && !postMerge && forkChoice.isDefined then
      // A side-chain block. Saving it as best — what this loop did for every block — made the LAST block of
      // the file the head whatever its total difficulty, so every ethereum/tests fork/uncle-race vector
      // (lotsOfLeafs, sideChainWithMoreTransactions, uncleBlockAtBlock3afterBlock4, ...) ended on the wrong
      // head. core-geth's `import` goes through InsertChain -> writeBlockAndSetHead, which moves the head only
      // when forker.ReorgNeeded says the block is heavier. The node's own fork choice does exactly that.
      forkChoice.get(block, blockchainConfig).unsafeRunSync()(using IORuntime.global) match
        case BlockImportedToTop(data) =>
          LenientOutcome.Imported(data.size)
        case ChainReorganised(_, newBranch, _) =>
          log.info(s"Chain import: block $blockNum reorganised to a heavier branch of ${newBranch.size} block(s)")
          LenientOutcome.Imported(newBranch.size)
        case BlockEnqueued | DuplicateBlock =>
          LenientOutcome.Skipped // kept as a side block (lighter or not yet rooted), exactly as core-geth writes a side chain
        case other =>
          log.error(s"Chain import: block $blockNum rejected by fork choice — $other")
          LenientOutcome.Failed
    else
      importBlock(block) match
        case Right((receipts, blockAccessList)) =>
          // Block was fully validated and executed, including all transactions (blob ones
          // included). Sidecars don't participate in the consensus state transition — they
          // are network-layer attachments verified separately at gossip time. The hive
          // consensus / consume-engine fixtures expect head to advance over blob blocks;
          // mainnet ingestion uses Engine API + sidecar verification, not chain.rlp.
          saveImported(block, receipts, blockAccessList)
          LenientOutcome.Imported(1)

        case Left(error) =>
          log.error(s"Chain import: block $blockNum failed — $error")
          LenientOutcome.Failed

  private def saveImported(block: Block, receipts: Seq[Receipt], blockAccessList: Option[BlockAccessList]): Unit =
    val parentWeight = blockchainReader
      .getChainWeightByHash(block.header.parentHash)
      .getOrElse(ChainWeight.zero)
    val newWeight = parentWeight.increase(block.header)
    blockchainWriter.save(block, receipts, newWeight, saveAsBestBlock = true)
    // EIP-7928: the list the block just validated against.
    blockAccessList.foreach(bal => blockchainWriter.storeBlockAccessList(block.header.hash, bal).commit())
    blockchainReader.recordBlockDifficulty(block.header.difficulty)

  /** Import a chain file the way go-ethereum's `geth import` / `admin_importChain` does, stopping at the first problem.
    *
    * Every block goes through the same checks as a synced block — header and body validation, execution, then gas used,
    * receipts root, state root, logs bloom, EIP-7685 requests hash and EIP-7928 access-list hash — before it is stored
    * as the new head. Nothing in the file is trusted.
    *
    *   - A genesis block in the file (number 0) is not imported; it must be this chain's genesis.
    *   - A block already on the local canonical chain is skipped (geth `missingBlocks`), so re-importing a file, or a
    *     file overlapping the local chain, is safe.
    *   - Every other block must be the child of the local head. A file that does not continue the local chain, skips a
    *     block, or forks from it is refused at that block, with nothing after it imported.
    *   - The first invalid or undecodable block ends the import with an error naming it. Blocks before it stay
    *     imported, as in geth.
    *
    * Not safe to run while the node is importing blocks from peers or a consensus client: run it offline (the `import`
    * launcher command) or on a node with sync idle.
    */
  def importChainFileStrict(
      filePath: String
  )(implicit blockchainConfig: BlockchainConfig): Either[ChainImporter.ImportFailure, ChainImporter.ImportSummary] =
    val path = java.nio.file.Paths.get(filePath)
    if !java.nio.file.Files.isRegularFile(path) then
      Left(ChainImporter.ImportFailure(s"chain file not found: $filePath", ChainImporter.ImportSummary(0, 0, None)))
    else if !ChainImporter.strictImportRunning.compareAndSet(false, true) then
      Left(
        ChainImporter.ImportFailure("another chain import is already running", ChainImporter.ImportSummary(0, 0, None))
      )
    else
      try
        val in = ChainFile.openInput(path)
        try importStrict(new ChainFile.Reader(in, filePath), filePath)
        finally in.close()
      catch
        case scala.util.control.NonFatal(e) =>
          Left(
            ChainImporter.ImportFailure(
              s"chain import of $filePath failed: ${e.getClass.getSimpleName}: ${e.getMessage}",
              ChainImporter.ImportSummary(0, 0, None)
            )
          )
      finally ChainImporter.strictImportRunning.set(false)

  private def importStrict(reader: ChainFile.Reader, source: String)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[ChainImporter.ImportFailure, ChainImporter.ImportSummary] =
    import ChainImporter.*
    log.info(s"Chain import (strict): importing $source onto local head #${blockchainReader.getBestBlockNumber}")
    val startNanos = System.nanoTime()
    var reportedNanos = startNanos
    var imported = 0L
    var skipped = 0L
    var lastHead: Option[BlockHeader] = None
    var previousInFile: Option[BlockHeader] = None
    var outcome: Option[Either[ImportFailure, ImportSummary]] = None

    def fail(message: String): Unit =
      val failure = ImportFailure(message, ImportSummary(imported, skipped, lastHead))
      log.error(s"Chain import (strict): ${failure.describe}")
      outcome = Some(Left(failure))

    while outcome.isEmpty do
      reader.next() match
        case Left(err) => fail(err.describe)
        case Right(None) =>
          outcome = Some(Right(ImportSummary(imported, skipped, lastHead)))
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
              else if blockchainReader.getBlockHeaderByNumber(number).exists(_.hash == header.hash) &&
                blockchainReader.getBlockBodyByHash(header.hash).isDefined
              then skipped += 1
              else
                val best = blockchainReader.getBestBlockHeader
                if !best.exists(_.hash == header.parentHash) then
                  val headDesc = best.map(h => s"#${h.number} (${h.hashAsHexString})").getOrElse("none")
                  val parentKnown = blockchainReader.getBlockHeaderByHash(header.parentHash).isDefined
                  val parentHex = ByteStringUtils.hash2string(header.parentHash.value)
                  fail(
                    if parentKnown then
                      s"block #$number (${header.hashAsHexString}) builds on $parentHex, " +
                        s"which is not the local head $headDesc: the file forks from the local chain"
                    else
                      s"block #$number (${header.hashAsHexString}) has parent $parentHex, " +
                        s"which is not in the local chain (local head $headDesc): the file does not continue " +
                        "this node's chain — import the blocks before it first"
                  )
                else
                  importBlock(block) match
                    case Left(error) =>
                      fail(s"block #$number (${header.hashAsHexString}) is invalid: $error")
                    case Right((receipts, blockAccessList)) =>
                      saveImported(block, receipts, blockAccessList)
                      imported += 1
                      lastHead = Some(header)
                      val now = System.nanoTime()
                      if now - reportedNanos >= ProgressIntervalNanos then
                        val rate = imported * 1e9 / (now - startNanos)
                        log.info(
                          f"Chain import (strict): imported=$imported skipped=$skipped head=#$number ($rate%.1f blocks/s)"
                        )
                        reportedNanos = now

    outcome.get.foreach { s =>
      log.info(
        s"Chain import (strict) complete: ${s.imported} imported, ${s.skipped} already present, " +
          s"head #${blockchainReader.getBestBlockNumber}"
      )
    }
    outcome.get

  private def importBlock(
      block: Block
  )(implicit blockchainConfig: BlockchainConfig): Either[Any, (Seq[Receipt], Option[BlockAccessList])] =
    // Validate header/body pre-execution (ommers, difficulty, nonce, withdrawals/blob fields, etc.),
    // then execute, then validate post-execution (gasUsed, receiptsRoot, stateRoot).
    // Invalid blocks are rejected and the previous chain head is preserved.
    blockValidation.validateBlockBeforeExecution(block) match
      case Left(err) =>
        Left(s"pre-execution validation failed: $err")
      case Right(_) =>
        blockExecution.executeBlockNoValidationWithRequests(block).flatMap {
          case (receipts, gasUsed, stateRootHash, requests, blockAccessList) =>
            blockValidation.validateBlockAfterExecution(block, stateRootHash, receipts, gasUsed) match
              case Left(err) =>
                receipts.zipWithIndex.foreach { case (r, i) =>
                  log.error(s"Chain import: block ${block.header.number} tx[$i] cumulativeGas=${r.cumulativeGasUsed}")
                }
                Left(s"post-execution validation failed: $err")
              case Right(_) =>
                // EIP-7685: the header's requestsHash must match the requests execution produced; EIP-7928: its
                // blockAccessListHash, the access list execution produced.
                blockExecution
                  .validateRequestsHash(block, requests)
                  .flatMap(_ => blockExecution.validateBlockAccessList(block, blockAccessList))
                  .left
                  .map(err => s"post-execution validation failed: ${err.describe}")
                  .map(_ => (receipts, blockAccessList))
        }

object ChainImporter:

  /** What a strict import did before it finished or stopped. `head` is the last block it imported. */
  final case class ImportSummary(imported: Long, skipped: Long, head: Option[BlockHeader])

  final case class ImportFailure(reason: String, progress: ImportSummary):
    def describe: String =
      s"$reason (imported ${progress.imported}, skipped ${progress.skipped} before stopping" +
        progress.head.map(h => s"; last imported #${h.number}").getOrElse("") + ")"

  private val ProgressIntervalNanos: Long = 8_000_000_000L

  /** One strict import at a time per JVM: two would race on the head. */
  private val strictImportRunning = new java.util.concurrent.atomic.AtomicBoolean(false)
