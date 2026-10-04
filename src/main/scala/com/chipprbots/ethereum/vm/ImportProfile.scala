package com.chipprbots.ethereum.vm

import java.util.concurrent.atomic.AtomicLong

/** Per-block timers for the block-import path: where one block's wall time goes. Logged once per imported block by
  * `BlockExecution` (INFO), so a throughput regression can be told apart without a profiler.
  *
  * Only the thread that called [[begin]] is counted (the import thread), so concurrent `eth_call` / tracing threads
  * touching the same code do not pollute a block's figures. Each probe is two `System.nanoTime` calls and a few
  * `AtomicLong` adds, against the milliseconds a frame, a trie walk or a 64 KB scan costs; calls made while no block is
  * being timed cost one volatile read.
  *
  * `getAccount` time is nested inside `getCode` time (getCode reads the account first), and every frame's `scan` time
  * is inside the EVM time: the lines are cumulative, not a partition of `total`.
  */
object ImportProfile:

  @volatile private var owner: Thread = null

  private val frames = new AtomicLong
  private val scanNs, scanCount, scanHits, scanBytes = new AtomicLong
  private val accountNs, accountCount = new AtomicLong
  private val codeNs, codeCount, codeBytes = new AtomicLong
  private val nodeHits, nodeMisses, memoHits, codeHits = new AtomicLong
  private val jumpMemoHits, jumpMemoMisses = new AtomicLong
  private var startNanos = 0L

  final case class Snapshot(
      totalNanos: Long,
      frames: Long,
      scanNanos: Long,
      scans: Long,
      scanHits: Long,
      scanBytes: Long,
      accountNanos: Long,
      accounts: Long,
      codeNanos: Long,
      codes: Long,
      codeBytes: Long,
      nodeHits: Long,
      nodeMisses: Long,
      memoHits: Long,
      codeHits: Long,
      jumpMemoHits: Long,
      jumpMemoMisses: Long
  )

  private def counting: Boolean =
    val o = owner
    o != null && (o eq Thread.currentThread)

  /** Starts timing a block on the calling thread. A second `begin` before `end` (nested import) is ignored. */
  def begin(): Boolean =
    if owner != null then false
    else
      List(
        frames,
        scanNs,
        scanCount,
        scanHits,
        scanBytes,
        accountNs,
        accountCount,
        codeNs,
        codeCount,
        codeBytes,
        nodeHits,
        nodeMisses,
        memoHits,
        codeHits,
        jumpMemoHits,
        jumpMemoMisses
      )
        .foreach(_.set(0L))
      startNanos = System.nanoTime()
      owner = Thread.currentThread
      true

  def end(): Snapshot =
    val total = System.nanoTime() - startNanos
    owner = null
    Snapshot(
      total,
      frames.get,
      scanNs.get,
      scanCount.get,
      scanHits.get,
      scanBytes.get,
      accountNs.get,
      accountCount.get,
      codeNs.get,
      codeCount.get,
      codeBytes.get,
      nodeHits.get,
      nodeMisses.get,
      memoHits.get,
      codeHits.get,
      jumpMemoHits.get,
      jumpMemoMisses.get
    )

  def frame(): Unit = if counting then frames.incrementAndGet()

  def scanHit(): Unit = if counting then scanHits.incrementAndGet()

  /** Times one JUMPDEST analysis of `length` bytes. */
  inline def scan[A](length: Int)(inline body: => A): A =
    if counting then
      val t = System.nanoTime()
      val r = body
      scanNs.addAndGet(System.nanoTime() - t)
      scanCount.incrementAndGet()
      scanBytes.addAndGet(length)
      r
    else body

  inline def account[A](inline body: => A): A =
    if counting then
      val t = System.nanoTime()
      try body
      finally
        accountNs.addAndGet(System.nanoTime() - t)
        accountCount.incrementAndGet()
    else body

  inline def code[A](inline body: => A): A =
    if counting then
      val t = System.nanoTime()
      try body
      finally
        codeNs.addAndGet(System.nanoTime() - t)
        codeCount.incrementAndGet()
    else body

  def nodeHit(): Unit = if counting then nodeHits.incrementAndGet()
  def nodeMiss(): Unit = if counting then nodeMisses.incrementAndGet()
  def memoHit(): Unit = if counting then memoHits.incrementAndGet()
  def jumpMemoHit(): Unit = if counting then jumpMemoHits.incrementAndGet()
  def jumpMemoMiss(): Unit = if counting then jumpMemoMisses.incrementAndGet()
  def codeHit(): Unit = if counting then codeHits.incrementAndGet()

  def codeBytesRead(n: Int): Unit = if counting then codeBytes.addAndGet(n)

  def format(blockNumber: BigInt, gasUsed: BigInt, txs: Int, s: Snapshot): String =
    def ms(n: Long) = f"${n / 1e6}%.1f"
    s"[IMPORT-TIMING] block=$blockNumber gas=$gasUsed txs=$txs total=${ms(s.totalNanos)}ms frames=${s.frames} " +
      s"scan=${ms(s.scanNanos)}ms(n=${s.scans},hits=${s.scanHits},memo=${s.jumpMemoHits}/${s.jumpMemoHits + s.jumpMemoMisses},${s.scanBytes / 1024}KiB) " +
      s"getAccount=${ms(s.accountNanos)}ms(n=${s.accounts}) getCode=${ms(s.codeNanos)}ms(n=${s.codes},${s.codeBytes / 1024}KiB,hits=${s.codeHits}) " +
      s"nodes(hit=${s.nodeHits},miss=${s.nodeMisses}) readMemoHits=${s.memoHits}"
