package com.chipprbots.scalanet.kademlia.codec

import java.util.UUID

import com.chipprbots.scalanet.kademlia.KMessage
import com.chipprbots.scalanet.kademlia.KMessage.KRequest.FindNodes
import com.chipprbots.scalanet.kademlia.KMessage.KRequest.Ping
import com.chipprbots.scalanet.kademlia.KMessage.KResponse.Nodes
import com.chipprbots.scalanet.kademlia.KMessage.KResponse.Pong
import com.chipprbots.scalanet.kademlia.KRouter.NodeRecord
import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs.bits
import scodec.codecs.discriminated
import scodec.codecs.uint4
import scodec.codecs.uuid

/** Encodings for scodec. */
object DefaultCodecs extends DefaultCodecDerivations {
  implicit def kMessageCodec[A: Codec]: Codec[KMessage[A]] =
    deriveKMessageCodec[A]
}

trait DefaultCodecDerivations {
  implicit def nodeRecordCodec[A: Codec]: Codec[NodeRecord[A]] = {
    (bits :: Codec[A] :: Codec[A]).as[NodeRecord[A]]
  }

  implicit def findNodesCodec[A: Codec]: Codec[FindNodes[A]] = {
    (uuid :: Codec[NodeRecord[A]] :: bits).as[FindNodes[A]]
  }

  implicit def pingCodec[A: Codec]: Codec[Ping[A]] = {
    (uuid :: Codec[NodeRecord[A]]).as[Ping[A]]
  }

  implicit def nodesCodec[A: Codec]: Codec[Nodes[A]] = {
    import com.chipprbots.scalanet.codec.DefaultCodecs.seqCoded
    (uuid :: Codec[NodeRecord[A]] :: Codec[Seq[NodeRecord[A]]]).as[Nodes[A]]
  }

  implicit def pongCodec[A: Codec]: Codec[Pong[A]] = {
    (uuid :: Codec[NodeRecord[A]]).as[Pong[A]]
  }

  protected def deriveKMessageCodec[A: Codec]: Codec[KMessage[A]] = {
    discriminated[KMessage[A]].by(uint4)
      .subcaseP(0) { case f: FindNodes[A] => f }(findNodesCodec[A])
      .subcaseP(1) { case p: Ping[A] => p }(pingCodec[A])
      .subcaseP(2) { case n: Nodes[A] => n }(nodesCodec[A])
      .subcaseP(3) { case p: Pong[A] => p }(pongCodec[A])
  }
}
