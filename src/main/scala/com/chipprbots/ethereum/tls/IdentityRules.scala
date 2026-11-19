package com.chipprbots.ethereum.tls

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.naming.ldap.LdapName
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

import scala.jdk.CollectionConverters.*
import scala.util.Try

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueType

/** What a rule matches on a verified client certificate (spec 030 FR-020). Every key given must match (AND); at least
  * one is given. Exact match only: DNS names and the fingerprint ignore case (the fingerprint also ignores colons); the
  * URI and the common name are case-sensitive.
  */
final case class CertMatch(
    sanDns: Option[String] = None,
    sanUri: Option[String] = None,
    subjectCn: Option[String] = None,
    sha256: Option[String] = None
):

  def matches(cert: X509Certificate): Boolean =
    sanDns.forall(d => CertMatch.sans(cert, CertMatch.DnsName).exists(_.equalsIgnoreCase(d))) &&
      sanUri.forall(u => CertMatch.sans(cert, CertMatch.UriName).contains(u)) &&
      subjectCn.forall(cn => CertMatch.commonNames(cert).contains(cn)) &&
      sha256.forall(_ == CertMatch.fingerprint(cert))

  def render: String =
    Seq(
      sanDns.map(v => s"san-dns=$v"),
      sanUri.map(v => s"san-uri=$v"),
      subjectCn.map(v => s"subject-cn=$v"),
      sha256.map(v => s"sha256=$v")
    ).flatten.mkString(" ")

object CertMatch:

  private val DnsName = 2
  private val UriName = 6

  private def sans(cert: X509Certificate, kind: Int): Seq[String] =
    Option(cert.getSubjectAlternativeNames).toSeq.flatMap(_.asScala).collect {
      case entry if entry.get(0) == kind && entry.get(1).isInstanceOf[String] => entry.get(1).asInstanceOf[String]
    }

  private def commonNames(cert: X509Certificate): Seq[String] =
    Try(new LdapName(cert.getSubjectX500Principal.getName)).toOption.toSeq
      .flatMap(_.getRdns.asScala)
      .filter(_.getType.equalsIgnoreCase("CN"))
      .map(_.getValue.toString)

  /** The SHA-256 of the certificate's DER encoding, as lower-case hex without separators. */
  def fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.getEncoded).map(b => f"${b & 0xff}%02x").mkString

  /** A fingerprint as the operator may write it (`AB:CD:…` or `abcd…`), normalised; `None` unless 64 hex digits. */
  def normaliseFingerprint(text: String): Option[String] =
    val hex = text.replace(":", "").trim.toLowerCase
    Option.when(hex.length == 64 && hex.forall(c => c.isDigit || (c >= 'a' && c <= 'f')))(hex)

/** One `network.rpc.client-identities` entry: the namespaces a matching certificate is granted (FR-023). `apis` holds
  * namespace names and possibly `graphql` (FR-028); `rpc` is always added when the rule is served.
  */
final case class IdentityRule(name: String, matchOn: CertMatch, apis: Seq[String]):
  def grantsGraphQl: Boolean = apis.contains(IdentityRules.GraphQl)

  /** The JSON-RPC namespaces the rule grants, without the `graphql` pseudo-namespace. */
  def rpcApis: Seq[String] = apis.filterNot(_ == IdentityRules.GraphQl)

/** Who is calling on a TLS session (data-model.md). */
enum Caller:
  /** No certificate: possible under `client-auth = want` only, or on a listener that asks for none. */
  case Anonymous

  /** A verified certificate that no rule matches. `subject` is for log lines only, never a metric label. */
  case Unmatched(subject: String)

  case Matched(rule: IdentityRule)

  /** The `identity_rule` label of `rpc_namespace_denials_total`: a rule's name, or `anonymous` / `unmatched`. */
  def label: String = this match
    case Anonymous     => IdentityRules.AnonymousLabel
    case Unmatched(_)  => IdentityRules.UnmatchedLabel
    case Matched(rule) => rule.name

/** The ordered rule list; the first rule that matches wins (FR-020). `generation` is bumped by a reload (slice 4), so a
  * caller cached on a TLS session under an older generation is resolved again.
  */
final case class IdentityRules(rules: Vector[IdentityRule], generation: Long = 0L):

  def isEmpty: Boolean = rules.isEmpty

  /** The caller of a verified leaf certificate, or of none. */
  def resolve(leaf: Option[X509Certificate]): Caller = leaf match
    case None => Caller.Anonymous
    case Some(cert) =>
      rules.find(_.matchOn.matches(cert)) match
        case Some(rule) => Caller.Matched(rule)
        case None       => Caller.Unmatched(cert.getSubjectX500Principal.getName)

  /** The caller of a TLS session, resolved once per session and generation (research D-3). The peer chain is the one
    * the handshake verified: a certificate that failed verification never reaches this.
    */
  def resolve(session: SSLSession): Caller =
    session.getValue(IdentityRules.SessionKey) match
      case IdentityRules.Cached(gen, caller) if gen == generation => caller
      case _ =>
        val caller = resolve(IdentityRules.verifiedLeaf(session))
        session.putValue(IdentityRules.SessionKey, IdentityRules.Cached(generation, caller))
        caller

