package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

import scala.annotation.tailrec

import org.apache.pekko.util.ByteString

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NullNode

/** Produces a `.checkpoint` archive for a specified block by walking the state trie. The resulting file is consumable
  * by [[CheckpointImporter]].
  *
  * Algorithm:
  *
  *   1. Resolve the block header + chain weight from `BlockchainReader`. Refuse if either is missing.
  *   1. Recompute the block's total difficulty from the headers themselves (see [[exactTotalDifficulty]]) and write
  *      THAT into the archive, not the stored chain weight. A SNAP-synced node stores an ESTIMATED weight at its pivot
  *      (a peer interpolation, `PivotSelector.updateBestBlockForPivot`) and every block above inherits the error; an
  *      archive would freeze it and hand it to every node that imports it, which has no headers below the checkpoint to
  *      correct it with. If an ancestor header is missing the export is refused (`IncompleteHeaderChain`) unless the
  *      caller passes `allowEstimatedTd`, which falls back to the stored weight with a warning.
  *   1. DFS the account trie from `header.stateRoot`. Emit every raw node we visit. For each leaf, decode the account
  *      and remember its `storageRoot` and `codeHash`.
  *   1. DFS each unique non-empty `storageRoot`. Emit every raw node we visit.
  *   1. Emit each unique non-empty `codeHash` looked up from `evmCodeStorage`.
  *   1. Close the archive with the CRC32 trailer.
  *
  * Read-only — does not mutate any storage. The streaming design keeps RAM bounded to ~O(unique hashes seen), not
  * O(trie size).
  */
