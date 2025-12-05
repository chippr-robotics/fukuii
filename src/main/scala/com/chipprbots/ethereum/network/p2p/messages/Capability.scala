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
  case object SNAP extends ProtocolFamily
  implicit class ProtocolFamilyEnc(val msg: ProtocolFamily) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = msg match {
      case ETH => RLPValue("eth".getBytes())
      case ETC => RLPValue("etc".getBytes())
      case SNAP => RLPValue("snap".getBytes())
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
  case object SNAP1 extends Capability(ProtocolFamily.SNAP, 1) // scalastyle:ignore magic.number

  def parse(s: String): Option[Capability] = s match {
    case "eth/63" => Some(ETH63)
    case "eth/64" => Some(ETH64)
    case "eth/65" => Some(ETH65)
    case "eth/66" => Some(ETH66)
    case "eth/67" => Some(ETH67)
    case "eth/68" => Some(ETH68)
    case "etc/64" => Some(ETC64)
    case "snap/1" => Some(SNAP1)
    case _        => None // TODO: log unknown capability?
  }

  def parseUnsafe(s: String): Capability =
    parse(s).getOrElse(throw new RuntimeException(s"Capability $s not supported by Fukuii"))

  def negotiate(c1: List[Capability], c2: List[Capability]): Option[Capability] = {
    // ETH protocol versions are backward compatible
    // If we advertise ETH68 and peer advertises ETH64, we should negotiate ETH64
    // This means we need to find the highest common version for each protocol family
    
    val ethVersions1 = c1.collect {
      case cap @ (ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68) => cap
    }
    val ethVersions2 = c2.collect {
      case cap @ (ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68) => cap
    }
    
    val etcVersions1 = c1.collect { case cap @ ETC64 => cap }
    val etcVersions2 = c2.collect { case cap @ ETC64 => cap }
    
    val snapVersions1 = c1.collect { case cap @ SNAP1 => cap }
    val snapVersions2 = c2.collect { case cap @ SNAP1 => cap }
    
    // For each protocol family, find the highest common version
    val negotiatedCapabilities = List(
      // ETH: if both support ETH, use the minimum of their maximum versions
      if (ethVersions1.nonEmpty && ethVersions2.nonEmpty) {
        val maxVersion = math.min(
          ethVersions1.maxBy(_.version).version,
          ethVersions2.maxBy(_.version).version
        )
        // Find the capability with that version number
        ethVersions1.find(_.version == maxVersion)
      } else None,
      // ETC: exact match required
      if (etcVersions1.intersect(etcVersions2).nonEmpty) Some(ETC64) else None,
      // SNAP: exact match required
      if (snapVersions1.intersect(snapVersions2).nonEmpty) Some(SNAP1) else None
    ).flatten
    
    negotiatedCapabilities match {
      case Nil => None
      case l   => Some(best(l))
    }
  }

  /** Select the best capability from a list, with protocol-family-aware scoring.
    * Priority: ETC > ETH > SNAP (within each family, higher versions preferred)
    */
  def best(capabilities: List[Capability]): Capability = {
    capabilities.groupBy {
      case ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68 => "ETH"
      case ETC64 => "ETC"
      case SNAP1 => "SNAP"
    }.toList.sortBy {
      case ("ETC", _) => 0  // Highest priority
      case ("ETH", _) => 1  // Medium priority
      case ("SNAP", _) => 2 // Lowest priority
      case _ => 3
    }.headOption.map {
      case (_, caps) => caps.maxBy(_.version)
    }.getOrElse(capabilities.head)
  }

  /** Determines if this capability uses RequestId wrapper in messages (ETH66+, SNAP1+)
    * ETH66, ETH67, ETH68, SNAP1 use RequestId wrapper
    * ETH63, ETH64, ETH65, ETC64 do not use RequestId wrapper
    */
  def usesRequestId(capability: Capability): Boolean = capability match {
    case ETH66 | ETH67 | ETH68 | SNAP1 => true
    case _                             => false
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
