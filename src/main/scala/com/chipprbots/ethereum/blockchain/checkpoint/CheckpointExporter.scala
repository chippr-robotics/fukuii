package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.storage.AncestorWindow
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.FinalizedBlock
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockBody
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
import com.chipprbots.ethereum.mpt.Node
import com.chipprbots.ethereum.mpt.NullNode

/** Produces a `.checkpoint` archive of format version 2 ([[CheckpointArchive]], spec 018 data-model E.1) for one block.
  * The resulting file is consumable by [[CheckpointImporter]].
  *
  * Every refusal below comes before the output file is opened. One found while the state is read (a missing node or
  * code, a corrupt account, the state moving away) leaves a file with no end tag and no CRC, which no reader accepts.
  *
  *   1. The block `C`, by number, is at least 1 (the genesis is no checkpoint), and the chain id fits a u64.
  *   1. On a proof-of-stake chain (one with a terminal total difficulty): the finalized block is canonical (its number
  *      and the canonical hash at that number match the stored finalized hash), `C` is at or below it, and, unless the
  *      chain was merged at genesis (TTD at most the genesis difficulty), after the merge (difficulty 0). A checkpoint
  *      below the merge would need ommer bodies, which a PoS archive never carries (E.1, settled at the PR1 sign-off).
  *   1. The total difficulty: summed from genesis over the stored headers ([[exactTotalDifficulty]]); else the stored
  *      weight where the node holds the chain-weight invariant; else, with `allowEstimatedTd`, the stored weight with a
  *      warning; else a refusal. The TD-exact flag is set when the sum from genesis succeeded or the invariant holds
  *      (E.1, amended at the PR1 sign-off).
  *   1. On a proof-of-stake chain, the importer's TD rule: TD at least the TTD on a post-merge `C`, and TD equal to the
  *      genesis difficulty on a chain merged at genesis ([[TotalDifficultyRefusedByMerge]]). Checked after the TD is
  *      resolved, so no flag (`allowEstimatedTd` included) produces an archive every importer refuses.
  *   1. The ancestor window: the `min(256, N)` headers below `C`, each found by the hash its child names, the last one
  *      the genesis when `N < 256`; on a proof-of-work chain, the bodies of `C` down to `C-4` whose headers name ommers
  *      (B-302, [[AncestorWindow]]).
  *   1. The state, read through `BlockchainReader.stateAt`: a state not held is `StateNotHeld`.
  *
  * The nodes are a pre-order walk of the whole state that writes '''every occurrence''': a storage trie two accounts
  * hold is written under each of them, directly after its account's leaf. There is no `visited` set, so the walk's
  * memory is its stack, O(depth x 16) per trie. Code hashes are collected as the walk meets them, then the codes are
  * written ascending by hash, each once.
  *
  * Read-only: does not mutate any storage.
  *
  * @param finalizedBlock
  *   the finalized block (number and hash), read on a proof-of-stake chain only; `None` refuses the export there
  */
