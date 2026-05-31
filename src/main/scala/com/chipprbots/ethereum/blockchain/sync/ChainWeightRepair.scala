package com.chipprbots.ethereum.blockchain.sync

import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.Logger

import scala.collection.mutable.ArrayBuffer

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.{ BlockchainReader, BlockchainWriter, BlockHeader }
import com.chipprbots.ethereum.domain.ChainWeight

/** Repairs corrupted chain-weight entries caused by ETH68_BOOTSTRAP inflation/deflation.
  *
  * Uses parentHash chain traversal exclusively — never calls getBlockHeaderByNumber.
  * SNAP-synced nodes do not build a complete number→hash canonical index for historical blocks,
  * so getBlockHeaderByNumber returns None for most pre-pivot blocks. getBlockHeaderByHash works
  * for all stored headers (SNAP backfill stores headers by hash).
  *
  * Algorithm:
  *   Phase 1 — Collect above-anchor chain: walk backward from bestBlock via parentHash,
  *              collecting headers until anchor block is reached.
  *   Phase 2 — Forward corrections: apply TD(N) = TD(N-1) + diff(N) from anchor to bestBlock.
  *   Phase 3 — Reverse corrections: walk backward via parentHash from anchor-1 to earliest header,
  *              applying TD(K) = TD(K+1) - diff(K+1) at each step.
  *
  * Anchor source:
  *   The anchor block's TD must be correct before this repair runs, seeded via
  *   -Dfukuii.seed-chain-weights=HASH:TD (operator-supplied canonical value from core-geth).
  */
object ChainWeightRepair {

  val MismatchThresholdBlocks = 5
  val ProgressLogInterval: Long = 500_000L

  case class RepairResult(walked: Long, mismatches: Long, corrected: Long) {
    def hadMismatches: Boolean = mismatches > 0
    def hadInflation: Boolean  = hadMismatches
  }

  val empty: RepairResult = RepairResult(0, 0, 0)

  def repairBidirectional(
      anchorBlock: BigInt,
      reader: BlockchainReader,
      writer: BlockchainWriter,
      slog: Logger
  ): RepairResult = {
    // Locate the anchor header. The anchor IS a recently-active block (seeded + canonical chain),
    // so getBlockHeaderByNumber works here. If it fails, fall back to parentHash traversal.
    val anchorHeader: BlockHeader = reader.getBlockHeaderByNumber(anchorBlock).orElse {
      // Fallback: walk back from best until we find the anchor block number
      findByNumber(anchorBlock, reader)
    }.getOrElse {
      slog.warn(
        s"CHAIN_WEIGHT_REPAIR_SKIP anchorBlock=$anchorBlock reason=header_not_found",
        kv("anchorBlock", anchorBlock.toString), kv("reason", "header_not_found")
      )
      return empty
    }

    val anchorTD: BigInt = reader.getChainWeightByHash(anchorHeader.hash).map(_.totalDifficulty).getOrElse {
      slog.warn(
        s"CHAIN_WEIGHT_REPAIR_SKIP anchorBlock=$anchorBlock reason=chain_weight_missing",
        kv("anchorBlock", anchorBlock.toString), kv("reason", "chain_weight_missing")
      )
      return empty
    }

    val t0 = System.currentTimeMillis()
    val bestHeader = reader.getBestBlockHeader().getOrElse {
      slog.warn("CHAIN_WEIGHT_REPAIR_SKIP reason=no_best_block", kv("reason", "no_best_block"))
      return empty
    }

    slog.info(
      s"CHAIN_WEIGHT_REPAIR_START anchorBlock=$anchorBlock anchorTD=$anchorTD bestBlock=${bestHeader.number}",
      kv("anchorBlock", anchorBlock.toString),
      kv("anchorTD",    anchorTD.toString),
      kv("bestBlock",   bestHeader.number.toString)
    )

    // Phase 1: collect headers [anchorBlock+1 .. bestBlock] via parentHash backward walk
    val aboveAnchor = collectAboveAnchor(anchorHeader, bestHeader, reader, slog)

    // Phase 2: forward corrections (anchor+1 → bestBlock)
    val fwd = applyForward(anchorTD, anchorHeader, aboveAnchor, reader, writer, slog)

    // Phase 3: reverse corrections (anchor-1 → earliest header)
    val rev = applyReverse(anchorTD, anchorHeader, reader, writer, slog)

    val total = RepairResult(
      fwd.walked    + rev.walked,
      fwd.mismatches + rev.mismatches,
      fwd.corrected  + rev.corrected
    )

    slog.info(
      s"CHAIN_WEIGHT_CHECK_COMPLETE walked=${total.walked} mismatches=${total.mismatches} corrected=${total.corrected} anchorBlock=$anchorBlock elapsedMs=${System.currentTimeMillis() - t0}",
      kv("walked",      total.walked.toString),
      kv("mismatches",  total.mismatches.toString),
      kv("corrected",   total.corrected.toString),
      kv("anchorBlock", anchorBlock.toString),
      kv("elapsedMs",   (System.currentTimeMillis() - t0).toString)
    )
    total
  }

