package com.chipprbots.ethereum.network.discovery

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.IORuntimeConfig

import scala.concurrent.ExecutionContext

/** A small, bounded cats-effect runtime that all peer-discovery work runs on (task #52).
  *
  * Discovery v4 is ECDSA-bound: every outbound packet is signed and every inbound one has its public key recovered,
  * each costing several milliseconds of pure-JVM curve arithmetic. It used to run on `IORuntime.global`, whose compute
  * pool is sized to the core count and is shared with block import, branch evaluation and the sync fetchers. On a
  * 4-core Ephemery host (2026-10-19, v0.9.122) discovery filled all four `io-compute` threads with ECDSA: block import
  * stalled, Engine API requests queued for over a minute (JWT `iat` skew 68–128 s), Lighthouse's upcheck timed out.
  *
  * The compute pool size here is the discovery CPU budget: with `computeThreads` threads discovery can never use more
  * than that many cores, however much traffic arrives — the rest of the node keeps its own pool. Inbound Ping → Pong
  * stays on the netty event-loop thread (see `Discv4SyncResponder`), so liveness towards peers does not depend on this
  * pool's latency. This mirrors `EngineApiHttpServer`'s dedicated runtime (#1209).
  *
  * Threads are daemon threads, so an unreleased runtime cannot hold the JVM open.
  */
object DiscoveryRuntime:

  /** Thread-name prefix of the compute pool; tests assert discovery crypto runs on threads carrying it. */
  val ComputeThreadPrefix: String = "discovery-compute"

  def apply(computeThreads: Int): IORuntime =
    require(computeThreads > 0, s"discovery compute-threads must be positive, got $computeThreads")
    val computeService = Executors.newFixedThreadPool(computeThreads, daemonThreadFactory(ComputeThreadPrefix))
    val compute = ExecutionContext.fromExecutorService(computeService)
    val (blocking, shutdownBlocking) =
      IORuntime.createDefaultBlockingExecutionContext(threadPrefix = "discovery-blocking")
    val (scheduler, shutdownScheduler) = IORuntime.createDefaultScheduler(threadPrefix = "discovery-scheduler")
    IORuntime(
      compute = compute,
      blocking = blocking,
      scheduler = scheduler,
      shutdown = () =>
        shutdownScheduler()
        shutdownBlocking()
        computeService.shutdown()
      ,
      config = IORuntimeConfig()
    )

  private def daemonThreadFactory(prefix: String): ThreadFactory =
    val counter = new AtomicInteger(0)
    (runnable: Runnable) =>
      val thread = new Thread(runnable, s"$prefix-${counter.getAndIncrement()}")
      thread.setDaemon(true)
      thread