object IdentityRules:

  val Empty: IdentityRules = IdentityRules(Vector.empty)

  /** The pseudo-namespace a rule lists to be served GraphQL (FR-028). */
  val GraphQl = "graphql"

  val AnonymousLabel = "anonymous"
  val UnmatchedLabel = "unmatched"

  private val Key = "network.rpc.client-identities"
  private val FileKey = "network.rpc.client-identities-file"
  private val ConfigPath = s"${TlsConfig.Prefix}.$Key"
  private val SessionKey = "fukuii.rpc.identity"
  private val NamePattern = "[a-z0-9_-]{1,32}".r
  private val MatchKeys = Set("san-dns", "san-uri", "subject-cn", "sha256")

  /** The value cached on a session: the generation it was resolved under, and the caller. */
  final private case class Cached(generation: Long, caller: Caller)

  /** The namespaces a caller is served (FR-021–FR-023): a matched rule's own, else the transport's. `rpc` is added by
    * the controller, as for every transport.
    */
  def effectiveApis(caller: Caller, transportApis: Seq[String]): Seq[String] = caller match
    case Caller.Matched(rule) => rule.rpcApis
    case _                    => transportApis

  /** Whether a caller is served GraphQL (FR-028): a matched rule only when it lists `graphql`; anonymous and unmatched
    * callers when the node serves GraphQL at all.
    */
  def graphQlAllowed(caller: Caller, graphQlEnabled: Boolean): Boolean = caller match
    case Caller.Matched(rule) => graphQlEnabled && rule.grantsGraphQl
    case _                    => graphQlEnabled

  /** The verified leaf of a session, or `None` when the client presented no certificate. */
  def verifiedLeaf(session: SSLSession): Option[X509Certificate] =
    try session.getPeerCertificates.headOption.collect { case c: X509Certificate => c }
    catch case _: SSLPeerUnverifiedException => None

  /** Whether `network.rpc.graphql.enabled` is on (default on, as `GraphQLConfig` reads it). */
  def graphQlEnabled(config: Config): Boolean =
    !config.hasPath("network.rpc.graphql.enabled") || config.getBoolean("network.rpc.graphql.enabled")

  /** Reads and validates `network.rpc.client-identities` from the `fukuii` section (contracts/config.md):
    *   - `name` required, unique, `[a-z0-9_-]{1,32}` (a metric label), and not `anonymous` or `unmatched`;
    *   - `match` with at least one of `san-dns`, `san-uri`, `subject-cn`, `sha256`, and no other key;
    *   - `apis` (a comma-separated string or a list) of known namespaces or `graphql`, and `graphql` only while
    *     `network.rpc.graphql.enabled` is on (FR-028).
    */
  def parse(config: Config, availableApis: Seq[String]): Either[TlsConfigError, IdentityRules] =
    file(config).flatMap {
      case None => parseList(config, Key, ConfigPath, availableApis, graphQlEnabled(config))
      case Some(path) =>
        for
          _ <- Either.cond(
            !config.hasPath(Key) || guard(ConfigPath)(config.getList(Key).isEmpty).getOrElse(false),
            (),
            TlsConfigError(
              s"$ConfigPath: set either client-identities or client-identities-file ($path), not both; the file is " +
                "the one that reloads"
            )
          )
          contents <- readFile(path)
          rules <- parseList(
            contents,
            "client-identities",
            s"$path: client-identities",
            availableApis,
            graphQlEnabled(config)
          )
        yield rules
    }

  /** `network.rpc.client-identities-file` (FR-033, research D-5): a HOCON file holding `client-identities = [ … ]`,
    * which the node re-reads on a change. `None` where the rules are inline (restart-only).
    */
  def file(config: Config): Either[TlsConfigError, Option[Path]] =
    if !config.hasPath(FileKey) || config.getIsNull(FileKey) then Right(None)
    else guard(s"${TlsConfig.Prefix}.$FileKey")(Some(Paths.get(config.getString(FileKey))))

  private def readFile(path: Path): Either[TlsConfigError, Config] =
    val key = s"${TlsConfig.Prefix}.$FileKey"
    if !Files.isRegularFile(path) then Left(TlsConfigError(s"$key: $path not found"))
    else if !Files.isReadable(path) then Left(TlsConfigError(s"$key: $path is not readable"))
    else
      try Right(ConfigFactory.parseFile(path.toFile).resolve())
      catch case e: ConfigException => Left(TlsConfigError(s"$key: $path does not parse (${e.getMessage})"))

  private def parseList(
      source: Config,
      key: String,
      path: String,
      availableApis: Seq[String],
      graphQlOn: Boolean
  ): Either[TlsConfigError, IdentityRules] =
    if !source.hasPath(key) then Right(Empty)
    else
      for
        entries <- guard(path)(source.getConfigList(key).asScala.toVector)
        parsed <- entries.zipWithIndex.foldLeft[Either[TlsConfigError, Vector[IdentityRule]]](Right(Vector.empty)) {
          case (acc, (entry, i)) => acc.flatMap(done => rule(entry, s"$path[$i]", availableApis, graphQlOn, done))
        }
      yield IdentityRules(parsed)

  private def rule(
      entry: Config,
      path: String,
      availableApis: Seq[String],
      graphQlOn: Boolean,
      before: Vector[IdentityRule]
  ): Either[TlsConfigError, Vector[IdentityRule]] =
    def err(cause: String) = Left(TlsConfigError(s"$path: $cause"))
    for
      name <- guard(s"$path.name")(entry.getString("name"))
      _ <-
        if !NamePattern.matches(name) then
          err(s"name '$name' must be 1-32 of a-z, 0-9, '_' and '-' (it is a metric label)")
        else if name == AnonymousLabel || name == UnmatchedLabel then err(s"name '$name' is reserved")
        else if before.exists(_.name == name) then err(s"name '$name' is used by an earlier rule")
        else Right(())
      matchOn <- certMatch(entry, s"$path.match")
      apis <- apis(entry, s"$path.apis")
      unknown = apis.filterNot(a => a == GraphQl || a == "rpc" || availableApis.contains(a))
      _ <-
        if unknown.nonEmpty then
          err(
            s"apis names unknown namespaces: ${unknown.mkString(",")} (known: ${availableApis.mkString(",")},$GraphQl)"
          )
        else Right(())
      _ <-
        if apis.contains(GraphQl) && !graphQlOn then
          err("apis lists graphql, but network.rpc.graphql.enabled = false, so no caller could be served it")
        else Right(())
    yield before :+ IdentityRule(name, matchOn, apis)

  private def certMatch(entry: Config, path: String): Either[TlsConfigError, CertMatch] =
    for
      m <- guard(path)(entry.getConfig("match"))
      keys = m.root.keySet.asScala.toSet
      _ <- Either.cond(
        (keys -- MatchKeys).isEmpty,
        (),
        TlsConfigError(
          s"$path: unknown keys ${(keys -- MatchKeys).toSeq.sorted.mkString(", ")} " +
            s"(known: ${MatchKeys.toSeq.sorted.mkString(", ")})"
        )
      )
      _ <- Either.cond(
        keys.exists(MatchKeys.contains),
        (),
        TlsConfigError(s"$path: needs at least one of ${MatchKeys.toSeq.sorted.mkString(", ")}")
      )
      dns <- opt(m, "san-dns", path)
      uri <- opt(m, "san-uri", path)
      cn <- opt(m, "subject-cn", path)
      shaText <- opt(m, "sha256", path)
      sha <- shaText match
        case None => Right(None)
        case Some(text) =>
          CertMatch
            .normaliseFingerprint(text)
            .map(Some(_))
            .toRight(TlsConfigError(s"$path.sha256: not a SHA-256 fingerprint (64 hex digits, colons allowed)"))
    yield CertMatch(dns, uri, cn, sha)

  private def opt(m: Config, key: String, path: String): Either[TlsConfigError, Option[String]] =
    if !m.hasPath(key) || m.getIsNull(key) then Right(None)
    else
      guard(s"$path.$key")(m.getString(key).trim).flatMap { v =>
        Either.cond(v.nonEmpty, Some(v), TlsConfigError(s"$path.$key: empty"))
      }

  private def apis(entry: Config, path: String): Either[TlsConfigError, Seq[String]] =
    guard(path) {
      val raw = entry.getValue("apis").valueType match
        case ConfigValueType.LIST => entry.getStringList("apis").asScala.toSeq
        case _                    => entry.getString("apis").split(",").toSeq
      raw.map(_.trim.toLowerCase).filter(_.nonEmpty).distinct
    }.flatMap(a => Either.cond(a.nonEmpty, a, TlsConfigError(s"$path: grants nothing; list at least one namespace")))

  private def guard[A](path: String)(body: => A): Either[TlsConfigError, A] =
    try Right(body)
    catch case e: ConfigException => Left(TlsConfigError(s"$path: ${e.getMessage}"))
