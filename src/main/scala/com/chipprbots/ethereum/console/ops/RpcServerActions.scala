package com.chipprbots.ethereum.console.ops

/** `rpc stop <http | ws>` and `rpc start <http | ws>` (spec 029 FR-055, contracts/actions.md): a JSON-RPC server
  * stops listening and its connections drop, or one that was stopped here listens again. Only a transport that was
  * enabled when the node started has a server to stop or start: the dashboard enables nothing (FR-052; conduit, slice
  * 7), so a transport that was off stays off until the configuration says otherwise and the node restarts.
  */
object RpcServerActions:

  /** One transport's server, as the node hands it over.
    *
    * @param label
    *   `HTTP` or `WebSocket`
    * @param address
    *   `interface:port`
    * @param namespaces
    *   the namespaces it serves
    * @param exposed
    *   the sensitive namespaces it would serve to other hosts, by the start-up warning's own rule; empty when it is
    *   bound to loopback or serves none
    * @param extras
    *   what else rides on its port and stops with it
    * @param openConnections
    *   its open connections, where the server counts them
    * @param listening
    *   whether it is bound now
    * @param stop
    *   stops it: in-flight requests finish, then every connection is closed
    * @param start
    *   binds it again
    */
  final case class Server(
      label: String,
      address: String,
      namespaces: Seq[String],
      exposed: Seq[String],
      tls: Boolean,
      extras: Seq[String],
      openConnections: () => Option[Int],
      listening: () => Boolean,
      stop: () => Either[String, Unit],
      start: () => Either[String, Unit]
  )

  /** `configKey` is the key that enables the transport, for the sentence that says it was off at start. */
  private def missing(transport: String): String =
    val key = if transport == "ws" then "fukuii.network.rpc.ws.enabled" else "fukuii.network.rpc.http.enabled"
    s"the ${if transport == "ws" then "WebSocket" else "HTTP"} server was not enabled when the node started " +
      s"($key); the dashboard enables nothing: change the configuration and restart"

  private def refused(why: String): Preview = Preview(Vector.empty, Some(why))

  /** @param servers
    *   the servers of the transports that were enabled at start, by `http` and `ws`
    */
  def handlers(servers: Map[String, Server]): Map[String, ActionHandler] =
    def of(request: ActionRequest): Either[String, Server] =
      val transport = request.param("transport").getOrElse("")
      servers.get(transport).toRight(missing(transport))
    Map(
      Actions.RpcStop.name -> ActionHandler(
        request =>
          of(request) match
            case Left(why)                        => refused(why)
            case Right(s) if !s.listening()       => refused(s"the ${s.label} server is not listening now")
            case Right(s) =>
              val served = (s.namespaces ++ s.extras).mkString(", ")
              Preview(
                Vector(
                  s"Stops the ${s.label} server listening on ${s.address}${if s.tls then " (TLS)" else ""}.",
                  s"It serves $served.",
                  s.openConnections().fold(
                    "Its open connections are not counted by this server: in-flight requests finish, then every connection is closed within 3 s."
                  )(n =>
                    s"$n open connection${if n == 1 then " is" else "s are"} closed at once and ${if n == 1 then "its" else "their"} subscriptions are dropped; clients must reconnect and resubscribe."
                  )
                ) ++ Option.when(s.extras.nonEmpty)(
                  "Readiness and health probes on this port will fail, and anything supervising the node by that port may restart it."
                ) ++ Vector("The Engine API is on its own port and is not affected.")
              )
        ,
        (request, _) =>
          of(request).flatMap(s => s.stop().map(_ => s"the ${s.label} server on ${s.address} is stopped"))
      ),
      Actions.RpcStart.name -> ActionHandler(
        request =>
          of(request) match
            case Left(why)                 => refused(why)
            case Right(s) if s.listening() => refused(s"the ${s.label} server is already listening on ${s.address}")
            case Right(s) =>
              Preview(
                Vector(
                  s"Binds ${s.address} again${if s.tls then " (TLS)" else ""}: the ${s.label} server listens.",
                  s"It serves ${(s.namespaces ++ s.extras).mkString(", ")}."
                ) ++ Option.when(s.exposed.nonEmpty)(
                  s"WARNING: it serves ${s.exposed.mkString(", ")} to other hosts: ${s.address} is not a loopback address."
                )
              )
        ,
        (request, _) =>
          of(request).flatMap(s => s.start().map(_ => s"the ${s.label} server listens on ${s.address}"))
      )
    )
