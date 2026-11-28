package com.chipprbots.ethereum.tls

import java.nio.file.Path
import java.time.Duration as JDuration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.duration.*
import scala.util.control.NonFatal

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.metrics.MetricsContainer

/** `tls_reloads_total`, `tls_reload_failures_total` and `tls_certificate_expiry_seconds` (data-model.md; Micrometer
  * adds the node's `app_` prefix, and the Prometheus registry appends `_total` to a counter).
  */
object TlsReloadMetrics extends MetricsContainer:

  val Reloads = "app.tls_reloads"
  val ReloadFailures = "app.tls_reload_failures"
  val Expiry = "app.tls_certificate_expiry_seconds"

  private val counters = new ConcurrentHashMap[(String, String), Counter]()
  private val notAfter = new ConcurrentHashMap[(String, String), AtomicLong]()

  private def counter(name: String, profile: String): Counter =
    counters.computeIfAbsent(
      (name, profile),
      _ => Counter.builder(name).tags("profile", profile).register(metrics.registry)
    )

  def reloads(profile: String): Counter = counter(Reloads, profile)
  def failures(profile: String): Counter = counter(ReloadFailures, profile)

  /** Sets the certificate a profile serves in `role` (`server` or `client`), for its expiry gauge (FR-032). */
  def certificate(profile: String, role: String, expires: Instant): Unit =
    notAfter
      .computeIfAbsent(
        (profile, role),
        _ =>
          val holder = new AtomicLong(expires.toEpochMilli)
          Gauge
            .builder(Expiry, holder, (h: AtomicLong) => (h.get - System.currentTimeMillis()) / 1000.0)
            .tags("profile", profile, "role", role)
            .strongReference(true)
            .register(metrics.registry)
          holder
      )
      .set(expires.toEpochMilli)

  /** The gauge's value now, for tests and the dashboard: seconds until the certificate expires. */
  def expirySeconds(profile: String, role: String): Option[Double] =
    Option(notAfter.get((profile, role))).map(h => (h.get - System.currentTimeMillis()) / 1000.0)

/** The node's one reload loop (spec 030 FR-030–FR-033, research D-13): a single named daemon thread that, for each
  * registered listener and for the identity-rules file, polls the files on their interval, re-loads on a change, and
  * warns daily of a certificate close to expiry.
  *
  * A registration is keyed by name (the listener's label): a listener that binds again, after `rpc stop` and `rpc
  * start`, replaces its registration rather than adding one.
  *
  * @param clock
  *   now, for the expiry warning; a test's own
  * @param scheduled
  *   false for a test that drives [[poll]] by hand
  */
