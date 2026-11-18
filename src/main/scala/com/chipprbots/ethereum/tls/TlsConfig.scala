package com.chipprbots.ethereum.tls

import java.nio.file.Path
import java.nio.file.Paths

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
    yield TlsProfile(name, path, keystore, alias, truststore, protocols, suites)

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
    else if tlsEnabled then
      for
        auth <- clientAuth(http, s"$key.tls")
        name <- Try(http.getString("tls.profile")).toOption
          .filter(_ => !http.getIsNull("tls.profile"))
          .toRight(TlsConfigError(s"$key.tls.profile: required when tls.enabled = true"))
        all <- profiles(config)
        profile <- all
          .get(name)
          .toRight(TlsConfigError(s"$key.tls.profile: there is no profile '$name' under $Prefix.tls.profiles"))
      yield Some(
        ResolvedListener(
          ListenerTls(ListenerId.RpcHttp, enabled = true, Some(name), auth),
          profile,
          legacy = false,
          s"$key.tls"
        )
      )
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
