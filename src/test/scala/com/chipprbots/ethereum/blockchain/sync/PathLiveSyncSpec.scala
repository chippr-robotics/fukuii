package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter
import com.chipprbots.ethereum.blockchain.sync.SyncController.SnapStartOnGuard
import com.chipprbots.ethereum.db.dataSource.DataSource.Namespace
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.BlockBodiesStorage
import com.chipprbots.ethereum.db.storage.ChainWeightStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.CanonicalChainOnPositions
import com.chipprbots.ethereum.db.storage.pathstate.SyncHandoff
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*

/** A sync on the unified state layout (spec 018 C5a part 10): the check every route into SNAP asks first, and the two
  * refusals of a start.
  */
class PathLiveSyncSpec extends AnyFlatSpec with Matchers:

  /** A store whose range deletes fail while `failing`: a clear killed after its first batch. */
  final private class Failing extends EphemDataSource(Map.empty):
    @volatile var failing = false
    override def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
      if failing then throw new IllegalStateException("the store failed on a range delete")
      super.deleteRange(namespace, fromKey, toKeyExclusive)

  /** The datadir and what the port does beside the clear, counted. */
  private class Node(recoveryExecuting: Boolean = false, val source: Failing = new Failing):
    val chain = new CanonicalChainOnPositions(source = source)
    // the block the chain began at stands for the genesis: what a node holds before it has synced
    val genesis: BlockRef = chain.refOf(chain.head)
    var cleared = 0
    var handedOff = 0
    var clearedBeforeTold: Option[Boolean] = None
    private val handoff = new SyncHandoff(
      chain.state,
      new PathNodeStorage(chain.source),
      chain.appState,
      chain.headersByHash,
      chain.hashesByNumber,
      new ChainWeightStorage(chain.source),
      new BlockBodiesStorage(chain.source),
      new EvmCodeStorage(chain.source)
    )
    val port = new PathLiveSync(
      chain.state,
      chain.appState,
      handoff,
      genesis = () => genesis,
      resetRecoveries = clear =>
        if recoveryExecuting then false
        else
          clear()
          true
      ,
      stateCleared = () =>
        // what the node keeps of the state goes after the clear, not before (beacon)
        clearedBeforeTold = Some(chain.state.anchor.isEmpty)
        cleared += 1
      ,
      handedOffNow = () => handedOff += 1,
      checkpointTarget = new CheckpointImporter.UnifiedTarget(
        chain.state,
        new PathNodeStorage(chain.source),
        handoff,
        chain.appState,
        () => genesis,
        () => 7L
      ),
      now = () => 7L
    )

    def followed(): Unit =
      chain.next(_.saveAccount(Address(0x10), Account(balance = UInt256(1))))
      chain.state.anchor shouldBe Some(chain.refOf(chain.head))

  "The check before a SNAP sync on the unified layout" should "clear the genesis state, and then what is kept of it" taggedAs UnitTest in {
    val node = new Node
    node.chain.state.anchor shouldBe Some(node.genesis)

    node.port.beforeSnap() shouldBe SnapStartOnGuard.Begin(clearedGenesis = true)

    node.chain.state.anchor shouldBe None
    node.chain.appState.isStateResetPending() shouldBe false
    withClue("the escalation's count and the fork choice's head are dropped, after the clear: ") {
      (node.cleared, node.clearedBeforeTold) shouldBe (1, Some(true))
    }
  }

  it should "let a sync begin, or resume, on a datadir that holds no state, and clear nothing" taggedAs UnitTest in {
    val node = new Node
    node.port.beforeSnap(): Unit
    node.cleared = 0

    node.port.beforeSnap() shouldBe SnapStartOnGuard.Begin(clearedGenesis = false)
    node.cleared shouldBe 0
  }

  it should "not start a SNAP sync over a state the node followed the chain to (HoldsState, not a fault), and leave it" taggedAs UnitTest in {
    val node = new Node
    node.followed()
    val held = node.chain.state.anchor

    node.port.beforeSnap() shouldBe SnapStartOnGuard.HoldsState(held.get.show)

    node.chain.state.anchor shouldBe held
    node.cleared shouldBe 0
  }

  it should "refuse while the mark of a clear that has not ended is set" taggedAs UnitTest in {
    val node = new Node
    node.followed()
    // an escalation's clear killed after its first batch: no anchor, and the mark
    node.source.failing = true
    an[IllegalStateException] should be thrownBy node.chain.state.clearForResync(node.genesis.hash, 1L, None)
    node.source.failing = false
    node.chain.state.anchor shouldBe None
    node.chain.appState.isStateResetPending() shouldBe true

    node.port.beforeSnap() shouldBe a[SnapStartOnGuard.Refused]
  }

  it should "wait, and clear nothing, while a recovery of the state executes a block" taggedAs UnitTest in {
    val node = new Node(recoveryExecuting = true)

    node.port.beforeSnap() shouldBe a[SnapStartOnGuard.Wait]

    node.chain.state.anchor shouldBe Some(node.genesis)
    node.cleared shouldBe 0
  }

  it should "begin the escalation's count again at a hand-off" taggedAs UnitTest in {
    val node = new Node
    node.port.handedOff()
    node.handedOff shouldBe 1
  }

  "A start on the unified layout" should "refuse -Dfukuii.snap.clearDoneOnStart" taggedAs UnitTest in {
    PathLiveSync.snapRepairRefusal(clearDoneOnStart = true).get should include("clearDoneOnStart")
    PathLiveSync.snapRepairRefusal(clearDoneOnStart = false) shouldBe None
  }

  // the user's decisions of 2026-11-18 (proof of work) and 2026-11-24 (B-314: a chain with a terminal total
  // difficulty, where hive's engine simulators start every client from a bare genesis with do-snap-sync off). The
  // refusal does not ask which kind of chain it is: StdNode passes it nothing that says so.
  it should "take regular sync from the genesis state, and refuse only a node that holds no state" taggedAs UnitTest in {
    val node = new Node
    val genesis = Some(node.genesis.copy(number = 0))
    def refusal(
        doSnap: Boolean = false,
        test: Boolean = false,
        anchor: Option[BlockRef] = genesis,
        started: Boolean = false
    ) =
      PathLiveSync.regularSyncWithNoStateRefusal(doSnap, test, anchor, started)

    withClue("the genesis state, do-snap-sync off, nothing synced (a fresh datadir, hive's engine tests): ")(
      refusal() shouldBe None
    )
    withClue("no state at all: regular sync has nothing to execute the first block on: ") {
      val why = refusal(anchor = None).get
      why should include("holds no state")
      why should include("fukuii.sync.do-snap-sync = false")
    }
    withClue("no state, but a sync begun, which goes on: ")(refusal(anchor = None, started = true) shouldBe None)
    withClue("no state, SNAP on: ")(refusal(doSnap = true, anchor = None) shouldBe None)
    withClue("no state, test mode, which does not sync: ")(refusal(test = true, anchor = None) shouldBe None)
    withClue("a chain file took it above the genesis: ")(
      refusal(anchor = Some(node.genesis.copy(number = 3))) shouldBe None
    )
    withClue("the genesis state, SNAP on: ")(refusal(doSnap = true) shouldBe None)
    withClue("the genesis state, a sync begun: ")(refusal(started = true) shouldBe None)
  }

  "A node that holds only its genesis" should "be one with the genesis state, no SNAP sync done or begun, and no clear pending" taggedAs UnitTest in {
    val fresh = new Node
    fresh.port.holdsOnlyGenesis() shouldBe true

    val begun = new Node
    begun.chain.appState.putSnapSyncPivotBlock(BigInt(5)).commit()
    withClue("a SNAP sync with a pivot: ")(begun.port.holdsOnlyGenesis() shouldBe false)

    val done = new Node
    done.chain.appState.snapSyncDone().commit()
    withClue("a SNAP sync done: ")(done.port.holdsOnlyGenesis() shouldBe false)

    val cleared = new Node
    cleared.port.beforeSnap(): Unit
    withClue("the genesis state cleared for SNAP: ")(cleared.port.holdsOnlyGenesis() shouldBe false)

    val followed = new Node
    followed.followed()
    withClue("a block above the genesis: ")(followed.port.holdsOnlyGenesis() shouldBe false)
  }

  // spec 018 C6 (T241/T242), the user's decisions of 2026-11-18
  "The snap-peer precondition" should "count the peers on this chain that advertise snap, and the declared ones among them" taggedAs UnitTest in {
    import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
    import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
    def id(i: Int) = org.apache.pekko.util.ByteString(Array.fill[Byte](64)(i.toByte))
    val status = RemoteStatus(
      com.chipprbots.ethereum.network.p2p.messages.Capability.ETH68,
      networkId = 1,
      chainWeight = com.chipprbots.ethereum.domain.ChainWeight.totalDifficultyOnly(1),
      bestHash = org.apache.pekko.util.ByteString("best"),
      genesisHash = org.apache.pekko.util.ByteString("genesis"),
      supportsSnap = true
    )
    def info(snap: Boolean, onChain: Boolean = true) =
      PeerInfo(status.copy(supportsSnap = snap), status.chainWeight, onChain, BigInt(1), status.bestHash)
    val peers = Seq(
      Some(id(1)) -> info(snap = true),
      Some(id(2)) -> info(snap = true),
      None -> info(snap = true),
      Some(id(4)) -> info(snap = false),
      // of another chain: its fork was not accepted
      Some(id(5)) -> info(snap = true, onChain = false)
    )
    PathLiveSync.countSnapPeers(peers, declared = Set(id(2), id(4), id(5), id(9))) shouldBe
      PathLiveSync.SnapPeerCount(connected = 5, snapCapable = 3, declaredSnapCapable = 1)
  }

  it should "on proof of work, at the genesis, wait for min-snap-peers, then begin with some or regular-sync with none" taggedAs UnitTest in {
    import PathLiveSync.SnapPeerVerdict.*
    def count(snap: Int) = Some(PathLiveSync.SnapPeerCount(connected = 4, snapCapable = snap, declaredSnapCapable = 0))
    def verdict(c: Option[PathLiveSync.SnapPeerCount], over: Boolean) =
      PathLiveSync.snapPeerVerdict(c, proofOfWork = true, holdsOnlyGenesis = true, minSnapPeers = 2, waitOver = over)

    verdict(count(2), over = false) shouldBe Begin
    verdict(count(1), over = false) shouldBe Wait
    verdict(count(0), over = false) shouldBe Wait
    verdict(None, over = false) shouldBe Wait
    verdict(count(1), over = true) shouldBe Begin
    verdict(count(0), over = true) shouldBe RegularSyncFromGenesis
    withClue("no count ever came: SNAP's own wait is what is left: ")(verdict(None, over = true) shouldBe Begin)
  }

  it should "never wait nor regular-sync from the genesis on a chain with a terminal total difficulty" taggedAs UnitTest in {
    val none = Some(PathLiveSync.SnapPeerCount(connected = 4, snapCapable = 0, declaredSnapCapable = 0))
    for
      over <- Seq(false, true)
      genesisOnly <- Seq(false, true)
      c <- Seq(none, None)
    do
      withClue(s"waitOver=$over holdsOnlyGenesis=$genesisOnly count=$c: ") {
        PathLiveSync.snapPeerVerdict(c, proofOfWork = false, genesisOnly, minSnapPeers = 2, waitOver = over) shouldBe
          PathLiveSync.SnapPeerVerdict.Begin
      }
  }

  it should "never regular-sync from the genesis on a node that holds more than it" taggedAs UnitTest in {
    val none = Some(PathLiveSync.SnapPeerCount(connected = 4, snapCapable = 0, declaredSnapCapable = 0))
    PathLiveSync.snapPeerVerdict(none, proofOfWork = true, holdsOnlyGenesis = false, 2, waitOver = true) shouldBe
      PathLiveSync.SnapPeerVerdict.Begin
  }

  // part 10 step 6 (B-308; the user's decision of 2026-11-17 narrows FR-020 to the hash layout)
  "The storage scheme of a SNAP sync" should "be the path scheme on the unified layout, on every network" taggedAs UnitTest in {
    import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
    import com.chipprbots.ethereum.utils.NetworkType
    NetworkType.values.foreach { network =>
      StorageScheme.values.foreach { configured =>
        withClue(s"$network, configured $configured: ") {
          SyncController.snapStorageScheme(network, configured, unifiedLayout = true) shouldBe Right(StorageScheme.Path)
        }
      }
    }
  }

  it should "be the network's on the hash layout: path on Ethereum's, hash on ETC's, and nothing else" taggedAs UnitTest in {
    import com.chipprbots.ethereum.blockchain.sync.snap.StorageScheme
    import com.chipprbots.ethereum.utils.NetworkType
    SyncController.snapStorageScheme(NetworkType.ETH, StorageScheme.Path, unifiedLayout = false) shouldBe
      Right(StorageScheme.Path)
    SyncController.snapStorageScheme(NetworkType.ETC, StorageScheme.Hash, unifiedLayout = false) shouldBe
      Right(StorageScheme.Hash)
    SyncController.snapStorageScheme(NetworkType.ETC, StorageScheme.Path, unifiedLayout = false) shouldBe a[Left[?, ?]]
    SyncController.snapStorageScheme(NetworkType.ETH, StorageScheme.Hash, unifiedLayout = false) shouldBe a[Left[?, ?]]
  }