final class TlsReloader(clock: () => Instant = () => Instant.now(), scheduled: Boolean = true):

  private val log = LoggerFactory.getLogger("com.chipprbots.ethereum.tls.TlsReloader")

  /** One registration: its files, whether a change to them reloads (`reload.enabled`), and what a pass does. */
  final private class Watch(
      val name: String,
      val poller: FilePoller,
      val pollFiles: Boolean,
      val check: Boolean => Unit
  )

  private val watches = new ConcurrentHashMap[String, Watch]()
  private val tasks = new ConcurrentHashMap[String, ScheduledFuture[?]]()
  private val lastWarned = new ConcurrentHashMap[String, Instant]()

  private lazy val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor(
      new ThreadFactory:
        override def newThread(r: Runnable): Thread =
          val t = new Thread(r, "fukuii-tls-reloader")
          t.setDaemon(true)
          t
    )

  /** Serves `server` until its listener binds again: its expiry gauge now, and, when its profile reloads, a poll of the
    * profile's files every `reload.interval`. The expiry is checked at least hourly either way.
    */
  def register(name: String, server: TlsServerContext): Unit =
    val profile = server.profile
    publishExpiry(profile, server.material)
    val watch = new Watch(
      name,
      new FilePoller(profile.files),
      profile.reload.enabled,
      filesChanged =>
        if filesChanged then reloadNow(profile, server)
        warnIfExpiring(profile, server.material)
    )
    watches.put(name, watch)
    warnIfExpiring(profile, server.material)
    val every = if profile.reload.enabled then profile.reload.interval.min(1.hour) else 1.hour
    schedule(name, every, watch)

  /** Re-reads the identity-rules file every `interval` (FR-033): a change that parses is handed to `apply`; one that
    * does not keeps the rules in service and is counted under the profile label `client-identities`.
    */
  def registerRules(
      file: Path,
      interval: FiniteDuration,
      parse: () => Either[TlsConfigError, IdentityRules],
      apply: IdentityRules => Unit
  ): Unit =
    val name = TlsReloader.RulesLabel
    val watch = new Watch(
      name,
      new FilePoller(Seq(file)),
      pollFiles = true,
      filesChanged =>
        if filesChanged then
          (try parse()
          catch case NonFatal(e) => Left(TlsConfigError(s"$file: ${e.getClass.getSimpleName}"))) match
            case Right(rules) =>
              apply(rules)
              TlsReloadMetrics.reloads(name).increment()
              log.info(s"TLS identity rules reloaded ($file changed): ${rules.rules.size} rule(s)")
            case Left(error) =>
              TlsReloadMetrics.failures(name).increment()
              log.error(s"TLS identity rules reload failed: ${error.message}; keeping the previous rules")
    )
    watches.put(name, watch)
    schedule(name, interval, watch)

  /** One pass over every registration, as the scheduler runs it: for tests that drive the reloader by hand. */
  def poll(): Unit = watches.values.forEach(w => run(w))

  def shutdown(): Unit =
    tasks.values.forEach(_.cancel(false))
    tasks.clear()
    if scheduled then scheduler.shutdownNow()
    ()

  private def schedule(name: String, every: FiniteDuration, watch: Watch): Unit =
    if scheduled then
      val task = scheduler.scheduleWithFixedDelay(
        () => run(watch),
        every.toMillis,
        every.toMillis,
        TimeUnit.MILLISECONDS
      )
      Option(tasks.put(name, task)).foreach(_.cancel(false))

  /** A failure here must never end the loop: the scheduler drops a task whose run throws. */
  private def run(watch: Watch): Unit =
    try watch.check(watch.pollFiles && watch.poller.changed())
    catch case NonFatal(e) => log.error(s"TLS reload check of ${watch.name} failed", e)

  private def reloadNow(profile: TlsProfile, server: TlsServerContext): Unit =
    server.reload(clock()) match
      case Right(material) =>
        TlsReloadMetrics.reloads(profile.name).increment()
        publishExpiry(profile, material)
        log.info(
          s"TLS profile ${profile.name} reloaded (${profile.files.mkString(", ")} changed); new handshakes use cert " +
            s"${TlsReloader.subject(material)} notAfter=${TlsReloader.date(material)}"
        )
      case Left(error) =>
        TlsReloadMetrics.failures(profile.name).increment()
        log.error(s"TLS profile ${profile.name} reload failed: ${error.message}; keeping the previous material")

  private def publishExpiry(profile: TlsProfile, material: TlsMaterial): Unit =
    material.leaf.foreach(c =>
      TlsReloadMetrics.certificate(profile.name, TlsReloader.ServerRole, c.getNotAfter.toInstant)
    )

  /** WARN once a day while the certificate is within the profile's `expiry-warning` (FR-032). */
  private def warnIfExpiring(profile: TlsProfile, material: TlsMaterial): Unit =
    material.leaf.foreach { cert =>
      val now = clock()
      val left = JDuration.between(now, cert.getNotAfter.toInstant)
      if left.toMillis <= profile.expiryWarning.toMillis then
        val key = s"${profile.name}/${TlsReloader.ServerRole}"
        val previous = Option(lastWarned.get(key))
        if previous.forall(p => JDuration.between(p, now).toHours >= 24) then
          lastWarned.put(key, now)
          log.warn(
            s"TLS profile ${profile.name} ${TlsReloader.ServerRole} certificate expires in ${left.toDays.max(0)} days " +
              s"(${TlsReloader.date(material)})"
          )
    }

object TlsReloader:
  val ServerRole = "server"
  val RulesLabel = "client-identities"

  private def subject(m: TlsMaterial): String = m.leaf.map(_.getSubjectX500Principal.getName).getOrElse("")
  private def date(m: TlsMaterial): String =
    m.leaf.map(_.getNotAfter.toInstant.atZone(ZoneOffset.UTC).toLocalDate.toString).getOrElse("")
