package com.chipprbots.ethereum.metrics

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.matching.Regex

import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** B-200: every `app_*` metric a Grafana dashboard under `ops/grafana/` queries is one fukuii's `/metrics` exposes, and
  * `rate()` and `increase()` are taken of counters only.
  *
  * The exposed names come from [[ExposedMetricNames]], which renders the node's registry as `/metrics` does. A
  * dashboard query is read from each `expr` (a panel's target) and each `query` (a templating variable) of every JSON
  * file. Only `app_*` names are checked: `jvm_*`, `process_*`, `up` and the other clients' metrics on the shared
  * dashboards (Lighthouse, node exporter) are not fukuii's application metrics.
  */
class GrafanaDashboardMetricNamesSpec extends AnyFlatSpec with Matchers:

  private val DashboardRoot: Path = Paths.get("ops", "grafana")

  /** The dashboards kept for history (provisioned in their own `Archive` folder). Only they may query [[Allowed]]. */
  private val ArchiveDir: Path = DashboardRoot.resolve("Archive")

  /** An `app_` name in a query: not part of a longer identifier. */
  private val AppName = """(?<![A-Za-z0-9_:])app_[A-Za-z0-9_:]+""".r

  /** The `app_` series a `rate`, `irate` or `increase` is taken of. */
  private val RateOf = """(?<![A-Za-z0-9_])(?:rate|irate|increase)\s*\(\s*(app_[A-Za-z0-9_:]+)""".r

  /** `app_*` names that fukuii no longer registers and that only the Archive dashboards still query, each with the
    * reason. No live dashboard may query one (the last test), and each must still be queried somewhere, or it goes.
    */
  private val Allowed: Seq[(Regex, String)] = Seq(
    "app_fastsync_.*".r -> "fast sync was removed (#1436); its panels are kept in the Archive dashboards for history",
    "app_transactions_pool_size_gauge".r -> "removed in v0.8.0 (#1382); the pool size is no longer exported",
    "app_sync_block_checkpoint_number_gauge".r -> "removed in c4d82db561",
    "app_network_peers_(high|low)SeverityOffense_counter_total".r -> "not registered anywhere in fukuii's history",
    "app_network_peers_(incoming|outgoing)_total_gauge".r ->
      "not registered anywhere in fukuii's history; app_network_peers_pending_gauge counts pending peers, not split",
    "app_sync_block_minedBlocks_counter_total".r -> "not registered anywhere in fukuii's history"
  )

  private def allowed(name: String): Boolean = Allowed.exists(_._1.matches(name))

  private lazy val exposition = ExposedMetricNames.collect()

  private def dashboards: Vector[Path] =
    Using.resource(Files.walk(DashboardRoot))(
      _.iterator.asScala.filter(p => Files.isRegularFile(p) && p.toString.endsWith(".json")).toVector.sorted
    )

  /** Every string under an `expr` or a `query` key, anywhere in the document. */
  private def queries(json: Json): Vector[String] =
    json.fold(
      Vector.empty,
      _ => Vector.empty,
      _ => Vector.empty,
      _ => Vector.empty,
      _.flatMap(queries),
      obj =>
        obj.toVector.flatMap { case (key, value) =>
          val own = if key == "expr" || key == "query" then value.asString.toVector else Vector.empty
          own ++ queries(value)
        }
    )

  /** The queries of each dashboard. */
  private lazy val queriesByDashboard: Vector[(Path, Vector[String])] =
    dashboards.map { path =>
      val text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
      val json = parse(text).fold(e => fail(s"$path is not valid JSON: ${e.getMessage}"), identity)
      path -> queries(json)
    }

  /** (dashboard, name) for each `app_` name each dashboard queries. */
  private def queried: Vector[(Path, String)] =
    queriesByDashboard.flatMap((path, qs) => qs.flatMap(AppName.findAllIn(_)).distinct.map(path -> _))

  private def listed(found: Seq[(Path, String)], heading: String): String =
    found.map((path, name) => s"$path: $name").mkString(s"$heading:\n  ", "\n  ", "\n")

  "The exposition" should "be rendered from the node's registry, with the names Prometheus gives each meter type" taggedAs
    UnitTest in {
      val names = exposition.names
      // every application meter has the prefix, also one made on the default registry before the exported registry
      // was added to it: those reach the exported leaf registries without the exported composite's filters (B-200)
      names.filterNot(_.startsWith("app_")) shouldBe empty
      // a counter gains `_total`
      names should contain("app_snapsync_errors_total")
      names should contain("app_network_messages_received_counter_total")
      // the Engine API counts are counters (B-200; as gauges `engine_newpayload_total` was exported as
      // `app_engine_newpayload`), and a name that ends in `_total` already does not get a second one
      (exposition.monotonic should contain).allOf("app_engine_newpayload_total", "app_engine_newpayload_valid_total")
      names should not contain "app_engine_newpayload_total_total"
      // a timer is `_seconds_count`, `_seconds_sum` and `_seconds_max`, and only the first two are monotonic
      (names should contain).allOf(
        "app_json_rpc_methods_timer_seconds_count",
        "app_json_rpc_methods_timer_seconds_sum",
        "app_json_rpc_methods_timer_seconds_max"
      )
      exposition.monotonic should not contain "app_json_rpc_methods_timer_seconds_max"
      // a gauge is exported as it is named, and is not monotonic
      names should contain("app_snapsync_phase_current_gauge")
      exposition.monotonic should not contain "app_snapsync_phase_current_gauge"
      // the bound Logback counter, a meter made on first use, one made per peer and one made with a data source
      names should contain("app_logback_events_total")
      names should contain("app_engine_state_hole_total")
      names should contain("app_network_peer_info")
      (names should contain).allOf("app_db_rocksdb_block_cache_hit_total", "app_db_rocksdb_block_cache_hit_rate")
    }

  "Every Grafana dashboard" should "query only app_ metrics that /metrics exposes" taggedAs UnitTest in {
    val all = queried
    all should not be empty
    val missing = all.filterNot((_, name) => exposition.names(name) || allowed(name))
    withClue(listed(missing, "not exposed"))(missing shouldBe empty)
  }

  it should "take rate() and increase() of counters only" taggedAs UnitTest in {
    val misused = queriesByDashboard.flatMap { (path, qs) =>
      qs.flatMap(RateOf.findAllMatchIn(_).map(_.group(1)))
        .filterNot(name => exposition.monotonic(name) || allowed(name))
        .distinct
        .map(path -> _)
    }
    withClue(listed(misused, "rate() or increase() of a series that is not a counter"))(misused shouldBe empty)
  }

  it should "query a metric fukuii no longer registers only from an Archive dashboard" taggedAs UnitTest in {
    val live = queried.filter((path, name) => allowed(name) && !path.startsWith(ArchiveDir))
    withClue(listed(live, "allow-listed but queried by a live dashboard"))(live shouldBe empty)
  }

  it should "keep no allow-list entry a dashboard no longer queries" taggedAs UnitTest in {
    val used = queried.map(_._2)
    Allowed.filterNot((pattern, _) => used.exists(pattern.matches)).map(_._2) shouldBe empty
  }