  // ── Phase 1 ──────────────────────────────────────────────────────────────────
  // Walk backward from bestBlock via parentHash until we reach the anchor block.
  // Returns headers [anchorBlock+1 .. bestBlock] in ascending block-number order.

  private def collectAboveAnchor(
      anchorHeader: BlockHeader,
      bestHeader: BlockHeader,
      reader: BlockchainReader,
      slog: Logger
  ): IndexedSeq[BlockHeader] = {
    if (bestHeader.number <= anchorHeader.number) return IndexedSeq.empty

    val buf = ArrayBuffer.empty[BlockHeader]
    var current = bestHeader

    while (current.number > anchorHeader.number) {
      buf += current
      if (current.parentHash == anchorHeader.hash) {
        // Reached the block just above anchor — stop
        current = anchorHeader  // sentinel to exit loop
      } else {
        reader.getBlockHeaderByHash(current.parentHash) match {
          case None =>
            slog.warn(
              s"CHAIN_WEIGHT_REPAIR_GAP block=${current.number} reason=parent_not_found",
              kv("block",  current.number.toString),
              kv("parent", current.parentHash.take(8).map("%02x".format(_)).mkString),
              kv("reason", "parent_not_found")
            )
            return buf.reverse.toIndexedSeq
          case Some(parent) => current = parent
        }
      }
    }

    buf.reverse.toIndexedSeq  // ascending: [anchorBlock+1, ..., bestBlock]
  }

  // ── Phase 2 ──────────────────────────────────────────────────────────────────
  // Forward pass: TD(N) = storedTD(parent(N)) + diff(N), for each block in headers.
  //
  // Uses each block's parent's STORED TD — NOT a running accumulated sum from the anchor.
  // This is correct even when collectAboveAnchor finds a gap (some parentHash lookups failed
  // for blocks in the "dark zone" between anchor and the first accessible block). The running-
  // sum approach would under-count by all missing intermediate diffs, producing a lower
  // expectedTD that "corrects" blocks DOWN rather than up.
  //
  // With this approach, the dark zone boundary block must be seeded with its canonical TD
  // (via -Dfukuii.seed-chain-weights) so the first accessible block gets the right parent TD.

  private def applyForward(
      anchorTD:     BigInt,
      anchorHeader: BlockHeader,
      headers:      IndexedSeq[BlockHeader],
      reader: BlockchainReader,
      writer: BlockchainWriter,
      slog: Logger
  ): RepairResult = {
    var walked = 0L; var mismatches = 0L; var corrected = 0L; var skipped = 0L

    for (header <- headers) {
      // Resolve parent TD: use anchor shortcut or look up from DB
      val parentTDOpt: Option[BigInt] =
        if (header.parentHash == anchorHeader.hash) Some(anchorTD)
        else reader.getChainWeightByHash(header.parentHash).map(_.totalDifficulty)

      parentTDOpt match {
        case None =>
          skipped += 1
          slog.warn(
            s"CHAIN_WEIGHT_REPAIR_SKIP_BLOCK block=${header.number} reason=parent_chain_weight_missing — seed the dark zone boundary block",
            kv("block",  header.number.toString),
            kv("reason", "parent_chain_weight_missing")
          )
        case Some(parentTD) =>
          val expectedTD = parentTD + header.difficulty
          walked += 1
          if (writeIfMismatch(header, expectedTD, reader, writer, slog).isDefined) {
            mismatches += 1; corrected += 1
          }
      }
    }

    val endBlock = headers.lastOption.map(_.number).getOrElse(BigInt(0))
    slog.info(
      s"CHAIN_WEIGHT_FORWARD_COMPLETE walked=$walked skipped=$skipped mismatches=$mismatches corrected=$corrected endBlock=$endBlock",
      kv("walked",     walked.toString),
      kv("skipped",    skipped.toString),
      kv("mismatches", mismatches.toString),
      kv("corrected",  corrected.toString),
      kv("endBlock",   endBlock.toString)
    )
    RepairResult(walked, mismatches, corrected)
  }