final class CheckpointExporter(
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    blockchainReader: BlockchainReader,
    chainId: BigInt
):
  import CheckpointExporter.*
  private val log = LoggerFactory.getLogger(getClass)

  def exportArchive(
      blockNumber: BigInt,
      output: Path,
      gzip: Boolean = false,
      allowEstimatedTd: Boolean = false
  ): Either[ExportError, ExportResult] =
    for
      header <- blockchainReader.getBlockHeaderByNumber(blockNumber).toRight(NoSuchBlock(blockNumber))
      storedWeight <- blockchainReader.getChainWeightByHash(header.hash).toRight(NoChainWeight(blockNumber))
      // Resolved before the output file is opened, so a refusal leaves no partial archive behind.
      weight <- archiveWeight(header, storedWeight, allowEstimatedTd)
      result <- writeArchive(blockNumber, header, weight, output, gzip)
    yield result

  /** The total difficulty to write into the archive: the exact one when the header chain down to genesis is stored,
    * otherwise the stored weight if `allowEstimatedTd`, otherwise a refusal.
    */
  private def archiveWeight(
      header: BlockHeader,
      storedWeight: ChainWeight,
      allowEstimatedTd: Boolean
  ): Either[ExportError, ChainWeight] =
    exactTotalDifficulty(header) match
      case Right(exactTd) =>
        val stored = storedWeight.totalDifficulty.value
        if exactTd != stored then
          log.warn(
            "[CHECKPOINT EXPORT] stored total difficulty at block {} is not the chain's: stored={} exact={} " +
              "(delta={}). The archive carries the exact value; the stored one is an estimate (SNAP pivot or " +
              "calibration).",
            header.number,
            stored,
            exactTd,
            exactTd - stored
          )
        else
          log.info("[CHECKPOINT EXPORT] total difficulty at block {} verified from genesis: {}", header.number, exactTd)
        Right(ChainWeight.totalDifficultyOnly(exactTd))
      case Left(err) if allowEstimatedTd =>
        log.warn(
          "[CHECKPOINT EXPORT] cannot verify total difficulty at block {} ({}); --allow-estimated-td set, writing the " +
            "STORED weight {}, which may be an estimate. Every node importing this archive inherits it.",
          header.number,
          err,
          storedWeight.totalDifficulty.value
        )
        Right(storedWeight)
      case Left(err) =>
        log.error(
          "[CHECKPOINT EXPORT] refusing to export block {}: {}. Its total difficulty cannot be computed from genesis, " +
            "and the stored weight may be an estimate. Wait for chain backfill to complete, or pass " +
            "--allow-estimated-td to export the stored weight anyway.",
          header.number,
          err
        )
        Left(err)

  /** Sum of `difficulty` over `header` and every ancestor down to genesis, following `parentHash` (not the number→hash
    * index, which a SNAP-synced node lacks below its pivot). The genesis term is the genesis header's difficulty, which
    * is the weight `GenesisDataLoader` stores for it (core-geth: `WriteTd(genesis, difficulty)`). Left when an ancestor
    * header is not stored or the walk reaches a block 0 that is not this chain's genesis.
    */
  private[checkpoint] def exactTotalDifficulty(header: BlockHeader): Either[ExportError, BigInt] =
    val genesisHash = blockchainReader.genesisHeader.hash

    @tailrec
    def walk(cur: BlockHeader, acc: BigInt, walked: Long): Either[ExportError, BigInt] =
      val td = acc + cur.difficulty.value
      if cur.number.value == 0 then Either.cond(cur.hash == genesisHash, td, GenesisMismatch(cur.hash.value))
      else
        if walked > 0 && walked % TdWalkLogInterval == 0 then
          log.info("[CHECKPOINT EXPORT] summing total difficulty: at block {} ({} headers walked)", cur.number, walked)
        blockchainReader.getBlockHeaderByHash(cur.parentHash) match
          case Some(parent) if parent.number.value == cur.number.value - 1 => walk(parent, td, walked + 1)
          case _                                                           =>
            // Absent, or (corrupt store) a header whose number does not continue the chain.
            Left(IncompleteHeaderChain(cur.number.value - 1, cur.parentHash.value))

    walk(header, BigInt(0), 0L)

  private def writeArchive(
      blockNumber: BigInt,
      header: BlockHeader,
      weight: ChainWeight,
      output: Path,
      gzip: Boolean
  ): Either[ExportError, ExportResult] =
    val start = System.currentTimeMillis()
    // Read through the StateStorage abstraction so per-chain wrapping (e.g.,
    // ReferenceCountNodeStorage on BasicPruning chains) is unwrapped automatically.
    // Using raw NodeStorage would surface ref-counted bytes here — see Bug 33.
    val mpt = stateStorage.getReadOnlyStorage
    val raw = new FileOutputStream(output.toFile)
    val out =
      if gzip then new GZIPOutputStream(new BufferedOutputStream(raw, 65536), 65536)
      else new BufferedOutputStream(raw, 65536)
    val writer = new CheckpointArchive.Writer(out)
    val visited = new java.util.HashSet[ByteString]
    val codeHashes = new java.util.HashSet[ByteString]
    val storageRoots = new scala.collection.mutable.Queue[ByteString]
    var nodesEmitted = 0L
    var bytecodesEmitted = 0L
    var exportError: Option[ExportError] = None

    try
      writer.writeHeader(
        CheckpointArchive.Header(
          chainId = chainId.toLong,
          blockHeader = header,
          chainWeight = weight
        )
      )

      log.info(
        "[CHECKPOINT EXPORT] block={} stateRoot={} writing to {}",
        blockNumber,
        hex8(header.stateRoot.value),
        output
      )

      def abortLog(err: ExportError): Unit =
        log.error(
          "[CHECKPOINT EXPORT] ABORTED after nodes={} bytecodes={}: {}. " +
            "Archive at {} is incomplete (missing end-of-stream marker + CRC) and unusable.",
          nodesEmitted,
          bytecodesEmitted,
          err,
          output
        )

      // Phase 1: account trie
      walkTrie(
        rootHash = header.stateRoot.value,
        isMainTrie = true,
        mpt = mpt,
        writer = writer,
        visited = visited,
        codeHashes = codeHashes,
        storageRoots = storageRoots,
        onNodeEmitted = () =>
          nodesEmitted += 1
          if nodesEmitted % LogInterval == 0 then
            log.info(
              "[CHECKPOINT EXPORT] nodes={} bytecodes={} storageQueue={}",
              nodesEmitted,
              bytecodesEmitted,
              storageRoots.size
            )
      ) match
        case Right(_) => ()
        case Left(err) =>
          abortLog(err)
          exportError = Some(err)

      // Phase 2: per-account storage tries
      while storageRoots.nonEmpty && exportError.isEmpty do
        val sroot = storageRoots.dequeue()
        if sroot != Account.EmptyStorageRootHash.value then
          walkTrie(
            rootHash = sroot,
            isMainTrie = false,
            mpt = mpt,
            writer = writer,
            visited = visited,
            codeHashes = codeHashes,
            storageRoots = storageRoots,
            onNodeEmitted = () =>
              nodesEmitted += 1
              if nodesEmitted % LogInterval == 0 then
                log.info(
                  "[CHECKPOINT EXPORT] nodes={} bytecodes={} storageQueue={}",
                  nodesEmitted,
                  bytecodesEmitted,
                  storageRoots.size
                )
          ) match
            case Right(_) => ()
            case Left(err) =>
              abortLog(err)
              exportError = Some(err)

      // Phase 3: bytecodes
      val it = codeHashes.iterator()
      while it.hasNext && exportError.isEmpty do
        val ch = it.next()
        if ch != Account.EmptyCodeHash.value then
          evmCodeStorage.get(ch) match
            case Some(code) =>
              writer.writeBytecode(ch, code.toArray)
              bytecodesEmitted += 1
            case None =>
              val err = MissingBytecode(ch)
              abortLog(err)
              exportError = Some(err)

      if exportError.isEmpty then writer.finish()
    finally
      try out.close()
      catch case _: Throwable => ()

    exportError match
      case Some(err) => Left(err)
      case None =>
        val elapsed = System.currentTimeMillis() - start
        log.info(
          "[CHECKPOINT EXPORT] complete: block={} nodes={} bytecodes={} elapsed={}s output={}",
          blockNumber,
          nodesEmitted,
          bytecodesEmitted,
          elapsed / 1000,
          output
        )
        Right(ExportResult(blockNumber, nodesEmitted, bytecodesEmitted, elapsed))

  private def walkTrie(
      rootHash: ByteString,
      isMainTrie: Boolean,
      mpt: MptStorage,
      writer: CheckpointArchive.Writer,
      visited: java.util.HashSet[ByteString],
      codeHashes: java.util.HashSet[ByteString],
      storageRoots: scala.collection.mutable.Queue[ByteString],
      onNodeEmitted: () => Unit
  ): Either[ExportError, Unit] =
    val stack = new scala.collection.mutable.Stack[ByteString]
    stack.push(rootHash)
    var walkError: Option[ExportError] = None
    while stack.nonEmpty && walkError.isEmpty do
      val h = stack.pop()
      if visited.add(h) then
        val nodeOpt =
          try Some(mpt.get(h.toArray))
          catch
            case _: MerklePatriciaTrie.MissingNodeException => walkError = Some(MissingTrieNode(h, isMainTrie)); None
        nodeOpt.foreach { decoded =>
          // The MptStorage path decodes once and caches the raw RLP it parsed. Prefer that —
          // it round-trips byte-identically and matches the content-addressed hash. Fall back
          // to re-encoding for the rare case a node lacks a cached RLP (in-memory test fixtures).
          val rlp = decoded.cachedRlpEncoded.getOrElse(MptTraversals.encodeNode(decoded))
          writer.writeNode(h, rlp)
          onNodeEmitted()
          collectChildren(decoded, isMainTrie, stack, codeHashes, storageRoots)
        }
    walkError.toLeft(())

  /** Walk a decoded node's tree pulling out: hash references to push onto the work stack; account info from leaves of
    * the main trie.
    */
  private def collectChildren(
      node: MptNode,
      isMainTrie: Boolean,
      stack: scala.collection.mutable.Stack[ByteString],
      codeHashes: java.util.HashSet[ByteString],
      storageRoots: scala.collection.mutable.Queue[ByteString]
  ): Unit = node match
    case LeafNode(_, value, _, _, _) if isMainTrie =>
      Account(value) match
        case scala.util.Success(acct) =>
          if acct.storageRoot != Account.EmptyStorageRootHash then storageRoots += acct.storageRoot.value
          if acct.codeHash != Account.EmptyCodeHash then codeHashes.add(acct.codeHash.value)
        case scala.util.Failure(_) =>
    // Storage-only or malformed leaf — best-effort; bytecodes still resolved per-trie.
    case _: LeafNode =>
    // Storage slot leaf; no further traversal
    case ExtensionNode(_, next, _, _, _) =>
      collectChildren(next, isMainTrie, stack, codeHashes, storageRoots)
    case BranchNode(children, _, _, _, _) =>
      var i = 0
      while i < children.length do
        collectChildren(children(i), isMainTrie, stack, codeHashes, storageRoots)
        i += 1
    case HashNode(hash) =>
      stack.push(ByteString(hash))
    case NullNode => ()

  private def hex8(bs: ByteString): String =
    bs.take(8).toArray.map("%02x".format(_)).mkString

object CheckpointExporter:
  val LogInterval: Long = 100000L
  val TdWalkLogInterval: Long = 1000000L

  sealed trait ExportError extends Product with Serializable
  final case class NoSuchBlock(blockNumber: BigInt) extends ExportError
  final case class NoChainWeight(blockNumber: BigInt) extends ExportError
  final case class MissingTrieNode(hash: ByteString, isMainTrie: Boolean) extends ExportError
  final case class MissingBytecode(hash: ByteString) extends ExportError

  /** An ancestor header (`number`, `hash`) of the export block is not stored, so its exact total difficulty cannot be
    * summed from genesis — typically a SNAP-synced node whose chain backfill has not finished.
    */
  final case class IncompleteHeaderChain(number: BigInt, hash: ByteString) extends ExportError:
    override def toString: String =
      s"IncompleteHeaderChain(missing header $number ${hash.take(8).toArray.map("%02x".format(_)).mkString}...)"

  /** The parentHash walk reached a block 0 that is not this chain's genesis. */
  final case class GenesisMismatch(hash: ByteString) extends ExportError

  final case class ExportResult(
      blockNumber: BigInt,
      nodesExported: Long,
      bytecodesExported: Long,
      elapsedMs: Long
  )
