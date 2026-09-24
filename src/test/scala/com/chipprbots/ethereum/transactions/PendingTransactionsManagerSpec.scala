package com.chipprbots.ethereum.transactions

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
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
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.domain.Transaction
import com.chipprbots.ethereum.domain.TransactionWithDynamicFee
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.SendMessageCmd
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeResult
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.OlympiaTest
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.*
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
  * @see
  *   [[com.chipprbots.ethereum.blockchain.sync.StateStorageActorSpec]] for eventually pattern
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
    pendingTransactionsManager ! AddOrOverrideTransaction(overrideTx.tx)

    eventually {
      val pendingTxs: Seq[PendingTransaction] = pendingTransactionsManager
        .ask[PendingTransactionsResponse](ref => GetPendingTransactionsReq(ref))
        .futureValue
        .pendingTransactions

      pendingTxs.map(_.stx).toSet shouldEqual Set(overrideTx, otherTx)
    }

    // AddOrOverride queues a NotifyPeers self-message whose broadcast list is
    // filtered to txs still in the pool when processed. Because AddOrOverride
    // processes in-order but NotifyPeers is deferred, firstTx gets invalidated
    // (by overrideTx) before its announce fires, so only otherTx and
    // overrideTx reach peer1. Both land as NewPooledTransactionHashes (ETH/67).
    val announces: Seq[SendMessageCmd] = etcPeerManager.receiveWhile(Timeouts.normalTimeout, messages = 3) {
      case m: NetworkPeerManagerActor.SendMessageCmd => m
    }
    announces.foreach(_.peerId shouldBe peer1.id)
    val announcedHashes: Set[ByteString] = announces
      .flatMap(_.message.underlyingMsg match
        case ETHPackets.NewPooledTransactionHashes(_, _, hashes) => hashes
        case SignedTransactions(txs)                             => txs.map(_.hash.value)
        case _                                                   => Nil
      )
      .toSet
    (announcedHashes should contain).allOf(otherTx.tx.hash.value, overrideTx.tx.hash.value)
    announcedHashes shouldNot contain(firstTx.tx.hash.value)

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
    val blobTx = BlobTransaction(
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
    val signed = SignedTransaction.sign(blobTx, keyPair1, Some(0x3d))
    val networkForm = ByteString(Array.fill[Byte](131330)(0))

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

  /** TestSetup variant with a fake BlockchainReader that returns baseFee = 1 gwei. */
  trait TestSetupWithBaseFee extends TestSetup:
    private val blockWithBaseFee: Block = Block(
      header = com.chipprbots.ethereum.Fixtures.Blocks.ValidBlock.header.copy(
        extraFields = HefPostOlympia(BaseFeeCalculator.InitialBaseFee)
      ),
      body = BlockBody(transactionList = Nil, uncleNodesList = Nil)
    )

    private val fakeBlockchainReader: BlockchainReader =
      new BlockchainReader(null, null, null, null, null, null, null):
        override def getBestBlock: Option[Block] = Some(blockWithBaseFee)

    override val pendingTransactionsManager: org.apache.pekko.actor.typed.ActorRef[Command] = testKit.spawn(
      PendingTransactionsManager(
        txPoolConfig,
        peerManager.ref,
        etcPeerManager.ref,
        peerMessageBus.ref,
        pendingTxTopic,
        blockchainReader = fakeBlockchainReader,
        stateStorage = null
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
