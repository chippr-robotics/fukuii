package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestSnapPeer
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchedAccountStorage
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.testing.Tags._

class AccountStorageFetcherSpec
    extends TestKit(ActorSystem("AccountStorageFetcherSpec"))
    with AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with TestSyncConfig {

  private var typedKit: ActorTestKit = _

  override def beforeEach(): Unit =
    typedKit = ActorTestKit("AccountStorageFetcherTest-" + System.nanoTime())

  override def afterEach(): Unit =
    typedKit.shutdownTestKit()

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system, verifySystemShutdown = false)

  private val accountAddress: ByteString = ByteString(Array.fill(20)(0x88.toByte))
  private val accountHash: ByteString = kec256(accountAddress)
  private val canonicalRoot: ByteString = ByteString(Array.fill(32)(0xaa.toByte))
  private val storageRoot: ByteString = ByteString(Array.fill(32)(0xd0.toByte))

  private val testPeer: Peer = Peer(
    PeerId("test-peer"),
    new InetSocketAddress("127.0.0.1", 30303),
    TestProbe().ref,
    false
  )

  private trait Setup {
    val peersClient: TestProbe = TestProbe()
    val replyTo: TestProbe = TestProbe()
    val stateStorage: StateStorage = null // not exercised in unit tests (writes bypassed by mocking)

    def spawnFetcher(): Unit =
      typedKit.spawn(
        AccountStorageFetcher(
          accountAddress,
          replyTo.ref,
          Some(canonicalRoot),
          peersClient.ref,
          stateStorage,
          syncConfig
        ),
        "account-storage-fetcher-" + System.nanoTime()
      )

    def expectGetAccountRange(): GetAccountRange = {
      val req = peersClient.expectMsgClass(3.seconds, classOf[PeersClient.Request[_]])
      req.peerSelector shouldBe BestSnapPeer
      req.message shouldBe a[GetAccountRange]
      req.message.asInstanceOf[GetAccountRange]
    }

    def respondWithAccount(reqId: BigInt, account: Account): Unit = {
      val response = AccountRange(
        requestId = reqId,
        accounts = Seq((accountHash, account)),
        proof = Seq.empty
      )
      peersClient.reply(PeersClient.Response(testPeer, response))
    }

    def expectGetStorageRanges(): GetStorageRanges = {
      val req = peersClient.expectMsgClass(3.seconds, classOf[PeersClient.Request[_]])
      req.peerSelector shouldBe BestSnapPeer
      req.message shouldBe a[GetStorageRanges]
      req.message.asInstanceOf[GetStorageRanges]
    }

    def respondWithStorageRanges(reqId: BigInt, proofNodes: Seq[ByteString] = Seq.empty): Unit = {
      val response = StorageRanges(
        requestId = reqId,
        slots = Seq(Seq.empty), // minimal slot response
        proof = proofNodes
      )
      peersClient.reply(PeersClient.Response(testPeer, response))
    }
  }

  "AccountStorageFetcher" should "send GetAccountRange to BestSnapPeer on start" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup {
    spawnFetcher()
    val gar = expectGetAccountRange()
    gar.rootHash shouldBe canonicalRoot
    gar.startingHash shouldBe accountHash
  }

  it should "escalate to GetStorageRanges after receiving AccountRange with canonical storageRoot" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup {
    spawnFetcher()
    val gar = expectGetAccountRange()

    val account = Account(storageRoot = storageRoot)
    respondWithAccount(gar.requestId, account)

    val gsr = expectGetStorageRanges()
    gsr.rootHash shouldBe canonicalRoot
    gsr.accountHashes should contain(accountHash)
  }

  it should "reply FetchedAccountStorage(success=true) with canonical account after StorageRanges response" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup {
    spawnFetcher()
    val gar = expectGetAccountRange()
    val account = Account(storageRoot = storageRoot)
    respondWithAccount(gar.requestId, account)

    val gsr = expectGetStorageRanges()
    val proofNode = ByteString(Array.fill(32)(0xbb.toByte))
    respondWithStorageRanges(gsr.requestId, proofNodes = Seq(proofNode))

    replyTo.expectMsgPF(3.seconds) { case FetchedAccountStorage(addr, Some(acc), true) =>
      addr shouldBe accountAddress
      acc.storageRoot shouldBe storageRoot
    }
  }

  it should "skip GetStorageRanges and reply success when account has empty storageRoot" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup {
    spawnFetcher()
    val gar = expectGetAccountRange()

    val emptyAccount = Account(storageRoot = Account.EmptyStorageRootHash)
    respondWithAccount(gar.requestId, emptyAccount)

    // No GetStorageRanges expected — empty storage, skip straight to success
    peersClient.expectNoMessage(200.millis)

    replyTo.expectMsgPF(3.seconds) { case FetchedAccountStorage(addr, Some(acc), true) =>
      addr shouldBe accountAddress
      acc.storageRoot shouldBe Account.EmptyStorageRootHash
    }
  }

  it should "reply FetchedAccountStorage(success=false) when account not found in AccountRange response" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup {
    spawnFetcher()
    val gar = expectGetAccountRange()

    // Respond with a DIFFERENT account hash — our account not found
    val wrongHash = ByteString(Array.fill(32)(0xff.toByte))
    val response = AccountRange(requestId = gar.requestId, accounts = Seq((wrongHash, Account())), proof = Seq.empty)
    peersClient.reply(PeersClient.Response(testPeer, response))

    replyTo.expectMsgPF(3.seconds) { case FetchedAccountStorage(addr, None, false) =>
      addr shouldBe accountAddress
    }
  }

  it should "reply FetchedAccountStorage(success=false) when no canonical state root provided" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup {
    // Spawn with no canonical state root
    typedKit.spawn(
      AccountStorageFetcher(accountAddress, replyTo.ref, None, peersClient.ref, stateStorage, syncConfig),
      "account-storage-fetcher-noroot"
    )

    peersClient.expectNoMessage(200.millis)

    replyTo.expectMsgPF(3.seconds) { case FetchedAccountStorage(addr, None, false) =>
      addr shouldBe accountAddress
    }
  }

  it should "reply FetchedAccountStorage(success=false) on GetAccountRange Retry (timeout/failure)" taggedAs (
    UnitTest,
    SyncTest
  ) in new Setup {
    spawnFetcher()
    expectGetAccountRange()

    // Respond with NoSuitablePeer to trigger Retry path
    peersClient.reply(PeersClient.NoSuitablePeer)

    replyTo.expectMsgPF(5.seconds) { case FetchedAccountStorage(addr, None, false) =>
      addr shouldBe accountAddress
    }
  }
}
