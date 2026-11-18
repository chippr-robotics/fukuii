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
      legacy: Boolean
  ):
    def render: String =
      s"TLS listener ${listener.label} $scheme://$interface:$port client-auth=${clientAuth.toString.toLowerCase} " +
        s"profile=$profile cert=\"$subject\" notAfter=$notAfter"

  /** Slice 1 serves `client-auth = none` only; `want` and `need` are refused rather than served as `none`. */
  def supported(resolved: ResolvedListener): Either[TlsConfigError, Unit] =
    Either.cond(
      resolved.listener.clientAuth == ClientAuth.None,
      (),
      TlsConfigError(
        s"${resolved.configPath}.client-auth: ${resolved.listener.clientAuth.toString.toLowerCase} is not served " +
          "yet (this build serves client-auth = none)"
      )
    )

  /** @param config
    *   the `fukuii` section of the node's configuration
    * @return
    *   one line per enabled TLS listener, or the refusal message (it starts with [[RefusalPrefix]])
    */
  def run(config: Config): Either[String, Seq[Line]] =
    val httpEnabled = config.getBoolean("network.rpc.http.enabled")
    val result = for
      // every named profile must parse, used or not
      _ <- TlsConfig.profiles(config)
      // a disabled listener is not validated: it never binds
      http <- if httpEnabled then TlsConfig.rpcHttp(config) else Right(None)
      lines <- http match
        case None => Right(Nil)
        case Some(resolved) =>
          for
            material <- TlsMaterialLoader.load(resolved.profile, resolved.listener.clientAuth, resolved.configPath)
            _ <- supported(resolved)
          yield List(
            Line(
              resolved.listener.id,
              "https",
              config.getString("network.rpc.http.interface"),
              config.getInt("network.rpc.http.port"),
              resolved.listener.clientAuth,
              resolved.profile.name,
              material.leaf.map(_.getSubjectX500Principal.getName).getOrElse(""),
              material.leaf.map(_.getNotAfter.toInstant.atZone(ZoneOffset.UTC).toLocalDate.toString).getOrElse(""),
              resolved.legacy
            )
          )
    yield lines
    result.left.map(e => RefusalPrefix + e.message)

  /** Whether the HTTP listener is on the deprecated form (the start-up check logs FR-007's warning once). */
  def usesLegacy(config: Config): Boolean = TlsConfig.rpcHttp(config).toOption.flatten.exists(_.legacy)
