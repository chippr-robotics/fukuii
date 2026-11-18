package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.Try

import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.consensus.validators.std.StdBlockValidator
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.storage.AncestorWindow
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Hex

/** Bootstrap a fresh datadir from a `.checkpoint` archive of format version 2 ([[CheckpointArchive]]; spec 018
  * data-model E.1, "The importer is a stack machine").
  *
  * '''Before anything is written''', in this order: the header block (the codec's bounds, the chain id, the genesis
  * hash, the checkpoint header's canonical RLP, the expected block hash, and on a proof-of-stake chain the total
  * difficulty rule), then the whole ancestor window (headers chained by `parentHash` with consecutive numbers and
  * canonical RLP, down to the genesis below block 256; on a proof-of-work chain the bodies of B-302's set, each matched
  * with `StdBlockValidator.validateHeaderAndBody`, and `AncestorWindow.check` empty). Proof of stake is the chain's
  * terminal total difficulty being set, not the checkpoint's difficulty. A refusal there writes nothing.
  *
  * '''The nodes''' are replayed by a stack of frames `(owner, path, expectedHash)`. It starts with `(None, [],
  * stateRoot)`, or empty for the empty trie. Each node entry pops a frame and must hash to the frame's hash; it is
  * handed to the [[CheckpointImporter.NodeSink]] at `(owner, path)`, and its hashed children are pushed in reverse
  * order, so child 0 comes next. An account leaf's storage root, unless empty, is pushed on top under the owner that is
  * the leaf's 64-nibble key, packed and not hashed again. There is no lookup by hash, no scratch store and no record of
  * what was written: a node two positions use is an entry at each. Five assertions make the machine sound, each a
  * refusal: an account leaf is a stored node; an inline node holds no hash; a storage root is an entry whatever its
  * size; an empty storage root pushes no frame; the stack is empty exactly when the first code or the end tag arrives,
  * and no node arrives while it is empty.
  *
  * '''The codes''' must each be named by an account the walk met, and every non-empty code hash met must have its code;
  * the number of gaps tolerated is `codeGapThreshold`, 0 by default.
  *
  * '''The write path is the hash layout''' (C5c PR2): [[CheckpointImporter.HashNodeSink]] stores each node under its
  * hash, as format version 1 did, so a node at many positions is stored, and counted, once per position. The import key
  * (`beginCheckpointImport`) is committed, synced, after the window is checked and before the first node; the CRC is
  * checked at the end tag, and one synced batch then writes the checkpoint header, the window's headers and bodies, the
  * trusted weight, the best block, the done flags and the end of the import key. Position writes, the atomic order on
  * the unified layout and the no-retry record are PR3's.
  */
