package com.chipprbots.ethereum.blockchain.sync

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

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
    val genesis = chain.refOf(chain.head)
    var cleared = 0
    var handedOff = 0
    var clearedBeforeTold = Option.empty[Boolean]
    val port = new PathLiveSync(
      chain.state,
      chain.appState,
      new SyncHandoff(
        chain.state,
        new PathNodeStorage(chain.source),
        chain.appState,
        chain.headersByHash,
        chain.hashesByNumber,
        new ChainWeightStorage(chain.source),
        new BlockBodiesStorage(chain.source),
        new EvmCodeStorage(chain.source)
      ),
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

  it should "refuse a SNAP sync over a state the node followed the chain to, and leave it" taggedAs UnitTest in {
    val node = new Node
    node.followed()
    val held = node.chain.state.anchor

    node.port.beforeSnap() shouldBe a[SnapStartOnGuard.Refused]

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

  it should "refuse, and clear nothing, while a recovery of the state executes a block" taggedAs UnitTest in {
    val node = new Node(recoveryExecuting = true)

    node.port.beforeSnap() shouldBe a[SnapStartOnGuard.Refused]

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

  it should "refuse regular sync from the genesis, naming the flag, and nothing else" taggedAs UnitTest in {
    val node = new Node
    val genesis = Some(node.genesis.copy(number = 0))
    def refusal(
        doSnap: Boolean = false,
        test: Boolean = false,
        anchor: Option[BlockRef] = genesis,
        started: Boolean = false
    ) =
      PathLiveSync.regularSyncFromGenesisRefusal(doSnap, test, anchor, started)

    refusal().get should include("fukuii.sync.do-snap-sync = false")
    withClue("no state at all: ")(refusal(anchor = None) should not be empty)
    withClue("SNAP on: ")(refusal(doSnap = true) shouldBe None)
    withClue("test mode, which does not sync: ")(refusal(test = true) shouldBe None)
    withClue("a chain file took it above the genesis: ")(
      refusal(anchor = Some(node.genesis.copy(number = 3))) shouldBe None
    )
    withClue("a sync done or begun, which goes on: ")(refusal(started = true) shouldBe None)
  }