final class CheckpointExporter(
    evmCodeStorage: EvmCodeStorage,
    blockchainReader: BlockchainReader,
    chainId: BigInt,
    terminalTotalDifficulty: Option[BigInt],
    finalizedBlock: () => Option[FinalizedBlock] = () => None
):
  import CheckpointExporter.*
  private val log = LoggerFactory.getLogger(getClass)

  /** Whether the blocks above a checkpoint can carry ommers: a chain with no terminal total difficulty. */
  private val proofOfWork: Boolean = terminalTotalDifficulty.isEmpty

  def exportArchive(
      blockNumber: BigInt,
      output: Path,
      gzip: Boolean = false,
      allowEstimatedTd: Boolean = false
  ): Either[ExportError, ExportResult] =
    for
      header <- blockchainReader.getBlockHeaderByNumber(blockNumber).toRight(NoSuchBlock(blockNumber))
      _ <- Either.cond(blockNumber >= 1, (), GenesisNotExportable)
      chainIdBits <- CheckpointArchive.Header.chainIdBits(chainId).toRight(ChainIdOutOfRange(chainId))
      _ <- proofOfStakeRules(header)
      storedWeight <- blockchainReader.getChainWeightByHash(header.hash).toRight(NoChainWeight(blockNumber))
      // Resolved before the output file is opened, so a refusal leaves no partial archive behind.
      resolved <- archiveWeight(header, storedWeight, allowEstimatedTd)
      (weight, tdExact) = resolved
      _ <- mergeTotalDifficultyRule(header, weight.totalDifficulty.value)
      window <- ancestorWindow(header)
      // Also before the file is opened: a state that is not held leaves no partial archive behind either.
      state <- stateOf(header)
      archiveHeader = CheckpointArchive.Header(
        chainId = chainIdBits,
        genesisHash = blockchainReader.genesisHeader.hash.value,
        tdExact = tdExact,
        blockHeader = header,
        totalDifficulty = weight.totalDifficulty.value
      )
      result <- writeArchive(archiveHeader, window, state, output, gzip)
    yield result

  /** On a proof-of-stake chain: `header` is at or below a canonical finalized block, and after the merge unless the
    * chain was merged at genesis.
    */
  private def proofOfStakeRules(header: BlockHeader): Either[ExportError, Unit] =
    terminalTotalDifficulty match
      case None => Right(())
      case Some(ttd) =>
        val number = header.number.value
        for
          finalized <- canonicalFinalized(blockchainReader, finalizedBlock(), number)
          _ <- Either.cond(number <= finalized, (), AboveFinalized(number, finalized))
          _ <- Either.cond(mergedAtGenesis(ttd) || header.difficulty.value == 0, (), BeforeTheMerge(number))
        yield ()

  /** "Merged at genesis" (E.1): the terminal total difficulty is at most the genesis difficulty. */
  private def mergedAtGenesis(ttd: BigInt): Boolean = ttd <= blockchainReader.genesisHeader.difficulty.value

  /** On a proof-of-stake chain, the TD rule PR2's importer runs before any write (E.1): on a post-merge header
    * (difficulty 0) TD >= TTD, and on a chain merged at genesis TD = the genesis difficulty.
    */
  private def mergeTotalDifficultyRule(header: BlockHeader, td: BigInt): Either[ExportError, Unit] =
    terminalTotalDifficulty match
      case None => Right(())
      case Some(ttd) =>
        val genesisDifficulty = blockchainReader.genesisHeader.difficulty.value
        val postMergeOk = header.difficulty.value != 0 || td >= ttd
        val genesisOk = !mergedAtGenesis(ttd) || td == genesisDifficulty
        Either.cond(
          postMergeOk && genesisOk,
          (),
          TotalDifficultyRefusedByMerge(header.number.value, td, ttd, genesisDifficulty)
        )

  /** The headers below `header`, child first, and on a proof-of-work chain the bodies the window carries. */
  private def ancestorWindow(header: BlockHeader): Either[ExportError, Window] =
    val depth = CheckpointArchive.windowHeaderCount(header.number.value)
    val genesisHash = blockchainReader.genesisHeader.hash

    @tailrec
    def down(child: BlockHeader, acc: Vector[BlockHeader]): Either[ExportError, Vector[BlockHeader]] =
      if acc.size == depth then
        // A window that reaches block 0 ends at this chain's genesis.
        acc.lastOption.filter(last => last.number.value == 0 && last.hash != genesisHash) match
          case Some(other) => Left(GenesisMismatch(other.hash.value))
          case None        => Right(acc)
      else
        val wanted = child.number.value - 1
        blockchainReader.getBlockHeaderByHash(child.parentHash) match
          case Some(parent) if parent.number.value == wanted => down(parent, acc :+ parent)
          case _ => Left(MissingWindowHeader(wanted, child.parentHash.value))

    // `C` and the four below it whose headers name ommers, highest first: the set `AncestorWindow.check` reads.
    def bodies(headers: Vector[BlockHeader]): Either[ExportError, Vector[(BlockHeader, BlockBody)]] =
      val read =
        if proofOfWork then (header +: headers).take(AncestorWindow.OmmerBodies).filter(AncestorWindow.bodyRead)
        else Vector.empty
      read.foldLeft[Either[ExportError, Vector[(BlockHeader, BlockBody)]]](Right(Vector.empty)) { (acc, h) =>
        acc.flatMap(found =>
          blockchainReader
            .getBlockBodyByHash(h.hash)
            .toRight(MissingWindowBody(h.number.value, h.hash.value))
            .map(body => found :+ (h -> body))
        )
      }

    for
      headers <- down(header, Vector.empty)
      withBodies <- bodies(headers)
    yield Window(headers, withBodies)

  /** The state of the block, to be read and not written. */
  private def stateOf(header: BlockHeader): Either[ExportError, MptStorage] =
    try Right(blockchainReader.stateAt(header))
    catch case fault: StateFault => Left(StateNotHeld(header.number.value, fault.getMessage))

  /** The total difficulty to write into the archive, and whether it is exact: the sum from genesis when the header
    * chain down to genesis is stored (exact); otherwise the stored weight where the node holds the chain-weight
    * invariant (exact), or if `allowEstimatedTd` (an estimate); otherwise a refusal.
    */
  private def archiveWeight(
      header: BlockHeader,
      storedWeight: ChainWeight,
      allowEstimatedTd: Boolean
  ): Either[ExportError, (ChainWeight, Boolean)] =
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
        Right(ChainWeight.totalDifficultyOnly(exactTd) -> true)
      case Left(err) if blockchainReader.hasChainWeightInvariant =>
        // Every stored weight follows from its parent's down to genesis, so the stored one is exact.
        log.info(
          "[CHECKPOINT EXPORT] cannot sum total difficulty at block {} ({}); the node holds the chain-weight " +
            "invariant, so the stored weight {} is exact",
          header.number,
          err,
          storedWeight.totalDifficulty.value
        )
        Right(storedWeight -> true)
      case Left(err) if allowEstimatedTd =>
        log.warn(
          "[CHECKPOINT EXPORT] cannot verify total difficulty at block {} ({}); --allow-estimated-td set, writing the " +
            "STORED weight {}, which may be an estimate. Every node importing this archive inherits it.",
          header.number,
          err,
          storedWeight.totalDifficulty.value
        )
        Right(storedWeight -> false)
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
      archiveHeader: CheckpointArchive.Header,
      window: Window,
      mpt: MptStorage,
      output: Path,
      gzip: Boolean
  ): Either[ExportError, ExportResult] =
    val header = archiveHeader.blockHeader
    val blockNumber = header.number.value
    val start = System.currentTimeMillis()
    val raw = new FileOutputStream(output.toFile)
    val out =
      if gzip then new GZIPOutputStream(new BufferedOutputStream(raw, 65536), 65536)
      else new BufferedOutputStream(raw, 65536)
    val writer = new CheckpointArchive.Writer(out)
    val walk = new StateWalk(blockNumber, mpt, writer)
    var bytecodesEmitted = 0L

    val outcome: Either[ExportError, Unit] =
      try
        writer.writeHeader(archiveHeader)
        window.headers.foreach(writer.writeWindowHeader)
        window.bodies.foreach((h, body) => writer.writeWindowBody(h.hash.value, body))
        log.info(
          "[CHECKPOINT EXPORT] block={} stateRoot={} window={} headers and {} bodies, tdExact={}: writing to {}",
          blockNumber,
          hex8(header.stateRoot.value),
          window.headers.size,
          window.bodies.size,
          archiveHeader.tdExact,
          output
        )
        val written =
          for
            _ <- walk.run(header.stateRoot.value)
            _ <- writeCodes(walk.codeHashes, writer, () => bytecodesEmitted += 1)
          yield ()
        written.foreach(_ => writer.finish())
        written
      finally
        try out.close()
        catch case _: Throwable => ()

    outcome match
      case Left(err) =>
        log.error(
          "[CHECKPOINT EXPORT] ABORTED after nodes={} bytecodes={}: {}. " +
            "Archive at {} is incomplete (missing end tag + CRC) and unusable.",
          walk.nodesEmitted,
          bytecodesEmitted,
          err,
          output
        )
        Left(err)
      case Right(_) =>
        val elapsed = System.currentTimeMillis() - start
        log.info(
          "[CHECKPOINT EXPORT] complete: block={} nodes={} bytecodes={} elapsed={}s output={}",
          blockNumber,
          walk.nodesEmitted,
          bytecodesEmitted,
          elapsed / 1000,
          output
        )
        Right(ExportResult(blockNumber, walk.nodesEmitted, bytecodesEmitted, elapsed))

  /** The codes, ascending by hash (the set is ordered so), each once. An entry carries no hash, so a code that is not
    * the code its hash names is refused, as a node refuses it, and not written under a hash it does not have.
    */
  private def writeCodes(
      codeHashes: java.util.TreeSet[ByteString],
      writer: CheckpointArchive.Writer,
      onWritten: () => Unit
  ): Either[ExportError, Unit] =
    val it = codeHashes.iterator()
    var refused: Option[ExportError] = None
    while it.hasNext && refused.isEmpty do
      val hash = it.next()
      evmCodeStorage.get(hash) match
        case Some(code) if crypto.kec256(code) == hash =>
          writer.writeCode(code.toArray)
          onWritten()
        case Some(_) => refused = Some(CorruptBytecode(hash))
        case None    => refused = Some(MissingBytecode(hash))
    refused.toLeft(())

  /** The pre-order walk of one state that writes every occurrence of every stored node (E.1 "Nodes").
    *
    * The stack holds frames `(owner, path, hash)`, as the importer's does: the account trie under no owner, a storage
    * trie under its account's 32-byte key. A node read is written, then its hashed children are pushed in reverse order
    * so that child 0 is next, and an account leaf's storage root is pushed last, so that the storage trie comes
    * directly after the leaf. A node under 32 bytes is inside its parent and is never a frame.
    */
  final private class StateWalk(blockNumber: BigInt, mpt: MptStorage, writer: CheckpointArchive.Writer):
    val codeHashes = new java.util.TreeSet[ByteString](CheckpointArchive.unsignedOrder)
    var nodesEmitted = 0L
    private val stack = new java.util.ArrayDeque[Frame]

    def run(stateRoot: ByteString): Either[ExportError, Unit] =
      // The empty trie has no node: an empty state writes none.
      if stateRoot != EmptyRoot then stack.push(Frame(None, Array.emptyByteArray, stateRoot))
      var error: Option[ExportError] = None
      while !stack.isEmpty && error.isEmpty do error = visit(stack.pop()).left.toOption
      error.toLeft(())

    private def visit(frame: Frame): Either[ExportError, Unit] =
      val isMainTrie = frame.owner.isEmpty
      val read: Either[ExportError, MptNode] =
        try Right(mpt.forOwner(frame.owner).getAt(NibblePath.fromNibbles(frame.path), frame.hash.toArray))
        catch
          // Both stores report an entry that is not the node its hash names as a CorruptNodeException, which extends
          // MissingNodeException so that execution fetches it again. An export cannot fetch: it says corrupt, so the
          // operator repairs the store and does not wait for a node that a healing pass would treat as present.
          case _: MerklePatriciaTrie.CorruptNodeException => Left(CorruptTrieNode(frame.hash))
          case _: MerklePatriciaTrie.MissingNodeException => Left(MissingTrieNode(frame.hash, isMainTrie))
          // The disk moved away from the state being exported, or no longer holds it: not a node that is missing.
          case fault: StateFault => Left(StateNotHeld(blockNumber, fault.getMessage))
      for
        node <- read
        // The MptStorage path decodes once and caches the raw RLP it parsed; it round-trips byte-identically.
        rlp = node.cachedRlpEncoded.getOrElse(MptTraversals.encodeNode(node))
        // The importer checks each entry against the hash its parent names: an export that would fail it is refused.
        _ <- Either.cond(Node.hashFn(rlp).sameElements(frame.hash.toArray), (), CorruptTrieNode(frame.hash))
        below <- children(node, frame.path, frame.owner, stored = true)
      yield
        writer.writeNode(rlp)
        nodesEmitted += 1
        if nodesEmitted % LogInterval == 0 then
          log.info("[CHECKPOINT EXPORT] nodes={} stack={} codes={}", nodesEmitted, stack.size, codeHashes.size)
        below.reverseIterator.foreach(stack.push)

    /** The frames below `node`, in walk order. `stored` is false for a node inside its parent. */
    private def children(
        node: MptNode,
        path: Array[Byte],
        owner: Option[ByteString],
        stored: Boolean
    ): Either[ExportError, List[Frame]] = node match
      case HashNode(hash) => Right(List(Frame(owner, path, ByteString(hash))))
      case NullNode       => Right(Nil)
      case LeafNode(key, value, _, _, _) if owner.isEmpty =>
        val fullKey = path ++ key.toArray
        val accountKey = accountAt(fullKey)
        for
          // A storage trie's owner is its account leaf's 64-nibble key, packed, and not keccak of it (E.1).
          _ <- Either.cond(fullKey.length == AccountKeyNibbles, (), CorruptAccountKey(accountKey))
          // An account's RLP is at least 68 bytes, so its leaf is always stored; the importer takes the owner from it.
          _ <- Either.cond(stored, (), CorruptAccountLeaf(accountKey))
          account <- Account(value).toEither.left.map(_ => CorruptAccountLeaf(accountKey))
        yield
          if account.codeHash != Account.EmptyCodeHash then codeHashes.add(account.codeHash.value)
          if account.storageRoot != Account.EmptyStorageRootHash then
            List(Frame(Some(accountKey), Array.emptyByteArray, account.storageRoot.value))
          else Nil
      case _: LeafNode => Right(Nil)
      case ExtensionNode(sharedKey, next, _, _, _) =>
        children(next, path ++ sharedKey.toArray, owner, stored = false)
      case BranchNode(branches, _, _, _, _) =>
        branches.zipWithIndex.foldLeft[Either[ExportError, List[Frame]]](Right(Nil)) { case (acc, (child, i)) =>
          acc.flatMap(found => children(child, path :+ i.toByte, owner, stored = false).map(found ++ _))
        }

  /** The account a leaf of the account trie is, by the nibbles of its key (64 of them, in any sound state). */
  private def accountAt(nibbles: Array[Byte]): ByteString =
    if nibbles.length % 2 == 0 then ByteString(HexPrefix.nibblesToBytes(nibbles)) else ByteString(nibbles)

  private def hex8(bs: ByteString): String =
    bs.take(8).toArray.map("%02x".format(_)).mkString

