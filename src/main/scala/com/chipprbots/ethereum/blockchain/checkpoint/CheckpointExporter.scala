package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.mpt.NibblePath
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
  *
  * '''Every node is read at its position''' (spec 018 Track C): the account trie under no owner, a storage trie under
  * its account, the path the walk has come down. The state is the block's, from the seam every reader takes it from
  * (`BlockchainReader.stateAt`), which with the unified layout off is the read-only store, unwrapping per-chain node
  * wrapping as before (Bug 33). The archive is format v1 still, a list of nodes by hash: each distinct node is written
  * once, and a storage trie two accounts hold is read under the first of them. '''An export is not an audit''': on a
  * position store the second account's rows are not looked at.
  */
final class CheckpointExporter(
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
      // Also before the file is opened: a state that is not held leaves no partial archive behind either.
      state <- stateOf(header)
      result <- writeArchive(blockNumber, header, weight, state, output, gzip)
    yield result

  /** The state of the block, to be read and not written. */
  private def stateOf(header: BlockHeader): Either[ExportError, MptStorage] =
    try Right(blockchainReader.stateAt(header))
    catch case fault: StateFault => Left(StateNotHeld(header.number.value, fault.getMessage))

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
      mpt: MptStorage,
      output: Path,
      gzip: Boolean
  ): Either[ExportError, ExportResult] =
    val start = System.currentTimeMillis()
    val raw = new FileOutputStream(output.toFile)
    val out =
      if gzip then new GZIPOutputStream(new BufferedOutputStream(raw, 65536), 65536)
      else new BufferedOutputStream(raw, 65536)
    val writer = new CheckpointArchive.Writer(out)
    val visited = new java.util.HashSet[ByteString]
    val codeHashes = new java.util.HashSet[ByteString]
    // each storage trie to walk: the account that holds it, and its root
    val storageRoots = new scala.collection.mutable.Queue[(ByteString, ByteString)]
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
        blockNumber = blockNumber,
        rootHash = header.stateRoot.value,
        owner = None,
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
        val (account, sroot) = storageRoots.dequeue()
        if sroot != Account.EmptyStorageRootHash.value then
          // Where an account's hash finds its storage trie, a key that is not 32 bytes finds none: the account trie
          // is corrupt, and that is said, not thrown from the store.
          (if !mpt.nodesSharedAcrossTries && account.size != 32 then Left(CorruptAccountKey(account))
           else
             walkTrie(
               blockNumber = blockNumber,
               rootHash = sroot,
               owner = Some(account),
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

  /** Walks the trie of `owner` (no owner: the account trie) from `rootHash`, writing every node not written yet.
    *
    * `visited` is of the whole export, by hash: a node another trie has already written is neither written nor read
    * again, which is right for an archive of nodes by hash.
    */
  private def walkTrie(
      blockNumber: BigInt,
      rootHash: ByteString,
      owner: Option[ByteString],
      mpt: MptStorage,
      writer: CheckpointArchive.Writer,
      visited: java.util.HashSet[ByteString],
      codeHashes: java.util.HashSet[ByteString],
      storageRoots: scala.collection.mutable.Queue[(ByteString, ByteString)],
      onNodeEmitted: () => Unit
  ): Either[ExportError, Unit] =
    val isMainTrie = owner.isEmpty
    val nodes = mpt.forOwner(owner)
    // each node still to read: its hash, and the nibbles from this trie's root to it
    val stack = new scala.collection.mutable.Stack[(ByteString, Array[Byte])]
    stack.push((rootHash, Array.emptyByteArray))
    var walkError: Option[ExportError] = None
    while stack.nonEmpty && walkError.isEmpty do
      val (h, path) = stack.pop()
      if visited.add(h) then
        val nodeOpt =
          try Some(nodes.getAt(NibblePath.fromNibbles(path), h.toArray))
          catch
            case _: MerklePatriciaTrie.MissingNodeException => walkError = Some(MissingTrieNode(h, isMainTrie)); None
            // The disk moved away from the state being exported, or no longer holds it: not a node that is missing.
            case fault: StateFault => walkError = Some(StateNotHeld(blockNumber, fault.getMessage)); None
        nodeOpt.foreach { decoded =>
          // The MptStorage path decodes once and caches the raw RLP it parsed. Prefer that —
          // it round-trips byte-identically and matches the content-addressed hash. Fall back
          // to re-encoding for the rare case a node lacks a cached RLP (in-memory test fixtures).
          val rlp = decoded.cachedRlpEncoded.getOrElse(MptTraversals.encodeNode(decoded))
          writer.writeNode(h, rlp)
          onNodeEmitted()
          collectChildren(decoded, path, isMainTrie, stack, codeHashes, storageRoots)
        }
    walkError.toLeft(())

  /** Walk a decoded node's tree pulling out: hash references to push onto the work stack; account info from leaves of
    * the main trie.
    */
  private def collectChildren(
      node: MptNode,
      path: Array[Byte],
      isMainTrie: Boolean,
      stack: scala.collection.mutable.Stack[(ByteString, Array[Byte])],
      codeHashes: java.util.HashSet[ByteString],
      storageRoots: scala.collection.mutable.Queue[(ByteString, ByteString)]
  ): Unit = node match
    case LeafNode(key, value, _, _, _) if isMainTrie =>
      Account(value) match
        case scala.util.Success(acct) =>
          if acct.storageRoot != Account.EmptyStorageRootHash then
            storageRoots += accountAt(path ++ key.toArray) -> acct.storageRoot.value
          if acct.codeHash != Account.EmptyCodeHash then codeHashes.add(acct.codeHash.value)
        case scala.util.Failure(_) =>
    // Storage-only or malformed leaf — best-effort; bytecodes still resolved per-trie.
    case _: LeafNode =>
    // Storage slot leaf; no further traversal
    // A child inside its parent (a node under 32 bytes) is not read, but the path still runs through it.
    case ExtensionNode(sharedKey, next, _, _, _) =>
      collectChildren(next, path ++ sharedKey.toArray, isMainTrie, stack, codeHashes, storageRoots)
    case BranchNode(children, _, _, _, _) =>
      var i = 0
      while i < children.length do
        collectChildren(children(i), path :+ i.toByte, isMainTrie, stack, codeHashes, storageRoots)
        i += 1
    case HashNode(hash) =>
      stack.push((ByteString(hash), path))
    case NullNode => ()

  /** The account a leaf of the account trie is, by the nibbles of its key (64 of them, in any state). */
  private def accountAt(nibbles: Array[Byte]): ByteString =
    if nibbles.length % 2 == 0 then ByteString(HexPrefix.nibblesToBytes(nibbles)) else ByteString(nibbles)

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

  /** The state of the block could not be read, or not to the end: it is not the state the disk holds, or the disk moved
    * on while the export read it (the unified layout holds one state, and import moves it). Not a missing node: the
    * state may be whole. Export a block the node holds, with import at rest.
    */
  final case class StateNotHeld(blockNumber: BigInt, detail: String) extends ExportError

  /** An account of the state sits under a key that is not 32 bytes, with storage: the account trie is corrupt. Only on
    * a store where the account's hash is what finds its storage trie; a hash store reads the trie by its root.
    */
  final case class CorruptAccountKey(key: ByteString) extends ExportError

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