  // ── Phase 3 ──────────────────────────────────────────────────────────────────
  // Reverse pass: TD(K) = TD(K+1) - diff(K+1), from anchor-1 to earliest header.
  // Uses getBlockHeaderByHash(parentHash) — works for all SNAP-stored headers.

  private def applyReverse(
      anchorTD: BigInt,
      anchorHeader: BlockHeader,
      reader: BlockchainReader,
      writer: BlockchainWriter,
      slog: Logger
  ): RepairResult = {
    var currentTD     = anchorTD
    var currentHeader = anchorHeader
    var walked        = 0L; var mismatches = 0L; var corrected = 0L
    var running       = true

    while (running) {
      reader.getBlockHeaderByHash(currentHeader.parentHash) match {
        case None =>
          running = false  // pre-backfill boundary — no more headers

        case Some(parentHeader) =>
          // TD(parent) = TD(current) - diff(current)
          val expectedTD = currentTD - currentHeader.difficulty
          walked += 1

          if (walked % ProgressLogInterval == 0) {
            val pct = if (anchorHeader.number > 0)
              ((anchorHeader.number - parentHeader.number) * 100 / anchorHeader.number).toInt
            else 0
            slog.info(
              s"CHAIN_WEIGHT_REPAIR_PROGRESS walked=$walked block=${parentHeader.number} progress=$pct%",
              kv("walked",   walked.toString),
              kv("block",    parentHeader.number.toString),
              kv("progress", s"$pct%")
            )
          }

          if (writeIfMismatch(parentHeader, expectedTD, reader, writer, slog).isDefined) {
            mismatches += 1; corrected += 1
          }

          currentTD     = expectedTD
          currentHeader = parentHeader
      }
    }

    slog.info(
      s"CHAIN_WEIGHT_REVERSE_COMPLETE walked=$walked mismatches=$mismatches corrected=$corrected earliestBlock=${currentHeader.number}",
      kv("walked",        walked.toString),
      kv("mismatches",    mismatches.toString),
      kv("corrected",     corrected.toString),
      kv("earliestBlock", currentHeader.number.toString)
    )
    RepairResult(walked, mismatches, corrected)
  }

  // ── Shared helpers ────────────────────────────────────────────────────────────

  private def writeIfMismatch(
      header:     BlockHeader,
      expectedTD: BigInt,
      reader:     BlockchainReader,
      writer:     BlockchainWriter,
      slog:       Logger
  ): Option[BigInt] = {
    val threshold = header.difficulty * MismatchThresholdBlocks
    reader.getChainWeightByHash(header.hash).map(_.totalDifficulty) match {
      case None =>
        writer.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(expectedTD)).commit()
        Some(BigInt(0))
      case Some(storedTD) =>
        val delta = storedTD - expectedTD
        if (delta.abs > threshold) {
          writer.storeChainWeight(header.hash, ChainWeight.totalDifficultyOnly(expectedTD)).commit()
          val deltaBlocks = delta / header.difficulty
          slog.warn(
            s"CHAIN_WEIGHT_MISMATCH block=${header.number} storedTD=$storedTD expectedTD=$expectedTD deltaBlocks=$deltaBlocks corrected=true",
            kv("block",       header.number.toString),
            kv("storedTD",    storedTD.toString),
            kv("expectedTD",  expectedTD.toString),
            kv("deltaBlocks", deltaBlocks.toString),
            kv("corrected",   "true")
          )
          Some(delta)
        } else None
    }
  }

  /** Fallback anchor lookup: walk backward from best block via parentHash until block number matches. */
  private def findByNumber(targetNumber: BigInt, reader: BlockchainReader): Option[BlockHeader] = {
    var current = reader.getBestBlockHeader().getOrElse(return None)
    while (current.number > targetNumber) {
      current = reader.getBlockHeaderByHash(current.parentHash).getOrElse(return None)
    }
    if (current.number == targetNumber) Some(current) else None
  }
}
