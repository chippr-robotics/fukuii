package com.chipprbots.ethereum.network.snapserver

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestActorRef
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

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
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP._
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.testing.TestMptStorage

// ── K9: Actor-level integration tests for NetworkPeerManagerActor's SNAP serving path.
//
// Companion to SnapServerSpec (K8, pure unit tests) and SnapServerLimitsSpec (K7, budgets).
// Tests that the actor correctly routes incoming SNAP protocol requests to SnapServer methods
// and sends responses via peerManagerActor.SendMessage, including when the peer is unknown,
// storage is absent, or the state root falls outside the 128-block freshness window.

class SnapServingActorSpec extends AnyFlatSpec with Matchers with MockFactory with BeforeAndAfterAll {

  implicit val system: ActorSystem = ActorSystem("SnapServingActorSpec_System")

  override def afterAll(): Unit = { val _ = system.terminate() }

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
      blockchainReader: Option[BlockchainReader] = None
  ): ActorRef = TestActorRef(
    NetworkPeerManagerActor.props(
      peerManagerActor = peerManager.ref,
      peerEventBusActor = peerEventBus.ref,
      appStateStorage = appStateStorage,
      forkResolverOpt = None,
      snapSyncControllerOpt = None,
      evmCodeStorageOpt = evmCodeStorageOpt,
      mptStorageOpt = mptStorageOpt,
      blockchainReader = blockchainReader,
      isPoWChain = false
    )
  )

  /** Build a state trie with n EOA accounts and return (rootHash, storage). */
  private def buildAccountTrie(n: Int): (ByteString, TestMptStorage) = {
    val storage = new TestMptStorage()
    val trie = (0 until n).foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { (t, i) =>
      t.put(kec256(ByteString(s"acct-actor-$i")), Account(nonce = i + 1, balance = 1000))
    }
    (ByteString(trie.getRootHash), storage)
  }

  /** Drain one outbound SendMessage from the peerManager probe. */
  private def nextSend(pm: TestProbe): PeerManagerActor.SendMessage =
    pm.expectMsgType[PeerManagerActor.SendMessage](2.seconds)

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

      actor ! MessageFromPeer(
        GetAccountRange(
          requestId = BigInt(1),
          rootHash = root,
          startingHash = zeroHash,
          limitHash = maxHash,
          responseBytes = bigBudget
        ),
        testPeer
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

    actor ! MessageFromPeer(
      GetByteCodes(requestId = BigInt(42), hashes = Seq(kec256(ByteString("somecode"))), responseBytes = bigBudget),
      testPeer
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

    actor ! MessageFromPeer(
      GetAccountRange(
        requestId = BigInt(7),
        rootHash = zeroHash,
        startingHash = zeroHash,
        limitHash = maxHash,
        responseBytes = bigBudget
      ),
      testPeer
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

    actor ! MessageFromPeer(GetAccountRange(BigInt(11), zeroHash, zeroHash, maxHash, bigBudget), testPeer)
    nextSend(pm).message.underlyingMsg.asInstanceOf[AccountRange].requestId shouldBe BigInt(11)

    actor ! MessageFromPeer(
      GetStorageRanges(BigInt(22), zeroHash, Seq(zeroHash), zeroHash, maxHash, bigBudget),
      testPeer
    )
    nextSend(pm).message.underlyingMsg.asInstanceOf[StorageRanges].requestId shouldBe BigInt(22)

    actor ! MessageFromPeer(GetByteCodes(BigInt(33), Seq.empty, bigBudget), testPeer)
    nextSend(pm).message.underlyingMsg.asInstanceOf[ByteCodes].requestId shouldBe BigInt(33)

    actor ! MessageFromPeer(GetTrieNodes(BigInt(44), zeroHash, Seq.empty, bigBudget), testPeer)
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
    val freshHeader = Fixtures.Blocks.Genesis.header.copy(stateRoot = freshRoot)

    // Stub: tip=200, every canonical header has freshRoot → staleRoot is never in the cache.
    val readerStub = stub[BlockchainReader]
    (readerStub.getBestBlockNumber _).when().returns(BigInt(200))
    (readerStub.getBlockHeaderByNumber _).when(*).returns(Some(freshHeader))

    val (_, storage) = buildAccountTrie(6)
    val actor = makeActor(pm, eb, mptStorageOpt = Some(storage), blockchainReader = Some(readerStub))

    actor ! MessageFromPeer(
      GetAccountRange(
        requestId = BigInt(99),
        rootHash = staleRoot,
        startingHash = zeroHash,
        limitHash = maxHash,
        responseBytes = bigBudget
      ),
      testPeer
    )

    val resp = nextSend(pm).message.underlyingMsg.asInstanceOf[AccountRange]
    resp.requestId shouldBe BigInt(99)
    resp.accounts shouldBe empty
    resp.proof shouldBe empty
  }
}
