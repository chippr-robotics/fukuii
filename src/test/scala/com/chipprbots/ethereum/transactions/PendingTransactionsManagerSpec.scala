package com.chipprbots.ethereum.transactions

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.ask
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor.Status.Handshaked
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerManagerActor.Peers
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions
import com.chipprbots.ethereum.network.p2p.messages.ETH67
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.transactions.PendingTransactionsManager._
import com.chipprbots.ethereum.transactions.SignedTransactionsFilterActor.ProperSignedTransactions
import com.chipprbots.ethereum.utils.TxPoolConfig
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.SendMessage
import com.chipprbots.ethereum.testing.Tags._

/** Test suite for PendingTransactionsManager actor.
  *
  * This test suite demonstrates proper actor testing patterns as specified in ADR-017, avoiding timing-dependent
  * Thread.sleep calls in favor of akka-testkit patterns.
  *
  * ==Actor Testing Best Practices==
  *
  * '''1. Actor Lifecycle Management'''
  *
  * All actor systems must be properly tracked and shut down to prevent hanging tests:
  * {{{
  * class MyActorSpec extends AnyFlatSpec with BeforeAndAfterEach {
  *   private var actorSystems: List[ActorSystem] = List.empty
  *
  *   override def afterEach(): Unit = {
  *     actorSystems.foreach { as =>
  *       try {
  *         TestKit.shutdownActorSystem(as, verifySystemShutdown = false)
  *       } catch {
  *         case _: Exception => // Ignore errors during cleanup
  *       }
  *     }
  *     actorSystems = List.empty
  *   }
  *
  *   trait TestSetup {
  *     implicit val system: ActorSystem = {
  *       val as = ActorSystem("MyActorSpec_System")
  *       actorSystems = as :: actorSystems
  *       as
  *     }
  *   }
  * }
  * }}}
  *
  * '''2. Message Waiting with TestProbe'''
  *
  * Use TestProbe's expectMsg/expectNoMessage instead of Thread.sleep:
  * {{{
  * // ❌ BAD: Using Thread.sleep
  * actor ! SomeMessage
  * Thread.sleep(1000)
  * val result = (actor ? GetState).futureValue
  *
  * // ✅ GOOD: Using TestProbe
  * val probe = TestProbe()
  * actor.tell(SomeMessage, probe.ref)
  * probe.expectMsg(Timeouts.normalTimeout, ExpectedResponse)
  * }}}
  *
  * '''3. State Verification with Eventually'''
  *
  * Use ScalaTest's `eventually` for non-deterministic state checks:
  * {{{
  * // ❌ BAD: Using Thread.sleep
  * actor ! UpdateState(newValue)
  * Thread.sleep(500)
  * val state = (actor ? GetState).futureValue
  * state shouldBe expectedState
  *
  * // ✅ GOOD: Using eventually
  * actor ! UpdateState(newValue)
  * eventually {
  *   val state = (actor ? GetState).futureValue
  *   state shouldBe expectedState
  * }
  * }}}
  *
  * The `eventually` block will retry the assertion until it succeeds or times out (default from NormalPatience).
  *
  * '''4. Testing Transaction Timeout Behavior'''
  *
  * For timeout-based behavior, use `eventually` with appropriate configuration:
  * {{{
  * // Configuration with short timeout
  * val txPoolConfig = new TxPoolConfig {
  *   override val transactionTimeout: FiniteDuration = 500.millis
  *   // ... other config
  * }
  *
  * // Verify timeout behavior
  * actor ! AddTransaction(tx)
  * eventually { /* verify tx is present */ }
  *
  * // Wait for timeout naturally
  * eventually { /* verify tx is removed */ }
  * }}}
  *
  * '''5. Avoid expectNoMessage Without Timeout'''
  *
  * Always specify a reasonable timeout for expectNoMessage:
  * {{{
  * // ❌ BAD: No timeout specified
  * probe.expectNoMessage()
  *
  * // ✅ GOOD: With explicit timeout
  * probe.expectNoMessage(Timeouts.shortTimeout)
  * }}}
  *
  * @see
  *   ADR-017 for comprehensive test suite strategy
  * @see
  *   [[com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcherSpec]] for actor cleanup pattern
  * @see
  *   [[com.chipprbots.ethereum.blockchain.sync.StateStorageActorSpec]] for eventually pattern
  */

