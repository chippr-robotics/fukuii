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
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
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
    // Pre-populate connectedPeers reactively — no GetPeers ask needed
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer2, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer3, new HandshakeResult {})

    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(stx)

    val msgs: Seq[SendMessage] = etcPeerManager.expectMsgAllConformingOf(
      classOf[SendMessage],
      classOf[SendMessage],
      classOf[SendMessage]
    )
    msgs.map(_.peerId) should contain.allOf(peer1.id, peer2.id, peer3.id)
    msgs.foreach { msg =>
      msg.message.underlyingMsg match {
        case ann: ETH67.NewPooledTransactionHashes => ann.hashes should contain(stx.tx.hash)
        case other => fail(s"Expected NewPooledTransactionHashes, got $other")
      }
    }

    val pendingTxs: PendingTransactionsResponse =
      (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.map(_.stx) shouldBe Seq(stx)
  }

  it should "notify other peers about received transactions and handle removal" taggedAs (UnitTest) in new TestSetup {
    // Pre-populate connectedPeers reactively
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer2, new HandshakeResult {})
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer3, new HandshakeResult {})

    val tx1: Seq[SignedTransactionWithSender] = Seq.fill(10)(newStx())
    val msg1 = tx1.toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg1, peer1.id)

    // peer1 is filtered (sent the txs); peer2 and peer3 each get one announcement
    val resps1: Seq[SendMessage] = etcPeerManager.expectMsgAllConformingOf(
      classOf[SendMessage],
      classOf[SendMessage]
    )
    resps1.map(_.peerId) should contain.allOf(peer2.id, peer3.id)
    resps1.foreach { msg =>
      msg.message.underlyingMsg match {
        case ann: ETH67.NewPooledTransactionHashes => ann.hashes.toSet shouldEqual msg1.map(_.tx.hash)
        case other => fail(s"Expected NewPooledTransactionHashes, got $other")
      }
    }
    etcPeerManager.expectNoMessage(Timeouts.shortTimeout)

    val tx2: Seq[SignedTransactionWithSender] = Seq.fill(5)(newStx())
    val msg2 = tx2.toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg2, peer2.id)

    // peer2 is filtered; peer1 and peer3 each get one announcement
    val resps2: Seq[SendMessage] = etcPeerManager.expectMsgAllConformingOf(
      classOf[SendMessage],
      classOf[SendMessage]
    )
    resps2.map(_.peerId) should contain.allOf(peer1.id, peer3.id)
    resps2.foreach { msg =>
      msg.message.underlyingMsg match {
        case ann: ETH67.NewPooledTransactionHashes => ann.hashes.toSet shouldEqual msg2.map(_.tx.hash)
        case other => fail(s"Expected NewPooledTransactionHashes, got $other")
      }
    }
    etcPeerManager.expectNoMessage(Timeouts.shortTimeout)

    pendingTransactionsManager ! RemoveTransactions(tx1.dropRight(4).map(_.tx))
    pendingTransactionsManager ! RemoveTransactions(tx2.drop(2).map(_.tx))

    val pendingTxs: PendingTransactionsResponse =
      (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.size shouldBe 6
    pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe (tx2.take(2) ++ tx1.takeRight(4)).toSet
  }

  it should "not add pending transaction again when it was removed while waiting for peers" taggedAs (UnitTest) in new TestSetup {
    // No peers connected — tx is added to pool but not announced
    val msg1: Set[SignedTransactionWithSender] = Set(newStx(1))
    pendingTransactionsManager ! ProperSignedTransactions(msg1, peer1.id)

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg1
    }

    // Remove the tx before any peer connects
    pendingTransactionsManager ! RemoveTransactions(msg1.map(_.tx).toSeq)

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxs.pendingTransactions.size shouldBe 0
    }

    // Peer2 connects after the tx was removed — pool is empty, no announcement expected
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer2, new HandshakeResult {})
    etcPeerManager.expectNoMessage(Timeouts.shortTimeout)
  }

  it should "override transactions with the same sender and nonce" taggedAs (UnitTest) in new TestSetup {
    val firstTx: SignedTransactionWithSender = newStx(1, tx, keyPair1)
    val otherTx: SignedTransactionWithSender = newStx(1, tx, keyPair2)
    val overrideTx: SignedTransactionWithSender = newStx(1, tx.copy(value = 2 * tx.value), keyPair1)

    // Connect peer1 before sending transactions
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

    // Drain all announcements sent to peer1 (timing-dependent: firstTx may or may not be announced
    // depending on whether its NotifyPeers runs before AddOrOverrideTransaction(overrideTx) removes it)
    val allMsgs = etcPeerManager.receiveWhile(max = Timeouts.normalTimeout, idle = Timeouts.shortTimeout) {
      case m: SendMessage => m
    }
    allMsgs.foreach(_.peerId shouldBe peer1.id)
    val announcedHashes = allMsgs.flatMap { m =>
      m.message.underlyingMsg match {
        case ann: ETH67.NewPooledTransactionHashes => ann.hashes
        case _ => Seq.empty
      }
    }.toSet
    // otherTx and overrideTx are always announced (they remain in the final pool)
    announcedHashes should contain(otherTx.tx.hash)
    announcedHashes should contain(overrideTx.tx.hash)
  }

  it should "broadcast pending transactions to newly connected peers" taggedAs (UnitTest) in new TestSetup {
    val stx: SignedTransactionWithSender = newStx()
    // No peers connected yet — AddTransactions does not notify anyone
    pendingTransactionsManager ! AddTransactions(stx)

    // Wait for tx to be in pool before connecting peer
    eventually {
      val pendingTxs: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxs.pendingTransactions.map(_.stx) should contain(stx)
    }

    // peer1 connects — receives the existing pool tx via NewPooledTransactionHashes
    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})

    val msg: SendMessage = etcPeerManager.expectMsgClass(Timeouts.normalTimeout, classOf[SendMessage])
    msg.peerId shouldBe peer1.id
    msg.message.underlyingMsg match {
      case ann: ETH67.NewPooledTransactionHashes => ann.hashes should contain(stx.tx.hash)
      case other => fail(s"Expected NewPooledTransactionHashes, got $other")
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
