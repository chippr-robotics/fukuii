package com.chipprbots.ethereum.console.ops

import java.time.Duration
import java.util.Locale

import com.chipprbots.ethereum.console.TuiPeer
import com.chipprbots.ethereum.console.TuiState
import com.chipprbots.ethereum.network.BlockedIPRegistry
import com.chipprbots.ethereum.console.TuiText.formatShort

/** The peer actions (spec 029 FR-054, contracts/actions.md): the limit on outgoing peers, trusting and untrusting,
  * adding, dropping, and blocking and unblocking an address. Each is carried out by the admin service's own function
  * (FR-052); each preview says what that function does and no more (herald, slice 7): the limit is on outgoing
  * connections only, trusting dials, untrusting does not disconnect, a dropped peer may come back, and a block does not
  * reach discovery's UDP.
  */
object PeerActions:

  /** What the peer manager says of its limits and of whom it trusts and keeps. */
  final case class Limits(
      outgoing: Int,
      incoming: Int,
      minOutgoing: Int,
      trusted: Set[String],
      maintained: Set[String]
  )

  /** What the actions need of the node: each function is the admin service's.
    *
    * @param limits
    *   the peer manager's limits and sets now; a Left is why they could not be read
    * @param blocked
    *   the addresses blocked now
    * @param drop
    *   disconnects the peer with this id and stops re-dialling it; told its enode where it has one
    * @param block
    *   blocks an address and disconnects the peers at it; the address comes back as the node wrote it down
    */
  final case class Node(
      limits: () => Either[String, Limits],
      blocked: () => Set[String],
      setMaxPeers: Int => Either[String, Unit],
      trust: String => Either[String, Unit],
      untrust: String => Either[String, Unit],
      add: String => Either[String, Unit],
      drop: (String, Option[String]) => Either[String, Unit],
      block: String => Either[String, String],
      unblock: String => Either[String, Unit]
  )

  private def lower(id: String): String = id.toLowerCase(Locale.ROOT).stripPrefix("0x")

  /** `ab12cd34…` for a 128-digit node id: enough to recognise it by. */
  def brief(id: String): String = if id.length > 12 then lower(id).take(8) + "…" else lower(id)

  /** The connected peer with this id, as the dashboard last read the peers. */
  private def connected(state: TuiState, id: String): Option[TuiPeer] =
    state.snapshot.flatMap(_.peers.value).getOrElse(Nil).find(p => lower(p.id) == lower(id))

  /** `host` and `port` of a peer's `host:port`, an IPv6 host in brackets or not. */
  private[ops] def hostPort(address: String): Option[(String, Int)] =
    val at = address.lastIndexOf(':')
    if at <= 0 then None
    else
      val host = address.take(at).stripPrefix("/").stripPrefix("[").stripSuffix("]")
      address.drop(at + 1).toIntOption.filter(p => p >= 1 && p <= 65535).filter(_ => host.nonEmpty).map(host -> _)

  /** A peer's enode, from what the node knows of it: its id and the address it was dialled at. */
  private[ops] def enode(peer: TuiPeer): Option[String] =
    hostPort(peer.address).filter(_ => lower(peer.id).length == 128).map { (host, port) =>
      s"enode://${lower(peer.id)}@${if host.contains(':') then s"[$host]" else host}:$port"
    }

  /** A connected peer in a sentence: its id, client, address, direction, age and score. */
  private def described(state: TuiState, peer: TuiPeer): String =
    val client = state.snapshot.flatMap(_.details).flatMap(_.value).flatMap(_.get(peer.id)).map(_.clientId)
    val age = formatShort(Duration.between(peer.connectedAt, state.now).getSeconds.max(0L))
    val score = state.peerScore(peer.id).fold("not scored yet")(s => String.format(Locale.ROOT, "score %.2f", s))
    s"${brief(peer.id)}${client.fold("")(c => s", $c")}, ${peer.address}, " +
      s"${if peer.incoming then "it dialled us" else "we dialled it"}, connected $age, $score"

  private def refused(why: String): Preview = Preview(Vector.empty, Some(why))

  /** An address as the node writes it down, or None for what is no address written out. The block list holds
    * addresses in this form ([[BlockedIPRegistry.canonical]]); no name is ever resolved.
    */
  def canonical(address: String): Option[String] = BlockedIPRegistry.canonical(address)

  private def NotAnAddress(typed: String): String = s"'$typed' is not an IP address written out"

  val InboundRefusal: String =
    "it dialled us, so its listening port is not known: use peer add enode://<id>@<host>:<port> with the port it listens on"

  def handlers(node: Node, state: () => TuiState): Map[String, ActionHandler] = Map(
    Actions.MaxPeers.name -> ActionHandler(
      request =>
        (request.param("n").flatMap(_.toIntOption), node.limits()) match
          case (None, _)      => refused("usage: maxpeers <n>")
          case (_, Left(why)) => refused(s"the peer manager's limits could not be read: $why")
          case (Some(n), Right(now)) if n == now.outgoing =>
            refused(s"the limit on outgoing connections is already $n")
          case (Some(n), Right(now)) =>
            val s = state()
            Preview(
              Vector(
                s"Sets the limit on outgoing connections from ${now.outgoing} to $n.",
                s"Incoming stays at ${now.incoming}, and no connection is closed: ${s.peerCount} peers are connected.",
                s"New dials start only while fewer than ${now.minOutgoing} outgoing peers have handshaked.",
                "Resets when the node restarts."
              ) ++ Option.when(n < now.minOutgoing)(
                s"$n is under the ${now.minOutgoing} the node tries to keep: it will hold fewer outgoing peers than it looks for."
              )
            )
      ,
      (request, _) =>
        request.param("n").flatMap(_.toIntOption).toRight("usage: maxpeers <n>").flatMap { n =>
          node.setMaxPeers(n).map(_ => s"the limit on outgoing connections is $n")
        }
    ),
    Actions.PeerTrust.name -> ActionHandler(
      request =>
        val id = request.param("id").getOrElse("")
        (connected(state(), id), node.limits()) match
          case (None, _)      => refused(s"no connected peer has the id ${brief(id)} any more")
          case (_, Left(why)) => refused(s"the trusted peers could not be read: $why")
          case (Some(peer), Right(now)) if now.trusted.map(lower).contains(lower(peer.id)) =>
            refused(s"${brief(peer.id)} is already trusted")
          case (Some(peer), _) if peer.incoming => refused(InboundRefusal)
          case (Some(peer), _) if enode(peer).isEmpty =>
            refused(s"an enode cannot be made of ${peer.address}: use peer add with its enode")
          case (Some(peer), _) =>
            Preview(
              Vector(
                s"Trusts ${described(state(), peer)}.",
                "Trusted now: no. After: yes.",
                "A trusted peer is exempt from the outgoing limit, from the pruning of incoming peers and from eviction; " +
                  "it is not exempt from the incoming cap.",
                "The node dials it now. Resets when the node restarts."
              )
            )
      ,
      (request, _) =>
        val id = request.param("id").getOrElse("")
        connected(state(), id) match
          case None                        => Left(s"no connected peer has the id ${brief(id)} any more")
          case Some(peer) if peer.incoming => Left(InboundRefusal)
          case Some(peer) =>
            enode(peer)
              .toRight(s"an enode cannot be made of ${peer.address}")
              .flatMap(node.trust)
              .map(_ => s"${brief(peer.id)} is trusted")
    ),
    Actions.PeerUntrust.name -> ActionHandler(
      request =>
        val id = lower(request.param("id").getOrElse(""))
        node.limits() match
          case Left(why) => refused(s"the trusted peers could not be read: $why")
          case Right(now) if !now.trusted.map(lower).contains(id) => refused(s"${brief(id)} is not trusted")
          case Right(_) =>
            Preview(
              Vector(
                s"Removes trust from ${connected(state(), id).fold(brief(id) + ", not connected now")(described(state(), _))}.",
                "Trusted now: yes. After: no.",
                "The connection stays open; the peer may later be pruned or evicted like any other."
              )
            )
      ,
      (request, _) =>
        val id = lower(request.param("id").getOrElse(""))
        node.untrust(id).map(_ => s"${brief(id)} is no longer trusted")
    ),
    Actions.PeerAdd.name -> ActionHandler(
      request =>
        val uri = request.param("enode").getOrElse("")
        val id = lower(uri.stripPrefix("enode://").takeWhile(_ != '@'))
        (connected(state(), id), node.limits()) match
          case (_, Left(why)) => refused(s"the peer manager could not be read: $why")
          case (Some(peer), Right(now)) if now.maintained.map(lower).contains(id) =>
            refused(s"${brief(id)} is already connected (${peer.address}) and already kept")
          case (found, Right(now)) =>
            val s = state()
            Preview(
              Vector(
                s"Adds ${brief(id)} at ${uri.drop(uri.indexOf('@') + 1)} as a peer the node keeps.",
                found.fold("It is not connected now: the node dials it.")(p =>
                  s"It is connected now, at ${p.address}."
                ),
                "It is dialled again 5 s after every disconnect, and is exempt from the outgoing limit.",
                s"${s.peerCount} peers are connected; the limit on outgoing connections is ${now.outgoing}.",
                "Held until the node restarts."
              )
            )
      ,
      (request, _) =>
        val uri = request.param("enode").getOrElse("")
        node.add(uri).map(_ => s"the node dials ${brief(uri.stripPrefix("enode://").takeWhile(_ != '@'))} and keeps it")
    ),
    Actions.PeerDrop.name -> ActionHandler(
      request =>
        val id = request.param("id").getOrElse("")
        connected(state(), id) match
          case None => refused(s"no connected peer has the id ${brief(id)} any more")
          case Some(peer) =>
            Preview(
              Vector(
                s"Disconnects ${described(state(), peer)}.",
                node.limits() match
                  case Right(now) if now.maintained.map(lower).contains(lower(peer.id)) =>
                    "It is a peer the node keeps: the node stops re-dialling it."
                  case Right(_) => "It is not a peer the node keeps: nothing else changes."
                  case Left(why) =>
                    s"Whether the node keeps it could not be read ($why): if it does, the node stops re-dialling it."
                ,
                "It may reconnect if discovery finds it again or it dials us; peer block keeps an address out."
              )
            )
      ,
      (request, _) =>
        val id = request.param("id").getOrElse("")
        connected(state(), id) match
          case None       => Left(s"no connected peer has the id ${brief(id)} any more")
          case Some(peer) => node.drop(peer.id, enode(peer)).map(_ => s"${brief(peer.id)} is disconnected")
    ),
    Actions.PeerBlock.name -> ActionHandler(
      request =>
        val typed = request.param("address").getOrElse("")
        canonical(typed) match
          case None                                            => refused(NotAnAddress(typed))
          case Some(address) if node.blocked().contains(address) => refused(s"$address is already blocked")
          case Some(address) =>
            val s = state()
            // The node writes an address one way and an operator may type it another: both are read before they
            // are compared.
            val there = s.snapshot
              .flatMap(_.peers.value)
              .getOrElse(Nil)
              .filter(p => hostPort(p.address).flatMap(h => canonical(h._1)).contains(address))
            Preview(
            Vector(
              s"Blocks $address until this node restarts: connections from it are refused, and the node will not dial it.",
              if there.isEmpty then "No connected peer is at that address now."
              else
                s"${there.size} connected peer${if there.size == 1 then "" else "s"} at it " +
                  s"(${there.map(p => brief(p.id)).mkString(", ")}) ${if there.size == 1 then "is" else "are"} disconnected now."
              ,
              "Discovery (UDP) packets to and from it are not blocked."
            )
          )
      ,
      (request, _) =>
        node
          .block(request.param("address").getOrElse(""))
          .map(written => s"$written is blocked until the node restarts")
    ),
    Actions.PeerUnblock.name -> ActionHandler(
      request =>
        val typed = request.param("address").getOrElse("")
        canonical(typed) match
          case None => refused(NotAnAddress(typed))
          case Some(address) if !node.blocked().contains(address) =>
            refused(
              s"$address is not blocked" +
                (if node.blocked().isEmpty then "; no address is"
                 else s"; blocked now: ${node.blocked().toSeq.sorted.take(5).mkString(", ")}")
            )
          case Some(address) =>
            Preview(
              Vector(
                s"Removes the block on $address, and any automatic blacklisting of it.",
                "It can connect and be dialled again; the peers the node keeps at it are dialled now."
              )
            )
      ,
      (request, _) =>
        val address = request.param("address").getOrElse("")
        node.unblock(address).map(_ => s"$address is no longer blocked")
    )
  )