class PendingTransactionsManagerSpec
    extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with Eventually
    with BeforeAndAfterEach
    with NormalPatience {

  // Track all actor systems created during tests for cleanup (ADR-017)
  private var actorSystems: List[ActorSystem] = List.empty

  override def afterEach(): Unit = {
    // Shutdown all actor systems to prevent hanging tests
    actorSystems.foreach { as =>
      try
        TestKit.shutdownActorSystem(as, verifySystemShutdown = false)
      catch {
        case _: Exception => // Ignore errors during cleanup
      }
    }
    actorSystems = List.empty
  }

  "PendingTransactionsManager" should "store pending transactions received from peers" taggedAs (UnitTest) in new TestSetup {
    val msg: Set[SignedTransactionWithSender] = (1 to 10).map(e => newStx(e)).toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg, PeerId("1"))

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg
    }
  }

  it should "ignore known transaction" taggedAs (UnitTest) in new TestSetup {
    val msg: Set[SignedTransactionWithSender] = Seq(newStx(1)).toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg, PeerId("1"))
    pendingTransactionsManager ! ProperSignedTransactions(msg, PeerId("2"))

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxs.pendingTransactions.map(_.stx).length shouldBe 1
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg
    }
  }

  it should "broadcast received pending transactions to other peers" taggedAs (UnitTest) in new TestSetup {
    // PendingTransactionsManager now tracks peers via PeerHandshakeSuccessful
    // events. When a tx lands it announces the hashes (ETH/67
    // NewPooledTransactionHashes) to every connected peer rather than
    // pushing the full tx body — peers pull the body via GetPooledTransactions.
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer2, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer3, new HandshakeResult {})

    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(stx)

    val announcements: Seq[SendMessage] =
      etcPeerManager.receiveWhile(Timeouts.normalTimeout, messages = 3) {
        case m @ NetworkPeerManagerActor.SendMessage(enc, _)
            if enc.underlyingMsg.isInstanceOf[ETH67.NewPooledTransactionHashes] =>
          m
      }
    announcements.map(_.peerId).toSet shouldBe Set(peer1.id, peer2.id, peer3.id)
    announcements.foreach { a =>
      a.message.underlyingMsg.asInstanceOf[ETH67.NewPooledTransactionHashes].hashes shouldBe Seq(stx.tx.hash)
    }

    val pendingTxs: PendingTransactionsResponse =
      (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.map(_.stx) shouldBe Seq(stx)
  }

  it should "notify other peers about received transactions and handle removal" taggedAs (UnitTest) in new TestSetup {
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer2, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer3, new HandshakeResult {})

    val tx1: Seq[SignedTransactionWithSender] = Seq.fill(10)(newStx())
    val msg1 = tx1.toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg1, peer1.id)

    // AddTransactions fires both a NotifyPeers (filtered per-peer via
    // isTxKnown) and an announceNewTxHashes (to every connected peer), so
    // each tx batch reaches peer1/2/3 via NewPooledTransactionHashes (ETH/67).
    // Drain until no more messages arrive within shortTimeout.
    val resps1: Seq[SendMessage] = etcPeerManager.receiveWhile(Timeouts.normalTimeout) {
      case m: NetworkPeerManagerActor.SendMessage => m
    }
    (resps1.map(_.peerId).toSet should contain).allOf(peer2.id, peer3.id)
    resps1.map(_.message.underlyingMsg).foreach {
      case ETH67.NewPooledTransactionHashes(_, _, hashes) => hashes.toSet shouldEqual msg1.map(_.tx.hash)
      case SignedTransactions(txs)                        => txs.toSet shouldEqual msg1.map(_.tx)
      case other                                          => fail(s"Unexpected message: $other")
    }

    val tx2: Seq[SignedTransactionWithSender] = Seq.fill(5)(newStx())
    val msg2 = tx2.toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg2, peer2.id)

    val resps2: Seq[SendMessage] = etcPeerManager.receiveWhile(Timeouts.normalTimeout) {
      case m: NetworkPeerManagerActor.SendMessage => m
    }
    (resps2.map(_.peerId).toSet should contain).allOf(peer1.id, peer3.id)
    resps2.map(_.message.underlyingMsg).foreach {
      case ETH67.NewPooledTransactionHashes(_, _, hashes) => hashes.toSet shouldEqual msg2.map(_.tx.hash)
      case SignedTransactions(txs)                        => txs.toSet shouldEqual msg2.map(_.tx)
      case other                                          => fail(s"Unexpected message: $other")
    }

    pendingTransactionsManager ! RemoveTransactions(tx1.dropRight(4).map(_.tx))
    pendingTransactionsManager ! RemoveTransactions(tx2.drop(2).map(_.tx))

    val pendingTxs: PendingTransactionsResponse =
      (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.size shouldBe 6
    pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe (tx2.take(2) ++ tx1.takeRight(4)).toSet
  }

  it should "not add pending transaction again when it was removed while waiting for peers" taggedAs (UnitTest) in new TestSetup {
    // Previously the broadcast path was deferred until the peer manager replied
    // to GetPeers; the test removed the tx before the reply arrived and verified
    // nothing was sent. With the event-driven peer tracking, we reproduce the
    // same invariant by removing the tx before any peer handshake is observed.
    val msg1: Set[SignedTransactionWithSender] = Set(newStx(1))
    pendingTransactionsManager ! ProperSignedTransactions(msg1, peer1.id)

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg1
    }

    pendingTransactionsManager ! RemoveTransactions(msg1.map(_.tx).toSeq)

    // No broadcast should follow since the tx is gone by the time peers show up.
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer2, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer3, new HandshakeResult {})

    etcPeerManager.expectNoMessage()

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxs.pendingTransactions.size shouldBe 0
    }
  }

  it should "override transactions with the same sender and nonce" taggedAs (UnitTest) in new TestSetup {
    val firstTx: SignedTransactionWithSender = newStx(1, tx, keyPair1)
    val otherTx: SignedTransactionWithSender = newStx(1, tx, keyPair2)
    val overrideTx: SignedTransactionWithSender = newStx(1, tx.copy(value = 2 * tx.value), keyPair1)

    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})

    pendingTransactionsManager ! AddOrOverrideTransaction(firstTx.tx)
    pendingTransactionsManager ! AddOrOverrideTransaction(otherTx.tx)
    pendingTransactionsManager ! AddOrOverrideTransaction(overrideTx.tx)

    eventually {
      val pendingTxs: Seq[PendingTransaction] = (pendingTransactionsManager ? GetPendingTransactions)
        .mapTo[PendingTransactionsResponse]
        .futureValue
        .pendingTransactions

      pendingTxs.map(_.stx).toSet shouldEqual Set(overrideTx, otherTx)
    }

    // AddOrOverride queues a NotifyPeers self-message whose broadcast list is
    // filtered to txs still in the pool when processed. Because AddOrOverride
    // processes in-order but NotifyPeers is deferred, firstTx gets invalidated
    // (by overrideTx) before its announce fires, so only otherTx and
    // overrideTx reach peer1. Both land as NewPooledTransactionHashes (ETH/67).
    val announces: Seq[SendMessage] = etcPeerManager.receiveWhile(Timeouts.normalTimeout, messages = 3) {
      case m: NetworkPeerManagerActor.SendMessage => m
    }
    announces.foreach(_.peerId shouldBe peer1.id)
    val announcedHashes = announces
      .flatMap(_.message.underlyingMsg match {
        case ETH67.NewPooledTransactionHashes(_, _, hashes) => hashes
        case SignedTransactions(txs)                        => txs.map(_.hash)
        case _                                              => Nil
      })
      .toSet
    (announcedHashes should contain).allOf(otherTx.tx.hash, overrideTx.tx.hash)
    announcedHashes shouldNot contain(firstTx.tx.hash)
  }

  it should "broadcast pending transactions to newly connected peers" taggedAs (UnitTest) in new TestSetup {
    // When a peer handshakes after the pool already holds transactions, the
    // manager should immediately replay them to that peer — the original intent
    // of this test. With the event-driven tracking the flow is: add tx (no
    // peers → no broadcast), then a handshake arrives → replay.
    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(stx)

    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})

    // On handshake the pool replays its current contents to the new peer as a
    // NewPooledTransactionHashes announce; the peer pulls bodies on demand.
    val replayed = etcPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessage]
    replayed.peerId shouldBe peer1.id
    replayed.message.underlyingMsg match {
      case ETH67.NewPooledTransactionHashes(_, _, hashes) => hashes shouldBe Seq(stx.tx.hash)
      case SignedTransactions(txs)                        => txs shouldBe Seq(stx.tx)
      case other                                          => fail(s"Unexpected: $other")
    }
  }

  it should "remove transaction on timeout" taggedAs (UnitTest) in new TestSetup {
    override val txPoolConfig: TxPoolConfig = new TxPoolConfig {
      override val txPoolSize: Int = 300
      override val transactionTimeout: FiniteDuration = 500.millis
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.normalTimeout

      // unused
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
    }

    override val pendingTransactionsManager: ActorRef = system.actorOf(
      PendingTransactionsManager.props(txPoolConfig, peerManager.ref, etcPeerManager.ref, peerMessageBus.ref)
    )

    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(stx)

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe Set(stx)
    }

    // Wait for transaction to timeout (500ms + some buffer for actor processing)
    eventually {
      val pendingTxsAfter: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxsAfter.pendingTransactions.map(_.stx).toSet shouldBe Set.empty
    }
  }

  trait TestSetup extends SecureRandomBuilder {
    implicit val system: ActorSystem = {
      val as = ActorSystem("PendingTransactionsManagerSpec_System")
      actorSystems = as :: actorSystems
      as
    }

    val keyPair1: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    val keyPair2: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)

    val tx: LegacyTransaction = LegacyTransaction(1, 1, 1, Some(Address(42)), 10, ByteString(""))

    def newStx(
        nonce: BigInt = 0,
        tx: LegacyTransaction = tx,
        keyPair: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    ): SignedTransactionWithSender =
      SignedTransactionWithSender(SignedTransaction.sign(tx, keyPair, Some(0x3d)), Address(keyPair))

    val peer1TestProbe: TestProbe = TestProbe()
    val peer1: Peer = Peer(PeerId("peer1"), new InetSocketAddress("127.0.0.1", 9000), peer1TestProbe.ref, false)
    val peer2TestProbe: TestProbe = TestProbe()
    val peer2: Peer = Peer(PeerId("peer2"), new InetSocketAddress("127.0.0.2", 9000), peer2TestProbe.ref, false)
    val peer3TestProbe: TestProbe = TestProbe()
    val peer3: Peer = Peer(PeerId("peer3"), new InetSocketAddress("127.0.0.3", 9000), peer3TestProbe.ref, false)

    val txPoolConfig: TxPoolConfig = new TxPoolConfig {
      override val txPoolSize: Int = 300

      // unused
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.veryLongTimeout
    }

    val peerManager: TestProbe = TestProbe()
    val etcPeerManager: TestProbe = TestProbe()
    val peerMessageBus: TestProbe = TestProbe()
    val pendingTransactionsManager: ActorRef = system.actorOf(
      PendingTransactionsManager.props(txPoolConfig, peerManager.ref, etcPeerManager.ref, peerMessageBus.ref)
    )
  }

}
