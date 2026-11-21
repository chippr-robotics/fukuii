package com.chipprbots.ethereum.network

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

import scala.jdk.CollectionConverters.*

import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect

/** Counts of the per-peer routine events of one reporting window: connections made, connections closed (by reason),
  * peers rejected for fork id, addresses blacklisted.
  *
  * A busy public node sees hundreds of these a minute, so each one is logged at DEBUG at its source and recorded here;
  * `NetworkPeerManagerActor` drains the counts once a minute into its `Network [60s]` line. Lock-free (`LongAdder`),
  * because the sources are the RLPx handlers, the peer actors and the blacklist, on different threads.
  */
final class PeerChurnStats:
  import PeerChurnStats.*

  private val connected = new LongAdder
  private val forkIdRejected = new LongAdder
  private val blacklisted = new LongAdder
  private val closed = new ConcurrentHashMap[String, LongAdder]

  def recordConnected(): Unit = connected.increment()

  def recordForkIdRejected(): Unit = forkIdRejected.increment()

  def recordBlacklisted(): Unit = blacklisted.increment()

  /** A connection closed for `reason`, a stable key from [[reasonKey]] or [[TcpClosed]]. */
  def recordClosed(reason: String): Unit = closed.computeIfAbsent(reason, _ => new LongAdder).increment()

  def recordClosedByDisconnect(reasonCode: Long): Unit = recordClosed(reasonKey(reasonCode))

  /** The counts since the last drain, without restarting them. */
  def current: Snapshot =
    Snapshot(
      connected.sum,
      closed.asScala.toMap.flatMap { case (reason, adder) =>
        val n = adder.sum
        if n > 0 then Some(reason -> n) else None
      },
      forkIdRejected.sum,
      blacklisted.sum
    )

  /** The counts since the last drain; the counters restart from zero. */
  def drain(): Snapshot =
    val byReason = closed.asScala.toMap.flatMap { case (reason, adder) =>
      val n = adder.sumThenReset()
      if n > 0 then Some(reason -> n) else None
    }
    Snapshot(connected.sumThenReset(), byReason, forkIdRejected.sumThenReset(), blacklisted.sumThenReset())

object PeerChurnStats:

  /** The process-wide instance the network layer writes to, as `RLPxConnectionHandler`'s failure counters are. */
  val global: PeerChurnStats = new PeerChurnStats

  /** A handshaked peer whose TCP connection closed with no Disconnect frame. */
  val TcpClosed = "tcp-closed"

  /** Rejections in one window, with no peer connected, past which the summary is a WARN. */
  val AllRejectingThreshold = 10

  /** Stable kebab-case key for a devp2p Disconnect reason code; bounded cardinality. */
  def reasonKey(reasonCode: Long): String =
    import Disconnect.Reasons.*
    reasonCode match
      case DisconnectRequested            => "requested"
      case TcpSubsystemError              => "tcp-error"
      case BreachOfProtocol               => "breach-of-protocol"
      case UselessPeer                    => "useless-peer"
      case TooManyPeers                   => "too-many-peers"
      case AlreadyConnected               => "already-connected"
      case IncompatibleP2pProtocolVersion => "incompatible-p2p"
      case NullNodeIdentityReceived       => "null-identity"
      case ClientQuitting                 => "client-quitting"
      case UnexpectedIdentity             => "unexpected-identity"
      case IdentityTheSame                => "identity-same"
      case TimeoutOnReceivingAMessage     => "timeout"
      case Other                          => "subprotocol"
      case _                              => "unknown"

  final case class Snapshot(
      connected: Long,
      closedByReason: Map[String, Long],
      forkIdRejected: Long,
      blacklisted: Long
  ):
    def closed: Long = closedByReason.values.sum

    /** Every handshake this window failed on fork id or was refused as useless, and none succeeded. */
    def allRejecting(active: Int): Boolean =
      active == 0 && connected == 0 &&
        forkIdRejected + closedByReason.getOrElse(
          reasonKey(Disconnect.Reasons.UselessPeer),
          0L
        ) >= AllRejectingThreshold

    /** `connected=N closed=N[reason=n,...] forkid-rejected=N blacklisted=N`, reasons by count, then name. */
    def describe: String =
      val reasons =
        if closedByReason.isEmpty then ""
        else
          closedByReason.toSeq
            .sortBy { case (reason, n) => (-n, reason) }
            .map { case (reason, n) => s"$reason=$n" }
            .mkString("[", ",", "]")
      s"connected=$connected closed=$closed$reasons forkid-rejected=$forkIdRejected blacklisted=$blacklisted"
