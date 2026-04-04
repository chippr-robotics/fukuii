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
  case object SNAP extends ProtocolFamily
  implicit class ProtocolFamilyEnc(val msg: ProtocolFamily) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = msg match {
      case ETH  => RLPValue("eth".getBytes())
      case SNAP => RLPValue("snap".getBytes())
    }
  }
}

sealed abstract class Capability(val name: ProtocolFamily, val version: Byte)

object Capability {
  // ETH68 is the only live ETH protocol on ETC mainnet.
  // core-geth: ETH68 only. Besu: ETH68 + ETH69. go-ethereum: ETH69 only (dropped ETH68 Mar 2026).
  // ETH63/64/65/66/67 are dead — removed from all reference clients, no ETC peer negotiates them.
  case object ETH68 extends Capability(ProtocolFamily.ETH, 68) // scalastyle:ignore magic.number
  case object SNAP1 extends Capability(ProtocolFamily.SNAP, 1) // scalastyle:ignore magic.number

  def parse(s: String): Option[Capability] = s match {
    case "eth/68" => Some(ETH68)
    case "snap/1" => Some(SNAP1)
    case _        => None // Silently ignore unknown/deprecated protocol versions from peer Hello
  }

  def parseUnsafe(s: String): Capability =
    parse(s).getOrElse(throw new RuntimeException(s"Capability $s not supported by Fukuii"))

  def negotiate(c1: List[Capability], c2: List[Capability]): Option[Capability] = {
    val ethVersions1 = c1.collect { case cap @ ETH68 => cap }
    val ethVersions2 = c2.collect { case cap @ ETH68 => cap }

    val snapVersions1 = c1.collect { case cap @ SNAP1 => cap }
    val snapVersions2 = c2.collect { case cap @ SNAP1 => cap }

    val negotiatedCapabilities = List(
      if (ethVersions1.nonEmpty && ethVersions2.nonEmpty) Some(ETH68) else None,
      if (snapVersions1.intersect(snapVersions2).nonEmpty) Some(SNAP1) else None
    ).flatten

    negotiatedCapabilities match {
      case Nil => None
      case l   => Some(best(l))
    }
  }

  /** Select the best capability from a list. ETH takes priority over SNAP. */
  def best(capabilities: List[Capability]): Capability =
    capabilities
      .groupBy {
        case ETH68 => "ETH"
        case SNAP1 => "SNAP"
      }
      .toList
      .sortBy {
        case ("ETH", _)  => 0
        case ("SNAP", _) => 1
        case _           => 2
      }
      .headOption
      .map { case (_, caps) =>
        caps.maxBy(_.version)
      }
      .getOrElse(capabilities.head)

  /** ETH68 and SNAP1 both use RequestId wrapper in messages. */
  def usesRequestId(capability: Capability): Boolean = true

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
