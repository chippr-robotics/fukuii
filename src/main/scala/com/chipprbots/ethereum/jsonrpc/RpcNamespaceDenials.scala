package com.chipprbots.ethereum.jsonrpc

import java.util.concurrent.ConcurrentHashMap

import io.micrometer.core.instrument.Counter
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.metrics.MetricsContainer

/** Calls refused because the caller's identity is not granted the namespace (spec 030 FR-024, FR-028):
  * `app_rpc_namespace_denials_total{identity_rule, namespace}`, and an INFO line at most once per minute per rule and
  * namespace. `identity_rule` is a configured rule name, `anonymous` or `unmatched`, never a certificate subject, so
  * the label set stays as small as the rule list.
  */
object RpcNamespaceDenials extends MetricsContainer:

  /** The Micrometer name; the Prometheus registry appends `_total` to a counter. */
  val Name = "app.rpc_namespace_denials"

  private val WindowMillis = 60000L
  private val log = LoggerFactory.getLogger("com.chipprbots.ethereum.jsonrpc.RpcNamespaceDenials")
  private val counters = new ConcurrentHashMap[(String, String), Counter]()
  private val lastLogged = new ConcurrentHashMap[(String, String), java.lang.Long]()

  def counter(identityRule: String, namespace: String): Counter =
    counters.computeIfAbsent(
      (identityRule, namespace),
      _ =>
        Counter
          .builder(Name)
          .tags("identity_rule", identityRule, "namespace", namespace)
          .register(metrics.registry)
    )

  /** Counts one refused call and logs it unless this rule and namespace were logged within the minute. */
  def record(identityRule: String, namespace: String, method: String, listener: String): Unit =
    counter(identityRule, namespace).increment()
    val now = System.currentTimeMillis()
    var due = false
    lastLogged.compute(
      (identityRule, namespace),
      (_, previous) =>
        if previous == null || now - previous >= WindowMillis then
          due = true
          java.lang.Long.valueOf(now)
        else previous
    )
    if due then log.info(s"RPC denied $method to $identityRule on $listener")

  /** The namespace of a method name: the part before the first `_`. */
  def namespaceOf(method: String): String = method.takeWhile(_ != '_')
