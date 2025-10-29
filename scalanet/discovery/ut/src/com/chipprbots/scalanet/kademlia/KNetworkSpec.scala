package com.chipprbots.scalanet.kademlia

import java.util.UUID
import cats.effect.Resource
import com.chipprbots.scalanet.kademlia.KMessage.KResponse
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import com.chipprbots.scalanet.kademlia.KMessage.KRequest.{FindNodes, Ping}
import com.chipprbots.scalanet.kademlia.KMessage.KResponse.{Nodes, Pong}
import com.chipprbots.scalanet.peergroup.{Channel, PeerGroup}
import com.chipprbots.scalanet.kademlia.KNetwork.KNetworkScalanetImpl
import com.chipprbots.scalanet.kademlia.KRouter.NodeRecord
import cats.effect.IO
import fs2.Stream
import org.scalatest.FlatSpec
import org.scalatest.Matchers._
import org.scalatestplus.mockito.MockitoSugar._
import org.mockito.Mockito.{when}

import scala.concurrent.duration._
.Implicits.global
import org.scalatest.concurrent.ScalaFutures._
import com.chipprbots.scalanet.TaskValues._
import KNetworkSpec._
import com.chipprbots.scalanet.peergroup.Channel.MessageReceived
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import com.chipprbots.scalanet.kademlia.KMessage.KRequest
import org.scalatest.prop.TableDrivenPropertyChecks._
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent
import com.chipprbots.scalanet.peergroup.Channel.ChannelEvent
import java.util.concurrent.atomic.AtomicInteger

class KNetworkSpec extends FlatSpec {
  import KNetworkRequestProcessing._

  implicit val patienceConfig = PatienceConfig(1 second)

  private val getFindNodesRequest: KNetwork[String] => IO[KRequest[String]] = getActualRequest(_.findNodesRequests())
  private val getPingRequest: KNetwork[String] => IO[KRequest[String]] = getActualRequest(_.pingRequests())

  private val sendFindNodesRequest: (NodeRecord[String], FindNodes[String]) => KNetwork[String] => IO[Nodes[String]] =
    (to, request) => network => network.findNodes(to, request)

  private val sendPingRequest: (NodeRecord[String], Ping[String]) => KNetwork[String] => IO[Pong[String]] =
    (to, request) => network => network.ping(to, request)

  private val rpcs = Table(
    ("Label", "Request", "Response", "Request extractor", "Client RPC"),
    ("FIND_NODES", findNodes, nodes, getFindNodesRequest, sendFindNodesRequest(targetRecord, findNodes)),
    ("PING", ping, pong, getPingRequest, sendPingRequest(targetRecord, ping))
  )

  trait Fixture {
    class MockChannel {
      val channel = mock[Channel[String, KMessage[String]]]
      val closed = new AtomicBoolean(false)
      val created = ChannelCreated(channel, Task { closed.set(true) })
      val resource = Resource.make(IO.pure(channel))(_ => Task { closed.set(true) })
    }

    val (network, peerGroup) = createKNetwork
    val (channel, channelCreated, channelClosed, channelResource) = {
      val mc = new MockChannel
      (mc.channel, mc.created, mc.closed, mc.resource)
    }
  }