final class CheckpointImporter(
    blockchainWriter: BlockchainWriter,
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    appStateStorage: AppStateStorage,
    trust: CheckpointImporter.Trust,
    codeGapThreshold: Int = 0,
    sinkFor: Option[BigInt => CheckpointImporter.NodeSink] = None
):
  import CheckpointImporter.*
  private val log = LoggerFactory.getLogger(getClass)

  /** Import from a file. Gzip-wrapped archives are auto-detected by either:
    *   - file path ending in `.gz` (operator convention), OR
    *   - first 2 bytes being the gzip magic `0x1F 0x8B` (peeked via a PushbackInputStream)
    *
    * The magic sniff covers Bug 35: an operator using `--gzip` who didn't add the `.gz` extension to the output path
    * still gets a working import.
    */
  def importFromFile(path: Path): Either[ImportError, ImportResult] =
    val raw = new FileInputStream(path.toFile)
    try
      val pushback = new java.io.PushbackInputStream(raw, 2)
      val b0 = pushback.read()
      val b1 = pushback.read()
      val isGzipped =
        path.toString.endsWith(".gz") ||
          (b0 == 0x1f && b1 == 0x8b)
      // Put the two peeked bytes back so the decoder sees them.
      if b1 >= 0 then pushback.unread(b1)
      if b0 >= 0 then pushback.unread(b0)
      val in: InputStream =
        if isGzipped then new GZIPInputStream(pushback, 65536)
        else pushback
      try importFromStream(in)
      finally in.close()
    finally raw.close()

  def importFromStream(in: InputStream): Either[ImportError, ImportResult] =
    val reader = new CheckpointArchive.Reader(in)
    for
      header <- reader.readHeader().left.map(err => BadFormat(Decode(err)))
      _ <- headerChecks(header, reader.archiveId)
      window <- readWindow(reader, header)
      result <- writeState(reader, header, window)
    yield result

  // ---- before any write: the header block -------------------------------------------------------------------------

  private def headerChecks(h: CheckpointArchive.Header, archiveId: Option[ByteString]): Either[ImportError, Unit] =
    val c = h.blockHeader
    val number = c.number.value
    def refuse(r: Refusal): Either[ImportError, Unit] = Left(BadFormat(r))
    if h.chainIdUnsigned != trust.chainId then refuse(WrongChainId(trust.chainId, h.chainIdUnsigned))
    else if h.genesisHash != trust.genesisHash then refuse(WrongGenesis(trust.genesisHash, h.genesisHash))
    // keccak of the header block as read equals keccak of the header block re-encoded only when the checkpoint
    // header's RLP is `header.toBytes` byte for byte (the codec already refuses a TD with a leading zero and a flag
    // other than 0 or 1): one checkpoint, one archive id (E.1 "Canonical header encoding").
    else if !Try(CheckpointArchive.archiveIdOf(h)).toOption.exists(id => archiveId.contains(id)) then
      refuse(NonCanonicalHeader(number))
    else
      trust.expectedBlockHash match
        case None                               => Left(NoExpectedBlockHash(number, c.hash.value))
        case Some(want) if want != c.hash.value => refuse(UnexpectedBlockHash(want, c.hash.value))
        case Some(_) =>
          totalDifficultyRule(h).map { _ =>
            if !h.tdExact then
              log.warn(
                "[CHECKPOINT IMPORT] the archive's total difficulty at block {} ({}) is an ESTIMATE: its exporter " +
                  "could neither sum it from genesis nor held the chain-weight invariant. It is trusted as given; " +
                  "ChainWeightReconcile checks it once the headers below the checkpoint are in.",
                number,
                h.totalDifficulty
              )
          }

  /** On a proof-of-stake chain: TD >= TTD on a post-merge header (difficulty 0), and TD = the genesis difficulty on a
    * chain merged at genesis (TTD at most the genesis difficulty). The exporter runs the same rule.
    */
  private def totalDifficultyRule(h: CheckpointArchive.Header): Either[ImportError, Unit] =
    trust.terminalTotalDifficulty match
      case None => Right(())
      case Some(ttd) =>
        val td = h.totalDifficulty
        val postMergeOk = h.blockHeader.difficulty.value != 0 || td >= ttd
        val genesisOk = ttd > trust.genesisDifficulty || td == trust.genesisDifficulty
        Either.cond(
          postMergeOk && genesisOk,
          (),
          BadFormat(TotalDifficultyRefused(h.blockHeader.number.value, td, ttd, trust.genesisDifficulty))
        )

  // ---- before any write: the ancestor window ----------------------------------------------------------------------

  /** Reads the window and checks it whole. Returns it with the first entry after it, which the codec has read. */
  private def readWindow(
      reader: CheckpointArchive.Reader,
      h: CheckpointArchive.Header
  ): Either[ImportError, Window] =
    val c = h.blockHeader
    val proofOfWork = trust.terminalTotalDifficulty.isEmpty
    val headers = Vector.newBuilder[BlockHeader]
    val byHash = mutable.HashMap.empty[ByteString, BlockHeader]
    val bodies = mutable.LinkedHashMap.empty[ByteString, BlockBody]
    var child: BlockHeader = c
    var lastBody: Option[BigInt] = None
    // C and the four below it whose headers name ommers: the only blocks a body may be given for.
    def bodyHeader(hash: ByteString): Option[BlockHeader] =
      (if hash == c.hash.value then Some(c) else byHash.get(hash))
        .filter(_.number.value > c.number.value - AncestorWindow.OmmerBodies)
    def refuse(r: Refusal): Left[ImportError, Nothing] = Left(BadFormat(r))

    @annotation.tailrec
    def loop(): Either[ImportError, CheckpointArchive.Entry] =
      reader.nextEntry() match
        case Left(err) => refuse(Decode(err))
        case Right(CheckpointArchive.WindowHeaderEntry(header, bytes)) =>
          val wanted = child.number.value - 1
          if !bytes.sameElements(header.toBytes: Array[Byte]) then refuse(NonCanonicalHeader(header.number.value))
          else if header.hash != child.parentHash then
            refuse(WindowBroken(s"the header below block ${child.number} is not the one its parentHash names"))
          else if header.number.value != wanted then
            refuse(WindowBroken(s"the header below block ${child.number} is numbered ${header.number}, not $wanted"))
          else
            headers += header
            byHash.update(header.hash.value, header)
            child = header
            loop()
        case Right(CheckpointArchive.WindowBodyEntry(blockHash, body, _)) =>
          if !proofOfWork then refuse(BodyOnProofOfStake(blockHash))
          else
            bodyHeader(blockHash) match
              case None =>
                refuse(WindowBodyRefused(s"a body for ${hex(blockHash)}, which is neither C nor C-1 to C-4"))
              case Some(header) if !AncestorWindow.bodyRead(header) =>
                refuse(WindowBodyRefused(s"a body for block ${header.number}, whose header names no ommers"))
              case Some(header) if lastBody.exists(_ <= header.number.value) =>
                refuse(WindowBodyRefused(s"the body of block ${header.number} out of order or repeated"))
              case Some(header) =>
                StdBlockValidator.validateHeaderAndBody(header, body) match
                  case Left(err) =>
                    refuse(WindowBodyRefused(s"the body of block ${header.number} does not match its header: $err"))
                  case Right(_) =>
                    bodies.update(blockHash, body)
                    lastBody = Some(header.number.value)
                    loop()
        case Right(other) => Right(other)

    for
      next <- loop()
      window = headers.result()
      // The codec counted min(256, N) headers and the loop chained them, so the last is block 0 when N < 256.
      _ <- window.lastOption.filter(_.number.value == 0) match
        case Some(last) if last.hash.value != trust.genesisHash =>
          refuse(
            WindowBroken(s"the window ends at a block 0 that is not this chain's genesis (${hex(last.hash.value)})")
          )
        case _ => Right(())
      missing = AncestorWindow.check(c, proofOfWork, hash => byHash.get(hash.value), hash => bodies.get(hash.value))
      _ <- Either.cond(missing.isEmpty, (), BadFormat(WindowBodyRefused(s"the window lacks ${missing.describe}")))
    yield Window(
      window,
      bodies.toVector.map((hash, body) => (if hash == c.hash.value then c else byHash(hash)) -> body),
      next
    )

  // ---- the state ----------------------------------------------------------------------------------------------------

  private def writeState(
      reader: CheckpointArchive.Reader,
      h: CheckpointArchive.Header,
      window: Window
  ): Either[ImportError, ImportResult] =
    val c = h.blockHeader
    val blockNum = c.number
    val blockHash = c.hash
    val stateRoot = c.stateRoot.value
    val startMs = System.currentTimeMillis()
    log.info(
      "[CHECKPOINT IMPORT] header and window checked: block={} hash={} chainId={} window={} headers and {} bodies, " +
        "tdExact={}; writing the state of root {}",
      blockNum,
      hex(blockHash.value),
      h.chainIdUnsigned,
      window.headers.size,
      window.bodies.size,
      h.tdExact,
      hex(stateRoot)
    )
    // spec 022 T042: said before the first node goes in. The nodes arrive parent-first and the CRC is checked at the end, so
    // until then a present node can sit over an absent child, and on a failure the node falls through to SNAP on this
    // datadir. Synced, since the nodes are not: a node on disk must imply this key is. It takes the invariant marker with
    // it: that a marked datadir's nodes were complete says nothing of what is written from here on. A success does not
    // bring the marker back: the done flags vouch for this root, and no walk has looked at the rest.
    appStateStorage.beginCheckpointImport(stateRoot, startMs).commitSync()
    val sink = sinkFor.fold[NodeSink](new HashNodeSink(stateStorage, blockNum.value))(_(blockNum.value))
    val machine = new StackMachine(stateRoot, sink)
    val codeBuf = mutable.ArrayBuffer.empty[(ByteString, Array[Byte])]
    var totalBytecodes = 0L
    var codeBytes = 0L

    def flushCodes(): Unit =
      if codeBuf.nonEmpty then
        var batch = evmCodeStorage.emptyBatchUpdate
        codeBuf.foreach((hash, code) => batch = batch.and(evmCodeStorage.put(hash, ByteString(code))))
        batch.commit()
        codeBuf.clear()

    def refuse(r: Refusal): Left[ImportError, Nothing] = Left(BadFormat(r))

    @annotation.tailrec
    def loop(entry: Either[CheckpointArchive.DecodeError, CheckpointArchive.Entry]): Either[ImportError, Unit] =
      entry match
        case Left(err) => refuse(Decode(err))
        case Right(CheckpointArchive.NodeEntry(bytes)) =>
          machine.node(bytes) match
            case Left(r)  => refuse(r)
            case Right(_) => loop(reader.nextEntry())
        case Right(CheckpointArchive.CodeEntry(code)) =>
          val hash = ByteString(crypto.kec256(code))
          machine.endOfNodes().flatMap { _ =>
            if !machine.codeHashes.remove(hash) then Left(UnreferencedCode(hash))
            else Right(())
          } match
            case Left(r) => refuse(r)
            case Right(_) =>
              codeBuf += ((hash, code))
              totalBytecodes += 1
              codeBytes += code.length
              if codeBuf.size >= BytecodeBatchSize then flushCodes()
              loop(reader.nextEntry())
        case Right(CheckpointArchive.EndOfStream) => machine.endOfNodes().left.map(BadFormat(_))
        case Right(other)                         =>
          // The codec refuses a window entry after a node; this is the importer's own guard on that.
          refuse(Decode(CheckpointArchive.Malformed(s"a window entry after the window: $other")))

    val streamed =
      try loop(Right(window.next))
      finally
        sink.flush()
        flushCodes()

    val outcome =
      for
        _ <- streamed
        _ <- reader.verifyCrc().left.map(err => BadFormat(Decode(err)))
        _ <- codeCompleteness(machine.codeHashes, blockNum.value)
      yield ()

    outcome match
      case Left(err) =>
        log.error(
          "[CHECKPOINT IMPORT] refused block {} after nodes={} bytecodes={}: {}. Nothing marks the import done; the " +
            "import key stays, so the next start treats this datadir as an interrupted import.",
          blockNum,
          machine.nodes,
          totalBytecodes,
          describeError(err)
        )
        Left(err)
      case Right(_) =>
        // Durability barrier (#1581): under pruning.mode=inmemory the nodes written above sit only in the node cache,
        // not the WAL. Flush them before the flags, whose synced commit then makes them durable with the flags.
        stateStorage.forcePersist(StateStorage.SnapSyncFinalization)
        // One synced batch: the checkpoint's header and the window's (so BLOCKHASH and the ommer check of C+1 find
        // them), the window's bodies, the trusted weight, the best block and the done flags.
        val withHeaders =
          (c +: window.headers).map(header => blockchainWriter.storeBlockHeader(header)).reduce(_.and(_))
        window.bodies
          .foldLeft(withHeaders)((batch, hb) => batch.and(blockchainWriter.storeBlockBody(hb._1.hash, hb._2)))
          .and(blockchainWriter.storeChainWeight(blockHash, h.chainWeight))
          .and(appStateStorage.putBestBlockInfo(BlockInfo(blockHash.value, blockNum.value)))
          .and(appStateStorage.snapSyncDone())
          .and(appStateStorage.bytecodeRecoveryDone())
          .and(appStateStorage.storageRecoveryDone())
          .and(appStateStorage.endCheckpointImport())
          .commitSync()

        val elapsed = System.currentTimeMillis() - startMs
        log.info(
          "[CHECKPOINT IMPORT] complete: block={} nodes={} ({} MiB) bytecodes={} ({} MiB) elapsed={}s. " +
            "RegularSync will resume from {}.",
          blockNum,
          machine.nodes,
          machine.nodeBytes / (1024 * 1024),
          totalBytecodes,
          codeBytes / (1024 * 1024),
          elapsed / 1000,
          blockNum + 1L
        )
        Right(ImportResult(blockNum.value, machine.nodes, totalBytecodes, elapsed))

  /** Every non-empty code hash an account named has a code: gaps above `codeGapThreshold` refuse the archive; gaps at
    * or below it are logged for the on-demand fetch.
    */
  private def codeCompleteness(gaps: mutable.Set[ByteString], blockNumber: BigInt): Either[ImportError, Unit] =
    if gaps.size > codeGapThreshold then Left(BadFormat(MissingCode(gaps.size, gaps.take(MissingCodeSample).toList)))
    else
      if gaps.nonEmpty then
        log.warn(
          "[CHECKPOINT IMPORT] block {}: {} code(s) named by accounts are not in the archive (threshold {}); they are " +
            "fetched on demand: {}",
          blockNumber,
          gaps.size,
          codeGapThreshold,
          gaps.map(hex).mkString(", ")
        )
      Right(())

  /** The stack machine of data-model E.1 over one state. */
  final private class StackMachine(stateRoot: ByteString, sink: NodeSink):
    private val stack = new java.util.ArrayDeque[Frame]
    // The empty trie has no node: its state is no entry.
    if stateRoot != EmptyRoot then stack.push(Frame(None, Array.emptyByteArray, stateRoot))
    val codeHashes: mutable.Set[ByteString] = mutable.HashSet.empty
    var nodes = 0L
    var nodeBytes = 0L

    /** Fifth assertion, its first half: the stack is empty when the first code or the end tag arrives. */
    def endOfNodes(): Either[Refusal, Unit] =
      Either.cond(
        stack.isEmpty,
        (),
        Assertion(
          5,
          s"${stack.size} node(s) still owed, the next at ${describeFrame(stack.peek())}, at the end of the nodes"
        )
      )

    def node(bytes: Array[Byte]): Either[Refusal, Unit] =
      if stack.isEmpty then Left(Assertion(5, "a node entry with no node owed (the stack is empty)"))
      else
        val frame = stack.pop()
        val hash = ByteString(crypto.kec256(bytes))
        if hash != frame.hash then Left(NodeHashMismatch(describeFrame(frame), frame.hash, hash))
        else
          for
            decoded <- Try(rlp.rawDecode(bytes)).toOption.toRight(MalformedNode(describeFrame(frame), "not RLP"))
            below <- children(decoded, frame.owner, frame.path, stored = true, frame)
          yield
            sink.node(frame.owner, frame.path, frame.hash, bytes)
            nodes += 1
            nodeBytes += bytes.length
            if nodes % LogInterval == 0 then
              log.info("[CHECKPOINT IMPORT] nodes={} ({} MiB) stack={}", nodes, nodeBytes / (1024 * 1024), stack.size)
            below.reverseIterator.foreach(stack.push)

    /** The frames below a node, in walk order. `stored` is false for a node inside its parent. */
    private def children(
        node: RLPEncodeable,
        owner: Option[ByteString],
        path: Array[Byte],
        stored: Boolean,
        at: Frame
    ): Either[Refusal, List[Frame]] =
      def malformed(detail: String) = Left(MalformedNode(describeFrame(at), detail))

      def reference(ref: RLPEncodeable, childPath: Array[Byte]): Either[Refusal, List[Frame]] = ref match
        case RLPValue(b) if b.isEmpty            => Right(Nil)
        case RLPValue(b) if b.length == HashSize =>
          // Second assertion: a hash makes a node at least 33 bytes, so no inline node holds one.
          if !stored then Left(Assertion(2, s"an inline node at ${describe(owner, path)} holds a hash reference"))
          else Right(List(Frame(owner, childPath, ByteString(b))))
        case inline: RLPList =>
          // The assertions on what an inline node holds come first; then its size, which they imply.
          children(inline, owner, childPath, stored = false, at).flatMap { found =>
            val size = rlp.encode(inline).length
            if size >= HashSize then malformed(s"an inline child of $size bytes (a node of 32 bytes or more is hashed)")
            else Right(found)
          }
        case RLPValue(b) => malformed(s"a child reference of ${b.length} bytes")
        case _           => malformed("a child reference that is neither a value nor a list")

      def keyed(key: Array[Byte], isLeaf: Boolean, next: RLPEncodeable): Either[Refusal, List[Frame]] =
        if !isLeaf then reference(next, path ++ key)
        else if owner.nonEmpty then Right(Nil)
        else
          val full = path ++ key
          // First assertion: an account's RLP is at least 68 bytes, so its leaf is always stored, and the owner of
          // its storage trie is the leaf's own key.
          if !stored then Left(Assertion(1, s"an account leaf inline in its parent, at ${describe(None, full)}"))
          else if full.length != AccountKeyNibbles then Left(AccountKeyLength(describe(None, full), full.length))
          else
            next match
              case RLPValue(value) =>
                Account(ByteString(value)).toOption match
                  case None => malformed("an account leaf whose value is not an account")
                  case Some(account) =>
                    if account.codeHash != Account.EmptyCodeHash then codeHashes += account.codeHash.value
                    // Third and fourth assertions: a storage root is an entry whatever its size, and the empty
                    // storage root pushes no frame.
                    if account.storageRoot == Account.EmptyStorageRootHash then Right(Nil)
                    else
                      val owner = ByteString(HexPrefix.nibblesToBytes(full))
                      Right(List(Frame(Some(owner), Array.emptyByteArray, account.storageRoot.value)))
              case _ => malformed("an account leaf whose value is a list")

      node match
        case RLPList(items*) if items.size == 17 =>
          val value = items(16) match
            case RLPValue(b) => Right(b)
            case _           => malformed("a branch value that is a list")
          value.flatMap { v =>
            if owner.isEmpty && v.nonEmpty then malformed("a branch with a value in the account trie")
            else
              (0 until 16).foldLeft[Either[Refusal, List[Frame]]](Right(Nil)) { (acc, i) =>
                acc.flatMap(found => reference(items(i), path :+ i.toByte).map(found ++ _))
              }
          }
        case RLPList(RLPValue(compact), next) if compact.nonEmpty =>
          Try(HexPrefix.decode(compact)).toOption match
            case None                => malformed("a key that is not hex-prefix encoded")
            case Some((key, isLeaf)) => keyed(key, isLeaf, next)
        case _ => malformed("neither a branch, an extension nor a leaf")

  private def describeError(err: ImportError): String = err match
    case BadFormat(r)                      => r.describe
    case NoExpectedBlockHash(number, hash) => s"no expected block hash for block $number (${hex(hash)})"

