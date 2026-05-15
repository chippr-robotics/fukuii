package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream

import org.apache.pekko.util.ByteString

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.appstate.BlockInfo

/** Bootstrap a fresh datadir from a `.checkpoint` archive (see [[CheckpointArchive]] for the on-disk format).
  *
  * After a successful import the database holds:
  *   - the checkpoint block's header (no body — the archive intentionally omits transactions/receipts)
  *   - all state trie nodes reachable from the header's stateRoot
  *   - all referenced contract bytecodes
  *   - the chain weight (totalDifficulty) at the checkpoint block
  *   - AppState pointers: bestBlock = checkpoint block; snap/bytecode/storage recovery all marked done so the SNAP
  *     coordinator is never spawned on the next start
  *
  * Regular sync resumes from `checkpoint.number + 1` driven by the Engine API (post-merge chains).
  */
final class CheckpointImporter(
    blockchainWriter: BlockchainWriter,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    appStateStorage: AppStateStorage
) {
  import CheckpointImporter._
  private val log = LoggerFactory.getLogger(getClass)

  /** Import from a file. Gzip-wrapped archives are auto-detected by either:
    *   - file path ending in `.gz` (operator convention), OR
    *   - first 2 bytes being the gzip magic `0x1F 0x8B` (peeked via a PushbackInputStream)
    *
    * The magic sniff covers Bug 35: an operator using `--gzip` who didn't add the `.gz` extension to
    * the output path still gets a working import.
    */
  def importFromFile(path: Path, expectedChainId: Option[Long] = None): Either[ImportError, ImportResult] = {
    val raw = new FileInputStream(path.toFile)
    try {
      val pushback = new java.io.PushbackInputStream(raw, 2)
      val b0 = pushback.read()
      val b1 = pushback.read()
      val isGzipped =
        path.toString.endsWith(".gz") ||
          (b0 == 0x1f && b1 == 0x8b)
      // Put the two peeked bytes back so the decoder sees them.
      if (b1 >= 0) pushback.unread(b1)
      if (b0 >= 0) pushback.unread(b0)
      val in: InputStream =
        if (isGzipped) new GZIPInputStream(pushback, 65536)
        else pushback
      try importFromStream(in, expectedChainId)
      finally in.close()
    } finally raw.close()
  }

  def importFromStream(in: InputStream, expectedChainId: Option[Long]): Either[ImportError, ImportResult] = {
    val reader = new CheckpointArchive.Reader(in)
    reader.readHeader() match {
      case Left(err) => Left(BadFormat(err))
      case Right(h) =>
        expectedChainId match {
          case Some(want) if want != h.chainId => Left(ChainIdMismatch(want, h.chainId))
          case _                               => streamEntries(reader, h)
        }
    }
  }

  private def streamEntries(
      reader: CheckpointArchive.Reader,
      header: CheckpointArchive.Header
  ): Either[ImportError, ImportResult] = {
    val startMs = System.currentTimeMillis()
    val blockNum = header.blockHeader.number
    val blockHash = header.blockHeader.hash
    // Pass the checkpoint block number so ReferenceCountNodeStorage tags nodes at the
    // imported block height — matches what SNAP healing does at pivot, so post-import
    // pruning math stays sane.
    val mpt = stateStorage.getBackingStorage(blockNum)
    val nodeBuf = scala.collection.mutable.ArrayBuffer.empty[(ByteString, Array[Byte])]
    val codeBuf = scala.collection.mutable.ArrayBuffer.empty[(ByteString, Array[Byte])]
    var totalNodes = 0L
    var totalBytecodes = 0L
    var nodeBytes = 0L
    var codeBytes = 0L

    log.info(
      "[CHECKPOINT IMPORT] starting block={} chainId={} stateRoot={}",
      blockNum,
      header.chainId,
      hex8(header.blockHeader.stateRoot)
    )

    def flushNodes(): Unit =
      if (nodeBuf.nonEmpty) {
        mpt.storeRawNodes(nodeBuf.toSeq)
        nodeBuf.clear()
      }

    def flushCodes(): Unit =
      if (codeBuf.nonEmpty) {
        var batch = evmCodeStorage.emptyBatchUpdate
        var i = 0
        while (i < codeBuf.length) {
          val (h, code) = codeBuf(i)
          batch = batch.and(evmCodeStorage.put(h, ByteString(code)))
          i += 1
        }
        batch.commit()
        codeBuf.clear()
      }

    var done = false
    while (!done) {
      reader.nextEntry() match {
        case Left(err) =>
          flushNodes(); flushCodes()
          return Left(BadFormat(err))
        case Right(CheckpointArchive.NodeEntry(hash, rlpBytes)) =>
          nodeBuf += ((hash, rlpBytes))
          totalNodes += 1
          nodeBytes += rlpBytes.length
          if (nodeBuf.size >= NodeBatchSize) {
            flushNodes()
            if (totalNodes % LogInterval == 0)
              log.info(
                "[CHECKPOINT IMPORT] nodes={} ({} MiB), bytecodes={}",
                totalNodes,
                nodeBytes / (1024 * 1024),
                totalBytecodes
              )
          }
        case Right(CheckpointArchive.BytecodeEntry(hash, code)) =>
          codeBuf += ((hash, code))
          totalBytecodes += 1
          codeBytes += code.length
          if (codeBuf.size >= BytecodeBatchSize) flushCodes()
        case Right(CheckpointArchive.EndOfStream) =>
          flushNodes(); flushCodes()
          done = true
      }
    }

    reader.verifyCrc() match {
      case Left(err) => return Left(BadFormat(err))
      case Right(_)  => ()
    }

    // Header, chain weight, best-block pointer, and the done-markers all committed in a
    // single atomic batch — partial state here would be worse than no state at all.
    blockchainWriter
      .storeBlockHeader(header.blockHeader)
      .and(blockchainWriter.storeChainWeight(blockHash, header.chainWeight))
      .and(appStateStorage.putBestBlockInfo(BlockInfo(blockHash, blockNum)))
      .and(appStateStorage.snapSyncDone())
      .and(appStateStorage.bytecodeRecoveryDone())
      .and(appStateStorage.storageRecoveryDone())
      .commit()

    val elapsed = System.currentTimeMillis() - startMs
    log.info(
      "[CHECKPOINT IMPORT] complete: block={} nodes={} ({} MiB) bytecodes={} ({} MiB) elapsed={}s. " +
        "RegularSync will resume from {}.",
      blockNum,
      totalNodes,
      nodeBytes / (1024 * 1024),
      totalBytecodes,
      codeBytes / (1024 * 1024),
      elapsed / 1000,
      blockNum + 1
    )

    Right(ImportResult(blockNum, totalNodes, totalBytecodes, elapsed))
  }

  private def hex8(bs: ByteString): String =
    bs.take(8).toArray.map("%02x".format(_)).mkString
}

object CheckpointImporter {
  // 10k * ~500 B per node ≈ 5 MiB per write batch — comfortable for RocksDB's default write buffer.
  val NodeBatchSize: Int = 10000
  val BytecodeBatchSize: Int = 1000
  val LogInterval: Long = 100000L

  sealed trait ImportError extends Product with Serializable
  final case class BadFormat(err: CheckpointArchive.DecodeError) extends ImportError
  final case class ChainIdMismatch(expected: Long, found: Long) extends ImportError

  final case class ImportResult(
      blockNumber: BigInt,
      nodesImported: Long,
      bytecodesImported: Long,
      elapsedMs: Long
  )
}
