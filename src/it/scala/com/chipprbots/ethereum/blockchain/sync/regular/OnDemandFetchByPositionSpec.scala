package com.chipprbots.ethereum.blockchain.sync.regular

import java.io.File
import java.nio.file.Files

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef as TypedActorRef
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList

import scala.concurrent.duration.*

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.ScenarioSetup
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.db.components.RocksDbDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BranchResolution
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 C5b, T212 (B-372): the importer's on-demand fetch by position, over a real [[PathStateStorage]] on RocksDB.
  *
  * RegularSyncSpec pins the importer's side with a recording
  * [[com.chipprbots.ethereum.db.storage.pathstate.PositionRepairs]], and RepairNodeAtSpec pins `repairNodeAt`'s lock
  * and anchor compare-and-set on the store. Here they meet: a node on the unified layout, on a RocksDB datadir, whose
  * genesis state lacks one row; a real BlockImporter with the node's real `ConsensusAdapter` (real VM, real execution,
  * the proof-of-work importer's branch resolution) imports block 1, which reads that row. The execution meets the hole,
  * the importer asks the fetcher for the node by its position, the node it is given is written at that position by
  * `repairNodeAt`, and the import completes. The datadir then holds block 1's state row for row, as an oracle on the
  * hash store executed it.
  *
  * The hole is a leaf of a storage trie that block 1 reads (SLOAD) and does not change, so the row the import leaves
  * there is the one the repair wrote: no commit of block 1 rewrites it.
  */
class OnDemandFetchByPositionSpec extends AnyFlatSpec with Matchers:
  import OnDemandFetchByPositionSpec.*

  "The importer on the unified layout" should "fetch a node missing from a RocksDB datadir by its position, write it there with repairNodeAt, and complete the import (T212)" taggedAs (
    IntegrationTest,
    DatabaseTest,
    SyncTest
  ) in withDatadir { dir =>
    val oracle = new Oracle
    val block1 = oracle.seal(oracle.genesis)
    val node = new Node(dir)
    try
      node.genesis.header shouldBe oracle.genesis.header
      val pathState = node.pathState
      pathState.anchor shouldBe Some(refOf(node.genesis.header))

      // the hole: the leaf of the storage trie of `Reader` that holds the slot block 1 reads
      val (hole, holeNode) = readSlotLeaf(oracle, oracle.genesis.header)
      val rows = new PathNodeStorage(node.storagesInstance.dataSource)
      rows.read(hole).map(ByteString(_)) shouldBe Some(holeNode)
      node.storagesInstance.dataSource.update(rows.rowUpdates(Seq(hole -> None)))
      rows.read(hole) shouldBe None

      val importer = node.spawnImporter()
      importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(block1)))

      val asked = node.fetcher.fishForSpecificMessage(30.seconds) { case m: BlockFetcher.FetchStateNode => m }
      withClue("the fetch names the node and its position, the owner's account hash then the path: ") {
        asked.hash shouldBe ByteString(kec256(holeNode.toArray))
        asked.paths shouldBe Some(Seq(Seq(hole.owner.get, hole.hp)))
        asked.stateRoot shouldBe Some(oracle.genesis.header.stateRoot.value)
      }

      // The importer sends its fetch from inside the import, before the import's end puts it in the wait for the
      // node: a node that lands in that gap is the late-arriving one, written all the same, and the retry is the
      // fetcher's next answer. So it is answered until the import is done, as a fetcher asked again does.
      val deadline = System.nanoTime() + 30.seconds.toNanos
      while node.blockchainReader.getBestBlockNumber < 1 && System.nanoTime() < deadline do
        asked.replyTo ! BlockFetcher.FetchedStateNode(NodeData(List(holeNode)))
        Thread.onSpinWait()
        scala.util.Try(node.awaitBest(1, 300.millis))

      withClue("the import completed: block 1 is the head and the anchor: ") {
        node.blockchainReader.getBestBlockNumber shouldBe 1
        node.blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(block1.header.hash)
        pathState.anchor shouldBe Some(refOf(block1.header))
      }
      withClue("the repaired row is the node, at its position: ") {
        rows.read(hole).map(ByteString(_)) shouldBe Some(holeNode)
      }
      withClue("the datadir is block 1's state, row for row, as the oracle executed it: ") {
        PathStateTestKit.allRows(rows) shouldBe stateOf(oracle, block1.header)
      }
      // and the hash-keyed store, which this layout does not read, was not written
      node.storagesInstance.storages.nodeStorage.get(ByteString(kec256(holeNode.toArray))) shouldBe None
    finally node.close()
  }

  private def withDatadir(test: File => Any): Unit =
    val dir = Files.createTempDirectory("on-demand-fetch-by-position").toFile
    try test(dir)
    finally deleteRecursively(dir)

  private def deleteRecursively(f: File): Unit =
    if f.isDirectory then Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete(): Unit

