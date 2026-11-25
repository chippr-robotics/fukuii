package com.chipprbots.ethereum.metrics

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import scala.concurrent.Await
import scala.concurrent.duration.*

import io.micrometer.core.instrument.binder.logging.LogbackMetrics
import io.prometheus.metrics.expositionformats.ExpositionFormats
import io.prometheus.metrics.model.registry.PrometheusRegistry

import com.chipprbots.ethereum.blockchain.sync.ChainWeightReconcile
import com.chipprbots.ethereum.blockchain.sync.RecoveryMetrics
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSyncMetrics
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.consensus.engine.EngineApiMetrics
import com.chipprbots.ethereum.consensus.mining.MiningMetrics
import com.chipprbots.ethereum.consensus.pow.PoWMiningMetrics
import com.chipprbots.ethereum.db.dataSource.RocksDbCacheMetrics
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PruningMetrics
import com.chipprbots.ethereum.db.storage.pathstate.StateMetrics
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.jsonrpc.JsonRpcControllerMetrics
import com.chipprbots.ethereum.jsonrpc.RpcNamespaceDenials
import com.chipprbots.ethereum.ledger.BlockMetrics
import com.chipprbots.ethereum.network.NetworkMetrics
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.NodeAllowlistMetrics
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerTelemetry
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.tls.HandshakeFailureReason
import com.chipprbots.ethereum.tls.ListenerId
import com.chipprbots.ethereum.tls.TlsHandshakeMetrics
import com.chipprbots.ethereum.tls.TlsReloadMetrics
import com.chipprbots.ethereum.vm.PrecompileDivergence

/** The metric names fukuii's `/metrics` endpoint exposes (B-200), obtained the way the node exports them:
  *
  *   - the registry is [[MeterRegistryBuilder.build]] (the composite with the `app.` prefix filter and Micrometer's
  *     `PrometheusMeterRegistry`), with a private `PrometheusRegistry` in place of the JVM-wide default and no JMX;
  *   - it is added to the default registry, as `Metrics.configure` adds the exported one, so every meter the holders
  *     made or make on the default registry is registered on it too (B-427), with the same `app.` prefix. Unlike
  *     `configure` it does not become the default instance: a holder first touched here (by this or by a spec running
  *     beside it) would otherwise hold this registry, closed after, for the rest of the JVM;
  *   - `LogbackMetrics` is bound to it, as `Metrics.start` does;
  *   - every holder of application meters is touched, and the meters that are made only on first use (a tag per call, a
  *     peer, a RocksDB data source, a lazy val) are made with a probe tag;
  *   - the `PrometheusRegistry` is rendered in the Prometheus text format, as `/metrics` serves it, and each sample's
  *     name is read off.
  *
  * JVM and process metrics (`JvmMetrics`, registered by `Metrics.start` on the default `PrometheusRegistry`) are not
  * application metrics and are not rendered here.
  *
  * Not made here, as nothing can make them without running the code they time: `consensus.head.lock.wait.seconds`
  * (CanonicalHeadLock's private wait timer, made on the first write of the head), the per-reason
  * `db.state.handoff.sample.stopped` counters and the two `db.state.handoff.*.consecutive` gauges (private holders
  * started by the sync controller), and `p2p.allowlist.edits` (a tag per admin edit). No dashboard queries them.
  */
