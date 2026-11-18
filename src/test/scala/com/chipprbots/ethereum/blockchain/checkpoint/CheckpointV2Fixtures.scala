package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.PositionTestKit.Rows
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

/** The chains and states the format version 2 specs export: golden streams A and B (data-model E.1 "Golden streams"),
  * and the exporter's rules (T289).
  */
object CheckpointV2Fixtures:

  /** Two storage slots whose keys, keccak of the 32-byte slot number, share their first 8 nibbles (`f291631c`) and
    * differ in the 9th: found by a birthday search over slot numbers 0 to 105566 (spec 018 T227). Under an extension of
    * 8 nibbles each leaf keeps 55 nibbles of key, so a leaf of a one-byte value is 31 bytes, inline in its branch.
    */
  val SharedPrefixSlots: (BigInt, BigInt) = (BigInt(40364), BigInt(105566))

  def slotKey(slot: BigInt): Array[Byte] =
    val bytes = slot.toByteArray.dropWhile(_ == 0)
    crypto.kec256(new Array[Byte](32 - bytes.length) ++ bytes)

  /** One code, held by both contracts of stream A: one entry in the archive. */
  val Code: ByteString = ByteString(Hex.decode("600160005500"))

  val EmptyTrieRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

  trait ArchivePruningConfig extends PruningConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
    override val pruningMode: PruningMode = ArchivePruning

  /** A fresh store: its chain, its state, and an exporter over them. */
  final class Chain:
    val storages: EphemDataSourceComponent & ArchivePruningConfig & Storages.DefaultStorages =
      new EphemDataSourceComponent
        with ArchivePruningConfig
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider
    val writer: BlockchainWriter = BlockchainWriter(storages.storages)
    val reader: BlockchainReader = BlockchainReader(storages.storages)
    private def state = storages.storages.stateStorage.getBackingStorage(0)

    def exporter(
        chainId: BigInt,
        terminalTotalDifficulty: Option[BigInt] = None,
        finalized: Option[BigInt] = None
    ): CheckpointExporter =
      new CheckpointExporter(
        storages.storages.evmCodeStorage,
        reader,
        chainId,
        terminalTotalDifficulty,
        () => finalized
      )

    /** A storage trie of `slots`, each holding the one-byte value `0x01`; its root. */
    def storageTrie(slots: Seq[BigInt]): ByteString =
      import MerklePatriciaTrie.defaultByteArraySerializable
      ByteString(
        slots
          .foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](state))((t, s) => t.put(slotKey(s), Array[Byte](1)))
          .getRootHash
      )

    /** An account trie of `accounts`, by address; its root. Codes the accounts name are stored. */
    def accountTrie(accounts: Seq[(Array[Byte], Account)], codes: Seq[ByteString] = Nil): ByteString =
      import MerklePatriciaTrie.defaultByteArraySerializable
      codes.foreach(c => storages.storages.evmCodeStorage.put(crypto.kec256(c), c).commit())
      ByteString(
        accounts
          .foldLeft(MerklePatriciaTrie[Array[Byte], Account](state))((t, a) => t.put(crypto.kec256(a._1), a._2))
          .getRootHash
      )

    /** Stores headers 0 to `headers.last` but `skip`, chained by parent hash, with their bodies and the tip's weight,
      * summed.
      */
    def store(headers: Seq[BlockHeader], bodies: Map[BigInt, BlockBody], skip: Set[Int] = Set.empty): Seq[BlockHeader] =
      val chained = headers.foldLeft(Vector.empty[BlockHeader]) { (acc, h) =>
        acc :+ h.copy(parentHash = acc.lastOption.map(_.hash).getOrElse(h.parentHash))
      }
      chained.filterNot(h => skip.contains(h.number.value.toInt)).foreach { h =>
        writer.storeBlockHeader(h).commit()
        bodies.get(h.number.value).foreach(b => writer.storeBlockBody(h.hash, b).commit())
      }
      val tip = chained.last
      writer.storeChainWeight(tip.hash, ChainWeight.totalDifficultyOnly(chained.map(_.difficulty.value).sum)).commit()
      chained

    def holdChainWeightInvariant(): Unit = storages.storages.appStateStorage.markChainWeightInvariant().commit()

    def exportBytes(exporter: CheckpointExporter, number: BigInt): Either[CheckpointExporter.ExportError, Array[Byte]] =
      val file: Path = Files.createTempFile("checkpoint-v2", ".checkpoint")
      try exporter.exportArchive(number, file).map(_ => Files.readAllBytes(file))
      finally Files.deleteIfExists(file)

  /** A PoW header of block `number`: the ETC fixture's fields, no transactions, and ommers only where given. */
  def powHeader(number: Int, ommers: Seq[BlockHeader] = Nil, stateRoot: ByteString = EmptyTrieRoot): BlockHeader =
    Fixtures.Blocks.Block3125369.header.copy(
      parentHash = BlockHash(ByteString(new Array[Byte](32))),
      ommersHash = BlockHash(ByteString(crypto.kec256(rlp.encode(RLPList(ommers.map(_.toRLPEncodable)*))))),
      stateRoot = TrieRoot(stateRoot),
      transactionsRoot = TrieRoot(EmptyTrieRoot),
      receiptsRoot = TrieRoot(EmptyTrieRoot),
      number = BlockNumber(number),
      difficulty = Difficulty(BigInt(131072) + number * 1000)
    )

  /** An ommer of the block at `number`: a sibling of its parent. */
  def ommerAt(number: Int): BlockHeader =
    powHeader(number - 1).copy(extraData = ByteString(s"ommer-$number".getBytes("UTF-8")))

  /** A post-Cancun header of block `number`, difficulty 0, on a chain merged at genesis. */
  def posHeader(number: Int, stateRoot: ByteString = EmptyTrieRoot): BlockHeader =
    powHeader(number, stateRoot = stateRoot).copy(
      difficulty = Difficulty(0),
      nonce = ByteString(new Array[Byte](8)),
      mixHash = BlockHash(ByteString(Array.fill[Byte](32)(0x5a))),
      extraFields = HefPostCancun(
        BigInt(7),
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        BigInt(131072),
        BigInt(0),
        ByteString(Array.fill[Byte](32)(0x6b))
      )
    )

  private val addressA = Hex.decode("11" * 20)
  private val addressB = Hex.decode("22" * 20)
  private val addressC = Hex.decode("33" * 20)

  /** Stream A's chain (ETC-shaped, chain id 61, no terminal total difficulty): blocks 0 to 3, block 2 with one ommer,
    * and the state of block 3: two contracts with one storage trie and one code, and a plain account. The node holds
    * the chain-weight invariant.
    */
  def streamAChain(): (Chain, BlockHeader) =
    val chain = new Chain
    val storageRoot = chain.storageTrie(Seq(SharedPrefixSlots._1, SharedPrefixSlots._2))
    val contract = Account(UInt256(1), UInt256(10), TrieRoot(storageRoot), CodeHash(crypto.kec256(Code)))
    val stateRoot = chain.accountTrie(
      Seq(addressA -> contract, addressB -> contract, addressC -> Account(UInt256(3), UInt256(30))),
      Seq(Code)
    )
    val ommer = ommerAt(2)
    val headers = Seq(powHeader(0), powHeader(1), powHeader(2, Seq(ommer)), powHeader(3, stateRoot = stateRoot))
    val stored = chain.store(headers, Map(BigInt(2) -> BlockBody(Nil, Seq(ommer))))
    chain.holdChainWeightInvariant()
    (chain, stored.last)

  val StreamBChainId: BigInt = BigInt(7091047534L)

  /** Stream B's chain (ETH-shaped, chain id 7091047534, merged at genesis): blocks 0 to 2, post-Cancun, with one
    * account. Block 2 is finalized. The node does not hold the chain-weight invariant.
    */
  def streamBChain(): (Chain, BlockHeader) =
    val chain = new Chain
    val stateRoot = chain.accountTrie(Seq(addressC -> Account(UInt256(1), UInt256(1000))))
    val stored = chain.store(Seq(posHeader(0), posHeader(1), posHeader(2, stateRoot)), Map.empty)
    (chain, stored.last)

  /** Every entry of an archive, after its header block, with the CRC checked. */
  def decode(bytes: Array[Byte]): (CheckpointArchive.Header, Seq[CheckpointArchive.Entry]) =
    val r = new CheckpointArchive.Reader(new ByteArrayInputStream(bytes))
    val header = r.readHeader().fold(e => throw new IllegalStateException(e.describe), identity)
    val entries = Seq.newBuilder[CheckpointArchive.Entry]
    var next = r.nextEntry()
    while next.exists(_ != CheckpointArchive.EndOfStream) do
      entries += next.toOption.get
      next = r.nextEntry()
    next.flatMap(_ => r.verifyCrc()).fold(e => throw new IllegalStateException(e.describe), identity)
    (header, entries.result())

  /** Replays the node entries of an archive as data-model E.1's stack machine does, over the raw RLP and nothing of the
    * exporter: each entry checked against the hash its frame expects, written at its position, its hashed children
    * pushed in reverse order and an account's storage root above them. Every stored position of the state, trie by
    * trie, as `PositionTestKit.RawTrieWalk.statePositions` gives it. Throws where the stream is not that walk.
    */
  def replay(stateRoot: ByteString, nodes: Seq[Array[Byte]]): Map[Option[ByteString], Rows] =
    final case class Frame(owner: Option[ByteString], nibbles: Array[Byte], hash: ByteString)
    val stack = mutable.Stack.empty[Frame]
    if stateRoot != EmptyTrieRoot then stack.push(Frame(None, Array.emptyByteArray, stateRoot))
    val rows = mutable.Map.empty[Option[ByteString], Rows]

    def below(node: RLPEncodeable, owner: Option[ByteString], nibbles: Array[Byte], stored: Boolean): List[Frame] =
      def reference(r: RLPEncodeable, at: Array[Byte]): List[Frame] = r match
        case RLPValue(bytes) if bytes.isEmpty      => Nil
        case RLPValue(bytes) if bytes.length == 32 => List(Frame(owner, at, ByteString(bytes)))
        case embedded: RLPList                     => below(embedded, owner, at, stored = false)
        case other                                 => throw new IllegalStateException(s"not a reference: $other")
      node match
        case RLPList(items*) if items.size == 17 =>
          (0 until 16).toList.flatMap(i => reference(items(i), nibbles :+ i.toByte))
        case RLPList(RLPValue(compact), next) =>
          val (key, isLeaf) = HexPrefix.decode(compact)
          if !isLeaf then reference(next, nibbles ++ key)
          else if owner.isEmpty then
            val full = nibbles ++ key
            require(stored && full.length == 64, s"an account leaf inline, or of ${full.length} nibbles")
            val RLPValue(value) = next: @unchecked
            rlp.rawDecode(value) match
              case RLPList(_, _, RLPValue(storageRoot), _) if ByteString(storageRoot) != EmptyTrieRoot =>
                List(
                  Frame(Some(ByteString(HexPrefix.nibblesToBytes(full))), Array.emptyByteArray, ByteString(storageRoot))
                )
              case _ => Nil
          else Nil
        case other => throw new IllegalStateException(s"not a node: $other")

    nodes.foreach { encoded =>
      require(stack.nonEmpty, "a node entry with the stack empty")
      val frame = stack.pop()
      require(ByteString(crypto.kec256(encoded)) == frame.hash, "a node that is not the one its parent names")
      val key = ByteString(HexPrefix.encode(frame.nibbles, isLeaf = false))
      rows.update(frame.owner, rows.getOrElse(frame.owner, Map.empty).updated(key, ByteString(encoded)))
      below(rlp.rawDecode(encoded), frame.owner, frame.nibbles, stored = true).reverse.foreach(stack.push)
    }
    require(stack.isEmpty, s"${stack.size} nodes still owed at the first non-node tag")
    rows.toMap
