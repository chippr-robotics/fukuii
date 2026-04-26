package com.chipprbots.scalanet.discovery.ethereum.v4.mocks

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.{IO, Resource}
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
        _ <- IO(channel.refCount.incrementAndGet())
      } yield channel
    ) { channel =>
      IO(channel.refCount.decrementAndGet()).void
    }
  }

  def getOrCreateChannel(to: A): IO[MockChannel[A, M]] =
    IO(channels.getOrElseUpdate(to, new MockChannel[A, M](processAddress, to)))

  def createServerChannel(from: A): IO[MockChannel[A, M]] =
    for {
      channel <- IO(new MockChannel[A, M](processAddress, from))
      _ <- IO(channel.refCount.incrementAndGet())
      event = ChannelCreated(channel, IO(channel.refCount.decrementAndGet()).void)
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
) extends Channel[A, M] {

  // In lieu of actually closing the channel,
  // just count how many times it was opened and released.
  val refCount = new AtomicInteger(0)

  // Synchronously-constructible blocking queues — the netty-thread pattern that
  // monix's `ConcurrentQueue.unsafe(Unbounded)` provided in the original Scala 2
  // version. Wrapping in IO at the access point keeps the test ergonomics clean.
  private val messagesFromSUT = new LinkedBlockingQueue[ChannelEvent[M]]()
  private val messagesToSUT = new LinkedBlockingQueue[ChannelEvent[M]]()

  def isClosed: Boolean =
    refCount.get() == 0

  // Messages coming from the System Under Test.
  override def sendMessage(message: M): IO[Unit] =
    IO(messagesFromSUT.put(MessageReceived(message)))

  // Messages consumed by the System Under Test. Blocks until an event arrives.
  override def nextChannelEvent: IO[Option[Channel.ChannelEvent[M]]] =
    IO.blocking(Some(messagesToSUT.take()))

  // Send a message from the test.
  def sendMessageToSUT(message: M): IO[Unit] =
    IO(messagesToSUT.put(MessageReceived(message)))

  def nextMessageFromSUT(timeout: FiniteDuration = 250.millis): IO[Option[ChannelEvent[M]]] =
    IO.blocking(Option(messagesFromSUT.poll(timeout.toMillis, TimeUnit.MILLISECONDS)))
}