object ExposedMetricNames:

  /** The rendered exposition and the names of the samples in it. */
  final case class Exposition(text: String, names: Set[String]):

    /** The samples that only go up, which `rate()` and `increase()` may be taken of: a counter's, and the `_count`,
      * `_sum` and `_bucket` of a summary or a histogram (a timer, a distribution summary). A gauge's are not, nor a
      * summary's `_max`, which is a gauge of its own.
      */
    lazy val monotonic: Set[String] =
      text.linesIterator
        .filter(_.startsWith("# TYPE "))
        .map(_.stripPrefix("# TYPE ").split(' '))
        .collect {
          case Array(family, "counter")               => Set(family, s"${family}_total")
          case Array(family, "summary" | "histogram") => Set("_count", "_sum", "_bucket").map(family + _)
        }
        .flatten
        .toSet
        .intersect(names)

  private val Probe = "b200-probe"

  def collect(): Exposition =
    val prometheusRegistry = new PrometheusRegistry()
    val registry = MeterRegistryBuilder.build(Metrics.MetricsPrefix, prometheusRegistry, withJmx = false)
    val logback = new LogbackMetrics()
    try
      Metrics.withRegistryAddedForTest(registry) {
        logback.bindTo(registry)
        touchHolders()
        val text = render(prometheusRegistry)
        Exposition(text, namesIn(text))
      }
    finally
      logback.close()
      registry.close()

  /** The sample names of a Prometheus text exposition: each line that is not a comment, up to its labels or value. */
  def namesIn(text: String): Set[String] =
    text.linesIterator
      .filterNot(l => l.isEmpty || l.startsWith("#"))
      .map(_.takeWhile(c => c != '{' && c != ' '))
      .toSet

  private def render(prometheusRegistry: PrometheusRegistry): String =
    val out = new ByteArrayOutputStream()
    ExpositionFormats.init().getPrometheusTextFormatWriter.write(out, prometheusRegistry.scrape())
    out.toString(StandardCharsets.UTF_8)

  private def touchHolders(): Unit =
    // Holders whose meters are all made when the object starts.
    val _ = Seq[AnyRef](
      BlockMetrics,
      ChainWeightReconcile.Metrics,
      EngineApiMetrics,
      JsonRpcControllerMetrics,
      MiningMetrics,
      NetworkMetrics,
      PoWMiningMetrics,
      PruningMetrics,
      RecoveryMetrics,
      RegularSyncMetrics,
      SNAPSyncMetrics
    )
    // Registered by the node once metrics are configured (StdNode).
    StateMetrics.register()
    NodeAllowlistMetrics.register()
    // Made on first use.
    val _ = PrecompileDivergence.UnexplainedCounter
    JsonRpcControllerMetrics.recordMethodTime(Probe, Duration.ZERO)
    EngineApiMetrics.recordStateHole(Probe)
    SNAPSyncMetrics.incrementStateOnDemandFetch(Probe, Probe)
    SNAPSyncMetrics.incrementHeaderRejected(Probe, Probe)
    SNAPSyncMetrics.recordHealNodesPerRepeg(0L)
    RegularSyncMetrics.incrementPipelineRestarts(Probe)
    RegularSyncMetrics.incrementOrphanBlocks(Probe, Probe)
    val _ = RpcNamespaceDenials.counter(Probe, Probe)
    val _ = TlsHandshakeMetrics.counter(ListenerId.Metrics, HandshakeFailureReason.Protocol)
    val _ = TlsReloadMetrics.reloads(Probe)
    val _ = TlsReloadMetrics.failures(Probe)
    TlsReloadMetrics.certificate(Probe, "server", java.time.Instant.EPOCH)
    withPeer(PeerTelemetry.registerPeer)
    withRocksDb(RocksDbCacheMetrics.register)

  /** A handshaked peer for [[PeerTelemetry]]'s per-peer series, deregistered after. */
  private def withPeer(register: (Peer, PeerInfo) => Unit): Unit =
    val system = ActorSystem("ExposedMetricNames")
    try
      val peer = Peer(
        PeerId(Probe),
        new InetSocketAddress("127.0.0.1", 30303),
        system.deadLetters.toTyped[PeerActor.Command],
        incomingConnection = false
      )
      val status = RemoteStatus(
        capability = Capability.ETH68,
        networkId = 1L,
        chainWeight = ChainWeight.zero,
        bestHash = ByteString.empty,
        genesisHash = ByteString.empty,
        supportsSnap = true,
        capabilities = List(Capability.ETH68),
        latestBlock = None,
        remoteClientId = "Probe/v1"
      )
      register(
        peer,
        PeerInfo(
          status,
          ChainWeight.zero,
          forkAccepted = true,
          maxBlockNumber = BigInt(0),
          bestBlockHash = ByteString.empty
        )
      )
    finally
      val _ = Await.ready(system.terminate(), 10.seconds)

  /** A RocksDB data source in a temporary directory: the block-cache gauges are registered only against one. Once it is
    * closed they read 0, and are still exposed.
    */
  private def withRocksDb(register: RocksDbDataSource => Unit): Unit =
    val dir = Files.createTempDirectory("b200-rocksdb")
    val config = new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = dir.toAbsolutePath.toString
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 64
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 8L * 1024 * 1024
    val dataSource = RocksDbDataSource(config, Namespaces.nsSeq)
    try register(dataSource)
    finally dataSource.destroy()
