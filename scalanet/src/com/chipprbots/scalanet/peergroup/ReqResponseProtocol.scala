package com.chipprbots.scalanet.peergroup

import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID

import cats.effect.Fiber
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.std.Semaphore
import cats.implicits._

import scala.concurrent.duration._

import com.chipprbots.scalanet.crypto.CryptoUtils
import com.chipprbots.scalanet.peergroup.Channel.ChannelEvent
import com.chipprbots.scalanet.peergroup.Channel.MessageReceived
import com.chipprbots.scalanet.peergroup.ReqResponseProtocol._
import com.chipprbots.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup
import com.chipprbots.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.FramingConfig
import com.chipprbots.scalanet.peergroup.dynamictls.DynamicTLSPeerGroup.PeerInfo
import com.chipprbots.scalanet.peergroup.dynamictls.Secp256k1
import com.chipprbots.scalanet.peergroup.implicits._
import com.chipprbots.scalanet.peergroup.udp.DynamicUDPPeerGroup
import scodec.Codec

/**
  * Simple higher level protocol on top of generic peer group. User is shielded from differnt implementation details like:
  * channels, observables etc.
  *
  * For now used only in testing as:
  *   - it lacks any error handling
  *   - it is not entairly thread safe
  *   - can only handle simple server handler
  *   - there is no resource cleaning
  *
  * @param group transport peer group
  * @param state currently open client channels
  * @tparam A used addressing scheme
  * @tparam M the message type.
  */
class ReqResponseProtocol[A, M](
    group: PeerGroup[A, MessageEnvelope[M]],
    channelSemaphore: Semaphore[IO],
    channelMapRef: Ref[IO, ReqResponseProtocol.ChannelMap[A, M]],
    fiberMapRef: Ref[IO, Map[ChannelId, Fiber[IO, Throwable, Unit]]]
)(a: Addressable[A]) {

  private def getChan(
      to: A,
      channelId: ChannelId
  ): IO[ReqResponseChannel[A, M]] = {
    channelMapRef.get.map(_.get(channelId)).flatMap {
      case Some(channel) =>
        IO.pure(channel)

      case None =>
        channelSemaphore.permit.use { _ =>
          channelMapRef.get.map(_.get(channelId)).flatMap {
            case Some(channel) =>
              IO.pure(channel)

            case None =>
              group.client(to).allocated.flatMap {
                case (underlying, release) =>
                  val cleanup = release >> channelMapRef.update(_ - channelId)
                  // Keep in mind that stream is back pressured for all subscribers so in case of many parallel requests to one client
                  // waiting for response on first request can influence result of second request
                  ReqResponseChannel(underlying, cleanup).flatMap { channel =>
                    channelMapRef.update(_.updated(channelId, channel)).as(channel)
                  }
              }
          }
        }
    }
  }

  // It do not close the client channel after each message as in case of tcp it would be really costly
  // to create new tcp connection for each message.
  // it probably should return IO[Either[E, M]]
  def send(m: M, to: A, requestDuration: FiniteDuration = 5.seconds): IO[M] = {
    val channelId = (a.getAddress(processAddress), a.getAddress(to))
    for {
      ch <- getChan(to, channelId)
      randomUuid = UUID.randomUUID()
      mes = MessageEnvelope(randomUuid, m)
      resp <- sendMandAwaitForResponse(ch, mes, requestDuration)
    } yield resp
  }

  private def sendMandAwaitForResponse(
      c: ReqResponseChannel[A, M],
      messageToSend: MessageEnvelope[M],
      timeOutDuration: FiniteDuration
  ): IO[M] =
    for {
      // Subscribe first so we don't miss the response.
      subscription <- c.subscribeForResponse(messageToSend.id, timeOutDuration).start
      _ <- c.sendMessage(messageToSend).timeout(timeOutDuration)
      envelope <- subscription.join.flatMap {
        case cats.effect.Outcome.Succeeded(fa) => fa
        case cats.effect.Outcome.Errored(e) => IO.raiseError(e)
        case cats.effect.Outcome.Canceled() => IO.raiseError(new ReqResponseProtocol.RequestCanceledException(messageToSend.id))
      }
    } yield envelope.m

  /** Start handling requests in the background. */
  def startHandling(requestHandler: M => M): IO[Unit] = {
    group.nextServerEvent.toStream.collectChannelCreated
      .evalMap {
        case (channel, release) =>
          val channelId = (a.getAddress(processAddress), a.getAddress(channel.to))
          channel.nextChannelEvent.toStream
            .collect {
              case MessageReceived(msg) => msg
            }
            .evalMap { msg =>
              channel.sendMessage(MessageEnvelope(msg.id, requestHandler(msg.m)))
            }
            .compile
            .drain
            .guarantee {
              // Release the channel and remove the background process from the map.
              release >> fiberMapRef.update(_ - channelId)
            }
            .start // Start running it in a background fiber.
            .flatMap { fiber =>
              // Remember we're running this so we can cancel when released.
              fiberMapRef.update(_.updated(channelId, fiber))
            }
      }
      .compile
      .drain
  }

  /** Stop background fibers. */
  private def cancelHandling(): IO[Unit] =
    fiberMapRef.get.flatMap { fiberMap =>
      fiberMap.values.toList.traverse(_.cancel.attempt)
    }.void >> fiberMapRef.set(Map.empty)

  /** Release all open channels */
  private def closeChannels(): IO[Unit] =
    channelMapRef.get.flatMap { channelMap =>
      channelMap.values.toList.traverse {
        _.release.attempt
      }.void
    }

  def processAddress: A = group.processAddress
}

