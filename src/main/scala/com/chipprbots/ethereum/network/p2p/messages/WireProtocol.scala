package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.MessageSerializableImplicit
import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.*
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.utils.ByteUtils

object WireProtocol:

  object Hello:

    /** The longest Hello clientId kept, in bytes (B-199): longer ones are cut at decode. go-ethereum's own id is about
      * 50 bytes; 256 leaves room for long build tags.
      */
    val MaxClientIdBytes: Int = 256

    val code = 0x00

    implicit class HelloEnc(val underlyingMsg: Hello)
        extends MessageSerializableImplicit[Hello](underlyingMsg)
        with RLPSerializable:
      import com.chipprbots.ethereum.rlp.*

      override def code: Int = Hello.code

      override def toRLPEncodable: RLPEncodeable =
        import msg.*
        RLPList(
          p2pVersion,
          clientId,
          RLPList(capabilities.map(_.toRLPEncodable)*),
          listenPort,
          RLPValue(nodeId.toArray[Byte])
        )

    extension (bytes: Array[Byte])
      def toHello: Hello =
        import Capability.*
        rawDecode(bytes) match
          case RLPList(
                RLPValue(p2pVersionBytes),
                RLPValue(clientIdBytes),
                (capabilities: RLPList),
                RLPValue(listenPortBytes),
                RLPValue(nodeIdBytes),
                _*
              ) =>
            val p2pVersion = ByteUtils.bytesToBigInt(p2pVersionBytes).toLong
            // B-199: kept to MaxClientIdBytes. Every reader (the client classifier, admin_peers, the TUI, logs) takes it
            // whole, and only the 16 MiB message cap bounded it; real client ids are well under 100 bytes.
            val clientId =
              new String(clientIdBytes.take(Hello.MaxClientIdBytes), java.nio.charset.StandardCharsets.UTF_8)
            val listenPort = ByteUtils.bytesToBigInt(listenPortBytes).toLong
            val nodeId = ByteString(nodeIdBytes)
            Hello(p2pVersion, clientId, capabilities.items.map(_.toCapability).flatten, listenPort, nodeId)
          case _ => throw new RuntimeException("Cannot decode Hello")

  case class Hello(
      p2pVersion: Long,
      clientId: String,
      capabilities: Seq[Capability],
      listenPort: Long,
      nodeId: ByteString
  ) extends Message:

    override val code: Int = Hello.code

    override def toString: String =
      s"Hello { " +
        s"p2pVersion: $p2pVersion " +
        s"clientId: $clientId " +
        s"capabilities: $capabilities " +
        s"listenPort: $listenPort " +
        s"nodeId: ${Hex.toHexString(nodeId.toArray[Byte])} " +
        s"}"
    override def toShortString: String = toString

  object Disconnect:
    object Reasons:
      val DisconnectRequested = 0x00
      val TcpSubsystemError = 0x01
      val BreachOfProtocol = 0x02
      val UselessPeer = 0x03
      val TooManyPeers = 0x04
      val AlreadyConnected = 0x05
      val IncompatibleP2pProtocolVersion = 0x06
      val NullNodeIdentityReceived = 0x07
      val ClientQuitting = 0x08
      val UnexpectedIdentity = 0x09
      val IdentityTheSame = 0xa
      val TimeoutOnReceivingAMessage = 0x0b
      val Other = 0x10

    def reasonToString(reasonCode: Long): String =
      reasonCode match
        case Reasons.DisconnectRequested            => "Disconnect requested"
        case Reasons.TcpSubsystemError              => "TCP sub-system error"
        case Reasons.BreachOfProtocol               => "Breach of protocol, e.g. malformed message, bad RLP"
        case Reasons.UselessPeer                    => "Useless peer"
        case Reasons.TooManyPeers                   => "Too many peers"
        case Reasons.AlreadyConnected               => "Already connected"
        case Reasons.IncompatibleP2pProtocolVersion => "Incompatible P2P protocol version"
        case Reasons.NullNodeIdentityReceived       => "Null node identity received - this is automatically invalid"
        case Reasons.ClientQuitting                 => "Client quitting"
        case Reasons.UnexpectedIdentity             => "Unexpected identity"
        case Reasons.IdentityTheSame                => "Identity is the same as this node"
        case Reasons.TimeoutOnReceivingAMessage     => "Timeout on receiving a message"
        case Reasons.Other                          => "Some other reason specific to a subprotocol"
        case other                                  => s"unknown reason code: $other"

    val code = 0x01

    implicit class DisconnectEnc(val underlyingMsg: Disconnect)
        extends MessageSerializableImplicit[Disconnect](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Disconnect.code

      override def toRLPEncodable: RLPEncodeable = RLPList(msg.reason)

    extension (bytes: Array[Byte])
      def toDisconnect: Disconnect = rawDecode(bytes) match
        case RLPList(RLPValue(reasonBytes), _*) =>
          val reason = ByteUtils.bytesToBigInt(reasonBytes).toLong
          Disconnect(reason = reason)
        case RLPValue(reasonBytes) =>
          // Handle case where peer sends a single RLP value instead of a list (protocol deviation but common)
          val reason = ByteUtils.bytesToBigInt(reasonBytes).toLong
          Disconnect(reason = reason)
        case _ => throw new RuntimeException("Cannot decode Disconnect")

  case class Disconnect(reason: Long) extends Message:
    override val code: Int = Disconnect.code

    override def toString: String =
      s"Disconnect(${Disconnect.reasonToString(reason)})"

    override def toShortString: String = toString

  object Ping:

    val code = 0x02

    implicit class PingEnc(val underlyingMsg: Ping)
        extends MessageSerializableImplicit[Ping](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Ping.code

      override def toRLPEncodable: RLPEncodeable = RLPList()

    extension (bytes: Array[Byte]) def toPing: Ping = Ping()

  case class Ping() extends Message:
    override val code: Int = Ping.code
    override def toShortString: String = toString

  object Pong:

    val code = 0x03

    implicit class PongEnc(val underlyingMsg: Pong)
        extends MessageSerializableImplicit[Pong](underlyingMsg)
        with RLPSerializable:
      override def code: Int = Pong.code

      override def toRLPEncodable: RLPEncodeable = RLPList()

    extension (bytes: Array[Byte]) def toPong: Pong = Pong()

  case class Pong() extends Message:
    override val code: Int = Pong.code
    override def toShortString: String = toString