  forAll(rpcs) { (label, request, response, requestExtractor, clientRpc) =>
    s"Server $label" should "not close server channels while yielding requests (it is the responsibility of the response handler)" in new Fixture {
      mockServerEvents(peerGroup, channelCreated)
      mockChannelEvents(channel, MessageReceived(request))

      val actualRequest = requestExtractor(network).evaluated

      actualRequest shouldBe request
      channelClosed.get shouldBe false
    }

    s"Server $label" should "close server channels when a request does not arrive before a timeout" in new Fixture {
      mockServerEvents(peerGroup, channelCreated)
      mockChannelEvents(channel)

      val t = requestExtractor(network).runToFuture.failed.futureValue

      // The timeout on the channel doesn't cause this exception, but rather the fact
      // that there's no subsequent server event and the server observable
      // gets closed, so `getActualRequest` fails because it uses `.headL`.
      t shouldBe a[NoSuchElementException]
      channelClosed.get shouldBe true
    }

    s"Server $label" should "close server channel in the response task" in new Fixture {
      mockServerEvents(peerGroup, channelCreated)
      mockChannelEvents(channel, MessageReceived(request))
      when(channel.sendMessage(response)).thenReturn(IO.unit)

      sendResponse(network, response).evaluated

      channelClosed.get shouldBe true
    }

    s"Server $label" should "close server channel in timed out response task" in new Fixture {
      mockServerEvents(peerGroup, channelCreated)
      mockChannelEvents(channel, MessageReceived(request))
      when(channel.sendMessage(response)).thenReturn(IO.never)

      sendResponse(network, response).evaluatedFailure shouldBe a[TimeoutException]
      channelClosed.get shouldBe true
    }

    s"Server $label" should "keep working even if there is an error" in new Fixture {
      val channel1 = new MockChannel
      val channel2 = new MockChannel

      mockServerEvents(peerGroup, channel1.created, channel2.created)
      mockChannelEvents(channel1.channel)
      mockChannelEvents(channel2.channel, MessageReceived(request))

      // Process incoming channels and requests. Need to wait a little to allow channel1 to time out.
      val actualRequest = requestExtractor(network).delayResult(requestTimeout).evaluated

      actualRequest shouldBe request
      channel1.closed.get shouldBe true
      channel2.closed.get shouldBe false
    }

    s"Client $label" should "close client channels when requests are successful" in new Fixture {
      when(peerGroup.client(targetRecord.routingAddress)).thenReturn(channelResource)
      when(channel.sendMessage(request)).thenReturn(IO.unit)
      mockChannelEvents(channel, MessageReceived(response))

      val actualResponse = clientRpc(network).evaluated

      actualResponse shouldBe response
      channelClosed.get shouldBe true
    }

    s"Client $label" should "pass exception when client call fails" in new Fixture {
      val exception = new Exception("failed")

      when(peerGroup.client(targetRecord.routingAddress))
        .thenReturn(Resource.liftF(IO.raiseError[Channel[String, KMessage[String]]](exception)))

      clientRpc(network).evaluatedFailure shouldBe exception
    }

    s"Client $label" should "close client channels when sendMessage calls fail" in new Fixture {
      val exception = new Exception("failed")
      when(peerGroup.client(targetRecord.routingAddress)).thenReturn(channelResource)
      when(channel.sendMessage(request)).thenReturn(IO.raiseError(exception))

      clientRpc(network).evaluatedFailure shouldBe exception
      channelClosed.get shouldBe true
    }

    s"Client $label" should "close client channels when response fails to arrive" in new Fixture {
      when(peerGroup.client(targetRecord.routingAddress)).thenReturn(channelResource)
      when(channel.sendMessage(request)).thenReturn(IO.unit)
      mockChannelEvents(channel)

      clientRpc(network).evaluatedFailure shouldBe a[TimeoutException]
      channelClosed.get shouldBe true
    }
  }

  s"In consuming only PING" should "channels should be closed for unhandled FIND_NODES requests" in new Fixture {
    val channel1 = new MockChannel
    val channel2 = new MockChannel
    mockServerEvents(peerGroup, channel1.created, channel2.created)

    mockChannelEvents(channel1.channel, MessageReceived(findNodes))
    mockChannelEvents(channel2.channel, MessageReceived(ping))

    when(channel2.channel.sendMessage(pong)).thenReturn(IO.unit)

    // `pingRequests` consumes all requests and call `ignore` on the FindNodes, passing None which should close the channel.
    val (actualRequest, handler) = network.pingRequests().headL.evaluated

    actualRequest shouldBe ping
    channel1.closed.get shouldBe true
    channel2.closed.get shouldBe false

    handler(Some(pong)).runToFuture.futureValue
    channel2.closed.get shouldBe true
  }
}

object KNetworkSpec {

  val requestTimeout = 50.millis

  private val nodeRecord: NodeRecord[String] = Generators.aRandomNodeRecord()
  private val targetRecord: NodeRecord[String] = Generators.aRandomNodeRecord()
  private val uuid: UUID = UUID.randomUUID()
  private val findNodes = FindNodes(uuid, nodeRecord, targetRecord.id)
  private val nodes = Nodes(uuid, targetRecord, Seq.empty)

  private val ping = Ping(uuid, nodeRecord)
  private val pong = Pong(uuid, targetRecord)

  private def createKNetwork: (KNetwork[String], PeerGroup[String, KMessage[String]]) = {
    val peerGroup = mock[PeerGroup[String, KMessage[String]]]
    when(peerGroup.nextServerEvent).thenReturn(IO.pure(None))
    (new KNetworkScalanetImpl(peerGroup, requestTimeout), peerGroup)
  }

  private def mockServerEvents(
      peerGroup: PeerGroup[String, KMessage[String]],
      events: ServerEvent[String, KMessage[String]]*
  ) =
    when(peerGroup.nextServerEvent).thenReturn(nextTask(events, complete = true))

  private def mockChannelEvents(
      channel: Channel[String, KMessage[String]],
      events: ChannelEvent[KMessage[String]]*
  ) =
    when(channel.nextChannelEvent).thenReturn(nextTask(events, complete = false))

  private def nextIO[T](events: Seq[T], complete: Boolean): IO[Option[T]] = {
    val count = new AtomicInteger(0)
    Task(count.getAndIncrement()).flatMap {
      case i if i < events.size => Task(Some(events(i)))
      case _ if complete => Task(None)
      case _ => IO.never
    }
  }

  private def getActualRequest[Request <: KRequest[String]](rpc: KNetwork[String] => Stream[IO, (Request, _)])(
      network: KNetwork[String]
  ): IO[Request] = {
    rpc(network).headL.map(_._1)
  }

  def sendResponse(network: KNetwork[String], response: KResponse[String]): IO[Unit] = {
    network.kRequests.headL.flatMap { case (_, handler) => handler(Some(response)) }
  }
}
