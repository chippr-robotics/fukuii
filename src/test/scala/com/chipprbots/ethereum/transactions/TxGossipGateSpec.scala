package com.chipprbots.ethereum.transactions

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.GasPrice
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransaction
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** go-ethereum's `synced` flag as fukuii keeps it (see [[TxGossipGate]]), and the Transactions path it closes. */
class TxGossipGateSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load())
    with AnyFlatSpecLike
    with Matchers
    with SecureRandomBuilder:

  "TxGossipGate" should "start closed: an unsynced node takes no transactions from peers" taggedAs (UnitTest) in {
    new TxGossipGate().acceptTxs shouldBe false
  }

  it should "open once the node is marked synced, and stay open (go-ethereum never unsets synced)" taggedAs (
    UnitTest
  ) in {
    val gate = new TxGossipGate()
    gate.markSynced("forkchoiceUpdated set the head")
    gate.acceptTxs shouldBe true
    gate.markSynced("again")
    gate.acceptTxs shouldBe true
  }

  it should "stay closed while state sync runs, synced or not, and reopen when it finishes" taggedAs (UnitTest) in {
    val gate = new TxGossipGate()
    gate.stateSyncStarted()
    gate.markSynced("forkchoiceUpdated naming SNAP's pivot")
    gate.acceptTxs shouldBe false
    gate.stateSyncFinished()
    gate.acceptTxs shouldBe true
    gate.stateSyncStarted() // RegularSyncStuck sends the node back to SNAP
    gate.acceptTxs shouldBe false
  }

  it should "stay closed after state sync if the node never caught up" taggedAs (UnitTest) in {
    val gate = new TxGossipGate()
    gate.stateSyncStarted()
    gate.stateSyncFinished()
    gate.acceptTxs shouldBe false
  }

  "TxGossipGate.alwaysOpen" should "accept from the start" taggedAs (UnitTest) in {
    TxGossipGate.alwaysOpen.acceptTxs shouldBe true
  }

  // ---- SignedTransactionsFilterActor: the Transactions (0x02) path --------------------------------------------------

  private def signedLegacy(): SignedTransaction =
    SignedTransaction.sign(
      LegacyTransaction(0, GasPrice(1), GasAmount(21000), Some(Address(42)), 10, ByteString.empty),
      crypto.generateKeyPair(secureRandom),
      Some(0x3d)
    )

  private def spawnFilter(accept: () => Boolean) =
    val ptm = createTestProbe[PendingTransactionsManager.Command]()
    val bus = createTestProbe[PeerEventBusActor.Command]()
    val filter = spawn(SignedTransactionsFilterActor(ptm.ref, bus.ref, () => Timestamp.Zero, accept))
    (ptm, filter)

  "SignedTransactionsFilterActor" should "drop a peer's Transactions message, unrecovered, while the node is not synced" taggedAs (
    UnitTest
  ) in {
    val (ptm, filter) = spawnFilter(() => false)
    filter ! SignedTransactionsFilterActor.PeerSignedTransactions(SignedTransactions(Seq(signedLegacy())), PeerId("p1"))
    ptm.expectNoMessage(500.millis)
  }

  it should "pass a peer's Transactions message on once the node is synced" taggedAs (UnitTest) in {
    val gate = new TxGossipGate()
    val (ptm, filter) = spawnFilter(() => gate.acceptTxs)
    gate.markSynced("test")
    val stx = signedLegacy()
    filter ! SignedTransactionsFilterActor.PeerSignedTransactions(SignedTransactions(Seq(stx)), PeerId("p1"))
    val forwarded = ptm.expectMessageType[PendingTransactionsManager.ProperSignedTransactions](5.seconds)
    forwarded.signedTransactions.map(_.tx) shouldBe Set(stx)
    forwarded.peerId shouldBe PeerId("p1")
  }
