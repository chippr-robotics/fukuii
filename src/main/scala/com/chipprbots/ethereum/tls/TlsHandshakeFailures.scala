package com.chipprbots.ethereum.tls

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509ExtendedTrustManager

import scala.annotation.tailrec

import io.micrometer.core.instrument.Counter

import com.chipprbots.ethereum.metrics.MetricsContainer

/** Why a TLS handshake on a listener failed: the `reason` label of `tls_handshake_failures_total` (spec 030 FR-017). */
enum HandshakeFailureReason(val label: String):
  case NoClientCert extends HandshakeFailureReason("no_client_cert")
  case UntrustedClientCert extends HandshakeFailureReason("untrusted_client_cert")
  case ExpiredClientCert extends HandshakeFailureReason("expired_client_cert")
  case Protocol extends HandshakeFailureReason("protocol")
  case Other extends HandshakeFailureReason("other")

/** A client certificate the listener's trust manager refused, with the verdict already made: the handshake exception
  * the JDK builds carries it as its cause, so the classifier reads the verdict rather than guessing from a message.
  */
final class ClientCertRefused(val reason: HandshakeFailureReason, cause: Throwable)
    extends CertificateException(s"client certificate refused (${reason.label}): ${cause.getMessage}", cause)

/** The trust manager of a listener that asks for client certificates (`want`, `need`): the profile's own, verifying as
  * it does, with each refusal tagged expired or untrusted (spec 030 FR-003, FR-017). A presented certificate is always
  * verified, under `want` as under `need`; this class only names the verdict.
  *
  * A certificate that is out of its validity period is `expired_client_cert` whether or not its issuer is trusted: the
  * operator's fix (issue a new one) is the same either way.
  */
final class VerdictTrustManager(delegate: X509ExtendedTrustManager) extends X509ExtendedTrustManager:

  private def verdict[A](chain: Array[X509Certificate])(check: => A): A =
    try check
    catch
      case e: CertificateException =>
        val outOfPeriod = chain != null && chain.nonEmpty && {
          try
            chain(0).checkValidity(); false
          catch case _: CertificateExpiredException | _: CertificateNotYetValidException => true
        }
        throw new ClientCertRefused(
          if outOfPeriod then HandshakeFailureReason.ExpiredClientCert else HandshakeFailureReason.UntrustedClientCert,
          e
        )

  override def checkClientTrusted(chain: Array[X509Certificate], authType: String, socket: Socket): Unit =
    verdict(chain)(delegate.checkClientTrusted(chain, authType, socket))
  override def checkClientTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit =
    verdict(chain)(delegate.checkClientTrusted(chain, authType, engine))
  override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit =
    verdict(chain)(delegate.checkClientTrusted(chain, authType))

  // A server never checks a server: these are the delegate's, unchanged.
  override def checkServerTrusted(chain: Array[X509Certificate], authType: String, socket: Socket): Unit =
    delegate.checkServerTrusted(chain, authType, socket)
  override def checkServerTrusted(chain: Array[X509Certificate], authType: String, engine: SSLEngine): Unit =
    delegate.checkServerTrusted(chain, authType, engine)
  override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit =
    delegate.checkServerTrusted(chain, authType)

  override def getAcceptedIssuers: Array[X509Certificate] = delegate.getAcceptedIssuers