object ReqResponseProtocol {
  type ChannelId = (InetSocketAddress, InetSocketAddress)
  
  /** Exception thrown when a request fiber is canceled before receiving a response. */
  class RequestCanceledException(requestId: UUID, cause: Throwable = null) 
    extends RuntimeException(s"Request fiber for message $requestId was canceled", cause)
  
  class ReqResponseChannel[A, M](
      channel: Channel[A, MessageEnvelope[M]],
      topic: fs2.concurrent.Topic[IO, ChannelEvent[MessageEnvelope[M]]],
      @annotation.unused producerFiber: cats.effect.Fiber[IO, Throwable, Unit],
      val release: Release
  ) {

    def sendMessage(message: MessageEnvelope[M]): IO[Unit] =
      channel.sendMessage(message)

    def subscribeForResponse(
        responseId: UUID,
        timeOutDuration: FiniteDuration
    ): IO[MessageEnvelope[M]] = {
      topic.subscribe(100)
        .collect {
          case MessageReceived(response) if response.id == responseId => response
        }
        .head
        .compile
        .lastOrError
        .timeout(timeOutDuration)
        .adaptError {
          case _: java.util.concurrent.TimeoutException =>
            new RuntimeException(s"Didn't receive a response for request $responseId")
        }
    }
  }
  object ReqResponseChannel {
    // Sending a request subscribes to the common channel with a single underlying message queue,
    // expecting to see the response with the specific ID. To avoid message stealing, broadcast
    // messages to a Topic, so every consumer gets every message.

    def apply[A, M](channel: Channel[A, MessageEnvelope[M]], release: IO[Unit]): IO[ReqResponseChannel[A, M]] =
      for {
        topic <- fs2.concurrent.Topic[IO, ChannelEvent[MessageEnvelope[M]]]
        producer <- channel.nextChannelEvent.toStream
          .evalMap(event => topic.publish1(event).as(event))
          .compile
          .drain
          .start
      } yield new ReqResponseChannel(channel, topic, producer, producer.cancel >> release)
  }

  type ChannelMap[A, M] = Map[ChannelId, ReqResponseChannel[A, M]]

  final case class MessageEnvelope[M](id: UUID, m: M)
  object MessageEnvelope {

    /** scodec scpecific codec for a single message. */
    def defaultCodec[M: Codec]: Codec[MessageEnvelope[M]] = {
      // scodec 2.x: Use Codec.derived for automatic derivation
      Codec.derived[MessageEnvelope[M]]
    }
  }

  private def buildProtocol[A, M](
      group: PeerGroup[A, MessageEnvelope[M]]
  )(a: Addressable[A]): Resource[IO, ReqResponseProtocol[A, M]] = {
    Resource
      .make(
        for {
          channelSemaphore <- Semaphore[IO](1)
          channelMapRef <- Ref.of[IO, ChannelMap[A, M]](Map.empty)
          fiberMapRef <- Ref.of[IO, Map[ChannelId, Fiber[IO, Throwable, Unit]]](Map.empty)
          protocol = new ReqResponseProtocol[A, M](group, channelSemaphore, channelMapRef, fiberMapRef)(a)
        } yield protocol
      ) { protocol =>
        protocol.cancelHandling() >>
          protocol.closeChannels()
      }
  }

  def getTlsReqResponseProtocolClient[M](framingConfig: FramingConfig)(
      address: InetSocketAddress
  )(implicit c: Codec[M]): Resource[IO, ReqResponseProtocol[PeerInfo, M]] = {
    import DynamicTLSPeerGroup.PeerInfo.peerInfoAddressable
    implicit lazy val envelopeCodec: Codec[MessageEnvelope[M]] = MessageEnvelope.defaultCodec[M]
    val rnd = new SecureRandom()
    val hostkeyPair = CryptoUtils.genEcKeyPair(rnd, Secp256k1.curveName)
    for {
      config <- Resource.eval(
        IO.fromTry(
          DynamicTLSPeerGroup
            .Config(
              address,
              Secp256k1,
              hostkeyPair,
              rnd,
              useNativeTlsImplementation = false,
              framingConfig,
              maxIncomingMessageQueueSize = 100,
              None,
              None
            )
        )
      )
      pg <- DynamicTLSPeerGroup[MessageEnvelope[M]](config)
      prot <- buildProtocol(pg)(peerInfoAddressable)
    } yield prot
  }

  def getDynamicUdpReqResponseProtocolClient[M](
      address: InetSocketAddress
  )(implicit c: Codec[M]): Resource[IO, ReqResponseProtocol[InetMultiAddress, M]] = {
    import InetMultiAddress.addressableInetMultiAddressInst
    implicit val codec: Codec[MessageEnvelope[M]] = MessageEnvelope.defaultCodec[M]
    for {
      pg <- DynamicUDPPeerGroup[MessageEnvelope[M]](DynamicUDPPeerGroup.Config(address))
      prot <- buildProtocol(pg)(addressableInetMultiAddressInst)
    } yield prot
  }

}
