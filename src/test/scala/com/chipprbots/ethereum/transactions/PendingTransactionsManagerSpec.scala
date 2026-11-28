package com.chipprbots.ethereum.transactions

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestInbox
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.pubsub.Topic
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlobTransaction
import com.chipprbots.ethereum.domain.BlobVersionedHash
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TransactionWithAccessList
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.jsonrpc.NewPendingTransaction
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.SendMessageCmd
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NewPooledTransactionHashes72
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.NoRandomAccessSeq
import com.chipprbots.ethereum.testing.Tags.OlympiaTest
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.TxPoolConfig

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
  */

class PendingTransactionsManagerSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load())
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with Eventually:

  "PendingTransactionsManager" should "store pending transactions received from peers" taggedAs (UnitTest) in new TestSetup:
    val msg: Set[SignedTransactionWithSender] = (1 to 10).map(e => newStx(e)).toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg, PeerId("1"))

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg
    }

  it should "ignore known transaction" taggedAs (UnitTest) in new TestSetup:
    val msg: Set[SignedTransactionWithSender] = Seq(newStx(1)).toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg, PeerId("1"))
    pendingTransactionsManager ! ProperSignedTransactions(msg, PeerId("2"))

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      pendingTxs.pendingTransactions.map(_.stx).length shouldBe 1
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg
    }

  it should "broadcast received pending transactions to other peers" taggedAs (UnitTest) in new TestSetup:
    // PendingTransactionsManager now tracks peers via PeerHandshakeSuccessful
    // events. When a tx lands it announces the hashes (ETH/67
    // NewPooledTransactionHashes) to every connected peer rather than
    // pushing the full tx body — peers pull the body via GetPooledTransactions.
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {}))
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer2, new HandshakeResult {}))
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer3, new HandshakeResult {}))

    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(stx)

    val announcements: Seq[SendMessageCmd] =
      etcPeerManager.receiveWhile(Timeouts.normalTimeout, messages = 3) {
        case m @ NetworkPeerManagerActor.SendMessageCmd(enc, _)
            if enc.underlyingMsg.isInstanceOf[ETHPackets.NewPooledTransactionHashes] =>
          m
      }
    announcements.map(_.peerId).toSet shouldBe Set(peer1.id, peer2.id, peer3.id)
    announcements.foreach { a =>
      a.message.underlyingMsg.asInstanceOf[ETHPackets.NewPooledTransactionHashes].hashes shouldBe Seq(stx.tx.hash)
    }

    val pendingTxs: PendingTransactionsResponse =
      pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
    pendingTxs.pendingTransactions.map(_.stx) shouldBe Seq(stx)

  it should "notify other peers about received transactions and handle removal" taggedAs (UnitTest) in new TestSetup:
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {}))
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer2, new HandshakeResult {}))
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer3, new HandshakeResult {}))

    val tx1: Seq[SignedTransactionWithSender] = Seq.fill(10)(newStx())
    val msg1 = tx1.toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg1, peer1.id)

    // AddTransactions queues a NotifyPeers broadcast, filtered per peer through
    // isTxKnown, so received txs are announced only to peers that don't already
    // know them.
    // Drain until no more messages arrive within shortTimeout.
    val resps1: Seq[SendMessageCmd] = etcPeerManager.receiveWhile(Timeouts.normalTimeout) {
      case m: NetworkPeerManagerActor.SendMessageCmd => m
    }
    (resps1.map(_.peerId).toSet should contain).allOf(peer2.id, peer3.id)
    resps1.map(_.message.underlyingMsg).foreach {
      case ETHPackets.NewPooledTransactionHashes(_, _, hashes) => hashes.toSet shouldEqual msg1.map(_.tx.hash)
      case SignedTransactions(txs)                             => txs.toSet shouldEqual msg1.map(_.tx)
      case other                                               => fail(s"Unexpected message: $other")
    }

    val tx2: Seq[SignedTransactionWithSender] = Seq.fill(5)(newStx())
    val msg2 = tx2.toSet
    pendingTransactionsManager ! ProperSignedTransactions(msg2, peer2.id)

    val resps2: Seq[SendMessageCmd] = etcPeerManager.receiveWhile(Timeouts.normalTimeout) {
      case m: NetworkPeerManagerActor.SendMessageCmd => m
    }
    (resps2.map(_.peerId).toSet should contain).allOf(peer1.id, peer3.id)
    resps2.map(_.message.underlyingMsg).foreach {
      case ETHPackets.NewPooledTransactionHashes(_, _, hashes) => hashes.toSet shouldEqual msg2.map(_.tx.hash)
      case SignedTransactions(txs)                             => txs.toSet shouldEqual msg2.map(_.tx)
      case other                                               => fail(s"Unexpected message: $other")
    }

    pendingTransactionsManager ! RemoveTransactions(tx1.dropRight(4).map(_.tx))
    pendingTransactionsManager ! RemoveTransactions(tx2.drop(2).map(_.tx))

    val pendingTxs: PendingTransactionsResponse =
      pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
    pendingTxs.pendingTransactions.size shouldBe 6
    pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe (tx2.take(2) ++ tx1.takeRight(4)).toSet

  it should "not add pending transaction again when it was removed while waiting for peers" taggedAs (UnitTest) in new TestSetup:
    // Previously the broadcast path was deferred until the peer manager replied
    // to GetPeers; the test removed the tx before the reply arrived and verified
    // nothing was sent. With the event-driven peer tracking, we reproduce the
    // same invariant by removing the tx before any peer handshake is observed.
    val msg1: Set[SignedTransactionWithSender] = Set(newStx(1))
    pendingTransactionsManager ! ProperSignedTransactions(msg1, peer1.id)

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe msg1
    }

    pendingTransactionsManager ! RemoveTransactions(msg1.map(_.tx).toSeq)

    // No broadcast should follow since the tx is gone by the time peers show up.
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {}))
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer2, new HandshakeResult {}))
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer3, new HandshakeResult {}))

    etcPeerManager.expectNoMessage()

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      pendingTxs.pendingTransactions.size shouldBe 0
    }

  it should "override transactions with the same sender and nonce" taggedAs (UnitTest) in new TestSetup:
    val firstTx: SignedTransactionWithSender = newStx(1, tx, keyPair1)
    val otherTx: SignedTransactionWithSender = newStx(1, tx, keyPair2)
    val overrideTx: SignedTransactionWithSender = newStx(1, tx.copy(value = 2 * tx.value), keyPair1)

    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {}))
    pendingTransactionsManager ! AddOrOverrideTransaction(firstTx.tx)
    pendingTransactionsManager ! AddOrOverrideTransaction(otherTx.tx)

    // Each add announces its tx through a NotifyPeers the actor sends itself, which announces only what is still pooled
    // when it is processed. Nothing takes either tx out before the override, and the override is sent only once both
    // announcements are out: both reach peer1 (B-497: this test used to send the override straight after, and assert
    // that firstTx was never announced, which held only if the override reached the mailbox before firstTx's NotifyPeers
    // did, a matter of thread scheduling; the next test pins that case with the order set by hand).
    announcedTo(this, peer1, count = 2) shouldBe Set(firstTx, otherTx).map(_.tx.hash.value)

    pendingTransactionsManager ! AddOrOverrideTransaction(overrideTx.tx)
    pool(this, pendingTransactionsManager).pendingTransactions.map(_.stx).toSet shouldEqual Set(overrideTx, otherTx)
    announcedTo(this, peer1, count = 1) shouldBe Set(overrideTx.tx.hash.value)

  // The order the test above cannot arrange from outside the actor: a replacement processed before the NotifyPeers of
  // the tx it replaces. Run synchronously, the actor's messages to itself held in its self inbox until the test runs
  // them, so the order is the one written here, whatever the threads do.
  it should "not announce a transaction replaced before its announcement went out" taggedAs (UnitTest) in new TestSetup:
    val networkPeers: TestInbox[NetworkPeerManagerActor.Command] = TestInbox()
    val kit: BehaviorTestKit[Command] = BehaviorTestKit(
      PendingTransactionsManager(
        txPoolConfig,
        TestInbox[PeerManagerActor.Command]().ref,
        networkPeers.ref,
        TestInbox[PeerEventBusActor.Command]().ref,
        TestInbox[Topic.Command[NewPendingTransaction]]().ref
      )
    )
    val firstTx: SignedTransactionWithSender = newStx(1, tx, keyPair1)
    val overrideTx: SignedTransactionWithSender = newStx(1, tx.copy(value = 2 * tx.value), keyPair1)

    kit.run(WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {})))
    kit.run(AddOrOverrideTransaction(firstTx.tx))
    kit.run(AddOrOverrideTransaction(overrideTx.tx))
    networkPeers.hasMessages shouldBe false // every announcement is still a NotifyPeers in the self inbox
    kit.selfInbox().receiveAll().foreach(kit.run)

    val announced: Seq[(PeerId, Seq[ByteString])] = networkPeers.receiveAll().map {
      case NetworkPeerManagerActor.SendMessageCmd(enc, peerId) =>
        enc.underlyingMsg match
          case ETHPackets.NewPooledTransactionHashes(_, _, hashes) => (peerId, hashes)
          case other                                               => fail(s"Unexpected: $other")
      case other => fail(s"Unexpected: $other")
    }
    announced shouldBe Seq((peer1.id, Seq(overrideTx.tx.hash.value)))

  it should "broadcast pending transactions to newly connected peers" taggedAs (UnitTest) in new TestSetup:
    // When a peer handshakes after the pool already holds transactions, the
    // manager should immediately replay them to that peer — the original intent
    // of this test. With the event-driven tracking the flow is: add tx (no
    // peers → no broadcast), then a handshake arrives → replay.
    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(stx)

    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {}))

    // On handshake the pool replays its current contents to the new peer as a
    // NewPooledTransactionHashes announce; the peer pulls bodies on demand.
    val replayed: SendMessageCmd = etcPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    replayed.peerId shouldBe peer1.id
    replayed.message.underlyingMsg match
      case ETHPackets.NewPooledTransactionHashes(_, _, hashes) => hashes shouldBe Seq(stx.tx.hash)
      case SignedTransactions(txs)                             => txs shouldBe Seq(stx.tx)
      case other                                               => fail(s"Unexpected: $other")

  it should "announce a blob tx at the length of the network form it serves" taggedAs (UnitTest) in new TestSetup:
    // A peer checks the PooledTransactions reply against the announced size, and a blob tx is
    // served in its network form (tx, blobs, commitments, proofs). 131,330 bytes is one blob's
    // network form in hive's "Request Blob Pooled Transactions" test, which was announced at 146,
    // the bare tx.
    val blobTx: BlobTransaction = BlobTransaction(
      chainId = 0x3d,
      nonce = 0,
      maxPriorityFeePerGas = 1,
      maxFeePerGas = 1,
      gasLimit = GasAmount(21000),
      receivingAddress = Some(Address(42)),
      value = 0,
      payload = ByteString.empty,
      accessList = Nil,
      maxFeePerBlobGas = 1,
      blobVersionedHashes = List(BlobVersionedHash(ByteString(Array.fill[Byte](32)(1))))
    )
    val signed: SignedTransaction = SignedTransaction.sign(blobTx, keyPair1, Some(0x3d))
    val networkForm: ByteString = ByteString(Array.fill[Byte](131330)(0))

    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {}))
    pendingTransactionsManager ! AddOrOverrideTransaction(signed, Some(networkForm))

    val announced: SendMessageCmd = etcPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    announced.peerId shouldBe peer1.id
    announced.message.underlyingMsg match
      case ETHPackets.NewPooledTransactionHashes(types, sizes, hashes) =>
        hashes shouldBe Seq(signed.hash)
        types shouldBe Seq(Transaction.Type03)
        sizes shouldBe Seq(BigInt(networkForm.length))
      case other => fail(s"Unexpected: $other")

  // ---- inbound NewPooledTransactionHashes / NewPooledTransactionHashes72 -> GetPooledTransactions -------------
  //
  // Regression coverage for hive's TestNewPooledTxs / TestBlobViolations: NewPooledTransactionHashes72 (ETH72's
  // 4-field announcement, EIP-8070) is a distinct case class from the 3-field NewPooledTransactionHashes, not a
  // subtype, so the inbound handler needed its OWN pattern-match case. Without one, an ETH72 peer's announcement
  // matched no case in the actor's Behaviors.receiveMessage at all and was silently dropped: fukuii never issued
  // GetPooledTransactions, which is what hive observed as a read timeout waiting for that request ("reading
  // pooled tx request failed: i/o timeout") rather than a decode failure — ETH72MessageDecoder already decoded
  // the message correctly; nothing downstream ever acted on it.

  it should "request unknown hashes via GetPooledTransactions when an ETH72 peer announces NewPooledTransactionHashes72 (4-field)" taggedAs (
    UnitTest
  ) in new TestSetup:
    val unknownHash: ByteString = ByteString(Array.fill[Byte](32)(7))
    val announcement: NewPooledTransactionHashes72 = ETHPackets.NewPooledTransactionHashes72(
      types = Seq(Transaction.Type02),
      sizes = Seq(BigInt(123)),
      hashes = Seq(unknownHash),
      mask = ETHPackets.NewPooledTransactionHashes72.NoCustody
    )

    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.MessageFromPeer(announcement, peer1.id))

    val requested: SendMessageCmd = etcPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    requested.peerId shouldBe peer1.id
    requested.message.underlyingMsg match
      case ETHPackets.GetPooledTransactions(_, requestedHashes) => requestedHashes shouldBe Seq(unknownHash)
      case other                                                => fail(s"Unexpected: $other")

  it should "request unknown hashes via GetPooledTransactions when a pre-ETH72 peer announces NewPooledTransactionHashes (3-field)" taggedAs (
    UnitTest
  ) in new TestSetup:
    val unknownHash: ByteString = ByteString(Array.fill[Byte](32)(9))
    val announcement: NewPooledTransactionHashes = ETHPackets.NewPooledTransactionHashes(
      types = Seq(Transaction.Type02),
      sizes = Seq(BigInt(456)),
      hashes = Seq(unknownHash)
    )

    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.MessageFromPeer(announcement, peer1.id))

    val requested: SendMessageCmd = etcPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    requested.peerId shouldBe peer1.id
    requested.message.underlyingMsg match
      case ETHPackets.GetPooledTransactions(_, requestedHashes) => requestedHashes shouldBe Seq(unknownHash)
      case other                                                => fail(s"Unexpected: $other")

  it should "not request an already-pending hash announced via NewPooledTransactionHashes72" taggedAs (
    UnitTest
  ) in new TestSetup:
    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(Set(stx))
    etcPeerManager.expectNoMessage(100.millis) // no connected peers yet — nothing to announce to

    val announcement: NewPooledTransactionHashes72 = ETHPackets.NewPooledTransactionHashes72(
      types = Seq(0.toByte),
      sizes = Seq(BigInt(1)),
      hashes = Seq(stx.tx.hash.value),
      mask = ETHPackets.NewPooledTransactionHashes72.NoCustody
    )
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.MessageFromPeer(announcement, peer1.id))

    etcPeerManager.expectNoMessage(200.millis)

  // ---- a move of the head (B-253) ---------------------------------------------------------------------------
  //
  // These pools have no blockchain reader and no state storage: the state check (nonce and balance at the head) is not
  // exercised, only the pool's own nonce gate and its pool-state rules.

  /** A pool on ETC's rules, or on ETH's (the pool-state rules, the replacement price bump among them, apply). */
  class TestSetupOn(eth: Boolean) extends TestSetup:
    val chainConfig: BlockchainConfig =
      if eth then
        com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
          .copy(networkType = com.chipprbots.ethereum.utils.NetworkType.ETH, minTip = BigInt(1))
      else com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
    override val pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[Command] = testKit.spawn(
      PendingTransactionsManager(
        txPoolConfig,
        peerManager.ref,
        etcPeerManager.ref,
        peerMessageBus.ref,
        pendingTxTopic,
        chainConfig = chainConfig
      ),
      s"ptm-test-on-${if eth then "eth" else "etc"}-${java.util.UUID.randomUUID()}"
    )
    def at(nonce: BigInt, gasPrice: BigInt, value: BigInt): SignedTransactionWithSender =
      SignedTransactionWithSender(
        SignedTransaction.sign(
          tx.copy(nonce = nonce, gasPrice = GasPrice(gasPrice), value = value, gasLimit = GasAmount(21000)),
          keyPair1,
          Some(0x3d)
        ),
        Address(keyPair1)
      )
    def pooled(): Set[SignedTransactionWithSender] =
      pendingTransactionsManager
        .ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref))
        .futureValue
        .pendingTransactions
        .map(_.stx)
        .toSet

  // go-ethereum list.Add: a reinjected tx at a pooled tx's sender and nonce replaces it only past the price bump (ETH);
  // on ETC, with no such rule, the pooled one is kept. Exactly one tx at that nonce either way.
  for (eth, bumped, kept) <- Seq(
      (true, true, "reinjected"),
      (true, false, "pooled"),
      (false, true, "pooled"),
      (false, false, "pooled")
    )
  do
    it should s"keep one tx at a nonce it holds when a reset takes back another there: the $kept one (${
        if eth then "ETH" else "ETC"
      }, ${if bumped then "" else "no "}price bump)" taggedAs (UnitTest) in new TestSetupOn(eth):
      val held: SignedTransactionWithSender = at(1, gasPrice = 10, value = 10)
      val back: SignedTransactionWithSender = at(1, gasPrice = if bumped then 20 else 10, value = 11)
      pendingTransactionsManager ! AddTransactions(held)
      pooled() shouldBe Set(held)
      pendingTransactionsManager ! HeadReset(Nil, Seq(back.tx))
      pooled() shouldBe Set(if kept == "reinjected" then back else held)

  it should "take back a tx it held once a reorganisation moves its block off the chain, behind a later nonce it holds" taggedAs (UnitTest) in new TestSetup:
    // an intrinsic gas the stateless admission takes: a reinjected tx is admitted as an unchecked add is
    val transfer: LegacyTransaction = tx.copy(gasLimit = GasAmount(21000))
    def signed(nonce: BigInt): SignedTransactionWithSender =
      SignedTransactionWithSender(
        SignedTransaction.sign(transfer.copy(nonce = nonce), keyPair1, Some(0x3d)),
        Address(keyPair1)
      )
    def pooled(): Set[SignedTransactionWithSender] =
      pendingTransactionsManager
        .ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref))
        .futureValue
        .pendingTransactions
        .map(_.stx)
        .toSet
    val (first, second) = (signed(1), signed(2))
    pendingTransactionsManager ! AddTransactions(first, second)
    pooled() shouldBe Set(first, second)
    // a block with the first becomes the head: the pool drops it, and asked next, says so
    pendingTransactionsManager ! HeadReset(Seq(Seq(first.tx)), Nil)
    pooled() shouldBe Set(second)
    // a reorganisation moves that block off: the first is taken back, below the nonce the pool took last, and a
    // build that asks right after the reset sees it
    pendingTransactionsManager ! HeadReset(Nil, Seq(first.tx))
    pooled() shouldBe Set(first, second)
    // the gate is the next nonce after the highest held again: a peer's tx below it is still refused
    pendingTransactionsManager ! HeadReset(Seq(Seq(first.tx)), Nil)
    pooled() shouldBe Set(second)
    val replay: SignedTransactionWithSender =
      SignedTransactionWithSender(
        SignedTransaction.sign(transfer.copy(nonce = 1, value = 11), keyPair1, Some(0x3d)),
        Address(keyPair1)
      )
    pendingTransactionsManager ! ProperSignedTransactions(Set(replay), PeerId("1"))
    pooled() shouldBe Set(second)

  it should "take back a tx it held once a reorganisation moves its block off the chain, the sender holding nothing else" taggedAs (UnitTest) in new TestSetup:
    val only: SignedTransactionWithSender =
      SignedTransactionWithSender(
        SignedTransaction.sign(tx.copy(gasLimit = GasAmount(21000)), keyPair2, Some(0x3d)),
        Address(keyPair2)
      )
    def pooled(): Set[SignedTransactionWithSender] =
      pendingTransactionsManager
        .ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref))
        .futureValue
        .pendingTransactions
        .map(_.stx)
        .toSet
    pendingTransactionsManager ! AddTransactions(only)
    pooled() shouldBe Set(only)
    pendingTransactionsManager ! HeadReset(Seq(Seq(only.tx)), Nil)
    pooled() shouldBe empty
    pendingTransactionsManager ! HeadReset(Nil, Seq(only.tx))
    pooled() shouldBe Set(only)

  it should "rebuild the nonce gate of the senders whose txs a reset removes from what it still holds of them" taggedAs (UnitTest) in new TestSetup:
    def signed(nonce: BigInt, value: BigInt = 10): SignedTransactionWithSender =
      SignedTransactionWithSender(
        SignedTransaction
          .sign(tx.copy(nonce = nonce, value = value, gasLimit = GasAmount(21000)), keyPair2, Some(0x3d)),
        Address(keyPair2)
      )
    def pooled(): Set[SignedTransactionWithSender] =
      pendingTransactionsManager
        .ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref))
        .futureValue
        .pendingTransactions
        .map(_.stx)
        .toSet
    val (first, second) = (signed(1), signed(2))
    pendingTransactionsManager ! AddTransactions(first, second)
    // the gate is 3: another tx at nonce 2 is refused
    pendingTransactionsManager ! ProperSignedTransactions(Set(signed(2, value = 11)), PeerId("1"))
    pooled() shouldBe Set(first, second)
    // the second leaves on a reset: the gate is the next nonce after the first, the highest held, as go-ethereum's
    // noncer is rebuilt on a reset; another tx at nonce 2 is taken
    pendingTransactionsManager ! HeadReset(Seq(Seq(second.tx)), Nil)
    val other: SignedTransactionWithSender = signed(2, value = 12)
    pendingTransactionsManager ! ProperSignedTransactions(Set(other), PeerId("1"))
    pooled() shouldBe Set(first, other)

  // ---- delivered PooledTransactions vs. the announcement --------------------------------------------------
  //
  // go-ethereum drops a peer over an announced/delivered size gap only above 8 bytes, and the size it announces can
  // be a byte short of the wire length (a blob tx sent without blobs). hive's BlobTxAvailabilityFailure delivers eight
  // such txs and expects no disconnect; BlobViolations overstates a size by 10 bytes and expects one.

  private def announceThenDeliver(setup: TestSetup, announcedSize: Int, deliveredSize: Int): Unit =
    import setup.*
    val stx: SignedTransaction = newStx().tx
    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets.NewPooledTransactionHashes(Seq(0.toByte), Seq(BigInt(announcedSize)), Seq(stx.hash.value)),
        peer1.id
      )
    )
    etcPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd] // the GetPooledTransactions
    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(ETHPackets.PooledTransactions(BigInt(1), Seq(stx), Seq(deliveredSize)), peer1.id)
    )

  it should "keep a peer whose delivered tx is within 8 bytes of the size it announced" taggedAs (
    UnitTest
  ) in new TestSetup:
    announceThenDeliver(this, announcedSize = 109, deliveredSize = 110)
    peerManager.expectNoMessage(300.millis)

  it should "disconnect a peer whose delivered tx is more than 8 bytes off the size it announced" taggedAs (
    UnitTest
  ) in new TestSetup:
    announceThenDeliver(this, announcedSize = 120, deliveredSize = 110)
    peerManager.expectMsg(PeerManagerActor.DisconnectPeerFireAndForgetCmd(peer1.id))

  // #1675: `originalSizes` is a decoded RLP list. Looking it up by index per delivered tx was O(i) per lookup on the
  // Queue the decoder built, so one PooledTransactions reply of N txs (N peer-controlled) cost O(N²) on the pool actor.
  it should "check delivered sizes against the announcement without random access into the reply" taggedAs (
    UnitTest
  ) in new TestSetup:
    val stxs: Seq[SignedTransaction] = (1 to 3).map(_ => newStx().tx)
    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets
          .NewPooledTransactionHashes(stxs.map(_ => 0.toByte), stxs.map(_ => BigInt(110)), stxs.map(_.hash.value)),
        peer1.id
      )
    )
    etcPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd] // the GetPooledTransactions
    // Only the last tx is off its announcement (by 20 bytes): it must be found, at its own index.
    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets.PooledTransactions(BigInt(1), stxs, NoRandomAccessSeq(110, 110, 130)),
        peer1.id
      )
    )
    peerManager.expectMsg(PeerManagerActor.DisconnectPeerFireAndForgetCmd(peer1.id))

  // go-ethereum eth/fetcher/tx_fetcher.go (c6e3a0d7, 2026-09-23): `maxTxRetrievals = 256`, the most transactions it
  // asks for in one request, "a failsafe against malicious announces which might cause us to request more data than
  // we'd expect". Every hash tracked from an announcement (up to maxTxAnnounces, below) is still requested.
  it should "request at most 256 announced hashes per GetPooledTransactions, and every hash once" taggedAs (
    UnitTest
  ) in new TestSetup:
    val hashes: Seq[ByteString] =
      (1 to 600).map(i => ByteString(java.nio.ByteBuffer.allocate(32).putInt(28, i).array()))
    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets
          .NewPooledTransactionHashes(hashes.map(_ => Transaction.Type02), hashes.map(_ => BigInt(100)), hashes),
        peer1.id
      )
    )
    // One request in flight per peer (B-028): the next goes out once the peer answers. These hashes name no real tx,
    // so each answer is empty, and gives up what it was asked for.
    val requests: Seq[(PeerId, Seq[ByteString])] = Seq.fill(3) {
      val request = requestedFrom(this)
      etcPeerManager.expectNoMessage(100.millis) // nothing more until it answers
      answerEmpty(pendingTransactionsManager, peer1)
      request
    }
    requests.map(_._1).distinct shouldBe Seq(peer1.id)
    requests.map(_._2.size) shouldBe Seq(256, 256, 88)
    requests.flatMap(_._2) shouldBe hashes
    etcPeerManager.expectNoMessage(200.millis)

  // B-028: go-ethereum's maxTxRetrievalSize, 128 KiB of announced txs per request.
  it should "ask for at most 128 KiB of announced txs per GetPooledTransactions" taggedAs (
    UnitTest
  ) in new TestSetup:
    val hashes: Seq[ByteString] =
      (1 to 10).map(i => ByteString(java.nio.ByteBuffer.allocate(32).putInt(28, i).array()))
    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets
          .NewPooledTransactionHashes(hashes.map(_ => Transaction.Type02), hashes.map(_ => BigInt(40000)), hashes),
        peer1.id
      )
    )
    requestedFrom(this) shouldBe ((peer1.id, hashes.take(4))) // 160,000 bytes once the fourth is in
    etcPeerManager.expectNoMessage(200.millis)
    answerEmpty(pendingTransactionsManager, peer1)
    requestedFrom(this) shouldBe ((peer1.id, hashes.slice(4, 8)))

  // Copilot, #2086: a tx admitted by another path (here AddTransactions) while its announcement is still tracked used to
  // be fetched again: its announcer's empty answer gave it up to the next announcer, which was then asked for it.
  it should "not fetch again a tx the pool took from elsewhere while it was being fetched" taggedAs (
    UnitTest
  ) in new TestSetup:
    val stx: SignedTransactionWithSender = newStx()
    val hash: ByteString = stx.tx.hash.value
    def announce(peer: Peer): Unit =
      pendingTransactionsManager ! WrappedPeerEvent(
        PeerEvent.MessageFromPeer(
          ETHPackets.NewPooledTransactionHashes(Seq(0.toByte), Seq(BigInt(100)), Seq(hash)),
          peer.id
        )
      )
    announce(peer1)
    requestedFrom(this) shouldBe ((peer1.id, Seq(hash)))
    announce(peer2) // an alternate
    pendingTransactionsManager ! AddTransactions(Set(stx))
    answerEmpty(pendingTransactionsManager, peer1)
    etcPeerManager.receiveWhile(500.millis) { case cmd: NetworkPeerManagerActor.SendMessageCmd => cmd }.collect {
      case cmd if cmd.message.underlyingMsg.isInstanceOf[ETHPackets.GetPooledTransactions] => cmd
    } shouldBe empty

  // Copilot, #2086: a reply that names another request id (a duplicate, or a reply to nothing asked) does not end the
  // request in flight, so it neither gives up its hashes nor lets a second request out.
  it should "take only the reply to its request in flight as the end of that request" taggedAs (
    UnitTest
  ) in new TestSetup:
    val hashes: Seq[ByteString] =
      (1 to 300).map(i => ByteString(java.nio.ByteBuffer.allocate(32).putInt(28, i).array()))
    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets
          .NewPooledTransactionHashes(hashes.map(_ => Transaction.Type02), hashes.map(_ => BigInt(100)), hashes),
        peer1.id
      )
    )
    requestedFrom(this) shouldBe ((peer1.id, hashes.take(256)))
    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(ETHPackets.PooledTransactions(lastRequestId + 1000, Seq.empty), peer1.id)
    )
    etcPeerManager.expectNoMessage(200.millis) // still one request in flight
    answerEmpty(pendingTransactionsManager, peer1)
    requestedFrom(this) shouldBe ((peer1.id, hashes.drop(256)))

  // go-ethereum eth/fetcher/tx_fetcher.go (c6e3a0d7): `maxTxAnnounces = 4096`, the most hashes tracked for one peer at
  // once; the excess of an announcement is dropped. Without it an announcement is bounded only by the 16 MiB message
  // size (MessageCodec.MaxDecompressedLength), and every hash it carries was requested.
  it should "track at most 4096 announced hashes per peer, and drop an announcement at the cap" taggedAs (
    UnitTest
  ) in new TestSetup:
    // No request times out (and frees its slot) while the test runs.
    val noFetchTimeout: TxPoolConfig = new TxPoolConfig:
      override val txPoolSize: Int = 300
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val announcementFetchTimeout: FiniteDuration = Timeouts.veryLongTimeout
    val ptm: ActorRef[Command] = spawnPtm(config = noFetchTimeout)
    def hashesFrom(first: Int, count: Int): Seq[ByteString] =
      (first until first + count).map(i => ByteString(java.nio.ByteBuffer.allocate(32).putInt(28, i).array()))
    def announce(peer: Peer, hashes: Seq[ByteString]): Unit =
      ptm ! WrappedPeerEvent(
        PeerEvent.MessageFromPeer(
          ETHPackets
            .NewPooledTransactionHashes(hashes.map(_ => Transaction.Type02), hashes.map(_ => BigInt(100)), hashes),
          peer.id
        )
      )

    val hashes: Seq[ByteString] = hashesFrom(1, 5000)
    announce(peer1, hashes)
    // peer1 is at the cap: its next announcement is dropped whole.
    val more: Seq[ByteString] = hashesFrom(10001, 10)
    announce(peer1, more)
    // One request in flight per peer (B-028): each answer (empty: these hashes name no real tx) lets the next go out.
    val requests: Seq[(PeerId, Seq[ByteString])] = Seq.fill(16) {
      val request = requestedFrom(this)
      answerEmpty(ptm, peer1)
      request
    }
    requests.map(_._1).distinct shouldBe Seq(peer1.id)
    requests.map(_._2.size) shouldBe Seq.fill(16)(256)
    requests.flatMap(_._2) shouldBe hashes.take(4096) // the first 4096, in announcement order
    etcPeerManager.expectNoMessage(200.millis) // the other 904, and `more`, were dropped, not requested

    // The cap is per peer: another peer announcing the same hashes is asked for them.
    announce(peer2, more)
    requestedFrom(this) shouldBe ((peer2.id, more))

  // ---- one requester per announced hash, re-requested from another announcer on failure ---------------------
  //
  // hive devp2p TestBlobTxWithoutSidecar / TestBlobTxWithMismatchedSidecar (go-ethereum #35869): three peers announce
  // one blob tx, the first one asked serves a bad copy and is dropped, and the test then waits 12 s for one of the
  // other two to be asked. The pool used to ask all three at once (the test's reader swallowed the two duplicate
  // requests while waiting for the first) and, after the drop, nobody: the dropped peer's PeerDisconnected only
  // arrives after the 15 s disconnect-poison-pill-timeout. go-ethereum's tx fetcher asks one announcer at a time and
  // moves on after txFetchTimeout.

  /** The hashes in the next `count` announcements the pool sends, each of which must go to `peer`. */
  private def announcedTo(setup: TestSetup, peer: Peer, count: Int): Set[ByteString] =
    setup.etcPeerManager
      .receiveN(count, Timeouts.normalTimeout)
      .flatMap {
        case NetworkPeerManagerActor.SendMessageCmd(enc, peerId) if peerId == peer.id =>
          enc.underlyingMsg match
            case ETHPackets.NewPooledTransactionHashes(_, _, hashes) => hashes
            case other                                               => fail(s"Expected an announcement, got $other")
        case other => fail(s"Expected an announcement to ${peer.id}, got $other")
      }
      .toSet

  private def requestedFrom(setup: TestSetup): (PeerId, Seq[ByteString]) =
    val cmd = setup.etcPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd]
    cmd.message.underlyingMsg match
      case ETHPackets.GetPooledTransactions(id, hashes) =>
        lastRequestId = id
        (cmd.peerId, hashes)
      case other => fail(s"Expected GetPooledTransactions, got $other")

  /** The request id of the last GetPooledTransactions [[requestedFrom]] read. */
  private var lastRequestId: BigInt = BigInt(-1)

  /** `peer` answers its last request with no txs: it has none of them. */
  private def answerEmpty(ptm: org.apache.pekko.actor.typed.ActorRef[Command], peer: Peer): Unit =
    ptm ! WrappedPeerEvent(PeerEvent.MessageFromPeer(ETHPackets.PooledTransactions(lastRequestId, Seq.empty), peer.id))

  private def announceFrom(
      ptm: org.apache.pekko.actor.typed.ActorRef[Command],
      peer: Peer,
      hash: ByteString,
      size: Int = 100,
      txType: Byte = Transaction.Type03
  ): Unit =
    ptm ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets.NewPooledTransactionHashes72(
          Seq(txType),
          Seq(BigInt(size)),
          Seq(hash),
          ETHPackets.NewPooledTransactionHashes72.NoCustody
        ),
        peer.id
      )
    )

  it should "request an announced hash from one announcer only, and from the next once that request times out" taggedAs (
    UnitTest
  ) in new TestSetup:
    // The tracker's clock moves only when the test moves it: a request times out once the test says so, never while
    // the test is still checking that nobody else was asked. It used to run on the wall clock with a 500 ms timeout,
    // and a test thread that read the first request more than 300 ms late saw peer2's inside its 200 ms window (B-497).
    val fetchTimeout: FiniteDuration = 500.millis
    val now: java.util.concurrent.atomic.AtomicLong = new java.util.concurrent.atomic.AtomicLong(0L)
    def timeOut(): Unit = now.addAndGet(fetchTimeout.toMillis + 1)
    val shortFetchTimeout: TxPoolConfig = new TxPoolConfig:
      override val txPoolSize: Int = 300
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val announcementTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val announcementFetchTimeout: FiniteDuration = fetchTimeout
    val ptm: ActorRef[Command] = spawnPtm(config = shortFetchTimeout, clock = () => now.get())
    val hash: ByteString = ByteString(Array.fill[Byte](32)(7))
    Seq(peer1, peer2, peer3).foreach(announceFrom(ptm, _, hash))

    requestedFrom(this) shouldBe ((peer1.id, Seq(hash)))
    // Not also from peer2 and peer3: every announcement is processed once the pool answers, and the timeout ticks that
    // run meanwhile find nothing timed out, the clock not having moved.
    pool(this, ptm)
    etcPeerManager.expectNoMessage(200.millis)
    timeOut()
    requestedFrom(this) shouldBe ((peer2.id, Seq(hash))) // peer1 never answered
    timeOut()
    requestedFrom(this) shouldBe ((peer3.id, Seq(hash))) // nor did peer2
    timeOut()
    etcPeerManager.expectNoMessage(1.second) // nobody left to ask

  it should "request a hash from the next announcer as soon as its requester disconnects" taggedAs (
    UnitTest
  ) in new TestSetup:
    val hash: ByteString = ByteString(Array.fill[Byte](32)(8))
    announceFrom(pendingTransactionsManager, peer1, hash)
    announceFrom(pendingTransactionsManager, peer2, hash)
    requestedFrom(this) shouldBe ((peer1.id, Seq(hash)))
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerDisconnected(peer1.id))
    // Well inside the 5 s default fetch timeout: the disconnect, not the timer, moved the request.
    requestedFrom(this) shouldBe ((peer2.id, Seq(hash)))

  it should "request a hash from the next announcer as soon as its requester is dropped for a bad delivery" taggedAs (
    UnitTest
  ) in new TestSetup:
    val stx: SignedTransaction = newStx().tx
    val hash = stx.hash.value
    announceFrom(pendingTransactionsManager, peer1, hash, size = 120, txType = 0.toByte)
    announceFrom(pendingTransactionsManager, peer2, hash, size = 110, txType = 0.toByte)
    requestedFrom(this) shouldBe ((peer1.id, Seq(hash)))
    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(ETHPackets.PooledTransactions(BigInt(1), Seq(stx), Seq(110)), peer1.id)
    )
    peerManager.expectMsg(PeerManagerActor.DisconnectPeerFireAndForgetCmd(peer1.id))
    // Asked at once, not after peer1's PeerDisconnected or the 5 s fetch timeout. Its reply will be checked against its
    // own announcement (110 bytes), not peer1's.
    requestedFrom(this) shouldBe ((peer2.id, Seq(hash)))

  // ---- the pool's counters (spec 029 FR-034): each event moves its own counter, and no request changes ------------

  it should "count the hashes peers announce that it does not hold, and each request it sends for one" taggedAs (
    UnitTest
  ) in new TestSetup:
    val counters = new TxPoolMetrics
    val ptm: ActorRef[Command] = spawnPtm(metrics = counters)
    val held: SignedTransactionWithSender = newStx()
    ptm ! AddTransactions(Set(held))
    val unknown: Seq[ByteString] = Seq(7, 8).map(b => ByteString(Array.fill[Byte](32)(b.toByte)))
    val hashes: Seq[ByteString] = held.tx.hash.value +: unknown
    ptm ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets.NewPooledTransactionHashes(hashes.map(_ => 0.toByte), hashes.map(_ => BigInt(100)), hashes),
        peer1.id
      )
    )
    // The request is what it was before the counters: the two hashes the pool does not hold, from the announcer.
    requestedFrom(this) shouldBe ((peer1.id, unknown))
    eventually {
      val counted = counters.snapshot
      (counted.announced, counted.fetchRequested, counted.fetchTimedOut) shouldBe (2L, 2L, 0L)
    }

  it should "count a request nobody answered once for each peer asked, the last one too" taggedAs (
    UnitTest
  ) in new TestSetup:
    val counters = new TxPoolMetrics
    val shortFetchTimeout: TxPoolConfig = new TxPoolConfig:
      override val txPoolSize: Int = 300
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val announcementFetchTimeout: FiniteDuration = 300.millis
    val ptm: ActorRef[Command] = spawnPtm(config = shortFetchTimeout, metrics = counters)
    val hash: ByteString = ByteString(Array.fill[Byte](32)(6))
    Seq(peer1, peer2).foreach(announceFrom(ptm, _, hash))
    requestedFrom(this) shouldBe ((peer1.id, Seq(hash)))
    requestedFrom(this) shouldBe ((peer2.id, Seq(hash))) // peer1 never answered
    // peer2 does not answer either, and there is nobody left to ask: that request timed out as well.
    eventually {
      val counted = counters.snapshot
      (counted.announced, counted.fetchRequested, counted.fetchTimedOut) shouldBe (2L, 2L, 2L)
    }

  it should "count a transaction pushed out for room as evicted, and one that aged out as expired" taggedAs (
    UnitTest
  ) in new TestSetup:
    val full = new TxPoolMetrics
    val small: ActorRef[Command] = spawnPtm(config = poolConfig(size = 4), metrics = full)
    val sent: Seq[SignedTransactionWithSender] = (1 to 12).map(n => newStx(n))
    sent.foreach(stx => small ! AddTransactions(Set(stx)))
    // Every transaction sent is in the pool or was pushed out of it: none is counted twice, and none goes missing.
    eventually {
      val pooled = small.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      pooled.pendingTransactions.size should be <= 4
      full.snapshot.evicted shouldBe (sent.size - pooled.pendingTransactions.size).toLong
    }
    full.snapshot.expired shouldBe 0L

    val aged = new TxPoolMetrics
    val brief: ActorRef[Command] = spawnPtm(config = poolConfig(timeout = 300.millis), metrics = aged)
    brief ! AddTransactions(newStx(99))
    eventually {
      brief
        .ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref))
        .futureValue
        .pendingTransactions shouldBe empty
      aged.snapshot.expired shouldBe 1L
    }
    aged.snapshot.evicted shouldBe 0L

  it should "remove transaction on timeout" taggedAs (UnitTest) in new TestSetup:
    override val txPoolConfig: TxPoolConfig = new TxPoolConfig:
      override val txPoolSize: Int = 300
      override val transactionTimeout: FiniteDuration = 500.millis
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.normalTimeout

      // unused
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout

    override val pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[Command] = testKit.spawn(
      PendingTransactionsManager(txPoolConfig, peerManager.ref, etcPeerManager.ref, peerMessageBus.ref, pendingTxTopic),
      s"ptm-test-timeout-${java.util.UUID.randomUUID()}"
    )

    val stx: SignedTransactionWithSender = newStx()
    pendingTransactionsManager ! AddTransactions(stx)

    eventually {
      val pendingTxs: PendingTransactionsResponse =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      pendingTxs.pendingTransactions.map(_.stx).toSet shouldBe Set(stx)
    }

    // Wait for transaction to timeout (500ms + some buffer for actor processing)
    eventually {
      val pendingTxsAfter: PendingTransactionsResponse =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      pendingTxsAfter.pendingTransactions.map(_.stx).toSet shouldBe Set.empty
    }

  // ── ECIP-1122 pool admission integration tests ──────────────────────────────
  //
  // These tests exercise the validateAgainstState rejection path for zero/low
  // effective tip — the integration coverage missing from OlympiaFeeMarketSpec
  // (which only tests the effectiveTip math, not the actual admission gate).
  //
  // Pattern matches core-geth TestMinGasPriceEnforced, Besu
  // shouldRejectNoPriorityTxsWhenMaxFeePerGasBelowMinGasPrice, and Nethermind
  // TxPoolTests.should_handle_zero_MaxFeePerGas_1559_tx.

  // TestSetup with null blockchainReader: baseFee falls back to baseFeeFloor=0.
  // minTip defaults to BigInt(1) (1 wei). Tests basic rejection of zero-tip txs.
  it should "reject zero-tip legacy tx at pool admission (ECIP-1122, null reader)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in new TestSetup:
    val zeroTipLegacy: LegacyTransaction = LegacyTransaction(
      nonce = BigInt(0),
      gasPrice = GasPrice.Zero, // tip = gasPrice - baseFee = 0 - 0 = 0 < minTip(1)
      gasLimit = GasAmount(21_000),
      receivingAddress = Some(Address(42)),
      value = BigInt(0),
      payload = ByteString.empty
    )
    val stx: SignedTransactionWithSender = newStx(0, zeroTipLegacy)
    pendingTransactionsManager ! AddTransactions(stx)
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions shouldBe empty
    }

  it should "accept 1-wei-tip legacy tx at pool admission (ECIP-1122, null reader)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in new TestSetup:
    val validLegacy: LegacyTransaction = LegacyTransaction(
      nonce = BigInt(0),
      gasPrice = GasPrice(1), // tip = 1 - 0 = 1 >= minTip(1)
      gasLimit = GasAmount(21_000),
      receivingAddress = Some(Address(42)),
      value = BigInt(0),
      payload = ByteString.empty
    )
    val stx: SignedTransactionWithSender = newStx(0, validLegacy)
    pendingTransactionsManager ! AddTransactions(stx)
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions.map(_.stx).toSet shouldBe Set(stx)
    }

  // TestSetup with a fake BlockchainReader returning baseFee = 1 gwei.
  // minTip = 1 wei (default). Tests rejection of zero-effectiveTip EIP-1559 txs
  // when baseFee is at the ETC floor — the realistic Olympia scenario.
  it should "reject Type-2 tx with zero effectiveTip at pool admission (ECIP-1122, 1 gwei baseFee)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in new TestSetupWithBaseFee:
    val zeroTipType2: TransactionWithDynamicFee = TransactionWithDynamicFee(
      chainId = BigInt(61),
      nonce = BigInt(0),
      maxPriorityFeePerGas = BigInt(0), // tip = 0 < minTip(1)
      maxFeePerGas = BaseFeeCalculator.InitialBaseFee, // = 1 gwei
      gasLimit = GasAmount(21_000),
      receivingAddress = Some(Address(42)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil
    )
    val stx: SignedTransactionWithSender = newDynamicStx(BigInt(0), zeroTipType2)
    pendingTransactionsManager ! AddTransactions(stx)
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions shouldBe empty
    }

  it should "accept Type-2 tx with 1-wei effectiveTip at pool admission (ECIP-1122, 1 gwei baseFee)" taggedAs (
    UnitTest,
    OlympiaTest
  ) in new TestSetupWithBaseFee:
    val validType2: TransactionWithDynamicFee = TransactionWithDynamicFee(
      chainId = BigInt(61),
      nonce = BigInt(0),
      maxPriorityFeePerGas = BigInt(1), // effectiveTip = min(1, 1gwei+1 - 1gwei) = 1 >= minTip(1)
      maxFeePerGas = BaseFeeCalculator.InitialBaseFee + 1, // baseFee + 1 wei
      gasLimit = GasAmount(21_000),
      receivingAddress = Some(Address(42)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil
    )
    val stx: SignedTransactionWithSender = newDynamicStx(BigInt(0), validType2)
    pendingTransactionsManager ! AddTransactions(stx)
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions.map(_.stx).toSet shouldBe Set(stx)
    }

  // hive's devp2p Transaction, InvalidTxs and LargeTxRequest all send this shape: tip cap 1 wei, fee cap exactly the
  // head's base fee. At that base fee it pays no tip, but it is includable once the base fee holds or falls, and
  // go-ethereum's pool admits and announces it (it checks the tip CAP against txpool.pricelimit).
  private def feeCapAtBaseFee(chainId: BigInt, tipCap: BigInt): TransactionWithDynamicFee =
    TransactionWithDynamicFee(
      chainId = chainId,
      nonce = BigInt(0),
      maxPriorityFeePerGas = tipCap,
      maxFeePerGas = BaseFeeCalculator.InitialBaseFee, // = the head's base fee in TestSetupWithBaseFee
      gasLimit = GasAmount(21_000),
      receivingAddress = Some(Address(42)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil
    )

  it should "admit a tx whose fee cap sits at the base fee on an ETH-family network, as go-ethereum does" taggedAs (
    UnitTest
  ) in new TestSetupWithBaseFee:
    override def chainConfig: BlockchainConfig =
      // eth, sepolia and hive set no min-tip, so they load go-ethereum's 1 wei default.
      super.chainConfig.copy(networkType = com.chipprbots.ethereum.utils.NetworkType.ETH, minTip = BigInt(1))
    val stx: SignedTransactionWithSender = newDynamicStx(BigInt(0), feeCapAtBaseFee(BigInt(61), tipCap = 1))
    pendingTransactionsManager ! AddTransactions(stx)
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions.map(_.stx).toSet shouldBe Set(stx)
    }

  it should "still reject a tip cap below minTip on an ETH-family network" taggedAs (UnitTest) in new TestSetupWithBaseFee:
    override def chainConfig: BlockchainConfig =
      // eth, sepolia and hive set no min-tip, so they load go-ethereum's 1 wei default.
      super.chainConfig.copy(networkType = com.chipprbots.ethereum.utils.NetworkType.ETH, minTip = BigInt(1))
    val stx: SignedTransactionWithSender = newDynamicStx(BigInt(0), feeCapAtBaseFee(BigInt(61), tipCap = 0))
    pendingTransactionsManager ! AddTransactions(stx)
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions shouldBe empty
    }

  it should "keep ECIP-1122's effective-tip rule on ETC: a fee cap at the base fee pays no tip and is rejected" taggedAs (
    UnitTest,
    OlympiaTest
  ) in new TestSetupWithBaseFee:
    val stx: SignedTransactionWithSender = newDynamicStx(BigInt(0), feeCapAtBaseFee(BigInt(61), tipCap = 1))
    pendingTransactionsManager ! AddTransactions(stx)
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions shouldBe empty
    }

  it should "protect nonce queue: rejected zero-tip tx does not block same-nonce valid tx" taggedAs (
    UnitTest,
    OlympiaTest
  ) in new TestSetupWithBaseFee:
    val baseFee = BaseFeeCalculator.InitialBaseFee
    val zeroTip: TransactionWithDynamicFee = TransactionWithDynamicFee(
      chainId = BigInt(61),
      nonce = BigInt(0),
      maxPriorityFeePerGas = BigInt(0),
      maxFeePerGas = baseFee,
      gasLimit = GasAmount(21_000),
      receivingAddress = Some(Address(42)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil
    )
    val validTip: TransactionWithDynamicFee = TransactionWithDynamicFee(
      chainId = BigInt(61),
      nonce = BigInt(0),
      maxPriorityFeePerGas = BigInt(1),
      maxFeePerGas = baseFee + 1,
      gasLimit = GasAmount(21_000),
      receivingAddress = Some(Address(42)),
      value = BigInt(0),
      payload = ByteString.empty,
      accessList = Nil
    )
    val keyPair: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    val rejectedStx: SignedTransactionWithSender =
      SignedTransactionWithSender(SignedTransaction.sign(zeroTip, keyPair, Some(0x3d)), Address(keyPair))
    val acceptedStx: SignedTransactionWithSender =
      SignedTransactionWithSender(SignedTransaction.sign(validTip, keyPair, Some(0x3d)), Address(keyPair))

    pendingTransactionsManager ! AddTransactions(rejectedStx)
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions shouldBe empty // zero-tip tx rejected, nonce NOT held
    }

    pendingTransactionsManager ! AddTransactions(acceptedStx)
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions.map(_.stx.tx.hash) shouldBe Seq(acceptedStx.tx.hash) // same nonce accepted
    }

  // WI-14 (#1430): the stateless filter admits under the fork active at the chain head, read from the best block header.
  // A 12,000-gas self-transfer is valid from Amsterdam (EIP-2780) and invalid before it (21,000). Driven through a
  // head reset's reinjection, which recovers senders and filters as a peer's transactions are (B-512: the unchecked add
  // these cases used had no sender left in the node, and was deleted).

  it should "filter the transactions it takes back under the fork active at the head: Osaka's rules before Amsterdam" taggedAs (
    UnitTest
  ) in new TestSetupWithHead(headTimestamp = 300L):
    val amsterdamOnly: SignedTransaction = selfTransfer(12000)
    val valid: SignedTransaction = selfTransfer(21000)
    // One message, one filter pass: once `valid` is pooled, `amsterdamOnly` has been judged too.
    pendingTransactionsManager ! HeadReset(Nil, Seq(amsterdamOnly, valid))
    eventually {
      val resp =
        pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
      resp.pendingTransactions.map(_.stx.tx) shouldBe Seq(valid)
    }

  it should "admit a transaction valid only from Amsterdam once the head is at Amsterdam" taggedAs (UnitTest) in
    new TestSetupWithHead(headTimestamp = 460L):
      val amsterdamOnly: SignedTransaction = selfTransfer(12000)
      pendingTransactionsManager ! HeadReset(Nil, Seq(amsterdamOnly))
      eventually {
        val resp =
          pendingTransactionsManager.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue
        resp.pendingTransactions.map(_.stx.tx) shouldBe Seq(amsterdamOnly)
      }

  // ---- tx gossip gate and blob sidecar lifecycle --------------------------------------------------------------------
  //
  // A Sepolia node in SNAP sync OOM-killed itself every ~10 h: 2.6 GB of EIP-4844 network-form sidecars for blob txs
  // that peers delivered and the pool rejected (no state to validate them against). The sidecars were stored before
  // admission and removed only by the cache's eviction listener, which never fires for a tx that never entered the
  // cache. Now an unsynced node ignores tx gossip altogether (go-ethereum's AcceptTxs), and a sidecar lives exactly as
  // long as its tx is pooled, within a byte budget.

  private def pool(setup: TestSetup, ptm: org.apache.pekko.actor.typed.ActorRef[Command]): PendingTransactionsResponse =
    import setup.*
    ptm.ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref)).futureValue

  it should "ignore tx announcements and pooled tx responses from peers while the node is not synced" taggedAs (
    UnitTest
  ) in new TestSetup:
    val gate = new TxGossipGate()
    val ptm: ActorRef[Command] = spawnPtm(gate = gate)
    val blob: SignedTransaction = blobStx(keyPair1)
    val sidecar: ByteString = networkForm(131330)

    ptm ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets
          .NewPooledTransactionHashes(Seq(Transaction.Type03), Seq(BigInt(sidecar.length)), Seq(blob.hash.value)),
        peer1.id
      )
    )
    ptm ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets.NewPooledTransactionHashes72(
          Seq(Transaction.Type03),
          Seq(BigInt(sidecar.length)),
          Seq(blob.hash.value),
          ETHPackets.NewPooledTransactionHashes72.NoCustody
        ),
        peer1.id
      )
    )
    ptm ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets.PooledTransactions(BigInt(1), Seq(blob), blobTxRawBytes = Map(blob.hash.value -> sidecar)),
        peer1.id
      )
    )

    val response: PendingTransactionsResponse = pool(this, ptm)
    response.pendingTransactions shouldBe empty
    response.blobTxNetworkBytes shouldBe empty
    etcPeerManager.expectNoMessage(300.millis) // no GetPooledTransactions
    peerManager.expectNoMessage(100.millis)

  it should "take tx gossip from peers once the node is synced, and stop again while state sync runs" taggedAs (
    UnitTest
  ) in new TestSetup:
    val gate = new TxGossipGate()
    val ptm: ActorRef[Command] = spawnPtm(gate = gate)
    def announce(hash: ByteString): Unit =
      ptm ! WrappedPeerEvent(
        PeerEvent.MessageFromPeer(
          ETHPackets.NewPooledTransactionHashes(Seq(Transaction.Type02), Seq(BigInt(100)), Seq(hash)),
          peer1.id
        )
      )

    gate.markSynced("test")
    val first: ByteString = ByteString(Array.fill[Byte](32)(3))
    announce(first)
    etcPeerManager.expectMsgType[NetworkPeerManagerActor.SendMessageCmd].message.underlyingMsg match
      case ETHPackets.GetPooledTransactions(_, requested) => requested shouldBe Seq(first)
      case other                                          => fail(s"Unexpected: $other")

    gate.stateSyncStarted()
    announce(ByteString(Array.fill[Byte](32)(4)))
    etcPeerManager.expectNoMessage(300.millis)

  it should "keep a delivered blob tx's sidecar only if the pool admits the tx" taggedAs (UnitTest) in new TestSetup:
    // Sender 1 already has nonce 5 pending, so its blob tx at nonce 0 is rejected; sender 2's is admitted.
    pendingTransactionsManager ! AddTransactions(newStx(5, tx.copy(nonce = 5), keyPair1))
    val rejected: SignedTransaction = blobStx(keyPair1, nonce = 0)
    val admitted: SignedTransaction = blobStx(keyPair2, nonce = 0)
    val rejectedSidecar: ByteString = networkForm(131330)
    val admittedSidecar: ByteString = networkForm(131331)

    pendingTransactionsManager ! WrappedPeerEvent(
      PeerEvent.MessageFromPeer(
        ETHPackets.PooledTransactions(
          BigInt(1),
          Seq(rejected, admitted),
          blobTxRawBytes = Map(rejected.hash.value -> rejectedSidecar, admitted.hash.value -> admittedSidecar)
        ),
        peer1.id
      )
    )

    val response: PendingTransactionsResponse = pool(this, pendingTransactionsManager)
    response.pendingTransactions.map(_.stx.tx.hash) should contain(admitted.hash)
    response.pendingTransactions.map(_.stx.tx.hash) shouldNot contain(rejected.hash)
    response.blobTxNetworkBytes shouldBe Map(admitted.hash.value -> admittedSidecar)

  it should "drop a blob tx's sidecar when the tx is removed" taggedAs (UnitTest) in new TestSetup:
    val blob: SignedTransaction = blobStx(keyPair1)
    pendingTransactionsManager ! AddOrOverrideTransaction(blob, Some(networkForm(131330)))
    pool(this, pendingTransactionsManager).blobTxNetworkBytes.keySet shouldBe Set(blob.hash.value)

    pendingTransactionsManager ! RemoveTransactions(Seq(blob))
    pool(this, pendingTransactionsManager).blobTxNetworkBytes shouldBe empty

  it should "drop every sidecar when the pool is cleared" taggedAs (UnitTest) in new TestSetup:
    pendingTransactionsManager ! AddOrOverrideTransaction(blobStx(keyPair1), Some(networkForm(131330)))
    pendingTransactionsManager ! AddOrOverrideTransaction(blobStx(keyPair2), Some(networkForm(131330)))
    pool(this, pendingTransactionsManager).blobTxNetworkBytes should have size 2

    pendingTransactionsManager ! ClearPendingTransactions
    val response: PendingTransactionsResponse = pool(this, pendingTransactionsManager)
    response.pendingTransactions shouldBe empty
    response.blobTxNetworkBytes shouldBe empty

  it should "drop a blob tx's sidecar when a tx with the same sender and nonce replaces it" taggedAs (
    UnitTest
  ) in new TestSetup:
    val blob: SignedTransaction = blobStx(keyPair1, nonce = 0)
    pendingTransactionsManager ! AddOrOverrideTransaction(blob, Some(networkForm(131330)))
    val replacement: SignedTransaction = newStx(0, tx.copy(nonce = 0), keyPair1).tx
    pendingTransactionsManager ! AddOrOverrideTransaction(replacement)

    val response: PendingTransactionsResponse = pool(this, pendingTransactionsManager)
    response.pendingTransactions.map(_.stx.tx.hash) shouldBe Seq(replacement.hash)
    response.blobTxNetworkBytes shouldBe empty

  it should "keep a blob tx's sidecar when the same tx is submitted again" taggedAs (UnitTest) in new TestSetup:
    val blob: SignedTransaction = blobStx(keyPair1)
    val sidecar: ByteString = networkForm(131330)
    pendingTransactionsManager ! AddOrOverrideTransaction(blob, Some(sidecar))
    pendingTransactionsManager ! AddOrOverrideTransaction(blob)

    val response: PendingTransactionsResponse = pool(this, pendingTransactionsManager)
    response.pendingTransactions.map(_.stx.tx.hash) shouldBe Seq(blob.hash)
    response.blobTxNetworkBytes shouldBe Map(blob.hash.value -> sidecar)

  it should "drop a blob tx's sidecar when the pool evicts the tx for size" taggedAs (UnitTest) in new TestSetup:
    val ptm: ActorRef[Command] = spawnPtm(config = poolConfig(size = 1))
    val blob: SignedTransaction = blobStx(keyPair1)
    ptm ! AddOrOverrideTransaction(blob, Some(networkForm(131330)))
    val other: SignedTransaction = newStx(0, tx, keyPair2).tx
    ptm ! AddOrOverrideTransaction(other)

    val response: PendingTransactionsResponse = pool(this, ptm)
    response.pendingTransactions.map(_.stx.tx.hash) shouldBe Seq(other.hash)
    response.blobTxNetworkBytes shouldBe empty

  it should "drop a blob tx's sidecar when the tx times out" taggedAs (UnitTest) in new TestSetup:
    val ptm: ActorRef[Command] = spawnPtm(config = poolConfig(timeout = 1.second))
    ptm ! AddOrOverrideTransaction(blobStx(keyPair1), Some(networkForm(131330)))
    pool(this, ptm).blobTxNetworkBytes should have size 1

    eventually {
      val response = pool(this, ptm)
      response.pendingTransactions shouldBe empty
      response.blobTxNetworkBytes shouldBe empty
    }

  it should "evict the oldest blob txs, sidecar and tx together, past the sidecar byte budget" taggedAs (
    UnitTest
  ) in new TestSetup:
    val ptm: ActorRef[Command] = spawnPtm(config = poolConfig(sidecarBudget = 300000L))
    val keyPair3: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    val blobs: Seq[SignedTransaction] = Seq(keyPair1, keyPair2, keyPair3).map(blobStx(_))
    blobs.foreach(blob => ptm ! AddOrOverrideTransaction(blob, Some(networkForm(131330))))

    val response: PendingTransactionsResponse = pool(this, ptm)
    response.blobTxNetworkBytes.keySet shouldBe blobs.drop(1).map(_.hash.value).toSet
    response.blobTxNetworkBytes.values.map(_.length.toLong).sum should be <= 300000L
    response.pendingTransactions.map(_.stx.tx.hash).toSet shouldBe blobs.drop(1).map(_.hash).toSet

  it should "enforce the sidecar byte budget on blob txs delivered by peers" taggedAs (UnitTest) in new TestSetup:
    val ptm: ActorRef[Command] = spawnPtm(config = poolConfig(sidecarBudget = 300000L))
    val keyPair3: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    val blobs: Seq[SignedTransaction] = Seq(keyPair1, keyPair2, keyPair3).map(blobStx(_))
    blobs.zipWithIndex.foreach { case (blob, i) =>
      ptm ! WrappedPeerEvent(
        PeerEvent.MessageFromPeer(
          ETHPackets
            .PooledTransactions(BigInt(i), Seq(blob), blobTxRawBytes = Map(blob.hash.value -> networkForm(131330))),
          peer1.id
        )
      )
    }

    val response: PendingTransactionsResponse = pool(this, ptm)
    response.blobTxNetworkBytes.keySet shouldBe blobs.drop(1).map(_.hash.value).toSet
    response.pendingTransactions.map(_.stx.tx.hash).toSet shouldBe blobs.drop(1).map(_.hash).toSet

  it should "not pool a blob tx whose sidecar alone exceeds the sidecar byte budget" taggedAs (UnitTest) in new TestSetup:
    val ptm: ActorRef[Command] = spawnPtm(config = poolConfig(sidecarBudget = 100000L))
    ptm ! AddOrOverrideTransaction(blobStx(keyPair1), Some(networkForm(131330)))

    val response: PendingTransactionsResponse = pool(this, ptm)
    response.pendingTransactions shouldBe empty
    response.blobTxNetworkBytes shouldBe empty

  // #1629 S0: the pool admits a peer's tx only if its type is active at the head (TxAdmission.typeRejection, the gate
  // eth_sendRawTransaction applies; go-ethereum / core-geth txpool.ValidateTransaction). A refused tx is not pooled and
  // so never announced.

  private lazy val shippedChains = ConfigFactory.load()
  private def shippedChain(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedChains.getConfig(s"fukuii.blockchains.$name"))

  it should "refuse a peer's 0x2 and 0x4 txs on Mordor before Olympia: not pooled, not announced" taggedAs (
    UnitTest,
    OlympiaTest
  ) in new TestSetupAtHead(shippedChain("mordor"), headNumber = 10_000_000, baseFee = None):
    val id: BigInt = chainConfig.chainId.value
    val keyPair: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    val legacyStx: SignedTransactionWithSender = SignedTransactionWithSender(
      SignedTransaction.sign(
        LegacyTransaction(
          0,
          GasPrice(BigInt(1_000_000_000)),
          GasAmount(21_000),
          Some(Address(42)),
          1,
          ByteString.empty
        ),
        keyPair,
        Some(id)
      ),
      Address(keyPair)
    )
    val type2: SignedTransactionWithSender = signedBy(dynamicFee(id), crypto.generateKeyPair(secureRandom))
    val type4: SignedTransactionWithSender = signedBy(
      com.chipprbots.ethereum.domain.SetCodeTransaction(
        id,
        0,
        1,
        2,
        GasAmount(100_000),
        Some(Address(42)),
        0,
        ByteString.empty,
        Nil,
        List(com.chipprbots.ethereum.domain.SetCodeAuthorization(id, Address(44), 0, 0, 1, 1))
      ),
      crypto.generateKeyPair(secureRandom)
    )
    pendingTransactionsManager ! WrappedPeerEvent(PeerEvent.PeerHandshakeSuccessful(peer1, new HandshakeResult {}))
    pendingTransactionsManager ! ProperSignedTransactions(Set(legacyStx, type2, type4), peer2.id)

    val announced: Seq[ByteString] = etcPeerManager
      .receiveWhile(Timeouts.normalTimeout) { case NetworkPeerManagerActor.SendMessageCmd(enc, _) =>
        enc.underlyingMsg match
          case ETHPackets.NewPooledTransactionHashes(_, _, hashes) => hashes
          case SignedTransactions(txs)                             => txs.map(_.hash.value)
          case _                                                   => Nil
      }
      .flatten
    announced shouldBe Seq(legacyStx.tx.hash.value)
    pool(this, pendingTransactionsManager).pendingTransactions.map(_.stx) shouldBe Seq(legacyStx)

  it should "admit a peer's 0x2 tx on ETH mainnet from London" taggedAs (UnitTest) in new TestSetupAtHead(
    shippedChain("eth"),
    headNumber = shippedChain("eth").forkBlockNumbers.olympiaBlockNumber,
    baseFee = Some(BaseFeeCalculator.InitialBaseFee)
  ):
    val stx: SignedTransactionWithSender =
      signedBy(dynamicFee(chainConfig.chainId.value), crypto.generateKeyPair(secureRandom))
    pendingTransactionsManager ! AddTransactions(stx)
    eventually {
      pool(this, pendingTransactionsManager).pendingTransactions.map(_.stx) shouldBe Seq(stx)
    }

  it should "refuse a peer's 0x1 tx on ETH mainnet before Berlin" taggedAs (UnitTest) in new TestSetupAtHead(
    shippedChain("eth"),
    headNumber = shippedChain("eth").forkBlockNumbers.berlinBlockNumber - 1,
    baseFee = None
  ):
    val id: BigInt = chainConfig.chainId.value
    val accessList: TransactionWithAccessList = com.chipprbots.ethereum.domain.TransactionWithAccessList(
      id,
      0,
      GasPrice(BigInt(2_000_000_000)),
      GasAmount(30_000),
      Some(Address(42)),
      1,
      ByteString.empty,
      Nil
    )
    val refused: SignedTransactionWithSender = signedBy(accessList, crypto.generateKeyPair(secureRandom))
    val admitted: SignedTransactionWithSender = signedBy(
      LegacyTransaction(0, GasPrice(BigInt(2_000_000_000)), GasAmount(21_000), Some(Address(42)), 1, ByteString.empty),
      crypto.generateKeyPair(secureRandom)
    )
    // One message, one admission pass: once `admitted` is pooled, `refused` has been judged too.
    pendingTransactionsManager ! ProperSignedTransactions(Set(refused, admitted), peer2.id)
    eventually {
      pool(this, pendingTransactionsManager).pendingTransactions.map(_.stx) shouldBe Seq(admitted)
    }

  /** A pool whose best block is `headNumber` (with `baseFee`, if any) on `chainConfig`. No state storage, so admission
    * ends at the tip and type checks.
    */
  class TestSetupAtHead(val chainConfig: BlockchainConfig, headNumber: BigInt, baseFee: Option[BigInt])
      extends TestSetup:
    private val head: Block = Block(
      header = com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock.header.copy(
        number = com.chipprbots.ethereum.domain.BlockNumber(headNumber),
        extraFields = baseFee
          .map(HefPostOlympia(_))
          .getOrElse(com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty)
      ),
      body = BlockBody(transactionList = Nil, uncleNodesList = Nil)
    )

    private val readerAtHead: BlockchainReader =
      new BlockchainReader(null, null, null, null, null, null, null, null):
        override def getBestBlockHeader: Option[com.chipprbots.ethereum.domain.BlockHeader] = Some(head.header)
        override def getBestBlock: Option[Block] = Some(head)

    override val pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[Command] = testKit.spawn(
      PendingTransactionsManager(
        txPoolConfig,
        peerManager.ref,
        etcPeerManager.ref,
        peerMessageBus.ref,
        pendingTxTopic,
        blockchainReader = readerAtHead,
        stateStorage = null,
        chainConfig = chainConfig
      ),
      s"ptm-test-at-head-${java.util.UUID.randomUUID()}"
    )

    def dynamicFee(chainId: BigInt): TransactionWithDynamicFee =
      TransactionWithDynamicFee(
        chainId = chainId,
        nonce = BigInt(0),
        maxPriorityFeePerGas = BigInt(1_000_000_000),
        maxFeePerGas = BigInt(3_000_000_000L),
        gasLimit = GasAmount(21_000),
        receivingAddress = Some(Address(42)),
        value = BigInt(1),
        payload = ByteString.empty,
        accessList = Nil
      )

    def signedBy(tx: Transaction, keyPair: AsymmetricCipherKeyPair): SignedTransactionWithSender =
      SignedTransactionWithSender(
        SignedTransaction.sign(tx, keyPair, Some(chainConfig.chainId.value)),
        Address(keyPair)
      )

  /** A pool on an ETH chain with the Amsterdam fixture schedule (Prague 120, Osaka 180, Amsterdam 360), whose best
    * block header sits at `headTimestamp`. No state storage, so admission ends at the tip check.
    */
  class TestSetupWithHead(headTimestamp: Long) extends TestSetup:
    val chainConfig: BlockchainConfig = com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
      .copy(
        networkType = com.chipprbots.ethereum.utils.NetworkType.ETH,
        minTip = BigInt(1),
        forkTimestamps = com.chipprbots.ethereum.utils.ForkTimestamps(
          shanghaiTimestamp = Some(0L),
          cancunTimestamp = Some(60L),
          pragueTimestamp = Some(120L),
          osakaTimestamp = Some(180L),
          amsterdamTimestamp = Some(360L)
        )
      )
      .withUpdatedForkBlocks(
        _.copy(
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip155BlockNumber = 0,
          eip160BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0,
          olympiaBlockNumber = 0
        )
      )

    private val head: Block = Block(
      header = com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock.header.copy(
        unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(headTimestamp),
        extraFields = HefPostOlympia(BaseFeeCalculator.InitialBaseFee)
      ),
      body = BlockBody(transactionList = Nil, uncleNodesList = Nil)
    )

    private val readerAtHead: BlockchainReader =
      new BlockchainReader(null, null, null, null, null, null, null, null):
        override def getBestBlockHeader: Option[com.chipprbots.ethereum.domain.BlockHeader] = Some(head.header)
        override def getBestBlock: Option[Block] = Some(head)

    override val pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[Command] = testKit.spawn(
      PendingTransactionsManager(
        txPoolConfig,
        peerManager.ref,
        etcPeerManager.ref,
        peerMessageBus.ref,
        pendingTxTopic,
        blockchainReader = readerAtHead,
        stateStorage = null,
        chainConfig = chainConfig
      ),
      s"ptm-test-head-${java.util.UUID.randomUUID()}"
    )

    /** A value-bearing self-transfer from a fresh account, tip 1 wei. */
    def selfTransfer(gasLimit: BigInt): SignedTransaction =
      val keyPair = crypto.generateKeyPair(secureRandom)
      SignedTransaction.sign(
        TransactionWithDynamicFee(
          chainId = chainConfig.chainId.value,
          nonce = BigInt(0),
          maxPriorityFeePerGas = BigInt(1),
          maxFeePerGas = BaseFeeCalculator.InitialBaseFee * 2,
          gasLimit = GasAmount(gasLimit),
          receivingAddress = Some(Address(keyPair)),
          value = BigInt(1),
          payload = ByteString.empty,
          accessList = Nil
        ),
        keyPair,
        Some(chainConfig.chainId.value)
      )

  /** TestSetup variant with a fake BlockchainReader that returns baseFee = 1 gwei. */
  trait TestSetupWithBaseFee extends TestSetup:
    /** The chain the pool admits for. The default is the loaded config, which is ETC, with Olympia active from genesis
      * (a synthetic schedule: Olympia is not scheduled on any ETC chain): the head below carries a base fee, so it is
      * an Olympia header, and 0x2 txs are only admissible from Olympia (TxTypeGate, #1629 S0).
      */
    def chainConfig: BlockchainConfig = com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
      .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = 0))

    private val blockWithBaseFee: Block = Block(
      header = com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock.header.copy(
        extraFields = HefPostOlympia(BaseFeeCalculator.InitialBaseFee)
      ),
      body = BlockBody(transactionList = Nil, uncleNodesList = Nil)
    )

    private val fakeBlockchainReader: BlockchainReader =
      new BlockchainReader(null, null, null, null, null, null, null, null):
        override def getBestBlock: Option[Block] = Some(blockWithBaseFee)

    override val pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[Command] = testKit.spawn(
      PendingTransactionsManager(
        txPoolConfig,
        peerManager.ref,
        etcPeerManager.ref,
        peerMessageBus.ref,
        pendingTxTopic,
        blockchainReader = fakeBlockchainReader,
        stateStorage = null,
        chainConfig = chainConfig
      ),
      s"ptm-test-basefee-${java.util.UUID.randomUUID()}"
    )

    def newDynamicStx(
        @scala.annotation.unused nonce: BigInt,
        tx: TransactionWithDynamicFee,
        keyPair: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    ): SignedTransactionWithSender =
      SignedTransactionWithSender(SignedTransaction.sign(tx, keyPair, Some(0x3d)), Address(keyPair))

  trait TestSetup extends SecureRandomBuilder:
    implicit val classicSystem: org.apache.pekko.actor.ActorSystem = testKit.system.classicSystem

    val keyPair1: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)
    val keyPair2: AsymmetricCipherKeyPair = crypto.generateKeyPair(secureRandom)

    val tx: LegacyTransaction = LegacyTransaction(1, GasPrice(1), GasAmount(1), Some(Address(42)), 10, ByteString(""))

    def newStx(
        @scala.annotation.unused nonce: BigInt = 0,
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

    val txPoolConfig: TxPoolConfig = new TxPoolConfig:
      override val txPoolSize: Int = 300

      // unused
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val transactionTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.veryLongTimeout

    implicit lazy val typedScheduler: org.apache.pekko.actor.typed.Scheduler = testKit.system.scheduler
    implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(Timeouts.veryLongTimeout)

    val peerManager: TestProbe = TestProbe()
    val etcPeerManager: TestProbe = TestProbe()
    val peerMessageBus: TestProbe = TestProbe()
    val pendingTxTopic: org.apache.pekko.actor.typed.ActorRef[
      org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewPendingTransaction]
    ] = testKit.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewPendingTransaction](
        "pending-tx-topic"
      ),
      s"pending-tx-topic-${java.util.UUID.randomUUID()}"
    )
    val pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[Command] = testKit.spawn(
      PendingTransactionsManager(txPoolConfig, peerManager.ref, etcPeerManager.ref, peerMessageBus.ref, pendingTxTopic),
      s"ptm-test-${java.util.UUID.randomUUID()}"
    )

    /** A pool on this setup's probes with its own config and gossip gate. */
    def spawnPtm(
        config: TxPoolConfig = txPoolConfig,
        gate: TxGossipGate = TxGossipGate.alwaysOpen,
        metrics: TxPoolMetrics = new TxPoolMetrics,
        clock: () => Long = () => System.currentTimeMillis()
    ): org.apache.pekko.actor.typed.ActorRef[Command] = testKit.spawn(
      PendingTransactionsManager(
        config,
        peerManager.ref,
        etcPeerManager.ref,
        peerMessageBus.ref,
        pendingTxTopic,
        txGossipGate = gate,
        metrics = metrics,
        clock = clock
      ),
      s"ptm-test-custom-${java.util.UUID.randomUUID()}"
    )

    def poolConfig(
        size: Int = 300,
        timeout: FiniteDuration = Timeouts.veryLongTimeout,
        sidecarBudget: Long = TxPoolConfig.DefaultBlobSidecarBudgetBytes
    ): TxPoolConfig = new TxPoolConfig:
      override val txPoolSize: Int = size
      override val pendingTxManagerQueryTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val transactionTimeout: FiniteDuration = timeout
      override val getTransactionFromPoolTimeout: FiniteDuration = Timeouts.veryLongTimeout
      override val blobSidecarBudgetBytes: Long = sidecarBudget

    /** A one-blob EIP-4844 tx on the test chain (id 0x3d), with a tip well above any floor. */
    def blobStx(keyPair: AsymmetricCipherKeyPair, nonce: BigInt = 0): SignedTransaction =
      SignedTransaction.sign(
        BlobTransaction(
          chainId = 0x3d,
          nonce = nonce,
          maxPriorityFeePerGas = BigInt(10).pow(10),
          maxFeePerGas = BigInt(10).pow(12),
          gasLimit = GasAmount(21000),
          receivingAddress = Some(Address(42)),
          value = 0,
          payload = ByteString.empty,
          accessList = Nil,
          maxFeePerBlobGas = 1,
          blobVersionedHashes = List(BlobVersionedHash(ByteString(Array.fill[Byte](32)(1))))
        ),
        keyPair,
        Some(0x3d)
      )

    /** Stand-in for a blob tx's network form: only its length matters to the pool. */
    def networkForm(length: Int): ByteString = ByteString(Array.fill[Byte](length)(0))
