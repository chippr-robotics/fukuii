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
import com.chipprbots.ethereum.consensus.engine.DesignatedHead
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
import com.chipprbots.ethereum.utils.BlockchainConfig

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
  ) in withDatadir(dir => fetchesTheHoleByPosition(dir, posChain = false))

  // B-394, the ETH/PoS leg. The chain has a terminal total difficulty, so the importer follows a consensus layer
  // (`BranchResolution.followsConsensusLayer`), and the head the consensus layer designated is block 1. The block is
  // post-merge (difficulty 0, so no branch is heavier than another) and extends the head: `compareBranch` takes it by
  // its equal-weight rule, and the designated-head arm would take it as well (mutation: with both off the import never
  // happens). The route is the one that serves the on-demand fetch on ETH: the p2p importer (`BlockImporter`, fed by
  // `BlockFetcher`). Engine API `newPayload` does not fetch: a hole it meets is answered SYNCING (`EngineStateHoles`).
  // A competing branch that only the designated head would take is not this test's: it is `BranchResolutionSpec`'s.
  it should "fetch the hole by its position, repair it with repairNodeAt, and complete the import of a post-merge block following the consensus layer (B-394)" taggedAs (
    IntegrationTest,
    DatabaseTest,
    SyncTest
  ) in withDatadir(dir => fetchesTheHoleByPosition(dir, posChain = true))

  private def fetchesTheHoleByPosition(dir: File, posChain: Boolean): Unit =
    val ttd = Option.when(posChain)(BigInt(0))
    val oracle = new Oracle(ttd)
    val block1 = oracle.seal(oracle.genesis)
    if posChain then
      withClue("the block is post-merge: ")(block1.header.difficulty shouldBe Difficulty.Zero)
      oracle.blockchainConfig.terminalTotalDifficulty shouldBe Some(BigInt(0))
    val node = new Node(dir, ttd)
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

      val designated = Option.when(posChain)(DesignatedHead(() => Some(block1.hash.value)))
      val importer = node.spawnImporter(designated)
      node.followsConsensusLayer(designated) shouldBe posChain
      importer ! BlockImporter.FetcherResponse(BlockFetcher.PickedBlocks(NonEmptyList.one(block1)))

      val asked = node.fetcher.fishForSpecificMessage(30.seconds) { case m: BlockFetcher.FetchStateNode => m }
      withClue("the fetch names the node and its position, the owner's account hash then the path: ") {
        asked.hash shouldBe ByteString(kec256(holeNode.toArray))
        asked.paths shouldBe Some(Seq(Seq(hole.owner.get, hole.hp)))
        asked.stateRoot shouldBe Some(oracle.genesis.header.stateRoot.value)
      }

      // One answer, as the fetcher gives. It may land before the import's end puts the importer in the wait for the
      // node; it is then handed to that wait (B-408), so the import completes well inside the wait's 30 s retry.
      asked.replyTo ! BlockFetcher.FetchedStateNode(NodeData(List(holeNode)))
      withClue("one answer completes the import, without the wait's retry: ")(
        noException should be thrownBy node.awaitBest(1, 15.seconds)
      )

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
  final class Oracle(terminalTotalDifficulty: Option[BigInt] = None) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig =
      terminalTotalDifficulty.fold(super.blockchainConfig)(ttd =>
        super.blockchainConfig.copy(terminalTotalDifficulty = Some(ttd))
      )

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
        difficulty = if terminalTotalDifficulty.isDefined then Difficulty.Zero else Difficulty(1000),
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
  final class Node(dir: File, terminalTotalDifficulty: Option[BigInt] = None) extends ScenarioSetup with TestSyncConfig:
    implicit override lazy val blockchainConfig: BlockchainConfig =
      terminalTotalDifficulty.fold(super.blockchainConfig)(ttd =>
        super.blockchainConfig.copy(terminalTotalDifficulty = Some(ttd))
      )

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

    /** The importer: on proof of work, branch resolution by chain weight and no consensus layer naming the head; given
      * a `designatedHead`, the post-merge one that follows a consensus layer.
      */
    def spawnImporter(designatedHead: Option[DesignatedHead] = None): TypedActorRef[BlockImporter.Command] =
      val importer = testKit.spawn(
        BlockImporter(
          fetcher.ref.toTyped[BlockFetcher.FetchCommand],
          consensusAdapter,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          new BranchResolution(blockchainReader, designatedHead),
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

    /** What the importer's branch resolution says of following a consensus layer, for `designatedHead`. */
    def followsConsensusLayer(designatedHead: Option[DesignatedHead]): Boolean =
      new BranchResolution(blockchainReader, designatedHead).followsConsensusLayer

    /** Waits up to `max` for the best block to reach `number`. */
    def awaitBest(number: BigInt, max: FiniteDuration): Unit =
      val deadline = System.nanoTime() + max.toNanos
      while blockchainReader.getBestBlockNumber < number && System.nanoTime() < deadline do Thread.onSpinWait()
      if blockchainReader.getBestBlockNumber < number then throw new IllegalStateException("not yet")

    def close(): Unit =
      testKit.shutdownTestKit()
      storagesInstance.dataSource.close()
