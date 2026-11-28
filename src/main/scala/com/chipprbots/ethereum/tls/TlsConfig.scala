package com.chipprbots.ethereum.tls

import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.typesafe.config.Config
import com.typesafe.config.ConfigException

/** A listener whose TLS is on, with the profile it names (or the implicit profile of the legacy block). */
final case class ResolvedListener(listener: ListenerTls, profile: TlsProfile, legacy: Boolean, configPath: String)

/** Reads the TLS blocks of a node's configuration (spec 030 FR-001, FR-002, FR-005, FR-007).
  *
  * Every function takes the configuration it is given, the `fukuii` section of a node's own configuration, and nothing
  * else: never `ConfigFactory.load()` (B-148). Messages name the key as the operator wrote it, under `fukuii.`.
  */
object TlsConfig:

  val Prefix = "fukuii"

  /** The name the JDK-default trust profile takes; reserved, outbound only (data-model.md). */
  val SystemProfile = "system"

  val LegacyProfileName = "legacy-rpc-http"

  private val HttpKey = "network.rpc.http"
  private val WsKey = "network.rpc.ws"
  private val EngineKey = "network.engine-api"
  private val MetricsKey = "metrics"

  /** `fukuii.tls.profiles`, every profile parsed (not yet loaded from disk). */
  def profiles(config: Config): Either[TlsConfigError, Map[String, TlsProfile]] =
    if !config.hasPath("tls.profiles") then Right(Map.empty)
    else
      val section = config.getConfig("tls.profiles")
      section.root.keySet.asScala.toList.sorted.foldLeft[Either[TlsConfigError, Map[String, TlsProfile]]](
        Right(Map.empty)
      ) { (acc, name) =>
        for
          done <- acc
          _ <- Either.cond(
            name != SystemProfile,
            (),
            TlsConfigError(s"$Prefix.tls.profiles.$name: 'system' is reserved for the JDK default trust")
          )
          profile <- profile(section, name)
        yield done + (name -> profile)
      }

  private def profile(section: Config, name: String): Either[TlsConfigError, TlsProfile] =
    val path = s"$Prefix.tls.profiles.$name"
    for
      body <- guard(path)(section.getConfig(quote(name)))
      keystore <- store(body, "keystore", s"$path.keystore")
      truststore <- store(body, "truststore", s"$path.truststore")
      alias <- guard(path)(optString(body, "key-alias"))
      protocols <- guard(path)(stringList(body, "protocols"))
      suites <- guard(path)(stringList(body, "cipher-suites"))
      reload <- reloadPolicy(body, s"$path.reload")
      warning <- duration(body, "expiry-warning", s"$path.expiry-warning", TlsProfile.DefaultExpiryWarning)
    yield TlsProfile(
      name,
      path,
      keystore,
      alias,
      truststore,
      protocols,
      suites,
      reload = reload,
      expiryWarning = warning
    )

  /** `reload { enabled, interval }` (FR-030): on and every minute by default; an interval of at least one second. */
  private def reloadPolicy(body: Config, path: String): Either[TlsConfigError, ReloadPolicy] =
    for
      enabled <- guard(s"$path.enabled")(
        if body.hasPath("reload.enabled") then body.getBoolean("reload.enabled") else ReloadPolicy.Default.enabled
      )
      interval <- duration(body, "reload.interval", s"$path.interval", ReloadPolicy.Default.interval)
      _ <- Either.cond(interval >= 1.second, (), TlsConfigError(s"$path.interval: $interval is below one second"))
    yield ReloadPolicy(enabled, interval)

  private def duration(
      body: Config,
      key: String,
      path: String,
      default: FiniteDuration
  ): Either[TlsConfigError, FiniteDuration] =
    if !body.hasPath(key) || body.getIsNull(key) then Right(default)
    else
      guard(path)(body.getDuration(key).toMillis.millis).flatMap(d =>
        Either.cond(d > Duration.Zero, d, TlsConfigError(s"$path: must be positive"))
      )

  private def store(body: Config, key: String, path: String): Either[TlsConfigError, Option[StoreRef]] =
    if !body.hasPath(key) || body.getIsNull(key) then Right(None)
    else
      guard(path) {
        val s = body.getConfig(key)
        Some(StoreRef(Paths.get(s.getString("path")), s.getString("type"), Paths.get(s.getString("password-file"))))
      }

  /** The HTTP listener's TLS, from the `tls` block or from the legacy `mode = "https"` and `certificate` (FR-007).
    * `Right(None)` is a plaintext listener.
    */
  def rpcHttp(config: Config): Either[TlsConfigError, Option[ResolvedListener]] =
    val http = config.getConfig(HttpKey)
    val key = s"$Prefix.$HttpKey"
    val legacyMode = Try(http.getString("mode")).toOption.contains("https")
    val tlsEnabled = Try(http.getBoolean("tls.enabled")).getOrElse(false)
    val legacyBlock = http.hasPath("certificate") && !http.getIsNull("certificate")
    if tlsEnabled && (legacyMode || legacyBlock) then
      Left(
        TlsConfigError(
          s"$key: tls.enabled = true cannot be combined with the deprecated mode = \"https\" and certificate; " +
            "keep tls { profile } and fukuii.tls.profiles, and remove mode and certificate"
        )
      )
    else if tlsEnabled then tlsBlock(config, http, key, ListenerId.RpcHttp).map(Some(_))
    else if legacyMode then
      for
        _ <- Either.cond(legacyBlock, (), TlsConfigError(s"$key.certificate is not set"))
        cert <- guard(s"$key.certificate")(http.getConfig("certificate"))
        ref <- guard(s"$key.certificate")(
          StoreRef(
            Paths.get(cert.getString("keystore-path")),
            cert.getString("keystore-type"),
            Paths.get(cert.getString("password-file"))
          )
        )
      yield Some(
        ResolvedListener(
          ListenerTls(ListenerId.RpcHttp, enabled = true, Some(LegacyProfileName), ClientAuth.None),
          TlsProfile(LegacyProfileName, s"$key.certificate", Some(ref), None, None, Nil, Nil, legacy = true),
          legacy = true,
          s"$key.certificate"
        )
      )
    else
      // a client-auth key on a listener that is not TLS is read, so a misspelling is still refused
      clientAuth(http, s"$key.tls").map(_ => None)

  /** The WebSocket listener's TLS, from its `tls` block (spec 030 FR-014); it has no deprecated form. `Right(None)` is
    * a plaintext listener.
    */
  def rpcWs(config: Config): Either[TlsConfigError, Option[ResolvedListener]] =
    val ws = config.getConfig(WsKey)
    val key = s"$Prefix.$WsKey"
    val tlsEnabled = Try(ws.getBoolean("tls.enabled")).getOrElse(false)
    if tlsEnabled then tlsBlock(config, ws, key, ListenerId.RpcWs).map(Some(_))
    else clientAuth(ws, s"$key.tls").map(_ => None)

  /** The Engine API's TLS (spec 030 FR-015): server TLS only. JWT authenticates the consensus client, so the listener
    * never asks for a client certificate, and a `client-auth` key under its `tls` block is refused.
    */
  def engineApi(config: Config): Either[TlsConfigError, Option[ResolvedListener]] =
    val key = s"$Prefix.$EngineKey"
    if !config.hasPath(EngineKey) then Right(None)
    else
      val engine = config.getConfig(EngineKey)
      if engine.hasPath("tls.client-auth") then
        Left(
          TlsConfigError(
            s"$key.tls.client-auth is not supported: the Engine API takes server TLS only; JWT authenticates the " +
              "consensus client"
          )
        )
      else if Try(engine.getBoolean("tls.enabled")).getOrElse(false) then
        tlsBlock(config, engine, key, ListenerId.EngineApi).map(Some(_))
      else Right(None)

  /** The metrics endpoint's TLS (FR-016), with all three client-auth modes; `Right(None)` is a plaintext endpoint. */
  def metrics(config: Config): Either[TlsConfigError, Option[ResolvedListener]] =
    val key = s"$Prefix.$MetricsKey"
    if !config.hasPath(MetricsKey) then Right(None)
    else
      val metrics = config.getConfig(MetricsKey)
      if Try(metrics.getBoolean("tls.enabled")).getOrElse(false) then
        tlsBlock(config, metrics, key, ListenerId.Metrics).map(Some(_))
      else clientAuth(metrics, s"$key.tls").map(_ => None)

  /** A listener's `tls { enabled = true, profile, client-auth }`, with the profile it names. */
  private def tlsBlock(
      config: Config,
      listener: Config,
      key: String,
      id: ListenerId
  ): Either[TlsConfigError, ResolvedListener] =
    for
      auth <- clientAuth(listener, s"$key.tls")
      name <- Try(listener.getString("tls.profile")).toOption
        .filter(_ => !listener.getIsNull("tls.profile"))
        .toRight(TlsConfigError(s"$key.tls.profile: required when tls.enabled = true"))
      all <- profiles(config)
      profile <- all
        .get(name)
        .toRight(TlsConfigError(s"$key.tls.profile: there is no profile '$name' under $Prefix.tls.profiles"))
    yield ResolvedListener(ListenerTls(id, enabled = true, Some(name), auth), profile, legacy = false, s"$key.tls")

  private def clientAuth(http: Config, path: String): Either[TlsConfigError, ClientAuth] =
    if !http.hasPath("tls.client-auth") then Right(ClientAuth.None)
    else
      guard(path)(http.getString("tls.client-auth")).flatMap(v =>
        ClientAuth.parse(v).left.map(m => TlsConfigError(s"$path.client-auth: $m"))
      )

  /** The deprecation text of FR-007, logged once by the start-up check. */
  val LegacyDeprecation: String =
    "fukuii.network.rpc.http.mode = \"https\" and .certificate are deprecated; use " +
      "fukuii.network.rpc.http.tls { enabled, profile, client-auth } and fukuii.tls.profiles.<name> " +
      "(docs/runbooks/tls-operations.md). They will be removed in 0.13.0."

  private def quote(name: String): String = "\"" + name.replace("\"", "\\\"") + "\""

  private def optString(c: Config, key: String): Option[String] =
    if c.hasPath(key) && !c.getIsNull(key) then Some(c.getString(key)) else None

  private def stringList(c: Config, key: String): Seq[String] =
    if c.hasPath(key) && !c.getIsNull(key) then c.getStringList(key).asScala.toList else Nil

  private def guard[A](path: String)(body: => A): Either[TlsConfigError, A] =
    try Right(body)
    catch case e: ConfigException => Left(TlsConfigError(s"$path: ${e.getMessage}"))

  /** For the loader: a path as the operator wrote it. */
  def display(path: Path): String = path.toString
