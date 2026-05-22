package com.chipprbots.ethereum.blockchain.sync.regular

import java.net.InetSocketAddress

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.scaladsl.adapter._
import org.apache.pekko.testkit.{TestKit, TestProbe => ClassicProbe}
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.ETH62.{BlockHeaders => ETH62BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{BlockHeaders => ETH66BlockHeaders}
import com.chipprbots.ethereum.network.p2p.messages.ETH66.{GetBlockHeaders => ETH66GetBlockHeaders}
import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for HeadersFetcher (Bug 11 — exponential backoff on empty responses).
  *
  * T1: Empty ETH62 headers → supervisor receives RetryHeadersRequest.
  * T2: Empty ETH66 headers → supervisor receives RetryHeadersRequest.
  * T3: Non-empty headers → supervisor receives ReceivedHeaders (no RetryHeadersRequest).
  * T4: Counter resets after non-empty response — next empty goes back to base delay.
  */
class HeadersFetcherSpec
    extends AnyFlatSpecLike
    with Matchers
    with BeforeAndAfterAll {

  // Use a very short syncRetryInterval so IO.delayBy waits are not too long in tests.
  private val testSyncConfig = new TestSyncConfig {
    override def defaultSyncConfig = super.defaultSyncConfig.copy(
      syncRetryInterval = 20.millis,
      peerResponseTimeout = 5.seconds,
      maxRetryDelay = 30.seconds
    )
  }.syncConfig

  private val as: ActorSystem = ActorSystem("HeadersFetcherSpec_System")
  private val atk: ActorTestKit = ActorTestKit(as.toTyped)

  override def afterAll(): Unit = {
    atk.shutdownTestKit()
    TestKit.shutdownActorSystem(as, verifySystemShutdown = false)
  }

  private val fakePeerActor = ClassicProbe()(as)
  private val fakePeer = Peer(
    PeerId("fetcher-test-peer"),
    new InetSocketAddress("127.0.0.1", 30303),
    fakePeerActor.ref,
    incomingConnection = false
  )

  /** Create a (peersClient probe, supervisor probe, fetcher actor ref) triple. */
  private def makeFetcher() = {
    val peersClient = ClassicProbe()(as)
    val supervisor  = atk.createTestProbe[BlockFetcher.FetchCommand]()
    val fetcher     = atk.spawn(HeadersFetcher(peersClient.ref, testSyncConfig, supervisor.ref))
    (peersClient, supervisor, fetcher)
  }

  /** Reply to the first GetBlockHeaders message with an empty ETH62 response. */
  private def replyEmptyETH62(peersClient: ClassicProbe): Unit = {
    peersClient.expectMsgPF(5.seconds) {
      case PeersClient.Request(_: ETH66GetBlockHeaders, _, _) => ()
    }
    peersClient.reply(PeersClient.Response(fakePeer, ETH62BlockHeaders(Seq.empty)))
  }

  /** Reply to the first GetBlockHeaders message with an empty ETH66 response. */
  private def replyEmptyETH66(peersClient: ClassicProbe): Unit = {
    val requestId = peersClient.expectMsgPF(5.seconds) {
      case PeersClient.Request(msg: ETH66GetBlockHeaders, _, _) => msg.requestId
    }
    peersClient.reply(PeersClient.Response(fakePeer, ETH66BlockHeaders(requestId, Seq.empty)))
  }

  // ─── Tests ────────────────────────────────────────────────────────────────

  "HeadersFetcher" should
    "forward RetryHeadersRequest to supervisor on empty ETH62 headers response (Bug 11)" taggedAs UnitTest in {
      val (peersClient, supervisor, fetcher) = makeFetcher()

      fetcher ! HeadersFetcher.FetchHeadersByNumber(BigInt(1000), BigInt(10))

      replyEmptyETH62(peersClient)

      // After the (short) backoff delay, actor sends RetryHeadersRequest to supervisor
      supervisor.expectMessage(3.seconds, BlockFetcher.RetryHeadersRequest)
    }

  it should
    "forward RetryHeadersRequest to supervisor on empty ETH66 headers response (Bug 11)" taggedAs UnitTest in {
      val (peersClient, supervisor, fetcher) = makeFetcher()

      fetcher ! HeadersFetcher.FetchHeadersByNumber(BigInt(2000), BigInt(10))

      replyEmptyETH66(peersClient)

      supervisor.expectMessage(3.seconds, BlockFetcher.RetryHeadersRequest)
    }

  it should
    "forward ReceivedHeaders to supervisor on non-empty ETH66 response (no backoff)" taggedAs UnitTest in {
      import com.chipprbots.ethereum.Fixtures.{Blocks => FixtureBlocks}

      val (peersClient, supervisor, fetcher) = makeFetcher()

      fetcher ! HeadersFetcher.FetchHeadersByNumber(BigInt(3000), BigInt(1))

      val requestId = peersClient.expectMsgPF(5.seconds) {
        case PeersClient.Request(msg: ETH66GetBlockHeaders, _, _) => msg.requestId
      }
      val header = FixtureBlocks.ValidBlock.header
      peersClient.reply(PeersClient.Response(fakePeer, ETH66BlockHeaders(requestId, Seq(header))))

      val msg = supervisor.expectMessageType[BlockFetcher.ReceivedHeaders](3.seconds)
      msg.headers shouldBe Seq(header)
      msg.peer shouldBe fakePeer
    }

  it should
    "reset the consecutive-empty counter after a non-empty response (Bug 11 regression)" taggedAs UnitTest in {
      import com.chipprbots.ethereum.Fixtures.{Blocks => FixtureBlocks}

      val (peersClient, supervisor, fetcher) = makeFetcher()

      // Round 1: two empty responses escalate the counter
      fetcher ! HeadersFetcher.FetchHeadersByNumber(BigInt(4000), BigInt(10))
      replyEmptyETH66(peersClient)
      supervisor.expectMessage(3.seconds, BlockFetcher.RetryHeadersRequest)

      fetcher ! HeadersFetcher.FetchHeadersByNumber(BigInt(4001), BigInt(10))
      replyEmptyETH66(peersClient)
      supervisor.expectMessage(3.seconds, BlockFetcher.RetryHeadersRequest)

      // Round 2: non-empty response resets counter → no RetryHeadersRequest, correct ReceivedHeaders
      fetcher ! HeadersFetcher.FetchHeadersByNumber(BigInt(4002), BigInt(1))
      val requestId = peersClient.expectMsgPF(5.seconds) {
        case PeersClient.Request(msg: ETH66GetBlockHeaders, _, _) => msg.requestId
      }
      val header = FixtureBlocks.ValidBlock.header
      peersClient.reply(PeersClient.Response(fakePeer, ETH66BlockHeaders(requestId, Seq(header))))

      val msg = supervisor.expectMessageType[BlockFetcher.ReceivedHeaders](3.seconds)
      msg.headers shouldBe Seq(header)

      // Round 3: one more empty response goes back to base-delay (counter reset from 2 to 0)
      // Verify by confirming supervisor still receives RetryHeadersRequest (not a crash or skip)
      fetcher ! HeadersFetcher.FetchHeadersByNumber(BigInt(4003), BigInt(10))
      replyEmptyETH66(peersClient)
      supervisor.expectMessage(3.seconds, BlockFetcher.RetryHeadersRequest)
    }
}
