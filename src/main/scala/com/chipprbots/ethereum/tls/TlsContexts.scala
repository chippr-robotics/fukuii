package com.chipprbots.ethereum.tls

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

import scala.jdk.CollectionConverters.*

/** A key manager that delegates to the profile's current one, so a reload swaps the key without a new `SSLContext`
  * (spec 030 research D-1). It offers nothing while the profile has no keystore.
  */
final class ReloadingKeyManager(initial: Option[X509ExtendedKeyManager]) extends X509ExtendedKeyManager:
  private val current = new AtomicReference[Option[X509ExtendedKeyManager]](initial)

  def set(next: Option[X509ExtendedKeyManager]): Unit = current.set(next)

  private def km = current.get

  override def getClientAliases(keyType: String, issuers: Array[Principal]): Array[String] =
    km.map(_.getClientAliases(keyType, issuers)).orNull
  override def chooseClientAlias(keyType: Array[String], issuers: Array[Principal], socket: Socket): String =
    km.map(_.chooseClientAlias(keyType, issuers, socket)).orNull
  override def getServerAliases(keyType: String, issuers: Array[Principal]): Array[String] =
    km.map(_.getServerAliases(keyType, issuers)).orNull
  override def chooseServerAlias(keyType: String, issuers: Array[Principal], socket: Socket): String =
    km.map(_.chooseServerAlias(keyType, issuers, socket)).orNull
  override def getCertificateChain(alias: String): Array[X509Certificate] =
    km.map(_.getCertificateChain(alias)).orNull
  override def getPrivateKey(alias: String): PrivateKey = km.map(_.getPrivateKey(alias)).orNull
  override def chooseEngineClientAlias(keyType: Array[String], issuers: Array[Principal], engine: SSLEngine): String =
    km.map(_.chooseEngineClientAlias(keyType, issuers, engine)).orNull
  override def chooseEngineServerAlias(keyType: String, issuers: Array[Principal], engine: SSLEngine): String =
    km.map(_.chooseEngineServerAlias(keyType, issuers, engine)).orNull

/** A trust manager that delegates to the profile's current one (research D-1). */
final class ReloadingTrustManager(initial: X509ExtendedTrustManager) extends X509ExtendedTrustManager:
  private val current = new AtomicReference[X509ExtendedTrustManager](initial)

  def set(next: X509ExtendedTrustManager): Unit = current.set(next)

  override def checkClientTrusted(chain: Array[X509Certificate], authType: String, socket: Socket): Unit =
    current.get.checkClientTrusted(chain, authType, socket)
  override def checkClientTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit =
    current.get.checkClientTrusted(chain, authType, engine)
  override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
    current.get.checkClientTrusted(chain, authType)
  override def checkServerTrusted(chain: Array[X509Certificate], authType: String, socket: Socket): Unit =
    current.get.checkServerTrusted(chain, authType, socket)
  override def checkServerTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit =
    current.get.checkServerTrusted(chain, authType, engine)
  override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
    current.get.checkServerTrusted(chain, authType)
  override def getAcceptedIssuers: Array[X509Certificate] = current.get.getAcceptedIssuers

/** A listener's server context, with what an engine made from it must be set to (spec 030 FR-003, FR-006), and the
  * material it serves now. A reload swaps the managers and the context new engines come from (FR-030); open connections
  * keep the engines and keys they negotiated, and new handshakes take the new material.
  *
  * @param listenerPath
  *   the listener's `tls` key, for the messages a reload logs
  */
final class TlsServerContext(
    newContext: () => SSLContext,
    val clientAuth: ClientAuth,
    val protocols: Seq[String],
    val cipherSuites: Seq[String],
    val profile: TlsProfile,
    val listenerPath: String,
    keys: ReloadingKeyManager,
    trust: ReloadingTrustManager,
    initial: TlsMaterial
):
  private val current = new AtomicReference[TlsMaterial](initial)
  private val currentContext = new AtomicReference[SSLContext](newContext())

  /** The material new handshakes use. */
  def material: TlsMaterial = current.get

  /** The context new engines are made from. Its managers are the reloading ones, so a consumer that keeps a context
    * still follows a reload; Pekko's engine factory takes the current one for each connection.
    */
  def context: SSLContext = currentContext.get

  /** Loads the profile's files again and, only if they validate, serves them (FR-030); otherwise the material in
    * service stays, and the error is returned (FR-031).
    */
  def reload(now: Instant = Instant.now()): Either[TlsConfigError, TlsMaterial] =
    TlsMaterialLoader.load(profile, clientAuth, listenerPath, now).map { next =>
      swap(next)
      next
    }

  /** Serves `next` for new handshakes, which cannot resume a session from before it (US4 scenario 3): a resumed
    * handshake skips the trust check, so a client whose CA was just removed would get through. The cached sessions are
    * invalidated, and the context is replaced, because TLS 1.3 resumes from stateless session tickets that no cache
    * holds; a new context has new ticket keys, so an old ticket no longer decrypts and the handshake is a full one.
    */
  def swap(next: TlsMaterial): Unit =
    keys.set(next.keyManager)
    trust.set(next.trustManager)
    current.set(next)
    val previous = currentContext.getAndSet(newContext())
    TlsContexts.invalidate(previous.getServerSessionContext)
    TlsContexts.invalidate(previous.getClientSessionContext)

/** One `SSLContext` per listener, with swappable managers (research D-1). */
object TlsContexts:

  /** The protocols an engine may use: the profile's own list, else the JDK's default filtered to TLSv1.2 and above. */
  def effectiveProtocols(context: SSLContext, profile: TlsProfile): Seq[String] =
    if profile.protocols.nonEmpty then profile.protocols
    else context.getDefaultSSLParameters.getProtocols.toList.filter(p => p == "TLSv1.2" || p == "TLSv1.3")

  def server(
      profile: TlsProfile,
      material: TlsMaterial,
      clientAuth: ClientAuth,
      secureRandom: SecureRandom,
      listenerPath: String = ""
  ): TlsServerContext =
    val keys = new ReloadingKeyManager(material.keyManager)
    val trust = new ReloadingTrustManager(material.trustManager)
    // The key manager is the profile's keystore; the trust manager is its truststore and never the keystore (FR-004).
    // A listener that asks for client certificates names each refusal, for tls_handshake_failures_total (FR-017).
    val verified = if clientAuth == ClientAuth.None then trust else new VerdictTrustManager(trust)
    def newContext(): SSLContext =
      val context = SSLContext.getInstance("TLS")
      context.init(Array(keys), Array(verified), secureRandom)
      context
    val protocols = effectiveProtocols(newContext(), profile)
    new TlsServerContext(
      () => newContext(),
      clientAuth,
      protocols,
      profile.cipherSuites,
      profile,
      listenerPath,
      keys,
      trust,
      material
    )

  /** Invalidates every session of a session context, so none is resumed. */
  def invalidate(sessions: SSLSessionContext): Unit =
    if sessions != null then
      sessions.getIds.asScala.toList.foreach { id =>
        val session = sessions.getSession(id)
        if session != null then session.invalidate()
      }
