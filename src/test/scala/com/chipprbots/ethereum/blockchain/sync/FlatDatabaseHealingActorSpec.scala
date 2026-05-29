package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.{ActorRef, ActorSystem}
import org.apache.pekko.testkit.{TestKit, TestProbe, ImplicitSender}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.{AppStateStorage, FlatAccountStorage}
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.testing.PeerTestHelpers
import com.chipprbots.ethereum.testing.Tags._

class FlatDatabaseHealingActorSpec
    extends TestKit(ActorSystem("FlatDatabaseHealingActorSpec"))
    with ImplicitSender
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = TestKit.shutdownActorSystem(system)

  // -----------------------------------------------------------------------
  // Test helpers
  // -----------------------------------------------------------------------

  private val alwaysValidVerifier
      : (Seq[(ByteString, Account)], Seq[ByteString], ByteString, ByteString) => Either[String, Unit] =
    (_, _, _, _) => Right(())

  private val alwaysInvalidVerifier
      : (Seq[(ByteString, Account)], Seq[ByteString], ByteString, ByteString) => Either[String, Unit] =
    (_, _, _, _) => Left("injected failure")

  private def newResources(): (FlatAccountStorage, AppStateStorage) = (
    new FlatAccountStorage(EphemDataSource()),
    new AppStateStorage(EphemDataSource())
  )

  private def spawnActor(
      flatDb: FlatAccountStorage,
      appState: AppStateStorage,
      networkPeerManager: ActorRef,
      controller: ActorRef,
      verifier: (Seq[(ByteString, Account)], Seq[ByteString], ByteString, ByteString) => Either[String, Unit] =
        alwaysValidVerifier,
      stateRoot: ByteString = ByteString(Array.fill[Byte](32)(0x42.toByte))
  ): ActorRef =
    system.actorOf(
      FlatDatabaseHealingActor.props(
        stateRoot = stateRoot,
        flatAccountStorage = flatDb,
        appStateStorage = appState,
        networkPeerManager = networkPeerManager,
        syncController = controller,
        proofVerifier = verifier
      )
    )

  private def encodeAccount(account: Account): ByteString =
    ByteString.fromArrayUnsafe(Account.accountSerializer.toBytes(account))

  private def makeHash(b: Byte): ByteString = ByteString(Array.fill[Byte](32)(b))

  private def accountResponse(
      accounts: Seq[(ByteString, Account)],
      requestId: BigInt = 1
  ): AccountRange =
    AccountRange(requestId = requestId, accounts = accounts, proof = Nil)

  private def sendPeerAndExpectProbe(actor: ActorRef, peerProbe: TestProbe, peerId: String): BigInt = {
    val peer = PeerTestHelpers.createTestPeer(peerId, peerProbe.ref)
    actor ! FlatDatabaseHealingActor.FlatHealPeerAvailable(peer)
    val sendMsg = peerProbe.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    sendMsg.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg.requestId
  }

  // -----------------------------------------------------------------------
  // Tests
  // -----------------------------------------------------------------------

  "FlatDatabaseHealingActor" should
    "overwrite a stale account value already present in flat-DB (H1 — core regression)" taggedAs UnitTest in {
      val (flatDb, appState) = newResources()
      val peerProbe  = TestProbe()
      val controller = TestProbe()
      val actor      = spawnActor(flatDb, appState, peerProbe.ref, controller.ref)

      val hash       = makeHash(0x00.toByte)
      val oldAccount = Account(nonce = 1, balance = 100)
      val newAccount = Account(nonce = 1, balance = 200)
      flatDb.putAccountsBatch(Seq((hash, encodeAccount(oldAccount)))).commit()

      val reqId = sendPeerAndExpectProbe(actor, peerProbe, "stale-peer")
      actor ! accountResponse(Seq((hash, newAccount)), requestId = reqId)

      Thread.sleep(200)
      flatDb.getAccount(hash) shouldBe Some(encodeAccount(newAccount))
    }

  it should "add a missing account not present in flat-DB (H2 — existing behavior regression guard)" taggedAs UnitTest in {
    val (flatDb, appState) = newResources()
    val peerProbe  = TestProbe()
    val controller = TestProbe()
    val actor      = spawnActor(flatDb, appState, peerProbe.ref, controller.ref)

    val hash    = makeHash(0x01.toByte)
    val account = Account(nonce = 5, balance = 999)
    flatDb.getAccount(hash) shouldBe None

    val reqId = sendPeerAndExpectProbe(actor, peerProbe, "missing-peer")
    actor ! accountResponse(Seq((hash, account)), requestId = reqId)

    Thread.sleep(200)
    flatDb.getAccount(hash) shouldBe Some(encodeAccount(account))
  }

  it should "not disturb a correctly-valued flat-DB entry (H3)" taggedAs UnitTest in {
    val (flatDb, appState) = newResources()
    val peerProbe  = TestProbe()
    val controller = TestProbe()
    val actor      = spawnActor(flatDb, appState, peerProbe.ref, controller.ref)

    val hash    = makeHash(0x02.toByte)
    val account = Account(nonce = 3, balance = 777)
    val encoded = encodeAccount(account)
    flatDb.putAccountsBatch(Seq((hash, encoded))).commit()

    val reqId = sendPeerAndExpectProbe(actor, peerProbe, "correct-peer")
    actor ! accountResponse(Seq((hash, account)), requestId = reqId)

    Thread.sleep(200)
    flatDb.getAccount(hash) shouldBe Some(encoded)
  }

  it should "not overwrite a flat-DB entry whose hash was NOT returned by the peer probe (H4)" taggedAs UnitTest in {
    val (flatDb, appState) = newResources()
    val peerProbe  = TestProbe()
    val controller = TestProbe()
    val actor      = spawnActor(flatDb, appState, peerProbe.ref, controller.ref)

    val hashA    = makeHash(0x03.toByte)
    val accountA = Account(nonce = 10, balance = 500)
    val hashB    = makeHash(0x04.toByte)
    val accountB = Account(nonce = 20, balance = 600)
    flatDb.putAccountsBatch(Seq((hashA, encodeAccount(accountA)))).commit()

    val reqId = sendPeerAndExpectProbe(actor, peerProbe, "partial-peer")
    actor ! accountResponse(Seq((hashB, accountB)), requestId = reqId)

    Thread.sleep(200)
    flatDb.getAccount(hashA) shouldBe Some(encodeAccount(accountA)) // untouched
    flatDb.getAccount(hashB) shouldBe Some(encodeAccount(accountB)) // added
  }

  it should "handle mixed response: add missing, overwrite stale, leave correct unchanged (H5)" taggedAs UnitTest in {
    val (flatDb, appState) = newResources()
    val peerProbe  = TestProbe()
    val controller = TestProbe()
    val actor      = spawnActor(flatDb, appState, peerProbe.ref, controller.ref)

    val hashMissing    = makeHash(0x05.toByte)
    val hashCorrect    = makeHash(0x06.toByte)
    val hashStale      = makeHash(0x07.toByte)
    val hashNotInProbe = makeHash(0x08.toByte)

    val accountNew     = Account(nonce = 1)
    val accountCorrect = Account(nonce = 2)
    val accountOld     = Account(nonce = 3, balance = 100)
    val accountUpdated = Account(nonce = 3, balance = 200)
    val accountAbsent  = Account(nonce = 4)

    flatDb.putAccountsBatch(Seq(
      (hashCorrect,    encodeAccount(accountCorrect)),
      (hashStale,      encodeAccount(accountOld)),
      (hashNotInProbe, encodeAccount(accountAbsent))
    )).commit()

    val reqId = sendPeerAndExpectProbe(actor, peerProbe, "mixed-peer")
    actor ! accountResponse(
      Seq(
        (hashMissing, accountNew),
        (hashCorrect, accountCorrect),
        (hashStale,   accountUpdated)
      ),
      requestId = reqId
    )

    Thread.sleep(200)
    flatDb.getAccount(hashMissing)    shouldBe Some(encodeAccount(accountNew))     // added
    flatDb.getAccount(hashCorrect)    shouldBe Some(encodeAccount(accountCorrect)) // unchanged
    flatDb.getAccount(hashStale)      shouldBe Some(encodeAccount(accountUpdated)) // overwritten
    flatDb.getAccount(hashNotInProbe) shouldBe Some(encodeAccount(accountAbsent))  // untouched
  }

  it should "send HealingComplete to syncController after all segments verified (H6)" taggedAs UnitTest in {
    val (flatDb, appState) = newResources()
    appState.putFlatHealingCursor(255).commit() // start at last segment
    val peerProbe  = TestProbe()
    val controller = TestProbe()
    val actor      = spawnActor(flatDb, appState, peerProbe.ref, controller.ref)

    val reqId = sendPeerAndExpectProbe(actor, peerProbe, "complete-peer")
    actor ! accountResponse(Nil, requestId = reqId)

    controller.expectMsg(5.seconds, FlatDatabaseHealingActor.HealingComplete)
  }

  it should "resume from persisted segment cursor on restart (H7)" taggedAs UnitTest in {
    val (flatDb, appState) = newResources()
    appState.putFlatHealingCursor(200).commit()
    val peerProbe  = TestProbe()
    val controller = TestProbe()
    val actor      = spawnActor(flatDb, appState, peerProbe.ref, controller.ref)

    val peer = PeerTestHelpers.createTestPeer("cursor-peer", peerProbe.ref)
    actor ! FlatDatabaseHealingActor.FlatHealPeerAvailable(peer)
    val sendMsg = peerProbe.expectMsgType[NetworkPeerManagerActor.SendMessage](3.seconds)
    val probe   = sendMsg.message.asInstanceOf[GetAccountRangeEnc].underlyingMsg
    // First probe must cover segment 200: startingHash[0] == 200.toByte
    probe.startingHash.head shouldBe 200.toByte
  }

  it should "send HealingComplete immediately and skip all probing when flatHealingDone is set (H8)" taggedAs UnitTest in {
    val (flatDb, appState) = newResources()
    appState.flatHealingDone().commit()
    val peerProbe  = TestProbe()
    val controller = TestProbe()

    spawnActor(flatDb, appState, peerProbe.ref, controller.ref)

    controller.expectMsg(3.seconds, FlatDatabaseHealingActor.HealingComplete)
    peerProbe.expectNoMessage(300.millis)
  }

  it should "switch to waitingForPeer on proof verification failure and not advance the segment (H9)" taggedAs UnitTest in {
    val (flatDb, appState) = newResources()
    appState.putFlatHealingCursor(254).commit()
    val peerProbe  = TestProbe()
    val controller = TestProbe()
    val actor      = spawnActor(flatDb, appState, peerProbe.ref, controller.ref, verifier = alwaysInvalidVerifier)

    val reqId = sendPeerAndExpectProbe(actor, peerProbe, "bad-proof-peer")
    actor ! accountResponse(Nil, requestId = reqId)

    // Actor should NOT advance to segment 255 or send HealingComplete
    controller.expectNoMessage(300.millis)
    // Cursor remains at 254
    appState.getFlatHealingCursor() shouldBe Some(254)
  }
}
