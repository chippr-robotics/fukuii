package com.chipprbots.ethereum.tls

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** What a profile loads to: the managers a context is built from, and the leaf for the start-up line. */
final case class TlsMaterial(
    keyManager: Option[X509ExtendedKeyManager],
    trustManager: X509ExtendedTrustManager,
    leaf: Option[X509Certificate],
    loadedAt: Instant
)

/** Loads a profile from disk and validates it (spec 030 FR-006, FR-010; data-model.md's validation table).
  *
  * The first failure wins, as `<config path>: <cause>`. A password is held as a `char[]` and zeroed after use, and no
  * message holds it, the content of a password file, or key bytes.
  */
object TlsMaterialLoader:

  private val Allowed = Set("TLSv1.2", "TLSv1.3")
  private val Below = Set("SSLv2Hello", "SSLv2", "SSLv3", "TLSv1", "TLSv1.1")

  /** A trust manager that trusts nothing: a listener without a truststore verifies no client (FR-004). */
  private object TrustsNothing extends X509ExtendedTrustManager:
    private def refuse(): Nothing =
      throw new java.security.cert.CertificateException("this listener has no truststore, so it trusts no client")
    override def checkClientTrusted(c: Array[X509Certificate], a: String, s: java.net.Socket): Unit = refuse()
    override def checkServerTrusted(c: Array[X509Certificate], a: String, s: java.net.Socket): Unit = refuse()
    override def checkClientTrusted(c: Array[X509Certificate], a: String, e: javax.net.ssl.SSLEngine): Unit = refuse()
    override def checkServerTrusted(c: Array[X509Certificate], a: String, e: javax.net.ssl.SSLEngine): Unit = refuse()
    override def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = refuse()
    override def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = refuse()
    override def getAcceptedIssuers: Array[X509Certificate] = Array.empty

  /** @param listenerPath
    *   the listener's `tls` key, for the messages about its client-auth
    */
  def load(
      profile: TlsProfile,
      clientAuth: ClientAuth,
      listenerPath: String,
      now: Instant = Instant.now()
  ): Either[TlsConfigError, TlsMaterial] =
    val keys = Keys(profile)
    for
      _ <- checkProtocols(profile)
      keystoreRef <- profile.keystore.toRight(
        TlsConfigError(s"$listenerPath: profile ${profile.name} has no keystore, which a listener needs")
      )
      _ <- Either.cond(
        clientAuth == ClientAuth.None || profile.truststore.isDefined,
        (),
        TlsConfigError(
          s"$listenerPath: client-auth = ${clientAuth.toString.toLowerCase}, but profile ${profile.name} has no truststore"
        )
      )
      keystore <- openStore(keystoreRef, keys.keystorePath, keys.keystorePassword, keys.keystoreType)
      keyManagerAndLeaf <-
        try
          for
            selected <- selectKey(profile, keystore.store, keys.keystore)
            (alias, leaf) = selected
            _ <- checkValidity(leaf, now, profile.configPath)
            keyManager <- buildKeyManager(keystore, alias, keys.keystore)
          yield (keyManager, leaf)
        finally java.util.Arrays.fill(keystore.password, '\u0000')
      (keyManager, leaf) = keyManagerAndLeaf
      trust <- profile.truststore match
        case None => Right(TrustsNothing: X509ExtendedTrustManager)
        case Some(ref) =>
          for
            ts <- openStore(ref, keys.truststorePath, keys.truststorePassword, keys.truststoreType)
            tm <- buildTrustManager(ts, keys.truststore)
          yield tm
    yield TlsMaterial(Some(keyManager), trust, Some(leaf), now)

  /** The key names of one profile, in the form the operator wrote them. */
  final private class Keys(profile: TlsProfile):
    private val base = profile.configPath
    val keystore: String = if profile.legacy then base else s"$base.keystore"
    val truststore: String = s"$base.truststore"
    val keystorePath: String = if profile.legacy then s"$base.keystore-path" else s"$base.keystore.path"
    val keystorePassword: String = if profile.legacy then s"$base.password-file" else s"$base.keystore.password-file"
    val keystoreType: String = if profile.legacy then s"$base.keystore-type" else s"$base.keystore.type"
    val truststorePath: String = s"$base.truststore.path"
    val truststorePassword: String = s"$base.truststore.password-file"
    val truststoreType: String = s"$base.truststore.type"

  final private case class Opened(store: KeyStore, password: Array[Char])

  private def checkProtocols(profile: TlsProfile): Either[TlsConfigError, Unit] =
    val supported = SSLContext.getDefault.getSupportedSSLParameters.getProtocols.toSet
    val suites = SSLContext.getDefault.getSupportedSSLParameters.getCipherSuites.toSet
    val badProtocol = profile.protocols.collectFirst {
      case p if Below.contains(p) =>
        TlsConfigError(s"${profile.configPath}.protocols: $p is below TLSv1.2, the minimum (FR-006)")
      case p if !Allowed.contains(p) || !supported.contains(p) =>
        TlsConfigError(s"${profile.configPath}.protocols: $p is not a protocol this JDK supports (TLSv1.2, TLSv1.3)")
    }
    val badSuite = profile.cipherSuites.collectFirst {
      case c if !suites.contains(c) =>
        TlsConfigError(s"${profile.configPath}.cipher-suites: $c is not a cipher suite this JDK supports")
    }
    badProtocol.orElse(badSuite).toLeft(())

  private def readable(file: Path, key: String): Either[TlsConfigError, Unit] =
    if !Files.exists(file) then Left(TlsConfigError(s"$key: $file not found"))
    else if !Files.isRegularFile(file) then Left(TlsConfigError(s"$key: $file is not a regular file"))
    else if !Files.isReadable(file) then Left(TlsConfigError(s"$key: $file is not readable"))
    else Right(())

  /** The password file's text without its trailing newline, as chars; the byte buffer is zeroed. */
  private def readPassword(file: Path, key: String): Either[TlsConfigError, Array[Char]] =
    try
      val bytes = Files.readAllBytes(file)
      try
        var end = bytes.length
        while end > 0 && (bytes(end - 1) == '\n' || bytes(end - 1) == '\r') do end -= 1
        Right(new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8).toCharArray)
      finally java.util.Arrays.fill(bytes, 0.toByte)
    catch case NonFatal(e) => Left(TlsConfigError(s"$key: cannot read $file (${e.getClass.getSimpleName})"))

  private def openStore(
      ref: StoreRef,
      pathKey: String,
      passwordKey: String,
      typeKey: String
  ): Either[TlsConfigError, Opened] =
    val storeType = ref.storeType.toLowerCase
    for
      _ <- Either.cond(
        storeType == "pkcs12" || storeType == "jks",
        (),
        TlsConfigError(s"$typeKey: ${ref.storeType} is not a store type (pkcs12 or jks)")
      )
      _ <- readable(ref.path, pathKey)
      _ <- readable(ref.passwordFile, passwordKey)
      password <- readPassword(ref.passwordFile, passwordKey)
      store <-
        val ks = KeyStore.getInstance(storeType)
        val in: InputStream = Files.newInputStream(ref.path)
        try
          ks.load(in, password)
          Right(Opened(ks, password))
        catch
          case NonFatal(e) =>
            java.util.Arrays.fill(password, '\u0000')
            // the JDK's own text ("keystore password was incorrect") names no password; it is scrubbed regardless
            val reason = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
            Left(
              TlsConfigError(
                s"$pathKey: ${ref.path} does not open with the password in ${ref.passwordFile} (${reason.take(120)})"
              )
            )
        finally in.close()
    yield store

  private def selectKey(
      profile: TlsProfile,
      store: KeyStore,
      key: String
  ): Either[TlsConfigError, (String, X509Certificate)] =
    val keyAliases = store.aliases.asScala.filter(store.isKeyEntry).toList
    val chosen: Either[TlsConfigError, String] = profile.keyAlias match
      case Some(alias) =>
        Either.cond(
          keyAliases.contains(alias),
          alias,
          TlsConfigError(s"$key: key-alias $alias is not a private-key entry")
        )
      case None =>
        keyAliases match
          case one :: Nil => Right(one)
          case Nil        => Left(TlsConfigError(s"$key: the keystore has no private-key entry"))
          case _ =>
            Left(
              TlsConfigError(s"$key: the keystore holds ${keyAliases.size} private keys; set key-alias to choose one")
            )
    chosen.flatMap { alias =>
      store.getCertificate(alias) match
        case cert: X509Certificate => Right((alias, cert))
        case _                     => Left(TlsConfigError(s"$key: the entry $alias has no X.509 certificate"))
    }

  private def checkValidity(cert: X509Certificate, now: Instant, path: String): Either[TlsConfigError, Unit] =
    val subject = cert.getSubjectX500Principal.getName
    if now.isAfter(cert.getNotAfter.toInstant) then
      Left(TlsConfigError(s"$path: certificate $subject expired ${cert.getNotAfter.toInstant} (notAfter)"))
    else if now.isBefore(cert.getNotBefore.toInstant) then
      Left(
        TlsConfigError(s"$path: certificate $subject is not valid before ${cert.getNotBefore.toInstant} (notBefore)")
      )
    else Right(())

  /** A key manager for the one chosen entry: the keystore's other entries are never offered. */
  private def buildKeyManager(
      opened: Opened,
      alias: String,
      key: String
  ): Either[TlsConfigError, X509ExtendedKeyManager] =
    try
      val only = KeyStore.getInstance("pkcs12")
      only.load(null, null)
      val privateKey = opened.store.getKey(alias, opened.password)
      only.setKeyEntry(alias, privateKey, opened.password, opened.store.getCertificateChain(alias))
      val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      factory.init(only, opened.password)
      factory.getKeyManagers
        .collectFirst { case km: X509ExtendedKeyManager => km }
        .toRight(TlsConfigError(s"$key: the JDK built no X.509 key manager"))
    catch
      case NonFatal(e) =>
        Left(TlsConfigError(s"$key: the private key of $alias cannot be used (${e.getClass.getSimpleName})"))

  private def buildTrustManager(store: Opened, key: String): Either[TlsConfigError, X509ExtendedTrustManager] =
    try
      val anchors = store.store.aliases.asScala.count(a => store.store.isCertificateEntry(a))
      if anchors == 0 then Left(TlsConfigError(s"$key: the truststore holds no certificate"))
      else
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
        factory.init(store.store)
        val tms: Array[TrustManager] = factory.getTrustManagers
        tms
          .collectFirst { case tm: X509ExtendedTrustManager => tm }
          .toRight(TlsConfigError(s"$key: the JDK built no X.509 trust manager"))
    catch case NonFatal(e) => Left(TlsConfigError(s"$key: the truststore cannot be used (${e.getClass.getSimpleName})"))
    finally java.util.Arrays.fill(store.password, '\u0000')