object OnDemandFetchByPositionSpec:

  val SenderKeyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(BigInt(1, Hex.decode("f3202185c84325302d43887e90a2e23e7bc058d0450bb58ef2f7585765d7d48b")))
  val SenderAddress: Address = Address(SenderKeyPair)
  val Miner: Address = Address(0x6d696e)

  /** `PUSH1 2; SLOAD; POP; STOP`: reads slot 2 and writes nothing. */
  val Reader: Address = Address(0x5104d)
  val ReaderCode: ByteString = ByteString(Hex.decode("6002545000"))
  val ReadSlot: UInt256 = UInt256(2)
  val OtherSlot: UInt256 = UInt256(1)

  val Genesis: GenesisData = GenesisData(
    nonce = ByteString(new Array[Byte](8)),
    mixHash = None,
    difficulty = "0x3e8",
    extraData = ByteString.empty,
    gasLimit = "0xf4240",
    coinbase = ByteString(new Array[Byte](20)),
    timestamp = "0xf4240",
    alloc = Map(
      Hex.toHexString(SenderAddress.bytes.toArray) ->
        GenesisAccount(None, UInt256(BigInt(10).pow(20)), None, None, None),
      // two slots, so that the storage trie's root is a branch and each slot a leaf of its own row
      Hex.toHexString(Reader.bytes.toArray) ->
        GenesisAccount(
          None,
          UInt256.Zero,
          Some(ReaderCode),
          None,
          Some(Map(OtherSlot -> UInt256(0x11), ReadSlot -> UInt256(0x22)))
        )
    )
  )

  def refOf(header: BlockHeader): BlockRef = BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  trait ArchivePruningBuilder extends PruningConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
    override val pruningMode: PruningMode = ArchivePruning

  /** Seals block 1 by executing it on its hash store: what block 1's state is. */
  final class Oracle extends EphemBlockchainTestSetup:
    val genesis: Block =
      GenesisDataLoader
        .over(blockchainReader, blockchainWriter, storagesInstance.storages)
        .loadGenesisData(Genesis)
        .get
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, 0).get

    /** A child of `parent` with one call of `Reader` from the sender, sealed with the state root execution gives. */
    def seal(parent: Block): Block =
      val call = SignedTransaction.sign(
        LegacyTransaction(
          nonce = parent.number.value,
          gasPrice = GasPrice(1),
          gasLimit = GasAmount(100000),
          receivingAddress = Reader,
          value = 0,
          payload = ByteString.empty
        ),
        SenderKeyPair,
        None
      )
      val template = parent.header.copy(
        parentHash = parent.hash,
        number = parent.number + 1,
        difficulty = Difficulty(1000),
        unixTimestamp = Timestamp(parent.header.unixTimestamp.toLong + 15),
        beneficiary = Miner.bytes,
        gasLimit = GasAmount(1000000),
        gasUsed = GasAmount.Zero
      )
      val block = Block(template, BlockBody(Seq(call), Nil))
      blockExecution.executeBlockNoValidation(block) match
        case Right((_, gasUsed, stateRoot)) =>
          val sealedBlock =
            block.copy(header = template.copy(stateRoot = TrieRoot(stateRoot), gasUsed = GasAmount(gasUsed)))
          blockchainWriter.storeBlock(sealedBlock).commit()
          sealedBlock
        case Left(error) => throw new IllegalStateException(s"the oracle could not seal block 1: $error")

  /** `header`'s state on `host`'s hash store, as rows of the position store. */
  def stateOf(host: Oracle, header: BlockHeader): (Map[ByteString, ByteString], Map[ByteString, ByteString]) =
    val positions = statePositions(host, header)
    (
      positions.getOrElse(None, Map.empty),
      positions.collect { case (Some(owner), trie) => trie.map((path, node) => (owner ++ path) -> node) }.flatten.toMap
    )

  private def statePositions(host: Oracle, header: BlockHeader) =
    val nodes = host.storagesInstance.storages.nodeStorage
    RawTrieWalk.statePositions(
      header.stateRoot.value.toArray,
      hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the oracle lacks a node"))
    )

  private def nibbles(bytes: Array[Byte]): Array[Byte] =
    bytes.flatMap(b => Array(((b >> 4) & 0xf).toByte, (b & 0xf).toByte))

  /** The row of `Reader`'s storage trie that is the leaf of [[ReadSlot]], and its node: the deepest row on that slot's
    * path, which is on no other slot's.
    */
  def readSlotLeaf(host: Oracle, header: BlockHeader): (PositionKey, ByteString) =
    val owner = ByteString(kec256(Reader.bytes.toArray))
    val trie = statePositions(host, header)(Some(owner))
    val read = nibbles(kec256(ReadSlot.bytes.toArray))
    val other = nibbles(kec256(OtherSlot.bytes.toArray))
    val onThePath = trie.toSeq.filter { (hp, _) =>
      val path = HexPrefix.decode(hp.toArray)._1
      path.nonEmpty && read.startsWith(path)
    }
    require(onThePath.nonEmpty, "no row of the storage trie is on the read slot's path")
    val (hp, node) = onThePath.maxBy((hp, _) => HexPrefix.decode(hp.toArray)._1.length)
    require(!other.startsWith(HexPrefix.decode(hp.toArray)._1), "the read slot's row is on the other slot's path too")
    (PositionKey(Some(owner), hp), node)

  private def rocksConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432

  /** The node under test: the unified layout on a RocksDB datadir at `dir`, with the oracle's genesis, a real VM, real
    * execution and the node's own `ConsensusAdapter`.
    */
  final class Node(dir: File) extends ScenarioSetup with TestSyncConfig:
    override lazy val vm: VMImpl = new VMImpl

    override lazy val storagesInstance: RocksDbDataSourceComponent & ArchivePruningBuilder & Storages.DefaultStorages =
      new RocksDbDataSourceComponent
        with ArchivePruningBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        override lazy val dataSource: RocksDbDataSource =
          RocksDbDataSource(rocksConfig(dir.getAbsolutePath), Namespaces.nsSeq)
        override protected def statePathLive: Boolean = true

    val genesis: Block =
      GenesisDataLoader
        .over(blockchainReader, blockchainWriter, storagesInstance.storages)
        .loadGenesisData(Genesis)
        .get
      blockchainReader.getBlockByNumber(blockchainReader.getBestBranch, 0).get

    def pathState: PathStateStorage = storagesInstance.storages.pathStateStorage.get

    val testKit: ActorTestKit = ActorTestKit()
    private given ActorSystem = testKit.system.classicSystem

    val fetcher: TestProbe = TestProbe()
    val supervisor: TestProbe = TestProbe()
    private val ommersPool = TestProbe()
    private val broadcaster = TestProbe()
    private val pendingTransactions = TestProbe()
    private val peerEventBus = TestProbe()
    private val networkPeerManager = TestProbe()

    /** The proof-of-work importer: branch resolution by chain weight, no consensus layer naming the head. */
    def spawnImporter(): TypedActorRef[BlockImporter.Command] =
      val importer = testKit.spawn(
        BlockImporter(
          fetcher.ref.toTyped[BlockFetcher.FetchCommand],
          consensusAdapter,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          new BranchResolution(blockchainReader),
          syncConfig,
          ommersPool.ref.toTyped[com.chipprbots.ethereum.ommers.OmmersPool.Command],
          broadcaster.ref.toTyped,
          pendingTransactions.ref.toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command],
          testKit.spawn(
            org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
              "on-demand-fetch-block-topic"
            )
          ),
          supervisor.ref.toTyped,
          peerEventBus.ref.toTyped,
          networkPeerManager.ref.toTyped,
          blockchain,
          CacheBasedBlacklist.empty(100),
          this,
          exhausts = new BlockImporter.ExhaustCount,
          importLock = ImportLock()
        )
      )
      fetcher.expectMsgClass(10.seconds, classOf[BlockFetcher.Start])
      importer

    /** Waits up to `max` for the best block to reach `number`. */
    def awaitBest(number: BigInt, max: FiniteDuration): Unit =
      val deadline = System.nanoTime() + max.toNanos
      while blockchainReader.getBestBlockNumber < number && System.nanoTime() < deadline do Thread.onSpinWait()
      if blockchainReader.getBestBlockNumber < number then throw new IllegalStateException("not yet")

    def close(): Unit =
      testKit.shutdownTestKit()
      storagesInstance.dataSource.close()
