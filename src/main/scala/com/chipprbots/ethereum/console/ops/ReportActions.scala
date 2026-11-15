package com.chipprbots.ethereum.console.ops

import java.net.InetAddress

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Try

/** The Observe actions that read the node and report (spec 029 FR-054): `tunables`, `net`, and the one Disrupt that
  * only reports, `detect-ip`. Each takes what it reads as functions, so that what it says is what the node says now.
  */
object ReportActions:

  // ---- tunables ---------------------------------------------------------------------------------------------------

  /** What changes without a restart, as it is now.
    *
    * @param rows
    *   each setting, the value it has now, and the action that changes it; read when asked
    */
  def tunables(rows: () => Seq[(String, String, String)]): ActionHandler =
    ActionHandler(
      _ => Preview(Vector("Shows the settings that change without a restart.")),
      (_, _) =>
        val read = rows()
        val nameCols = read.map(_._1.length).maxOption.getOrElse(0)
        val valueCols = read.map(_._2.length).maxOption.getOrElse(0).min(60)
        Right(
          (Seq("These change while the node runs, and return to their configured values when it restarts.") ++
            read.map((name, value, by) => s"${name.padTo(nameCols, ' ')}  ${value.padTo(valueCols, ' ')}  change with: $by"))
            .mkString("\n")
        )
    )

  // ---- net --------------------------------------------------------------------------------------------------------

  /** The node's addresses, as it knows them.
    *
    * @param listening
    *   the address and port it listens on, as configured, and whether it is listening
    * @param advertised
    *   the address it tells peers, once it has one
    * @param detected
    *   how the external address was last found (`UPnP`, `STUN`, `HTTPS`, `a local interface`), the address, and how
    *   long ago; None when it was configured, or not detected yet
    * @param detection
    *   `automatic`, `configured` or `off`
    * @param inbound
    *   the peers that dialled this node: the evidence that it can be reached
    */
  final case class NetInfo(
      listening: String,
      isListening: Boolean,
      advertised: Option[String],
      detected: Option[String],
      detection: String,
      inbound: Int,
      connected: Int
  )

  def net(read: () => NetInfo): ActionHandler =
    ActionHandler(
      _ => Preview(Vector("Shows the node's listening and external addresses.")),
      (_, _) =>
        val n = read()
        Right(
          Seq(
            s"Listening   ${n.listening}${if n.isListening then "" else "  (NOT listening)"}",
            s"Advertised  ${n.advertised.getOrElse("none yet: peers are told no address")}",
            s"Detection   ${n.detection}${n.detected.fold("")(how => s": $how")}",
            "UPnP        port mapping is not reported by this node (it is in the log at start)",
            s"Inbound     ${n.inbound} of ${n.connected} connected peers dialled this node" +
              (if n.inbound > 0 then ": it can be reached from outside"
               else if n.connected > 0 then ": none has reached it from outside yet"
               else ""),
            "Whether it can be reached is known only from peers that reach it: detect-ip asks outside services what address they see."
          ).mkString("\n")
        )
    )

  // ---- detect-ip --------------------------------------------------------------------------------------------------

  /** The outside services the detection may contact, by name: they see this node's address. */
  val StunHosts: Seq[String] = Seq("stun.l.google.com", "stun1.l.google.com", "stun.cloudflare.com")
  val HttpsHosts: Seq[String] = Seq("icanhazip.com", "checkip.amazonaws.com", "api4.ipify.org", "4.ident.me")

  /** How long the detection may take before the action gives up on it. */
  val DetectTimeout: FiniteDuration = 30.seconds

  /** `detect-ip`: asks the gateway and then outside services what this node's public address is, and reports it. It
    * changes nothing: what the node advertises changes only when its own detection runs.
    *
    * @param detect
    *   the node's own detection, the whole cascade; it blocks
    * @param mode
    *   whether the node's own detection is `automatic`, `configured` or `off`, for the sentence that says when what
    *   it advertises changes
    */
  def detectIp(detect: () => Option[InetAddress], mode: () => String, advertised: () => Option[String])(using
      ExecutionContext
  ): ActionHandler =
    ActionHandler(
      _ =>
        Preview(
          Vector(
            "Asks the gateway on this network (UPnP) for this node's public address.",
            s"If it has none: ${StunHosts.mkString(", ")} (STUN), then ${HttpsHosts.mkString(", ")} (HTTPS).",
            "Each service contacted sees this node's address.",
            s"It takes up to ${DetectTimeout.toSeconds} s, reports what it finds and changes nothing.",
            s"What the node advertises (${advertised().getOrElse("nothing yet")}) changes only when the node's own " +
              s"detection runs: that is ${mode()}."
          )
        ),
      (_, progress) =>
        progress("asking the gateway, then outside services")
        Try(Await.result(Future(detect()), DetectTimeout)).toEither.left
          .map {
            case _: java.util.concurrent.TimeoutException => s"no answer within ${DetectTimeout.toSeconds} s"
            case e                                        => Option(e.getMessage).getOrElse(e.toString)
          }
          .flatMap {
            case Some(address) =>
              Right(
                s"the address seen from outside is ${address.getHostAddress}; the node advertises " +
                  advertised().getOrElse("nothing yet")
              )
            case None => Left("no service gave an address")
          }
    )
