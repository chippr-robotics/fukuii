package com.chipprbots.scalanet.discovery.ethereum.v4.mocks

import cats.effect.{Resource, IO, Ref}
import cats.effect.std.Queue
import com.chipprbots.scalanet.peergroup.PeerGroup
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent
import com.chipprbots.scalanet.peergroup.PeerGroup.ServerEvent.ChannelCreated
import com.chipprbots.scalanet.peergroup.Channel.{ChannelEvent, MessageReceived}
import com.chipprbots.scalanet.peergroup.Channel
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._

class MockPeerGroup[A, M](
    override val processAddress: A,
    serverEventsQueue: Queue[IO, ServerEvent[A, M]]
) extends PeerGroup[A, M] {

  private val channels = TrieMap.empty[A, MockChannel[A, M]]

  // Intended for the System Under Test to read incoming channels.
  override def nextServerEvent: IO[Option[PeerGroup.ServerEvent[A, M]]] =
    serverEventsQueue.take.map(Some(_))

  // Intended for the System Under Test to open outgoing channels.
  override def client(to: A): Resource[IO, Channel[A, M]] = {
    Resource.make(
      for {
        channel <- getOrCreateChannel(to)
        _ <- IO(channel.refCount.increment())
      } yield channel
    ) { channel =>
      IO(channel.refCount.decrement())
    }
  }

  def getOrCreateChannel(to: A): IO[MockChannel[A, M]] =
    IO(channels.getOrElseUpdate(to, new MockChannel[A, M](processAddress, to)))

  def createServerChannel(from: A): IO[MockChannel[A, M]] =
    for {
      channel <- IO(new MockChannel[A, M](processAddress, from))
      _ <- IO(channel.refCount.increment())
      event = ChannelCreated(channel, IO(channel.refCount.decrement()))
      _ <- serverEventsQueue.offer(event)
    } yield channel
}

object MockPeerGroup {
  def apply[A, M](processAddress: A): IO[MockPeerGroup[A, M]] =
    Queue.unbounded[IO, ServerEvent[A, M]].map(queue => new MockPeerGroup(processAddress, queue))
}

class MockChannel[A, M](
    override val from: A,
    override val to: A
)(implicit val s: Scheduler)
    extends Channel[A, M] {

  // In lieu of actually closing the channel,
  // just count how many times t was opened and released.
  val refCount = AtomicInt(0)

  private val messagesFromSUT = ConcurrentQueue[Task].unsafe[ChannelEvent[M]](BufferCapacity.Unbounded())
  private val messagesToSUT = ConcurrentQueue[Task].unsafe[ChannelEvent[M]](BufferCapacity.Unbounded())

  def isClosed: Boolean =
    refCount.get() == 0

  // Messages coming from the System Under Test.
  override def sendMessage(message: M): IO[Unit] =
    messagesFromSUT.offer(MessageReceived(message))

  // Messages consumed by the System Under Test.
  override def nextChannelEvent: IO[Option[Channel.ChannelEvent[M]]] =
    messagesToSUT.poll.map(Some(_))

  // Send a message from the test.
  def sendMessageToSUT(message: M): IO[Unit] =
    messagesToSUT.offer(MessageReceived(message))

  def nextMessageFromSUT(timeout: FiniteDuration = 250.millis): IO[Option[ChannelEvent[M]]] =
    messagesFromSUT.poll.map(Some(_)).timeoutTo(timeout, IO.pure(None))
}
