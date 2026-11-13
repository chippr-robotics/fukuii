package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString

import scala.util.Failure
import scala.util.Success

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.StateAnchor
import com.chipprbots.ethereum.db.storage.pathstate.StateLayout
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Spec 018 Track C, C3c (T107, T115): the genesis of a datadir on the unified state layout.
  *
  * Each genesis is loaded twice by the production loader: onto the hash store of one datadir, and onto the unified
  * layout of another. The first says what the genesis state is, by a walk of its raw nodes; the second must hold
  * exactly those nodes as rows, each trie's under its owner, with the genesis block as the anchor.
  */
class GenesisCommitSpec extends AnyFlatSpec with Matchers:

  private type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()
  private def shipped(chain: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig(s"fukuii.blockchains.$chain"))

  /** A node over `source`, on the hash store or, with `guard`, on the unified layout. */
  final private class Node(val source: EphemDataSource, guard: Boolean) extends EphemBlockchainTestSetup:
    private trait Shared extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new Shared with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = guard
    def storages = storagesInstance.storages
    def loader: GenesisDataLoader = GenesisDataLoader.over(blockchainReader, blockchainWriter, storages)
    def genesis: Option[BlockHeader] = blockchainReader.getBlockHeaderByNumber(0)
    def disk: Disk = PathStateTestKit.allRows(new PathNodeStorage(source))

  /** What every client of the network has as the hash of its genesis: the two layouts agreeing is not enough. */
  private def pinned(header: BlockHeader, hash: String): Unit =
    org.bouncycastle.util.encoders.Hex.toHexString(header.hash.value.toArray) shouldBe hash

  private def refOf(header: BlockHeader): BlockRef =
    BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  /** The rows a state is, by a walk of its raw nodes in a hash store. */
  private def rowsOf(root: ByteString, hashes: EphemDataSource): Disk =
    val nodes = new NodeStorage(hashes)
    val positions = RawTrieWalk.statePositions(
      root.toArray,
      hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
    )
    (
      positions.getOrElse(None, Map.empty),
      positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
    )

  /** The genesis on the hash store, and on the unified layout: both nodes, loaded. */
  private def loadedBothWays(load: GenesisDataLoader => Unit): (Node, Node) =
    val onHashes = new Node(EphemDataSource(), guard = false)
    load(onHashes.loader)
    val onPositions = new Node(EphemDataSource(), guard = true)
    load(onPositions.loader)
    (onHashes, onPositions)

  /** Everything the genesis commit of a datadir must leave. */
  private def committed(onHashes: Node, onPositions: Node): BlockHeader =
    val header = onPositions.genesis.getOrElse(fail("no genesis block on the unified layout"))
    Some(header.hash) shouldBe onHashes.genesis.map(_.hash)
    // the rows are the genesis state, and the hash store of this datadir holds none of it
    onPositions.disk shouldBe rowsOf(header.stateRoot.value, onHashes.source)
    PathStateTestKit.hashStoreRows(onPositions.source) shouldBe empty
    // the anchor, in memory and on disk, and a view of it at once
    val pathState = onPositions.storages.pathStateStorage.get
    pathState.anchor shouldBe Some(refOf(header))
    onPositions.storages.appStateStorage.getStateAnchor() shouldBe Some(refOf(header))
    // and the block the state began at: no move of the head goes below it
    onPositions.storages.appStateStorage.getStateOrigin() shouldBe Some(refOf(header))
    pathState.view(refOf(header)).isAnchorViewNow shouldBe true
    // the block, the head, the stamp, and the marks of a datadir this build created
    onPositions.storages.appStateStorage.getBestBlockInfo() shouldBe BlockInfo(header.hash.value, 0)
    onPositions.blockchainReader.getChainWeightByHash(header.hash) shouldBe
      onHashes.blockchainReader.getChainWeightByHash(header.hash)
    onPositions.blockchainReader.getBlockBodyByHash(header.hash) shouldBe
      onHashes.blockchainReader.getBlockBodyByHash(header.hash)
    onPositions.storages.appStateStorage.getStateLayout() shouldBe Some(StateLayout.PathLive1)
    onPositions.storages.appStateStorage.isDatadirCreatedUnderInvariant() shouldBe true
    onPositions.storages.appStateStorage.hasChainWeightInvariant() shouldBe true
    // nothing on this layout has reference counts: it is never said that they are exact
    onPositions.storages.appStateStorage.hasRefCountsExact() shouldBe false
    // nothing came before a genesis: no changeset record, and no count of pruned ones
    new ChangesetStorage(onPositions.source).isEmpty shouldBe true
    onPositions.storages.appStateStorage.getChangesetPrunedTo() shouldBe None
    header

  "The genesis of a shipped chain, on the unified layout" should "be the rows of its state: Ethereum Classic's 8,893 accounts" taggedAs
    UnitTest in {
      given BlockchainConfig = shipped("etc")
      val (onHashes, onPositions) = loadedBothWays(_.loadGenesisData())
      val header = committed(onHashes, onPositions)
      pinned(header, "d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3")
      onPositions.disk._1.size should be > 8000
      onPositions.disk._2 shouldBe empty
      header.stateRoot.value should not be ByteString(MerklePatriciaTrie.EmptyRootHash)
    }

  // Mordor allocates nothing: the root of the empty trie, and no row.
  it should "be no row at all for Mordor, whose genesis allocates nothing" taggedAs UnitTest in {
    given BlockchainConfig = shipped("mordor")
    val (onHashes, onPositions) = loadedBothWays(_.loadGenesisData())
    val header = committed(onHashes, onPositions)
    pinned(header, "a68ebde7932eccb177d38d55dcc6461a019dd795a681e59b5a3e4f3a7259a3f1")
    header.stateRoot.value shouldBe ByteString(MerklePatriciaTrie.EmptyRootHash)
    onPositions.disk shouldBe (Map.empty, Map.empty)
  }

  // Contracts with code, with storage, and with nonces: the system contracts of an Ethereum genesis.
  it should "hold each contract's storage under its own account: Platåberget and Sepolia" taggedAs UnitTest in {
    Seq(
      "plataberget" -> "ee33ef92bbabcf07bcf44fea1d18a7925c5f7f9da8f81334ea19b0f3cb892b31",
      "sepolia" -> "25a5cc106eea7138acab33231d7160d69cb777ee0c2c553fcddf5138993e6dd9"
    ).foreach { (chain, hash) =>
      withClue(s"$chain: ") {
        given BlockchainConfig = shipped(chain)
        val (onHashes, onPositions) = loadedBothWays(_.loadGenesisData())
        pinned(committed(onHashes, onPositions), hash)
        if chain == "plataberget" then onPositions.disk._2 should not be empty
      }
    }
  }

  private def account(storage: Map[Int, Int] = Map.empty, balance: Int = 1): GenesisAccount =
    GenesisAccount(
      precompiled = None,
      balance = UInt256(balance),
      code = None,
      nonce = None,
      storage = Option.when(storage.nonEmpty)(storage.map((k, v) => UInt256(k) -> UInt256(v)))
    )
  private def genesisOf(alloc: (String, GenesisAccount)*): GenesisData =
    GenesisData(
      nonce = ByteString(Array.fill[Byte](8)(0)),
      mixHash = None,
      difficulty = "0x400",
      extraData = ByteString.empty,
      gasLimit = "0x47e7c4",
      coinbase = ByteString(Array.fill[Byte](20)(0)),
      timestamp = "0x0",
      alloc = alloc.toMap
    )
  private val manySlots: Map[Int, Int] = (1 to 40).map(i => i -> (i * 7919)).toMap
  private def owner(address: String): ByteString =
    ByteString(com.chipprbots.ethereum.crypto.kec256(org.bouncycastle.util.encoders.Hex.decode(address)))
  private val alice = "00000000000000000000000000000000000000a1"
  private val bob = "00000000000000000000000000000000000000b2"
  private val carol = "00000000000000000000000000000000000000c3"

  // On the hash store two accounts with one storage share its nodes, which is why that build drops the tries'
  // removals. Here each has rows of its own.
  "A genesis whose accounts hold the same storage" should "give each of them the rows of its own trie" taggedAs
    UnitTest in {
      given BlockchainConfig = shipped("etc")
      val genesis =
        genesisOf(alice -> account(manySlots), bob -> account(manySlots), carol -> account(Map(1 -> 1), balance = 5))
      val (onHashes, onPositions) = loadedBothWays(_.loadGenesisData(genesis).get)
      committed(onHashes, onPositions)
      val storage = onPositions.disk._2
      val ofA = storage.collect { case (key, node) if key.startsWith(owner(alice)) => key.drop(32) -> node }
      val ofB = storage.collect { case (key, node) if key.startsWith(owner(bob)) => key.drop(32) -> node }
      ofA.size should be > 1
      ofA shouldBe ofB
      storage.keys.count(_.startsWith(owner(carol))) shouldBe 1
    }

  // A slot of zero is not stored, so a storage of zeros is no storage: the account's root is the empty root.
  "A genesis account whose storage is all zeros" should "have no row of storage" taggedAs UnitTest in {
    given BlockchainConfig = shipped("etc")
    val genesis = genesisOf(alice -> account(Map(1 -> 0, 2 -> 0)), bob -> account(Map(1 -> 0, 2 -> 5)))
    val (onHashes, onPositions) = loadedBothWays(_.loadGenesisData(genesis).get)
    committed(onHashes, onPositions)
    onPositions.disk._2.keys.exists(_.startsWith(owner(alice))) shouldBe false
    onPositions.disk._2.keys.count(_.startsWith(owner(bob))) shouldBe 1
  }

  // The storages of the unified layout over a data source of the test's own (a watched one). The data source and the
  // pruning mode are trait parameters' values, so that they are there when the storages are built.
  private trait Given(source: com.chipprbots.ethereum.db.dataSource.DataSource)
      extends com.chipprbots.ethereum.db.components.DataSourceComponent
      with Storages.PruningModeComponent:
    override val dataSource: com.chipprbots.ethereum.db.dataSource.DataSource = source
    override val pruningMode: com.chipprbots.ethereum.db.storage.pruning.PruningMode =
      com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
  private class OnUnifiedLayout(source: com.chipprbots.ethereum.db.dataSource.DataSource)
      extends Given(source)
      with Storages.DefaultStorages:
    override protected def statePathLive: Boolean = true

  // ─── One batch ─────────────────────────────────────────────────────────────────────────────────────────────────────

  "The genesis commit" should "be one write: the block, the rows, the stamp, and the anchor last" taggedAs UnitTest in {
    import PathStateTestKit.Watched
    given BlockchainConfig = shipped("etc")
    val watched = new Watched(EphemDataSource())
    val Parts = new OnUnifiedLayout(watched)
    val reader = BlockchainReader(Parts.storages)
    val writer = BlockchainWriter(Parts.storages)
    val genesis = genesisOf(alice -> account(manySlots), bob -> account(balance = 9))
    val before = watched.batchesWritten.size

    var seenInFlight = false
    watched.beforeUpdate = batch =>
      // code is written first, on its own, as it always was; the commit's batch is the one that carries the anchor
      val anchors = batch.collect { case DataSourceUpdate(_, _, upserts) =>
        upserts.exists((key, _) => new String(key.toArray, "UTF-8") == StateAnchor.Key)
      }
      if anchors.contains(true) then
        seenInFlight = Parts.storages.pathStateStorage.get.clock.atRest.isEmpty &&
          Parts.storages.pathStateStorage.get.lock.holder.exists(_.contains("genesis commit"))
    GenesisDataLoader.over(reader, writer, Parts.storages).loadGenesisData(genesis).get
    watched.beforeUpdate = _ => ()

    val batches = watched.batchesWritten.drop(before)
    batches should have size 1
    seenInFlight shouldBe true
    // a datadir's first state is synced to the disk before the node goes on
    watched.callsMade.filter(_.startsWith("update")) shouldBe Seq("updateSync")
    val keys = batches.head.collect { case DataSourceUpdate(_, _, upserts) =>
      upserts.map((key, _) => new String(key.toArray, "UTF-8"))
    }.flatten
    keys should contain(StateLayout.Key)
    keys should contain("BestBlockNumber")
    batches.head.last match
      case DataSourceUpdate(_, _, upserts) =>
        upserts.map((key, _) => new String(key.toArray, "UTF-8")) shouldBe Seq(StateAnchor.Key)
      case other => fail(s"the batch ends on $other")
    Parts.storages.pathStateStorage.get.clock.epoch shouldBe 2L
  }

  // Code is kept by its hash on both layouts, and written as the build meets it: before the batch, and not synced. The
  // batch's sync is the one that makes it durable, and a build that is lost writes the same code again.
  it should "follow the code of the genesis, which is written first and by its hash" taggedAs UnitTest in {
    import PathStateTestKit.Watched
    given BlockchainConfig = shipped("etc")
    val watched = new Watched(EphemDataSource())
    val Parts = new OnUnifiedLayout(watched)
    val loader =
      GenesisDataLoader.over(BlockchainReader(Parts.storages), BlockchainWriter(Parts.storages), Parts.storages)
    val code = ByteString(Array[Byte](0x60, 0x00, 0x60, 0x00, 0xf3.toByte))
    val before = watched.callsMade.size
    loader.loadGenesisData(genesisOf(alice -> account(manySlots).copy(code = Some(code)), bob -> account())).get

    watched.callsMade.drop(before).filter(_.startsWith("update")) shouldBe Seq("update", "updateSync")
    val keysOf = (batch: Seq[com.chipprbots.ethereum.db.dataSource.DataUpdate]) =>
      batch.collect { case DataSourceUpdate(_, _, upserts) =>
        upserts.map((key, _) => new String(key.toArray, "UTF-8"))
      }.flatten
    val Seq(first, second) = watched.batchesWritten.takeRight(2): @unchecked
    keysOf(first) should not contain StateAnchor.Key
    keysOf(second) should contain(StateAnchor.Key)
    Parts.storages.evmCodeStorage.get(ByteString(com.chipprbots.ethereum.crypto.kec256(code))) shouldBe Some(code)
  }

  it should "leave nothing when its write fails: no block, no row, no stamp, no anchor" taggedAs UnitTest in {
    import PathStateTestKit.Watched
    given BlockchainConfig = shipped("etc")
    val watched = new Watched(EphemDataSource())
    val Parts = new OnUnifiedLayout(watched)
    val reader = BlockchainReader(Parts.storages)
    val writer = BlockchainWriter(Parts.storages)
    val loader = GenesisDataLoader.over(reader, writer, Parts.storages)
    val genesis = genesisOf(alice -> account(manySlots))
    watched.beforeUpdate = _ => throw new java.io.IOException("the disk is full")
    loader.loadGenesisData(genesis) match
      case Failure(failed) => failed shouldBe a[java.io.IOException]
      case Success(_)      => fail("a write that failed was taken as a genesis")
    watched.beforeUpdate = _ => ()

    reader.getBlockHeaderByNumber(0) shouldBe None
    PathStateTestKit.allRows(new PathNodeStorage(watched.under)) shouldBe (Map.empty, Map.empty)
    Parts.storages.appStateStorage.getStateLayout() shouldBe None
    Parts.storages.appStateStorage.getStateAnchor() shouldBe None
    Parts.storages.appStateStorage.getStateOrigin() shouldBe None
    Parts.storages.pathStateStorage.get.anchor shouldBe None
    Parts.storages.pathStateStorage.get.lock.holder shouldBe None
    // and the next start commits it
    loader.loadGenesisData(genesis).get
    Parts.storages.pathStateStorage.get.anchor.map(_.number) shouldBe Some(BigInt(0))
  }

  // ─── A datadir that already has something ──────────────────────────────────────────────────────────────────────────

  "A start on a datadir that has its genesis" should "write nothing: the build is dropped" taggedAs UnitTest in {
    given BlockchainConfig = shipped("plataberget")
    val node = new Node(EphemDataSource(), guard = true)
    node.loader.loadGenesisData()
    val rows = node.disk
    val anchor = node.storages.pathStateStorage.get.anchor
    val epoch = node.storages.pathStateStorage.get.clock.epoch

    // the same process again, and a new process over the same datadir
    node.loader.loadGenesisData()
    val restarted = new Node(node.source, guard = true)
    restarted.loader.loadGenesisData()
    restarted.disk shouldBe rows
    restarted.storages.pathStateStorage.get.anchor shouldBe anchor
    node.storages.pathStateStorage.get.clock.epoch shouldBe epoch
  }

  it should "still refuse a genesis that is not the one stored" taggedAs UnitTest in {
    given BlockchainConfig = shipped("etc")
    val node = new Node(EphemDataSource(), guard = true)
    node.loader.loadGenesisData(genesisOf(alice -> account(balance = 1))).get
    val rows = node.disk
    node.loader.loadGenesisData(genesisOf(alice -> account(balance = 2))) match
      case Failure(refused) => refused.getMessage should include("does not match genesis block")
      case Success(_)       => fail("another genesis was taken")
    node.disk shouldBe rows
  }

  "The genesis commit itself" should "refuse a datadir that holds a state already" taggedAs UnitTest in {
    given BlockchainConfig = shipped("etc")
    val node = new Node(EphemDataSource(), guard = true)
    node.loader.loadGenesisData(genesisOf(alice -> account(manySlots))).get
    val pathState = node.storages.pathStateStorage.get
    val rows = node.disk
    val again = pathState.genesisLayer()
    val refused = the[IllegalStateException] thrownBy
      pathState.commitGenesis(again, pathState.anchor.get.copy(hash = ByteString(Array.fill[Byte](32)(9))), None)
    refused.getMessage should (include("already holds").and(include("the state of block 0")))
    node.disk shouldBe rows
    // a block that is not block 0 is no genesis
    an[IllegalArgumentException] should be thrownBy
      pathState.commitGenesis(pathState.genesisLayer(), pathState.anchor.get.copy(number = 1), None)
  }

  // While SNAP owns the rows there is no anchor, and there are rows: not a datadir to commit a genesis to.
  it should "refuse a datadir that holds rows of a state and no anchor" taggedAs UnitTest in {
    val source = EphemDataSource()
    new PathNodeStorage(source).writeStorageNode(owner(alice), Array[Byte](1), Array.fill[Byte](40)(7))
    val node = new Node(source, guard = true)
    val pathState = node.storages.pathStateStorage.get
    pathState.anchor shouldBe None
    val named = BlockRef(ByteString(Array.fill[Byte](32)(1)), 0, ByteString(MerklePatriciaTrie.EmptyRootHash))
    (the[IllegalStateException] thrownBy pathState.commitGenesis(
      pathState.genesisLayer(),
      named,
      None
    )).getMessage should
      include("rows of a state")
    node.storages.appStateStorage.getStateLayout() shouldBe None
    pathState.anchor shouldBe None
  }

  // Each of these is a trace of a state that was here: records of what blocks changed, a count of the records pruned,
  // the stamp of a datadir this layout created. None of them comes with an anchor in a datadir that is whole.
  it should "refuse a datadir that holds a trace of a state: records, a count of pruned ones, the stamp" taggedAs
    UnitTest in {
      import com.chipprbots.ethereum.db.storage.Namespaces
      def text(value: String): IndexedSeq[Byte] = value.getBytes("UTF-8").toIndexedSeq
      val traces = Seq(
        "changeset records" -> DataSourceUpdate(
          Namespaces.StateChangesetNamespace,
          Seq.empty,
          Seq(IndexedSeq.fill[Byte](32)(4) -> IndexedSeq[Byte](1, 2, 3))
        ),
        "a count of pruned changesets (5)" ->
          DataSourceUpdate(
            Namespaces.AppStateNamespace,
            Seq.empty,
            Seq(text("state.changeset.pruned-to") -> text("5"))
          ),
        "a state that began at block 7" ->
          DataSourceUpdate(
            Namespaces.AppStateNamespace,
            Seq.empty,
            Seq(text("state.origin") -> text(s"v1:${"ab" * 32}:7:${"cd" * 32}"))
          ),
        "the layout stamp 'path-live-1'" ->
          DataSourceUpdate(Namespaces.AppStateNamespace, Seq.empty, Seq(text(StateLayout.Key) -> text("path-live-1")))
      )
      traces.foreach { (named, trace) =>
        withClue(s"$named: ") {
          val source = EphemDataSource()
          source.update(Seq(trace))
          val node = new Node(source, guard = true)
          val pathState = node.storages.pathStateStorage.get
          pathState.anchor shouldBe None
          val block = BlockRef(ByteString(Array.fill[Byte](32)(1)), 0, ByteString(MerklePatriciaTrie.EmptyRootHash))
          (the[IllegalStateException] thrownBy pathState.commitGenesis(
            pathState.genesisLayer(),
            block,
            None
          )).getMessage should
            (include("already holds").and(include(named)))
          pathState.anchor shouldBe None
          node.storages.appStateStorage.getStateAnchor() shouldBe None
          node.disk shouldBe (Map.empty, Map.empty)
        }
      }
    }

  // Nothing of a genesis replaces a storage trie whole: there is no account before it to remove.
  it should "refuse a build that replaced a storage trie whole" taggedAs UnitTest in {
    val node = new Node(EphemDataSource(), guard = true)
    val pathState = node.storages.pathStateStorage.get
    val layer = pathState.genesisLayer()
    layer.storage.resetOwner(owner(alice))
    val block = BlockRef(ByteString(Array.fill[Byte](32)(1)), 0, ByteString(MerklePatriciaTrie.EmptyRootHash))
    (the[IllegalStateException] thrownBy pathState.commitGenesis(layer, block, None)).getMessage should
      include("replaced a storage trie whole")
    pathState.anchor shouldBe None
    node.storages.appStateStorage.getStateLayout() shouldBe None
  }

  it should "refuse a build that is not the state its block names" taggedAs UnitTest in {
    val source = EphemDataSource()
    val node = new Node(source, guard = true)
    val pathState = node.storages.pathStateStorage.get
    val layer = pathState.genesisLayer()
    import com.chipprbots.ethereum.mpt.byteStringSerializer
    MerklePatriciaTrie[ByteString, ByteString](layer.storage.forOwner(None)).put(ByteString(1), ByteString(1))
    val named = BlockRef(ByteString(Array.fill[Byte](32)(1)), 0, ByteString(MerklePatriciaTrie.EmptyRootHash))
    (the[IllegalStateException] thrownBy pathState.commitGenesis(layer, named, None)).getMessage should
      include("is not the genesis state")
    node.disk shouldBe (Map.empty, Map.empty)
    pathState.anchor shouldBe None
  }

  // ─── What a genesis may not have here, and which layout may open a datadir ─────────────────────────────────────────

  "A genesis on the unified layout" should "be refused when it allocates one account twice" taggedAs UnitTest in {
    given BlockchainConfig = shipped("etc")
    val twice =
      genesisOf(alice -> account(manySlots), ("0x" + alice.toUpperCase) -> account(balance = 3), bob -> account())
    val node = new Node(EphemDataSource(), guard = true)
    node.loader.loadGenesisData(twice) match
      case Failure(refused) => refused.getMessage should (include("more than once").and(include(alice)))
      case Success(_)       => fail("an account allocated twice was taken")
    node.genesis shouldBe None
    node.disk shouldBe (Map.empty, Map.empty)
    // unpadded and padded are one address too
    GenesisDataLoader.unifiedLayoutRefusal(genesisOf("0x1" -> account(), "0x01" -> account()), 40) should not be empty
    GenesisDataLoader.unifiedLayoutRefusal(genesisOf(alice -> account(), bob -> account()), 40) shouldBe None
    // the hash store takes it as it always did: the last entry is the account
    new Node(EphemDataSource(), guard = false).loader.loadGenesisData(twice) shouldBe a[Success[?]]
  }

  it should "be refused when it has storage and the chain's storage is not hashed the Ethereum way" taggedAs
    UnitTest in {
      given BlockchainConfig = shipped("etc").copy(ethCompatibleStorage = false)
      val node = new Node(EphemDataSource(), guard = true)
      node.loader.loadGenesisData(genesisOf(alice -> account(Map(1 -> 1)))) match
        case Failure(refused) => refused.getMessage should include("eth-compatible-storage")
        case Success(_)       => fail("genesis storage was placed for a chain that would not find it")
      node.genesis shouldBe None
      // accounts with no storage are no difficulty, and a storage of zeros is no storage
      node.loader.loadGenesisData(genesisOf(alice -> account(Map(1 -> 0)), bob -> account())) shouldBe a[Success[?]]
    }

  "A datadir" should "be opened only by the layout that wrote it" taggedAs UnitTest in {
    given BlockchainConfig = shipped("etc")
    val genesis = genesisOf(alice -> account(manySlots))

    // written by the unified layout, opened with the guard off
    val unified = new Node(EphemDataSource(), guard = true)
    unified.loader.loadGenesisData(genesis).get
    new Node(unified.source, guard = false).loader.loadGenesisData(genesis) match
      case Failure(refused) => refused.getMessage should include("fukuii.state.path-live is off")
      case Success(_)       => fail("the hash layout opened a datadir of the unified layout")

    // written by the hash layout, opened with the guard on
    val hashed = new Node(EphemDataSource(), guard = false)
    hashed.loader.loadGenesisData(genesis).get
    val opened = new Node(hashed.source, guard = true)
    opened.loader.loadGenesisData(genesis) match
      case Failure(refused) => refused.getMessage should include("earlier storage layout")
      case Success(_)       => fail("the unified layout opened a datadir of the hash layout")
    opened.disk shouldBe (Map.empty, Map.empty)
    opened.storages.pathStateStorage.get.anchor shouldBe None

    // the rule, case by case
    import GenesisDataLoader.layoutRefusal
    layoutRefusal(pathLive = false, stamp = None, genesisStored = true) shouldBe None
    layoutRefusal(pathLive = false, stamp = None, genesisStored = false) shouldBe None
    layoutRefusal(pathLive = false, stamp = Some(StateLayout.PathLive1), genesisStored = true) should not be empty
    layoutRefusal(pathLive = true, stamp = Some(StateLayout.PathLive1), genesisStored = true) shouldBe None
    layoutRefusal(pathLive = true, stamp = None, genesisStored = false) shouldBe None
    layoutRefusal(pathLive = true, stamp = None, genesisStored = true) should not be empty
    layoutRefusal(pathLive = true, stamp = Some("path-live-2"), genesisStored = true).get should include(
      "does not know"
    )
  }

  // With the guard off nothing of this is touched: the load is what it was.
  "The genesis on the hash store" should "write no stamp, no anchor and no row of the unified layout" taggedAs UnitTest in {
    given BlockchainConfig = shipped("plataberget")
    val node = new Node(EphemDataSource(), guard = false)
    node.loader.loadGenesisData()
    node.genesis should not be empty
    node.storages.appStateStorage.getStateLayout() shouldBe None
    node.storages.appStateStorage.getStateAnchor() shouldBe None
    node.disk shouldBe (Map.empty, Map.empty)
    PathStateTestKit.hashStoreRows(node.source) should not be empty
  }
