package com.chipprbots.ethereum.network.p2p.messages

import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.*
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.rawDecode

sealed trait ProtocolFamily
object ProtocolFamily:
  case object ETH extends ProtocolFamily
  case object SNAP extends ProtocolFamily
  extension (msg: ProtocolFamily)
    def toRLPEncodable: RLPEncodeable = msg match
      case ETH  => RLPValue("eth".getBytes())
      case SNAP => RLPValue("snap".getBytes())

sealed abstract class Capability(val name: ProtocolFamily, val version: Byte)

object Capability:
  case object ETH63 extends Capability(ProtocolFamily.ETH, 63) // scalastyle:ignore magic.number
  case object ETH64 extends Capability(ProtocolFamily.ETH, 64) // scalastyle:ignore magic.number
  case object ETH65 extends Capability(ProtocolFamily.ETH, 65) // scalastyle:ignore magic.number
  case object ETH66 extends Capability(ProtocolFamily.ETH, 66) // scalastyle:ignore magic.number
  case object ETH67 extends Capability(ProtocolFamily.ETH, 67) // scalastyle:ignore magic.number
  case object ETH68 extends Capability(ProtocolFamily.ETH, 68) // scalastyle:ignore magic.number
  case object ETH69 extends Capability(ProtocolFamily.ETH, 69) // scalastyle:ignore magic.number
  case object ETH70 extends Capability(ProtocolFamily.ETH, 70) // scalastyle:ignore magic.number
  case object ETH71 extends Capability(ProtocolFamily.ETH, 71) // scalastyle:ignore magic.number
  case object ETH72 extends Capability(ProtocolFamily.ETH, 72) // scalastyle:ignore magic.number
  case object SNAP1 extends Capability(ProtocolFamily.SNAP, 1) // scalastyle:ignore magic.number
  case object SNAP2 extends Capability(ProtocolFamily.SNAP, 2) // scalastyle:ignore magic.number

  def parse(s: String): Option[Capability] = s match
    case "eth/63" => Some(ETH63)
    case "eth/64" => Some(ETH64)
    case "eth/65" => Some(ETH65)
    case "eth/66" => Some(ETH66)
    case "eth/67" => Some(ETH67)
    case "eth/68" => Some(ETH68)
    case "eth/69" => Some(ETH69)
    case "eth/70" => Some(ETH70)
    case "eth/71" => Some(ETH71)
    case "eth/72" => Some(ETH72)
    case "snap/1" => Some(SNAP1)
    case "snap/2" => Some(SNAP2)
    case _        => None

  def parseUnsafe(s: String): Capability =
    parse(s).getOrElse(throw new RuntimeException(s"Capability $s not supported by Fukuii"))

  /** Find the highest mutually-supported version within one protocol family, returned from `own` (never from `peer`) so
    * the caller is guaranteed to hold a decoder for it. This is devp2p's standard subprotocol negotiation rule — see
    * rlpx.md "Capability Messaging": each side advertises every version it can speak; the highest version present in
    * BOTH lists wins.
    */
  private def negotiateFamily(peer: List[Capability], own: List[Capability]): Option[Capability] =
    if peer.isEmpty || own.isEmpty then None
    else
      val peerVersions = peer.map(_.version).toSet
      val ownVersions = own.map(_.version).toSet
      val commonVersions = peerVersions.intersect(ownVersions)
      if commonVersions.isEmpty then None
      else
        val maxCommon = commonVersions.max
        own.find(_.version == maxCommon) // always from our side — we have the decoder

  /** SNAP-family negotiation alone, exposed so callers that only care about the satellite protocol (decoder selection,
    * wire-offset sizing) don't have to re-derive it from the combined `negotiate` result — `negotiate` only ever
    * returns ETH's pick when both families are present (see `best`).
    */
  def negotiateSnap(peerCapabilities: List[Capability], ownCapabilities: List[Capability]): Option[Capability] =
    negotiateFamily(
      peerCapabilities.collect { case cap @ (SNAP1 | SNAP2) => cap },
      ownCapabilities.collect { case cap @ (SNAP1 | SNAP2) =>
        cap
      }
    )

  /** ETH-family negotiation alone — same rationale as `negotiateSnap`. */
  def negotiateEth(peerCapabilities: List[Capability], ownCapabilities: List[Capability]): Option[Capability] =
    negotiateFamily(
      peerCapabilities.collect {
        case cap @ (ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68 | ETH69 | ETH70 | ETH71 | ETH72) =>
          cap
      },
      ownCapabilities.collect {
        case cap @ (ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68 | ETH69 | ETH70 | ETH71 | ETH72) =>
          cap
      }
    )

  def negotiate(c1: List[Capability], c2: List[Capability]): Option[Capability] =
    // ETH protocol versions are backward compatible
    // If we advertise ETH68 and peer advertises ETH64, we should negotiate ETH64
    // This means we need to find the highest common version for each protocol family.
    // For each protocol family, find the highest common version.
    val negotiatedCapabilities = List(negotiateEth(c1, c2), negotiateSnap(c1, c2)).flatten

    negotiatedCapabilities match
      case Nil => None
      case l   => Some(best(l))

  /** Select the best capability from a list, with protocol-family-aware scoring. Priority: ETC > ETH > SNAP (within
    * each family, higher versions preferred)
    */
  def best(capabilities: List[Capability]): Capability =
    capabilities
      .groupBy {
        case ETH63 | ETH64 | ETH65 | ETH66 | ETH67 | ETH68 | ETH69 | ETH70 | ETH71 | ETH72 => "ETH"
        case SNAP1 | SNAP2                                                                 => "SNAP"
      }
      .toList
      .sortBy {
        case ("ETH", _)  => 0 // Highest priority now that ETC is removed
        case ("SNAP", _) => 1
        case _           => 2
      }
      .headOption
      .map { case (_, caps) =>
        caps.maxBy(_.version)
      }
      .getOrElse(capabilities.head)

  /** Determines if this capability uses RequestId wrapper in messages (ETH66+, SNAP1+) ETH66, ETH67, ETH68, SNAP1 use
    * RequestId wrapper ETH63, ETH64, ETH65 do not use RequestId wrapper
    */
  def usesRequestId(capability: Capability): Boolean = capability match
    case ETH66 | ETH67 | ETH68 | ETH69 | ETH70 | ETH71 | ETH72 | SNAP1 | SNAP2 => true
    case _                                                                     => false

  /** True for ETH69, ETH70, ETH71, ETH72 — the "no total-difficulty on the wire" tier (EIP-7642). These versions share
    * the same Status shape (no TD, carries a block range instead), the same BlockRangeUpdate change notification, and
    * the same 3-tier chainWeight resolution (BlockchainReader.resolveETH69ChainWeight). Call sites throughout
    * NetworkPeerManagerActor/BlockBroadcast/SNAPSyncController historically compared `capability == Capability.ETH69`
    * directly; that stopped being correct the moment ETH70+ became reachable (they carry the exact same "no TD"
    * property ETH69 does) — use this predicate instead of hardcoding ETH69 in new code.
    */
  def isEth69Plus(capability: Capability): Boolean = capability match
    case ETH69 | ETH70 | ETH71 | ETH72 => true
    case _                             => false

  extension (msg: Capability) def toRLPEncodable: RLPEncodeable = RLPList(msg.name.toRLPEncodable, msg.version)

  extension (bytes: Array[Byte]) def toCapability: Option[Capability] = rawDecode(bytes).toCapability

  extension (rLPEncodeable: RLPEncodeable)
    def toCapability: Option[Capability] = rLPEncodeable match
      case RLPList(RLPValue(nameBytes), RLPValue(versionBytes), _*) if versionBytes.nonEmpty =>
        parse(s"${new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8)}/${versionBytes(0)}")
      case _ => None // Silently ignore unknown/malformed capability structures (EIP-8 lenience)
