package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32C

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.ImportError
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.ImportResult
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.NodeSink
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.Trust
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.mpt.PositionTestKit.Rows
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

/** What the importer's specs (spec 018 C5c PR2: T222, T223, T224, T226, T290) share: a datadir to import into, the
  * trust that accepts an archive, archives written byte by byte (so that a spec can write what the codec's writer
  * refuses to), and tries built node by node.
  */
object CheckpointV2ImportKit:
  import CheckpointV2Fixtures.*

  type Snapshot = Map[ByteBuffer, Seq[Byte]]

  /** Every key and value of a store, to show that a refused import wrote nothing: but for the record of the refused
    * archive (data-model E.1, "no retry"), which is the one write a refusal makes, and which the specs of the record
    * look at ([[CheckpointImportRetrySpec]]).
    */
  def snapshot(chain: Chain): Snapshot =
    chain.storages.dataSource.storage
      .filterNot((k, _) => new String(k.array(), "UTF-8").endsWith(AppStateStorage.Keys.CheckpointImportRefused))
      .map((k, v) => k -> v.toSeq)

  def importInto(
      target: Chain,
      bytes: Array[Byte],
      trust: Trust,
      codeGapThreshold: Int = 0,
      sink: Option[BigInt => NodeSink] = None
  ): Either[ImportError, ImportResult] =
    val s = target.storages.storages
    new CheckpointImporter(
      target.writer,
      s.stateStorage,
      s.evmCodeStorage,
      s.appStateStorage,
      trust,
      codeGapThreshold,
      sink
    ).importFromStream(new ByteArrayInputStream(bytes))

  /** The trust of a node on `chain`'s chain, expecting `block`. */
  def trustOf(chain: Chain, chainId: BigInt, block: BlockHeader, ttd: Option[BigInt] = None): Trust =
    val genesis = chain.reader.genesisHeader
    Trust(chainId, genesis.hash.value, genesis.difficulty.value, ttd, Some(block.hash.value))

  /** The node stored under `hash` in `chain`'s hash-keyed store. */
  def loader(chain: Chain): Array[Byte] => Array[Byte] =
    hash => chain.storages.storages.nodeStorage.get(ByteString(hash)).getOrElse(sys.error("no such node"))

  /** Every stored position of the state of `root` in `chain`, trie by trie. */
  def positionsOf(chain: Chain, root: ByteString): Map[Option[ByteString], Rows] =
    RawTrieWalk.statePositions(root.toArray, loader(chain))

  /** Records every node the importer places, by its position, and passes it on to the hash layout. */
  final class RecordingSink(target: Chain, blockNumber: BigInt) extends NodeSink:
    private val hashLayout = new CheckpointImporter.HashNodeSink(target.storages.storages.stateStorage, blockNumber)
    val placed: mutable.ArrayBuffer[(Option[ByteString], ByteString, ByteString)] = mutable.ArrayBuffer.empty
    def node(owner: Option[ByteString], path: Array[Byte], hash: ByteString, rlp: Array[Byte]): Unit =
      placed += ((owner, ByteString(HexPrefix.encode(path, isLeaf = false)), ByteString(rlp)))
      hashLayout.node(owner, path, hash, rlp)
    def flush(): Unit = hashLayout.flush()
    def rows: Map[Option[ByteString], Rows] =
      placed.groupBy(_._1).map((owner, rs) => owner -> rs.map(r => r._2 -> r._3).toMap)

  // ---- archives, byte by byte ---------------------------------------------------------------------------------------

  object Raw:
    def u32(v: Int): Array[Byte] = Array((v >>> 24).toByte, (v >>> 16).toByte, (v >>> 8).toByte, v.toByte)
    def u64(v: Long): Array[Byte] = u32((v >>> 32).toInt) ++ u32(v.toInt)

    def entry(tag: Int, payload: Array[Byte]): Array[Byte] = Array(tag.toByte) ++ u32(payload.length) ++ payload
    def header(rlp: Array[Byte]): Array[Byte] = entry(0x03, rlp)
    def body(blockHash: ByteString, rlp: Array[Byte]): Array[Byte] =
      Array(0x04.toByte) ++ blockHash.toArray ++ u32(rlp.length) ++ rlp
    def node(rlp: Array[Byte]): Array[Byte] = entry(0x01, rlp)
    def code(code: Array[Byte]): Array[Byte] = entry(0x02, code)

    def of(e: CheckpointArchive.Entry): Array[Byte] = e match
      case CheckpointArchive.WindowHeaderEntry(_, bytes)  => header(bytes)
      case CheckpointArchive.WindowBodyEntry(h, _, bytes) => body(h, bytes)
      case CheckpointArchive.NodeEntry(bytes)             => node(bytes)
      case CheckpointArchive.CodeEntry(bytes)             => code(bytes)
      case CheckpointArchive.EndOfStream                  => Array(0x00.toByte)

    /** A header block (fields 1 to 9) with each field as given. */
    def headerBlock(
        chainId: Long,
        genesisHash: ByteString,
        flag: Int,
        headerRlp: Array[Byte],
        td: Array[Byte],
        version: Int = 2
    ): Array[Byte] =
      CheckpointArchive.Magic ++ Array(version.toByte) ++ u64(chainId) ++ genesisHash.toArray ++ Array(flag.toByte) ++
        u32(headerRlp.length) ++ headerRlp ++ u32(td.length) ++ td

    /** The header block, the entries, the end tag and the CRC32C over all of it. */
    def archive(headerBlock: Array[Byte], entries: Seq[Array[Byte]]): Array[Byte] =
      val body = headerBlock ++ entries.flatten ++ Array(0x00.toByte)
      val crc = new CRC32C
      crc.update(body)
      body ++ u32(crc.getValue.toInt)

    def rebuild(h: CheckpointArchive.Header, entries: Seq[CheckpointArchive.Entry]): Array[Byte] =
      archive(CheckpointArchive.headerBlockBytes(h), entries.filterNot(_ == CheckpointArchive.EndOfStream).map(of))

  // ---- tries, node by node ------------------------------------------------------------------------------------------

  object Nodes:
    def leaf(nibbles: Array[Byte], value: Array[Byte]): Array[Byte] =
      rlp.encode(RLPList(RLPValue(HexPrefix.encode(nibbles, isLeaf = true)), RLPValue(value)))
    def extension(nibbles: Array[Byte], child: RLPEncodeable): Array[Byte] =
      rlp.encode(RLPList(RLPValue(HexPrefix.encode(nibbles, isLeaf = false)), child))
    def branch(children: (Int, RLPEncodeable)*): Array[Byte] =
      val slots = Array.fill[RLPEncodeable](17)(RLPValue(Array.emptyByteArray))
      children.foreach((i, c) => slots(i) = c)
      rlp.encode(RLPList(slots.toIndexedSeq*))

    /** A child by its hash. */
    def ref(node: Array[Byte]): RLPEncodeable = RLPValue(crypto.kec256(node))

    /** A child inside its parent. */
    def inline(node: Array[Byte]): RLPEncodeable = rlp.rawDecode(node)

    def hash(node: Array[Byte]): ByteString = ByteString(crypto.kec256(node))

    def account(storageRoot: ByteString = EmptyTrieRoot, code: Option[Array[Byte]] = None): Array[Byte] =
      val withStorage = Account(UInt256(1), UInt256(7), TrieRoot(storageRoot))
      Account.accountSerializer.toBytes(
        code.fold(withStorage)(c => withStorage.copy(codeHash = CodeHash(crypto.kec256(ByteString(c)))))
      )

    /** 64 nibbles of an account key: keccak of `seed`. */
    def key(seed: String): Array[Byte] = HexPrefix.bytesToNibbles(crypto.kec256(seed.getBytes("UTF-8")))

    /** An account trie of two accounts under a branch, by the seeds of their keys (which must differ in their first
      * nibble): its root, and its nodes in pre-order, the root first.
      */
    def twoAccounts(a: (String, Array[Byte]), b: (String, Array[Byte])): (ByteString, Seq[Array[Byte]]) =
      val (ka, kb) = (key(a._1), key(b._1))
      require(ka(0) != kb(0), "the two keys share their first nibble")
      val la = leaf(ka.tail, a._2)
      val lb = leaf(kb.tail, b._2)
      val root = branch(ka(0).toInt -> ref(la), kb(0).toInt -> ref(lb))
      val ordered = if ka(0) < kb(0) then Seq(la, lb) else Seq(lb, la)
      (hash(root), root +: ordered)

  /** An archive of a checkpoint at block 3 with `stateRoot`, its window of three synthetic headers down to a synthetic
    * genesis (no ommers, so no bodies), and these node and code entries in the order given; and the trust that accepts
    * its header.
    */
  def crafted(
      stateRoot: ByteString,
      nodes: Seq[Array[Byte]],
      codes: Seq[Array[Byte]] = Nil,
      chainId: Long = 61L
  ): (Array[Byte], Trust) =
    val (c, window) = CheckpointV2TestKit.withWindow(powHeader(3, stateRoot = stateRoot))
    val header = CheckpointArchive.Header(chainId, CheckpointV2TestKit.genesisOf(window), true, c, BigInt(1000))
    val bytes = Raw.archive(
      CheckpointArchive.headerBlockBytes(header),
      window.map(h => Raw.header(h.toBytes)) ++ nodes.map(Raw.node) ++ codes.map(Raw.code)
    )
    (bytes, Trust(chainId, header.genesisHash, window.last.difficulty.value, None, Some(c.hash.value)))
