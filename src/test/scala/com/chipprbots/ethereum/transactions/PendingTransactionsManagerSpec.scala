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
import com.chipprbots.ethereum.network.EtcPeerManagerActor
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor.Status.Handshaked
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.PeerManagerActor.Peers
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.transactions.PendingTransactionsManager._
import com.chipprbots.ethereum.transactions.SignedTransactionsFilterActor.ProperSignedTransactions
import com.chipprbots.ethereum.utils.TxPoolConfig
import com.chipprbots.ethereum.network.EtcPeerManagerActor.SendMessage
import com.chipprbots.ethereum.testing.Tags._

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
    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(stx)

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked, peer2 -> Handshaked, peer3 -> Handshaked)))

    etcPeerManager.expectMsgAllOf(
      EtcPeerManagerActor.SendMessage(SignedTransactions(Seq(stx.tx)), peer1.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(Seq(stx.tx)), peer2.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(Seq(stx.tx)), peer3.id)
    )

    val pendingTxs: PendingTransactionsResponse =
      (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.map(_.stx) shouldBe Seq(stx)
  }

  it should "notify other peers about received transactions and handle removal" taggedAs (UnitTest) in new TestSetup {
    val tx1: Seq[SignedTransactionWithSender] = Seq.fill(10)(newStx())
    val msg1 = tx1.toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg1, peer1.id)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked, peer2 -> Handshaked, peer3 -> Handshaked)))

    val resps1: Seq[SendMessage] = etcPeerManager.expectMsgAllConformingOf(
      classOf[EtcPeerManagerActor.SendMessage],
      classOf[EtcPeerManagerActor.SendMessage]
    )

    resps1.map(_.peerId) should contain.allOf(peer2.id, peer3.id)
    resps1.map(_.message.underlyingMsg).foreach { case SignedTransactions(txs) => txs.toSet shouldEqual msg1.map(_.tx) }
    etcPeerManager.expectNoMessage()

    val tx2: Seq[SignedTransactionWithSender] = Seq.fill(5)(newStx())
    val msg2 = tx2.toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg2, peer2.id)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked, peer2 -> Handshaked, peer3 -> Handshaked)))

    val resps2: Seq[SendMessage] = etcPeerManager.expectMsgAllConformingOf(
      classOf[EtcPeerManagerActor.SendMessage],
      classOf[EtcPeerManagerActor.SendMessage]
    )
    resps2.map(_.peerId) should contain.allOf(peer1.id, peer3.id)
    resps2.map(_.message.underlyingMsg).foreach { case SignedTransactions(txs) => txs.toSet shouldEqual msg2.map(_.tx) }
    etcPeerManager.expectNoMessage()

    pendingTransactionsManager ! RemoveTransactions(tx1.dropRight(4).map(_.tx))
    pendingTransactionsManager ! RemoveTransactions(tx2.drop(2).map(_.tx))

    val pendingTxs: PendingTransactionsResponse =
      (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
    pendingTxs.pendingTransactions.size shouldBe 6
    pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe (tx2.take(2) ++ tx1.takeRight(4)).toSet
  }

  it should "not add pending transaction again when it was removed while waiting for peers" taggedAs (UnitTest) in new TestSetup {
    val msg1: Set[SignedTransactionWithSender] = Set(newStx(1))
    pendingTransactionsManager ! ProperSignedTransactions(msg1, peer1.id)

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        (pendingTransactionsManager ? GetPendingTransactions).mapTo[PendingTransactionsResponse].futureValue
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg1
    }

    pendingTransactionsManager ! RemoveTransactions(msg1.map(_.tx).toSeq)

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked, peer2 -> Handshaked, peer3 -> Handshaked)))

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

    pendingTransactionsManager ! AddOrOverrideTransaction(firstTx.tx)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked)))

    pendingTransactionsManager ! AddOrOverrideTransaction(otherTx.tx)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked)))

    pendingTransactionsManager ! AddOrOverrideTransaction(overrideTx.tx)
    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map(peer1 -> Handshaked)))

    eventually {
      val pendingTxs: Seq[PendingTransaction] = (pendingTransactionsManager ? GetPendingTransactions)
        .mapTo[PendingTransactionsResponse]
        .futureValue
        .pendingTransactions

      pendingTxs.map(_.stx).toSet shouldEqual Set(overrideTx, otherTx)
    }

    // overriden TX will still be broadcast to peers
    etcPeerManager.expectMsgAllOf(
      EtcPeerManagerActor.SendMessage(SignedTransactions(List(firstTx.tx)), peer1.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(List(otherTx.tx)), peer1.id),
      EtcPeerManagerActor.SendMessage(SignedTransactions(List(overrideTx.tx)), peer1.id)
    )
  }

  it should "broadcast pending transactions to newly connected peers" taggedAs (UnitTest) in new TestSetup {
    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(stx)

    peerManager.expectMsg(PeerManagerActor.GetPeers)
    peerManager.reply(Peers(Map.empty))

    pendingTransactionsManager ! PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})

    etcPeerManager.expectMsgAllOf(EtcPeerManagerActor.SendMessage(SignedTransactions(Seq(stx.tx)), peer1.id))
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
