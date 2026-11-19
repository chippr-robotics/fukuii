package com.chipprbots.ethereum.tls

import java.time.ZoneOffset

import com.typesafe.config.Config

/** The pass that runs before any port binds (spec 030 FR-010, FR-011): every enabled listener's TLS is read, its
  * material loaded and validated, and the first failure is the node's refusal to start.
  */
object TlsStartupCheck:

  val RefusalPrefix = "Refusing to start: "

  /** One enabled TLS listener, for the start-up line (FR-060). */
  final case class Line(
      listener: ListenerId,
      scheme: String,
      interface: String,
      port: Int,
      clientAuth: ClientAuth,
      profile: String,
      subject: String,
      notAfter: String,
      legacy: Boolean,
      rules: Seq[IdentityRule] = Nil
  ):
    def render: String =
      s"TLS listener ${listener.label} $scheme://$interface:$port client-auth=${clientAuth.toString.toLowerCase} " +
        s"profile=$profile cert=\"$subject\" notAfter=$notAfter" + (if rules.nonEmpty then s" rules=${rules.size}"
                                                                    else "")

    /** The listener's line, then one per identity rule with the namespaces it grants (FR-060, FR-027). */
    def renderAll: Seq[String] =
      render +: rules.map(r =>
        s"TLS listener ${listener.label} rule ${r.name}: ${(r.apis :+ "rpc").distinct.mkString(",")}"
      )

  /** The namespaces a rule may name when the caller gives none: those of `ApisBuilder.available`. */
  val KnownApis: Seq[String] =
    Seq(
      "eth",
      "miner",
      "web3",
      "net",
      "personal",
      "fukuii",
      "mcp",
      "debug",
      "test",
      "testing",
      "qa",
      "admin",
      "txpool",
      "trace",
      "subscribe"
    )

  /** @param config
    *   the `fukuii` section of the node's configuration
    * @return
    *   one line per enabled TLS listener, or the refusal message (it starts with [[RefusalPrefix]])
    */
  def run(config: Config, availableApis: Seq[String] = KnownApis): Either[String, Seq[Line]] =
    val httpEnabled = config.getBoolean("network.rpc.http.enabled")
    val wsEnabled = config.hasPath("network.rpc.ws.enabled") && config.getBoolean("network.rpc.ws.enabled")
    val result = for
      // every named profile must parse, used or not
      _ <- TlsConfig.profiles(config)
      // no plaintext listener on a non-loopback interface without the opt-out (FR-018)
      _ <- BindPolicy.check(config).left.map(m => TlsConfigError(m.stripPrefix(RefusalPrefix)))
      // a disabled listener is not validated: it never binds
      http <- if httpEnabled then TlsConfig.rpcHttp(config) else Right(None)
      ws <- if wsEnabled then TlsConfig.rpcWs(config) else Right(None)
      _ <-
        if httpEnabled && wsEnabled then samePort(config, http, ws)
        else Right(())
      rules <- IdentityRules.parse(config, availableApis)
      _ <- rulesCanMatch(rules, http.toList ++ ws.toList)
      engine <- if enabled(config, "network.engine-api.enabled") then TlsConfig.engineApi(config) else Right(None)
      metrics <- if enabled(config, "metrics.enabled") then TlsConfig.metrics(config) else Right(None)
      httpLine <- line(config, "network.rpc.http", http, rules)
      wsLine <- line(config, "network.rpc.ws", ws, rules)
      engineLine <- line(config, "network.engine-api", engine, rules)
      metricsLine <- line(config, "metrics", metrics, rules)
    yield httpLine.toList ++ wsLine.toList ++ engineLine.toList ++ metricsLine.toList
    result.left.map(e => RefusalPrefix + e.message)

  private def enabled(config: Config, key: String): Boolean = config.hasPath(key) && config.getBoolean(key)

  /** Whether a listener resolves client identities: one that asks for client certificates (`want` or `need`). */
  def resolvesIdentities(listener: ResolvedListener): Boolean =
    listener.listener.clientAuth != ClientAuth.None &&
      (listener.listener.id == ListenerId.RpcHttp || listener.listener.id == ListenerId.RpcWs)

  /** A non-empty rule list needs an enabled listener that asks for certificates, or no rule could ever match
    * (contracts/config.md).
    */
  private def rulesCanMatch(rules: IdentityRules, listeners: Seq[ResolvedListener]): Either[TlsConfigError, Unit] =
    Either.cond(
      rules.isEmpty || listeners.exists(resolvesIdentities),
      (),
      TlsConfigError(
        s"${TlsConfig.Prefix}.network.rpc.client-identities: ${rules.rules.size} rule(s), but no enabled HTTP or WS " +
          "listener has tls.client-auth = want or need, so no rule could ever match"
      )
    )

  private def line(
      config: Config,
      key: String,
      resolved: Option[ResolvedListener],
      rules: IdentityRules
  ): Either[TlsConfigError, Option[Line]] =
    resolved match
      case None => Right(None)
      case Some(listener) =>
        TlsMaterialLoader.load(listener.profile, listener.listener.clientAuth, listener.configPath).map { material =>
          Some(
            Line(
              listener.listener.id,
              if listener.listener.id == ListenerId.RpcWs then "wss" else "https",
              if config.hasPath(s"$key.interface") then config.getString(s"$key.interface") else "localhost",
              config.getInt(s"$key.port"),
              listener.listener.clientAuth,
              listener.profile.name,
              material.leaf.map(_.getSubjectX500Principal.getName).getOrElse(""),
              material.leaf.map(_.getNotAfter.toInstant.atZone(ZoneOffset.UTC).toLocalDate.toString).getOrElse(""),
              listener.legacy,
              if resolvesIdentities(listener) then rules.rules else Nil
            )
          )
        }

  /** HTTP and WS on one port take one TLS setting (spec 030 Edge Cases): differing settings there are refused. */
  private def samePort(
      config: Config,
      http: Option[ResolvedListener],
      ws: Option[ResolvedListener]
  ): Either[TlsConfigError, Unit] =
    def setting(l: Option[ResolvedListener]) = l.map(r => (r.profile.name, r.listener.clientAuth))
    def render(l: Option[ResolvedListener]) =
      l.map(r => s"tls on, profile ${r.profile.name}, client-auth ${r.listener.clientAuth.toString.toLowerCase}")
        .getOrElse("tls off")
    val port = config.getInt("network.rpc.http.port")
    Either.cond(
      // port 0 is an ephemeral port each, never a shared one
      port == 0 || port != config.getInt("network.rpc.ws.port") || setting(http) == setting(ws),
      (),
      TlsConfigError(
        s"${TlsConfig.Prefix}.network.rpc.ws.tls: the WebSocket listener shares port $port with the HTTP listener, " +
          s"so it must take the same TLS setting (HTTP: ${render(http)}; WS: ${render(ws)})"
      )
    )

  /** Whether the HTTP listener is on the deprecated form (the start-up check logs FR-007's warning once). */
  def usesLegacy(config: Config): Boolean = TlsConfig.rpcHttp(config).toOption.flatten.exists(_.legacy)