object CheckpointImporter:
  // 10k * ~500 B per node ≈ 5 MiB per write batch — comfortable for RocksDB's default write buffer.
  val NodeBatchSize: Int = 10000
  val BytecodeBatchSize: Int = 1000
  val LogInterval: Long = 100000L
  private val MissingCodeSample: Int = 8

  private val HashSize: Int = 32
  private val AccountKeyNibbles: Int = 64
  private val EmptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

  /** What the node trusts an archive by (data-model E.1 "Trust"): its chain, its genesis, its terminal total
    * difficulty, and the hash the checkpoint block must have.
    *
    * @param expectedBlockHash
    *   the operator's, else the one the chain config pins; `None` refuses every archive ([[NoExpectedBlockHash]]). ETH
    *   and ETC share a genesis hash, so only the chain id and this hash tell their archives apart.
    */
  final case class Trust(
      chainId: BigInt,
      genesisHash: ByteString,
      genesisDifficulty: BigInt,
      terminalTotalDifficulty: Option[BigInt],
      expectedBlockHash: Option[ByteString]
  )

  object Trust:
    def apply(config: BlockchainConfig, genesis: BlockHeader, operatorExpectedHash: Option[ByteString]): Trust =
      Trust(
        chainId = config.chainId.value,
        genesisHash = genesis.hash.value,
        genesisDifficulty = genesis.difficulty.value,
        terminalTotalDifficulty = config.terminalTotalDifficulty,
        expectedBlockHash = operatorExpectedHash.orElse(config.checkpointExpectedBlockHash)
      )

  /** Where the importer puts each node: at its position `(owner, path)`, and under `hash`, which is keccak of `rlp`.
    * The hash layout keys by `hash` ([[HashNodeSink]]); the unified layout's position sink is spec 018 C5c PR3.
    */
  trait NodeSink:
    def node(owner: Option[ByteString], path: Array[Byte], hash: ByteString, rlp: Array[Byte]): Unit

    /** Writes what is buffered. Called before the codes' last batch, on success and on failure. */
    def flush(): Unit

  /** The hash layout: each node under keccak of its bytes, at the checkpoint's height (so `ReferenceCountNodeStorage`
    * tags it as SNAP healing does at its pivot). A node at several positions is written at each, so its count is the
    * number of positions that use it (#1692).
    */
  final class HashNodeSink(stateStorage: StateStorage, blockNumber: BigInt) extends NodeSink:
    private val mpt = stateStorage.getBackingStorage(blockNumber)
    private val buf = mutable.ArrayBuffer.empty[(ByteString, Array[Byte])]
    def node(owner: Option[ByteString], path: Array[Byte], hash: ByteString, rlp: Array[Byte]): Unit =
      buf += ((hash, rlp))
      if buf.size >= NodeBatchSize then flush()
    def flush(): Unit =
      if buf.nonEmpty then
        mpt.storeRawNodes(buf.toSeq)
        buf.clear()

  /** A node still owed: its trie (`None` for the account trie), the nibbles from that trie's root, and its hash. */
  final private case class Frame(owner: Option[ByteString], path: Array[Byte], hash: ByteString)

  /** The checked window: its headers child first, its bodies highest first, and the entry after it. */
  final private case class Window(
      headers: Vector[BlockHeader],
      bodies: Vector[(BlockHeader, BlockBody)],
      next: CheckpointArchive.Entry
  )

  private def hex(bs: ByteString): String = Hex.toHexString(bs.toArray)

  private def describe(owner: Option[ByteString], path: Array[Byte]): String =
    val trie = owner.fold("the account trie")(o => s"the storage trie of ${hex(o)}")
    s"$trie, path [${path.map(n => Integer.toHexString(n & 0xf)).mkString}]"

  private def describeFrame(f: Frame): String = s"${describe(f.owner, f.path)} (${hex(f.hash)})"

  sealed trait ImportError extends Product with Serializable

  /** The archive is refused: not of the format, not this chain's, not the block expected, or not sound. */
  final case class BadFormat(reason: Refusal) extends ImportError

  /** No expected block hash: the operator gave none and the chain config pins none. Not the archive's fault, so not
    * [[BadFormat]]: a node given the hash may import the same archive.
    */
  final case class NoExpectedBlockHash(blockNumber: BigInt, blockHash: ByteString) extends ImportError

  /** Why an archive is refused. */
  sealed trait Refusal extends Product with Serializable:
    def describe: String = toString

  /** The codec's refusal: the bytes are not format version 2. */
  final case class Decode(err: CheckpointArchive.DecodeError) extends Refusal:
    override def describe: String = err.describe
  final case class WrongChainId(expected: BigInt, found: BigInt) extends Refusal
  final case class WrongGenesis(expected: ByteString, found: ByteString) extends Refusal:
    override def describe: String = s"WrongGenesis(expected ${hex(expected)}, found ${hex(found)})"

  /** The checkpoint block is not the one the operator or the chain config names. */
  final case class UnexpectedBlockHash(expected: ByteString, found: ByteString) extends Refusal:
    override def describe: String = s"UnexpectedBlockHash(expected ${hex(expected)}, found ${hex(found)})"

  /** A header (the checkpoint's or the window's) whose RLP is not `header.toBytes` byte for byte. */
  final case class NonCanonicalHeader(number: BigInt) extends Refusal
  final case class TotalDifficultyRefused(
      blockNumber: BigInt,
      totalDifficulty: BigInt,
      terminalTotalDifficulty: BigInt,
      genesisDifficulty: BigInt
  ) extends Refusal

  /** The window's headers do not chain down from the checkpoint, or end at another genesis. */
  final case class WindowBroken(detail: String) extends Refusal

  /** A window body that does not belong, does not match its header, or is missing. */
  final case class WindowBodyRefused(detail: String) extends Refusal

  /** A window body on a proof-of-stake chain, which carries none. */
  final case class BodyOnProofOfStake(blockHash: ByteString) extends Refusal
  final case class NodeHashMismatch(at: String, expected: ByteString, found: ByteString) extends Refusal:
    override def describe: String = s"NodeHashMismatch(at $at: expected ${hex(expected)}, found ${hex(found)})"
  final case class MalformedNode(at: String, detail: String) extends Refusal

  /** An account leaf whose full key is not 64 nibbles. */
  final case class AccountKeyLength(at: String, nibbles: Int) extends Refusal

  /** One of the stack machine's five assertions (data-model E.1), by its number there. */
  final case class Assertion(number: Int, detail: String) extends Refusal

  /** A code no account of the state names. */
  final case class UnreferencedCode(hash: ByteString) extends Refusal:
    override def describe: String = s"UnreferencedCode(${hex(hash)})"

  /** More codes missing than the threshold allows; `sample` names some of them. */
  final case class MissingCode(count: Int, sample: List[ByteString]) extends Refusal:
    override def describe: String = s"MissingCode($count, e.g. ${sample.map(hex).mkString(", ")})"

  final case class ImportResult(
      blockNumber: BigInt,
      nodesImported: Long,
      bytecodesImported: Long,
      elapsedMs: Long
  )