/** Names the reason of a failed server-side handshake from the exception the engine threw (spec 030 FR-017). */
object HandshakeFailureClassifier:

  // The JDK's own texts (sun.security.ssl), which carry no exception type of their own.
  private val NoCertificate = Seq("Empty client certificate chain", "Empty server certificate chain")
  private val ProtocolTexts = Seq(
    "protocol is disabled",
    "No appropriate protocol",
    "not enabled or supported",
    "Unsupported or unrecognized SSL message",
    "no cipher suites in common",
    "Received fatal alert: protocol_version",
    "Received fatal alert: handshake_failure",
    "Client requested protocol",
    "not accepted by server preferences",
    "Unsupported protocol"
  )

  def classify(failure: Throwable): HandshakeFailureReason =
    refusal(failure) match
      case Some(reason) => reason
      case None =>
        val messages = chain(failure).flatMap(t => Option(t.getMessage))
        if messages.exists(m => NoCertificate.exists(m.contains)) then HandshakeFailureReason.NoClientCert
        else if messages.exists(m => ProtocolTexts.exists(m.contains)) then HandshakeFailureReason.Protocol
        else HandshakeFailureReason.Other

  private def refusal(t: Throwable): Option[HandshakeFailureReason] =
    chain(t).collectFirst { case r: ClientCertRefused => r.reason }

  /** The exception and its causes and suppressed exceptions, without cycles, at most 16 deep. */
  private def chain(t: Throwable): List[Throwable] =
    @tailrec
    def go(todo: List[Throwable], seen: List[Throwable]): List[Throwable] = todo match
      case Nil                                                    => seen.reverse
      case _ if seen.size >= 16                                   => seen.reverse
      case head :: rest if head == null || seen.exists(_ eq head) => go(rest, seen)
      case head :: rest =>
        go(rest ++ Option(head.getCause).toList ++ head.getSuppressed.toList, head :: seen)
    go(List(t), Nil)

  /** Whether an exception the engine threw is the end of a handshake (rather than of an established session). */
  def isHandshakeFailure(e: SSLException, handshakeDone: Boolean): Boolean =
    e.isInstanceOf[SSLHandshakeException] || !handshakeDone

/** Rate-limits the WARN line of refused handshakes: at most one per remote address per `window`, carrying the count of
  * the lines suppressed since the last one (spec 030 FR-017). Addresses are forgotten a window after their last line,
  * so a scan from many addresses holds at most the addresses of one window.
  */
final class HandshakeFailureLog(windowMillis: Long = 60000L, clock: () => Long = () => System.currentTimeMillis()):

  final private case class Slot(loggedAt: Long, suppressed: Int)
  private val slots = new ConcurrentHashMap[String, Slot]()
  @volatile private var lastSweep = 0L

  /** The line to log for a refusal now, or `None` when one was logged for the address in this window. */
  def record(listener: String, address: String, reason: HandshakeFailureReason, detail: String): Option[String] =
    val now = clock()
    sweep(now)
    var line: Option[String] = None
    slots.compute(
      address,
      (_, slot) =>
        if slot == null || now - slot.loggedAt >= windowMillis then
          val suffix = if slot != null && slot.suppressed > 0 then s" (${slot.suppressed} suppressed)" else ""
          line = Some(s"TLS handshake refused on $listener from $address: ${reason.label} ($detail)$suffix")
          Slot(now, 0)
        else slot.copy(suppressed = slot.suppressed + 1)
    )
    line

  private def sweep(now: Long): Unit =
    if now - lastSweep >= windowMillis then
      lastSweep = now
      slots.entrySet().removeIf(e => now - e.getValue.loggedAt >= 2 * windowMillis)
      ()

  private[tls] def tracked: Int = slots.size()

/** `tls_handshake_failures_total{listener, reason}` (data-model.md; Micrometer adds the node's `app_` prefix). */
object TlsHandshakeMetrics extends MetricsContainer:

  /** The Micrometer name; the Prometheus registry appends `_total` to a counter, so it is exported as
    * `app_tls_handshake_failures_total`.
    */
  val Name = "app.tls_handshake_failures"

  private val counters = new ConcurrentHashMap[(String, HandshakeFailureReason), Counter]()

  def counter(listener: ListenerId, reason: HandshakeFailureReason): Counter =
    counters.computeIfAbsent(
      (listener.label, reason),
      _ =>
        Counter
          .builder(Name)
          .tags("listener", listener.label, "reason", reason.label)
          .register(metrics.registry)
    )

  def increment(listener: ListenerId, reason: HandshakeFailureReason): Unit = counter(listener, reason).increment()
