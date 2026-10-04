package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.Scheduler
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*

import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.Blacklist.BlacklistReason
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockAccessList.AccountChanges
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags.*

/** EIP-8159 list fetching for regular-sync catch-up: a list is a hint, so every failure mode yields "no list". */
class BlockAccessListFetcherSpec extends ScalaTestWithActorTestKit with AnyFreeSpecLike with Matchers:

  private given scheduler: Scheduler = system.scheduler

  private val zero32 = ByteString(new Array[Byte](32))

  private def bal(seed: Int): BlockAccessList =
    BlockAccessList(Seq(AccountChanges(Address(BigInt(0x1000 + seed)), Nil, Seq(UInt256(seed + 1)), Nil, Nil, Nil)))

  private def amsterdamBlock(number: Int, balHash: ByteString): Block =
    val base = Fixtures.Blocks.ValidBlock.header
    Block(
      base.copy(
        number = BlockNumber(number),
        extraFields = HefPostAmsterdam(7, zero32, 0, 0, zero32, zero32, balHash, slotNumber = number)
      ),
      BlockBody.empty
    )

  private def plainBlock(number: Int): Block =
    Block(Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(number)), BlockBody.empty)

  private class Fixture(timeout: FiniteDuration = 2.seconds):
    var clock: Long = 1_000L
    val client: TestProbe[PeersClient.Command] = createTestProbe[PeersClient.Command]()
    val peerProbe: TestProbe[PeerActor.Command] = createTestProbe[PeerActor.Command]()
    val peer: Peer =
      Peer(PeerId("p1"), new InetSocketAddress("127.0.0.1", 0), peerProbe.ref, incomingConnection = false)
    val fetcher = new BlockAccessListFetcher(client.ref, timeout, () => clock)

    def request(): (PeersClient.Request[?], ETHPackets.GetBlockAccessLists) =
      val req = client.expectMessageType[PeersClient.Request[?]]
      req.peerSelector shouldBe PeersClient.BestEth71PeerExcluding(Set.empty)
      (req, req.message.asInstanceOf[ETHPackets.GetBlockAccessLists])

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 10.seconds)

  "BlockAccessListFetcher" - {

    "fetches the lists of an Amsterdam batch from an eth/71 peer and hands on those that match the header" taggedAs (
      UnitTest,
      NetworkTest
    ) in new Fixture:
      val (b1, b2) = (bal(1), bal(2))
      val blocks = Seq(amsterdamBlock(1, b1.hash), amsterdamBlock(2, b2.hash))
      val result = fetcher.fetch(blocks).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)
      val (req, get) = request()
      get.blockHashes shouldBe blocks.map(_.header.hash.value)
      req.replyTo ! PeersClient.Response(
        peer,
        ETHPackets.BlockAccessLists(get.requestId, Seq(b1.toRLPEncodable, b2.toRLPEncodable))
      )
      await(result) shouldBe Map(blocks(0).header.hash.value -> b1, blocks(1).header.hash.value -> b2)
      client.expectNoMessage(100.millis)

    "drops a list that does not hash to the header's and penalises the peer" taggedAs (
      UnitTest,
      NetworkTest
    ) in new Fixture:
      val (good, other) = (bal(1), bal(2))
      val blocks = Seq(amsterdamBlock(1, good.hash))
      val result = fetcher.fetch(blocks).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)
      val (req, get) = request()
      req.replyTo ! PeersClient.Response(peer, ETHPackets.BlockAccessLists(get.requestId, Seq(other.toRLPEncodable)))
      await(result) shouldBe empty
      client.expectMessageType[PeersClient.BlacklistPeer] match
        case PeersClient.BlacklistPeer(id, reason) =>
          id shouldBe peer.id
          reason shouldBe a[BlacklistReason.InvalidBlockAccessList]

    "treats the empty string as unavailable, without penalty" taggedAs (UnitTest, NetworkTest) in new Fixture:
      val blocks = Seq(amsterdamBlock(1, bal(1).hash))
      val result = fetcher.fetch(blocks).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)
      val (req, get) = request()
      req.replyTo ! PeersClient.Response(
        peer,
        ETHPackets.BlockAccessLists(get.requestId, Seq(RLPValue(Array.emptyByteArray)))
      )
      await(result) shouldBe empty
      client.expectNoMessage(100.millis)

    "keeps the valid entries around an unavailable one" taggedAs (UnitTest, NetworkTest) in new Fixture:
      val (b1, b3) = (bal(1), bal(3))
      val blocks = Seq(amsterdamBlock(1, b1.hash), amsterdamBlock(2, bal(2).hash), amsterdamBlock(3, b3.hash))
      val result = fetcher.fetch(blocks).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)
      val (req, get) = request()
      req.replyTo ! PeersClient.Response(
        peer,
        ETHPackets.BlockAccessLists(
          get.requestId,
          Seq(b1.toRLPEncodable, RLPValue(Array.emptyByteArray), b3.toRLPEncodable)
        )
      )
      await(result) shouldBe Map(blocks(0).header.hash.value -> b1, blocks(2).header.hash.value -> b3)

    "follows a response cut short by the soft limit with a request for the tail" taggedAs (
      UnitTest,
      NetworkTest
    ) in new Fixture:
      val (b1, b2) = (bal(1), bal(2))
      val blocks = Seq(amsterdamBlock(1, b1.hash), amsterdamBlock(2, b2.hash))
      val result = fetcher.fetch(blocks).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)
      val (req1, get1) = request()
      req1.replyTo ! PeersClient.Response(peer, ETHPackets.BlockAccessLists(get1.requestId, Seq(b1.toRLPEncodable)))
      val (req2, get2) = request()
      get2.blockHashes shouldBe Seq(blocks(1).header.hash.value)
      req2.replyTo ! PeersClient.Response(peer, ETHPackets.BlockAccessLists(get2.requestId, Seq(b2.toRLPEncodable)))
      await(result).keySet shouldBe blocks.map(_.header.hash.value).toSet

    "returns no lists when no eth/71 peer is available, and stays quiet afterwards" taggedAs (
      UnitTest,
      NetworkTest
    ) in new Fixture:
      val blocks = Seq(amsterdamBlock(1, bal(1).hash))
      val result = fetcher.fetch(blocks).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)
      request()._1.replyTo ! PeersClient.NoSuitablePeer
      await(result) shouldBe empty
      await(fetcher.fetch(blocks).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)) shouldBe empty
      client.expectNoMessage(100.millis)
      clock += BlockAccessListFetcher.SuppressionMs + 1
      val again = fetcher.fetch(blocks).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)
      request()._1.replyTo ! PeersClient.NoSuitablePeer
      await(again) shouldBe empty

    "gives up after its timeout when the peer never answers" taggedAs (UnitTest, NetworkTest) in new Fixture(
      300.millis
    ):
      val blocks = Seq(amsterdamBlock(1, bal(1).hash))
      val result = fetcher.fetch(blocks).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)
      request()
      await(result) shouldBe empty

    "sends nothing for blocks without a block access list hash" taggedAs (UnitTest, NetworkTest) in new Fixture:
      await(
        fetcher.fetch(Seq(plainBlock(1), plainBlock(2))).unsafeToFuture()(cats.effect.unsafe.IORuntime.global)
      ) shouldBe empty
      client.expectNoMessage(200.millis)
  }
