package com.chipprbots.ethereum.network.p2p.messages

import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPException
import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
import com.chipprbots.ethereum.rlp.RLPImplicits._
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPSerializable
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.rawDecode

sealed trait ProtocolFamily
object ProtocolFamily {
  case object ETH extends ProtocolFamily
  case object ETC extends ProtocolFamily
  implicit class ProtocolFamilyEnc(val msg: ProtocolFamily) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = msg match {
      case ETH => RLPValue("eth".getBytes())
      case ETC => RLPValue("etc".getBytes())
    }
  }
}

sealed abstract class Capability(val name: ProtocolFamily, val version: Byte)

object Capability {
  case object ETH63 extends Capability(ProtocolFamily.ETH, 63) // scalastyle:ignore magic.number
  case object ETH64 extends Capability(ProtocolFamily.ETH, 64) // scalastyle:ignore magic.number
  case object ETH65 extends Capability(ProtocolFamily.ETH, 65) // scalastyle:ignore magic.number
  case object ETH66 extends Capability(ProtocolFamily.ETH, 66) // scalastyle:ignore magic.number
  case object ETH67 extends Capability(ProtocolFamily.ETH, 67) // scalastyle:ignore magic.number
  case object ETH68 extends Capability(ProtocolFamily.ETH, 68) // scalastyle:ignore magic.number
  case object ETC64 extends Capability(ProtocolFamily.ETC, 64) // scalastyle:ignore magic.number

  def parse(s: String): Option[Capability] = s match {
    case "eth/63" => Some(ETH63)
    case "eth/64" => Some(ETH64)
    case "eth/65" => Some(ETH65)
    case "eth/66" => Some(ETH66)
    case "eth/67" => Some(ETH67)
    case "eth/68" => Some(ETH68)
    case "etc/64" => Some(ETC64)
    case _        => None // TODO: log unknown capability?
  }

  def parseUnsafe(s: String): Capability =
    parse(s).getOrElse(throw new RuntimeException(s"Capability $s not supported by Fukuii"))

  def negotiate(c1: List[Capability], c2: List[Capability]): Option[Capability] =
    c1.intersect(c2) match {
      case Nil => None
      case l   => Some(best(l))
    }

  // TODO consider how this scoring should be handled with 'snap' and other extended protocols
  def best(capabilities: List[Capability]): Capability =
    capabilities.maxBy(_.version)

  /** Determines if this capability uses RequestId wrapper in messages (ETH66+) ETH66, ETH67, ETH68 use RequestId
    * wrapper ETH63, ETH64, ETH65, ETC64 do not use RequestId wrapper
    */
  def usesRequestId(capability: Capability): Boolean = capability match {
    case ETH66 | ETH67 | ETH68 => true
    case _                     => false
  }

  implicit class CapabilityEnc(val msg: Capability) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = RLPList(msg.name.toRLPEncodable, msg.version)
  }

  implicit class CapabilityDec(val bytes: Array[Byte]) extends AnyVal {
    def toCapability: Option[Capability] = CapabilityRLPEncodableDec(rawDecode(bytes)).toCapability
  }

  implicit class CapabilityRLPEncodableDec(val rLPEncodeable: RLPEncodeable) extends AnyVal {
    def toCapability: Option[Capability] = rLPEncodeable match {
      case RLPList(RLPValue(nameBytes), RLPValue(versionBytes)) if versionBytes.nonEmpty =>
        parse(s"${new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)}/${versionBytes(0)}")
      case _ => throw new RLPException("Cannot decode Capability")
    }
  }

}
