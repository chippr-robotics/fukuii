package com.chipprbots.ethereum.network.snapserver

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerEventCmd
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testing.TestMptStorage

// ── K9: Actor-level integration tests for NetworkPeerManagerActor's SNAP serving path.
//
// Companion to SnapServerSpec (K8, pure unit tests) and SnapServerLimitsSpec (K7, budgets).
// Tests that the actor correctly routes incoming SNAP protocol requests to SnapServer methods
// and sends responses via peerManagerActor.SendMessage, including when the peer is unknown,
// storage is absent, or the state root falls outside the 128-block freshness window.

class SnapServingActorSpec extends AnyFlatSpec with Matchers with MockFactory with BeforeAndAfterAll:

  implicit val system: ActorSystem = ActorSystem("SnapServingActorSpec_System")

  override def afterAll(): Unit =
    val _ = system.terminate()

  private val zeroHash = ByteString(new Array[Byte](32))
  private val maxHash = ByteString(Array.fill[Byte](32)(0xff.toByte))
  private val bigBudget = BigInt(2 * 1024 * 1024)
  private val testPeer = PeerId("snap-serving-test-peer")

  // Shared appStateStorage: the actor reads nothing from it during SNAP request handling.
  private val appStateStorage = new AppStateStorage(EphemDataSource())

  /** Create the actor under test with a fresh TestProbe for each logical role. */
  private def makeActor(
      peerManager: TestProbe,
      peerEventBus: TestProbe,
      evmCodeStorageOpt: Option[com.chipprbots.ethereum.db.storage.EvmCodeStorage] = None,
      mptStorageOpt: Option[com.chipprbots.ethereum.db.storage.MptStorage] = None,
      blockchainReader: Option[BlockchainReader] = None,
      // the state served for a root; by default `mptStorageOpt`, whatever the root
      stateForRoot: Option[ByteString => Option[com.chipprbots.ethereum.db.storage.MptStorage]] = None
  ): ActorRef = system
    .spawn(
      NetworkPeerManagerActor.behavior(
        peerManagerActor = peerManager.ref.toTyped[PeerManagerActor.Command],
        peerEventBusActor = peerEventBus.ref.toTyped[PeerEventBusActor.Command],
        appStateStorage = appStateStorage,
        forkResolverOpt = None,
        initialSnapSyncControllerOpt = None,
        evmCodeStorageOpt = evmCodeStorageOpt,
        stateForRoot = stateForRoot.getOrElse(_ => mptStorageOpt),
        blockchainReader = blockchainReader,
        isPoWChain = false
      ),
      s"npma-snap-${java.util.UUID.randomUUID()}"
    )
    .toClassic

  /** Build a state trie with n EOA accounts and return (rootHash, storage). */
  private def buildAccountTrie(n: Int): (ByteString, TestMptStorage) =
    val storage = new TestMptStorage()
    val trie = (0 until n).foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { (t, i) =>
      t.put(kec256(ByteString(s"acct-actor-$i")), Account(nonce = i + 1, balance = 1000))
    }
    (ByteString(trie.getRootHash), storage)

  /** Drain one outbound SendMessage from the peerManager probe. */
  private def nextSend(pm: TestProbe): PeerManagerActor.SendMessageCmd =
    pm.expectMsgType[PeerManagerActor.SendMessageCmd](2.seconds)

  // ── Test 1: unknown peer → response still served ────────────────────────────
  //
  // go-ethereum subscribes globally to SNAP codes (before per-peer subscriptions) so it can
  // serve GetAccountRange requests that arrive before the ETH-status handshake completes.
  // Fukuii mirrors this: SNAP requests are handled regardless of peersWithInfo membership.

  "NetworkPeerManagerActor SNAP serving" should
    "serve GetAccountRange even when the peer is not yet in peersWithInfo" taggedAs (
      UnitTest,
      SyncTest
    ) in {
      val pm = TestProbe("pm-unknown-peer")
      val eb = TestProbe("eb-unknown-peer")
      val (root, storage) = buildAccountTrie(6)
      val actor = makeActor(pm, eb, mptStorageOpt = Some(storage))

      actor ! PeerEventCmd(
        MessageFromPeer(
          GetAccountRange(
            requestId = BigInt(1),
            rootHash = root,
            startingHash = zeroHash,
            limitHash = maxHash,
            responseBytes = bigBudget
          ),
          testPeer
        )
      )

      val msg = nextSend(pm)
      msg.peerId shouldBe testPeer
      val resp = msg.message.underlyingMsg.asInstanceOf[AccountRange]
      resp.requestId shouldBe BigInt(1)
      resp.accounts should not be empty
    }

  // ── Test 2: GetByteCodes + evmCodeStorageOpt=None → empty ByteCodes, no crash ─

  it should "return empty ByteCodes with no crash when evmCodeStorageOpt is None" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val pm = TestProbe("pm-no-codes")
    val eb = TestProbe("eb-no-codes")
    val actor = makeActor(pm, eb, evmCodeStorageOpt = None)

    actor ! PeerEventCmd(
      MessageFromPeer(
        GetByteCodes(requestId = BigInt(42), hashes = Seq(kec256(ByteString("somecode"))), responseBytes = bigBudget),
        testPeer
      )
    )

    val resp = nextSend(pm).message.underlyingMsg.asInstanceOf[ByteCodes]
    resp.requestId shouldBe BigInt(42)
    resp.codes shouldBe empty
  }

  // ── Test 3: GetAccountRange + mptStorageOpt=None → empty AccountRange, no crash ─

  it should "return empty AccountRange with no crash when mptStorageOpt is None" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val pm = TestProbe("pm-no-mpt")
    val eb = TestProbe("eb-no-mpt")
    val actor = makeActor(pm, eb, mptStorageOpt = None)

    actor ! PeerEventCmd(
      MessageFromPeer(
        GetAccountRange(
          requestId = BigInt(7),
          rootHash = zeroHash,
          startingHash = zeroHash,
          limitHash = maxHash,
          responseBytes = bigBudget
        ),
        testPeer
      )
    )

    val resp = nextSend(pm).message.underlyingMsg.asInstanceOf[AccountRange]
    resp.requestId shouldBe BigInt(7)
    resp.accounts shouldBe empty
    resp.proof shouldBe empty
  }

  // ── Test 4: requestId echoed for all 4 SNAP handlers ────────────────────────
  //
  // Each handler must echo msg.requestId so the remote peer can correlate the response.
  // Use both storages=None so all 4 handlers take the fast "empty" path — avoids the
  // need to build a trie for GetStorageRanges/GetTrieNodes inline.

  it should "echo requestId in responses from all 4 SNAP handlers" taggedAs (UnitTest, SyncTest) in {
    val pm = TestProbe("pm-reqid-echo")
    val eb = TestProbe("eb-reqid-echo")
    val actor = makeActor(pm, eb, evmCodeStorageOpt = None, mptStorageOpt = None)

    actor ! PeerEventCmd(MessageFromPeer(GetAccountRange(BigInt(11), zeroHash, zeroHash, maxHash, bigBudget), testPeer))
    nextSend(pm).message.underlyingMsg.asInstanceOf[AccountRange].requestId shouldBe BigInt(11)

    actor ! PeerEventCmd(
      MessageFromPeer(
        GetStorageRanges(BigInt(22), zeroHash, Seq(zeroHash), zeroHash, maxHash, bigBudget),
        testPeer
      )
    )
    nextSend(pm).message.underlyingMsg.asInstanceOf[StorageRanges].requestId shouldBe BigInt(22)

    actor ! PeerEventCmd(MessageFromPeer(GetByteCodes(BigInt(33), Seq.empty, bigBudget), testPeer))
    nextSend(pm).message.underlyingMsg.asInstanceOf[ByteCodes].requestId shouldBe BigInt(33)

    actor ! PeerEventCmd(MessageFromPeer(GetTrieNodes(BigInt(44), zeroHash, Seq.empty, bigBudget), testPeer))
    nextSend(pm).message.underlyingMsg.asInstanceOf[TrieNodes].requestId shouldBe BigInt(44)
  }

  // ── Test 5: stale state root → empty AccountRange ───────────────────────────
  //
  // Per SNAP/1 spec (and go-ethereum's 128-block window): nodes only need to serve state
  // for "recent" canonical roots. A root not in the last 128 canonical headers should
  // receive an empty AccountRange (no data, no proof) — the client retries with a fresher
  // pivot or falls back to a different peer.

  it should "return empty AccountRange when requested state root is outside the 128-block window" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val pm = TestProbe("pm-freshness")
    val eb = TestProbe("eb-freshness")

    val freshRoot = kec256(ByteString("fresh-state-root"))
    val staleRoot = kec256(ByteString("stale-state-root"))
    // A BlockHeader whose stateRoot is freshRoot. Derived from the genesis fixture so
    // all mandatory fields are valid; only stateRoot is replaced.
    val freshHeader = Fixtures.Blocks.Genesis.header.copy(stateRoot = TrieRoot(freshRoot))

    // Stub: tip=200, every canonical header has freshRoot → staleRoot is never in the cache.
    val readerStub = stub[BlockchainReader]
    (() => readerStub.getBestBlockNumber).when().returns(BigInt(200))
    readerStub.getBlockHeaderByNumber.when(*).returns(Some(freshHeader))

    val (_, storage) = buildAccountTrie(6)
    val actor = makeActor(pm, eb, mptStorageOpt = Some(storage), blockchainReader = Some(readerStub))

    actor ! PeerEventCmd(
      MessageFromPeer(
        GetAccountRange(
          requestId = BigInt(99),
          rootHash = staleRoot,
          startingHash = zeroHash,
          limitHash = maxHash,
          responseBytes = bigBudget
        ),
        testPeer
      )
    )

    val resp = nextSend(pm).message.underlyingMsg.asInstanceOf[AccountRange]
    resp.requestId shouldBe BigInt(99)
    resp.accounts shouldBe empty
    resp.proof shouldBe empty
  }

  // ── Spec 018 Track C (C2b): the state a request is served from is asked for by the request's root ─────────
  //
  // On the unified layout a state is a view of a block, and which block the disk holds moves. So the handlers ask
  // for the state of the root each request names, when the request arrives, and a root that is not served is
  // answered with the empty response.

  it should "ask for the state of the root each request names, and answer a root that is not served with nothing" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.*
    val pm = TestProbe("pm-by-root")
    val eb = TestProbe("eb-by-root")
    val hashed = new HashedState
    val otherRoot = kec256(ByteString("a root that is not served"))
    val asked = scala.collection.mutable.ArrayBuffer.empty[ByteString]
    val actor = makeActor(
      pm,
      eb,
      stateForRoot = Some { root =>
        asked += root
        Option.when(root == hashed.root)(hashed.nodes)
      }
    )
    val aliceHash = owner(alice)
    val rootPath = ByteString(Array[Byte](0x00))

    def accounts(root: ByteString): AccountRange =
      actor ! PeerEventCmd(MessageFromPeer(GetAccountRange(BigInt(1), root, zeroHash, maxHash, bigBudget), testPeer))
      nextSend(pm).message.underlyingMsg.asInstanceOf[AccountRange]
    def slots(root: ByteString): StorageRanges =
      actor ! PeerEventCmd(
        MessageFromPeer(GetStorageRanges(BigInt(2), root, Seq(aliceHash), zeroHash, maxHash, bigBudget), testPeer)
      )
      nextSend(pm).message.underlyingMsg.asInstanceOf[StorageRanges]
    def nodes(root: ByteString): TrieNodes =
      actor ! PeerEventCmd(
        MessageFromPeer(
          GetTrieNodes(BigInt(3), root, Seq(Seq(rootPath), Seq(aliceHash, rootPath)), bigBudget),
          testPeer
        )
      )
      nextSend(pm).message.underlyingMsg.asInstanceOf[TrieNodes]

    accounts(hashed.root).accounts should have size (4 + crowd.size)
    slots(hashed.root).slots.map(_.size) shouldBe Seq(manySlots.size)
    nodes(hashed.root).nodes.map(_.nonEmpty) shouldBe Seq(true, true)

    accounts(otherRoot) shouldBe AccountRange(BigInt(1), Seq.empty, Seq.empty)
    slots(otherRoot) shouldBe StorageRanges(BigInt(2), Seq.empty, Seq.empty)
    nodes(otherRoot) shouldBe TrieNodes(BigInt(3), Seq.empty)

    asked.toSeq shouldBe Seq.fill(3)(hashed.root) ++ Seq.fill(3)(otherRoot)
  }

  // The handler of GetStorageRanges looks each account's storage root up itself and hands the lookup to the server.
  // It took any failure of that lookup as "no such account", which is sent as an account with no storage. When the
  // disk has moved away from the state being read that is false, and a peer that checks it against the account it
  // holds drops this node. The whole request is answered with nothing, wherever the move falls: in the lookup of the
  // first account, of a later one, or in the walk of a storage trie.

  it should "answer GetStorageRanges with nothing wherever the state moves, the lookup of every account's storage root included" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.*
    val pm = TestProbe("pm-stale-lookup")
    val eb = TestProbe("eb-stale-lookup")
    val hashed = new HashedState
    val state =
      new java.util.concurrent.atomic.AtomicReference[com.chipprbots.ethereum.db.storage.MptStorage](hashed.nodes)
    val actor = makeActor(pm, eb, stateForRoot = Some(_ => Some(state.get)))
    def ask(): StorageRanges =
      actor ! PeerEventCmd(
        MessageFromPeer(
          GetStorageRanges(BigInt(7), hashed.root, Seq(owner(alice), owner(carol)), zeroHash, maxHash, bigBudget),
          testPeer
        )
      )
      nextSend(pm).message.underlyingMsg.asInstanceOf[StorageRanges]

    val counted = new StaleAfter(hashed.nodes, healthy = Int.MaxValue)
    state.set(counted)
    val whole = ask()
    whole.slots.map(_.size) shouldBe Seq(manySlots.size, otherSlots.size)
    val reads = counted.readsAsked
    reads should be > 10

    (0 until reads).foreach { healthy =>
      state.set(new StaleAfter(hashed.nodes, healthy))
      withClue(s"the state moved after $healthy of $reads reads: ") {
        ask() shouldBe StorageRanges(BigInt(7), Seq.empty, Seq.empty)
      }
    }
    state.set(new StaleAfter(hashed.nodes, reads))
    ask() shouldBe whole
  }

  // The three handlers on the state the chain reader hands out with the guard on: the position store's view of the
  // block the disk holds, for that block's root and no other, while the block is within the served window.

  it should "serve the root the position store holds through the chain reader, and stop once it is out of the window" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    import com.chipprbots.ethereum.db.storage.PathNodeStorage
    import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
    import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.*
    val pm = TestProbe("pm-guard-on")
    val eb = TestProbe("eb-guard-on")
    val hashed = new HashedState
    val source = EphemDataSource()
    seed(source, hashed, block(1, hashed.root))
    val appState = new AppStateStorage(source)
    appState.putBestBlockNumber(1).commit()
    val reader = new BlockchainReader(
      null,
      null,
      null,
      null,
      null,
      appState,
      null,
      null,
      Some(new PathStateStorage(new PathNodeStorage(source), appState))
    )
    val actor = makeActor(pm, eb, stateForRoot = Some(reader.stateForRoot))
    val aliceHash = owner(alice)
    val rootPath = ByteString(Array[Byte](0x00))
    val paths = Seq(Seq(rootPath), Seq(aliceHash, rootPath))

    def accounts(root: ByteString): AccountRange =
      actor ! PeerEventCmd(MessageFromPeer(GetAccountRange(BigInt(1), root, zeroHash, maxHash, bigBudget), testPeer))
      nextSend(pm).message.underlyingMsg.asInstanceOf[AccountRange]
    def slots(root: ByteString): StorageRanges =
      actor ! PeerEventCmd(
        MessageFromPeer(GetStorageRanges(BigInt(2), root, Seq(aliceHash), zeroHash, maxHash, bigBudget), testPeer)
      )
      nextSend(pm).message.underlyingMsg.asInstanceOf[StorageRanges]
    def nodes(root: ByteString): TrieNodes =
      actor ! PeerEventCmd(MessageFromPeer(GetTrieNodes(BigInt(3), root, paths, bigBudget), testPeer))
      nextSend(pm).message.underlyingMsg.asInstanceOf[TrieNodes]

    // what the server answers from the same state on a hash store
    accounts(hashed.root) shouldBe SnapServer.serveAccountRange(
      1,
      hashed.root,
      zeroHash,
      maxHash,
      bigBudget,
      hashed.nodes
    )
    slots(hashed.root) shouldBe SnapServer.serveStorageRanges(
      2,
      hashed.root,
      Seq(aliceHash),
      zeroHash,
      maxHash,
      bigBudget,
      hashed.nodes,
      SnapServer.storageRootOf(hashed.root, hashed.nodes)
    )
    nodes(hashed.root) shouldBe SnapServer.serveTrieNodes(3, hashed.root, paths, bigBudget, hashed.nodes)
    accounts(hashed.root).accounts should have size (4 + crowd.size)

    val otherRoot = kec256(ByteString("a root the disk does not hold"))
    accounts(otherRoot) shouldBe AccountRange(BigInt(1), Seq.empty, Seq.empty)
    slots(otherRoot) shouldBe StorageRanges(BigInt(2), Seq.empty, Seq.empty)
    nodes(otherRoot) shouldBe TrieNodes(BigInt(3), Seq.empty)

    // the head moves on past the window: the block the disk holds is no longer one a syncing peer may ask about
    appState.putBestBlockNumber(1 + BlockchainReader.ServedRootWindow + 1).commit()
    accounts(hashed.root) shouldBe AccountRange(BigInt(1), Seq.empty, Seq.empty)
    slots(hashed.root) shouldBe StorageRanges(BigInt(2), Seq.empty, Seq.empty)
    nodes(hashed.root) shouldBe TrieNodes(BigInt(3), Seq.empty)
  }

  // A request is answered whatever happens while its state is being found: a handler that threw would take the actor
  // through a restart with the peer still waiting, for every other peer's requests too.

  it should "answer each request with nothing, and stay up, when the state of its root cannot be handed out" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val pm = TestProbe("pm-no-state")
    val eb = TestProbe("eb-no-state")
    val (root, storage) = buildAccountTrie(6)
    val broken = new java.util.concurrent.atomic.AtomicBoolean(true)
    val actor = makeActor(
      pm,
      eb,
      stateForRoot = Some { _ =>
        if broken.get then throw new IllegalStateException("the store is closing") else Some(storage)
      }
    )
    def accounts(): AccountRange =
      actor ! PeerEventCmd(MessageFromPeer(GetAccountRange(BigInt(1), root, zeroHash, maxHash, bigBudget), testPeer))
      nextSend(pm).message.underlyingMsg.asInstanceOf[AccountRange]

    accounts() shouldBe AccountRange(BigInt(1), Seq.empty, Seq.empty)
    actor ! PeerEventCmd(
      MessageFromPeer(GetStorageRanges(BigInt(2), root, Seq(zeroHash), zeroHash, maxHash, bigBudget), testPeer)
    )
    nextSend(pm).message.underlyingMsg shouldBe StorageRanges(BigInt(2), Seq.empty, Seq.empty)
    actor ! PeerEventCmd(
      MessageFromPeer(GetTrieNodes(BigInt(3), root, Seq(Seq(ByteString(Array[Byte](0x00)))), bigBudget), testPeer)
    )
    nextSend(pm).message.underlyingMsg shouldBe TrieNodes(BigInt(3), Seq.empty)

    // the same actor serves the next request
    broken.set(false)
    accounts().accounts should have size 6
  }