object CheckpointExporter:
  val LogInterval: Long = 100000L

  /** The finalized block's number, when it is canonical: a block is stored at that number and its hash is the finalized
    * hash. `FinalizedUnknown(forBlock)` when none is stored.
    */
  def canonicalFinalized(
      reader: BlockchainReader,
      finalized: Option[FinalizedBlock],
      forBlock: BigInt
  ): Either[ExportError, BigInt] =
    for
      named <- finalized.toRight(FinalizedUnknown(forBlock))
      _ <- Either.cond(
        reader.getBlockHeaderByNumber(named.number).exists(_.hash.value == named.hash),
        (),
        FinalizedNotCanonical(named.number, named.hash)
      )
    yield named.number

  /** The block an export takes when the operator names none: on a proof-of-work chain the best block; on a
    * proof-of-stake chain (a terminal total difficulty) the canonical finalized block, since only a finalized block is
    * exported there.
    */
  def defaultBlock(
      reader: BlockchainReader,
      terminalTotalDifficulty: Option[BigInt],
      finalized: Option[FinalizedBlock],
      best: BigInt
  ): Either[ExportError, BigInt] =
    if terminalTotalDifficulty.isEmpty then Right(best) else canonicalFinalized(reader, finalized, best)
  val TdWalkLogInterval: Long = 1000000L

  /** An account's key in the account trie: keccak of its address, 32 bytes. */
  private val AccountKeyNibbles: Int = 64

  private val EmptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

  /** A node still to read: its trie (`None` for the account trie), the nibbles from that trie's root, and its hash. */
  final private case class Frame(owner: Option[ByteString], path: Array[Byte], hash: ByteString)

  /** The ancestor window: the headers below the block, child first, and the bodies it carries, highest first. */
  final private case class Window(headers: Vector[BlockHeader], bodies: Vector[(BlockHeader, BlockBody)])

  sealed trait ExportError extends Product with Serializable
  final case class NoSuchBlock(blockNumber: BigInt) extends ExportError
  final case class NoChainWeight(blockNumber: BigInt) extends ExportError
  final case class MissingTrieNode(hash: ByteString, isMainTrie: Boolean) extends ExportError
  final case class MissingBytecode(hash: ByteString) extends ExportError

  /** A code stored under `hash` whose keccak is something else. */
  final case class CorruptBytecode(hash: ByteString) extends ExportError

  /** The state of the block could not be read, or not to the end: it is not the state the disk holds, or the disk moved
    * on while the export read it (the unified layout holds one state, and import moves it). Not a missing node: the
    * state may be whole. Export a block the node holds, with import at rest.
    */
  final case class StateNotHeld(blockNumber: BigInt, detail: String) extends ExportError

  /** An account of the state sits under a key that is not 32 bytes: the account trie is corrupt. Format version 2 takes
    * a storage trie's owner from its account's key, so no store exports it.
    */
  final case class CorruptAccountKey(key: ByteString) extends ExportError

  /** A leaf of the account trie that does not decode as an account, or sits inside its parent. */
  final case class CorruptAccountLeaf(key: ByteString) extends ExportError

  /** A node read under a hash whose RLP hashes to something else, or that the store reports corrupt (bytes that fail
    * keccak, or do not decode): not a node that is missing.
    */
  final case class CorruptTrieNode(hash: ByteString) extends ExportError

  /** Block 0: the genesis is no checkpoint (`commitHandoff` refuses it). */
  case object GenesisNotExportable extends ExportError

  /** The chain id does not fit the archive's u64. */
  final case class ChainIdOutOfRange(chainId: BigInt) extends ExportError

  /** A proof-of-stake chain whose finalized block this node does not know, so no block can be shown to be final. */
  final case class FinalizedUnknown(blockNumber: BigInt) extends ExportError

  /** A proof-of-stake chain whose stored finalized block is not canonical: no block is stored at its number, or the
    * canonical hash there is not the finalized hash.
    */
  final case class FinalizedNotCanonical(number: BigInt, hash: ByteString) extends ExportError

  /** A proof-of-stake chain, and a TD the importer refuses (E.1): below the TTD on a post-merge block, or, on a chain
    * merged at genesis, other than the genesis difficulty.
    */
  final case class TotalDifficultyRefusedByMerge(
      blockNumber: BigInt,
      totalDifficulty: BigInt,
      terminalTotalDifficulty: BigInt,
      genesisDifficulty: BigInt
  ) extends ExportError

  /** A proof-of-stake chain, and a block above its finalized block, which a reorg can still replace. */
  final case class AboveFinalized(blockNumber: BigInt, finalized: BigInt) extends ExportError

  /** A proof-of-stake chain not merged at genesis, and a block before the merge: the block above it can carry ommers,
    * and a proof-of-stake archive carries no bodies.
    */
  final case class BeforeTheMerge(blockNumber: BigInt) extends ExportError

  /** A header of the ancestor window, at `number` and by the hash its child names, is not stored. */
  final case class MissingWindowHeader(number: BigInt, hash: ByteString) extends ExportError

  /** The body of a block of the window whose header names ommers is not stored. */
  final case class MissingWindowBody(number: BigInt, hash: ByteString) extends ExportError

  /** An ancestor header (`number`, `hash`) of the export block is not stored, so its exact total difficulty cannot be
    * summed from genesis — typically a SNAP-synced node whose chain backfill has not finished.
    */
  final case class IncompleteHeaderChain(number: BigInt, hash: ByteString) extends ExportError:
    override def toString: String =
      s"IncompleteHeaderChain(missing header $number ${hash.take(8).toArray.map("%02x".format(_)).mkString}...)"

  /** The parentHash walk, for the total difficulty or the window, reached a block 0 that is not this chain's genesis.
    */
  final case class GenesisMismatch(hash: ByteString) extends ExportError

  final case class ExportResult(
      blockNumber: BigInt,
      nodesExported: Long,
      bytecodesExported: Long,
      elapsedMs: Long
  )
