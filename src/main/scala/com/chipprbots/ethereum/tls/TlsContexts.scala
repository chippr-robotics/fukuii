package com.chipprbots.ethereum.tls

import java.security.SecureRandom
import javax.net.ssl.SSLContext

/** A server context with what an engine made from it must be set to (spec 030 FR-003, FR-006). */
final case class TlsServerContext(
    context: SSLContext,
    clientAuth: ClientAuth,
    protocols: Seq[String],
    cipherSuites: Seq[String],
    material: TlsMaterial
)

/** One `SSLContext` per (profile, role); the server role is built here, from loaded material that does not change
  * (slice 4 swaps the managers behind the same context).
  */
object TlsContexts:

  /** The protocols an engine may use: the profile's own list, else the JDK's default filtered to TLSv1.2 and above. */
  def effectiveProtocols(context: SSLContext, profile: TlsProfile): Seq[String] =
    if profile.protocols.nonEmpty then profile.protocols
    else context.getDefaultSSLParameters.getProtocols.toList.filter(p => p == "TLSv1.2" || p == "TLSv1.3")

  def server(
      profile: TlsProfile,
      material: TlsMaterial,
      clientAuth: ClientAuth,
      secureRandom: SecureRandom
  ): TlsServerContext =
    val context = SSLContext.getInstance("TLS")
    // The key manager is the profile's keystore; the trust manager is its truststore and never the keystore (FR-004).
    // A listener that asks for client certificates names each refusal, for tls_handshake_failures_total (FR-017).
    val trust =
      if clientAuth == ClientAuth.None then material.trustManager else new VerdictTrustManager(material.trustManager)
    context.init(material.keyManager.toArray, Array(trust), secureRandom)
    TlsServerContext(context, clientAuth, effectiveProtocols(context, profile), profile.cipherSuites, material)
