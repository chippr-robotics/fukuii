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
  private val storageNs, storageCount = new AtomicLong
  private val txNs, txPersistNs, balNs, finalPersistNs = new AtomicLong
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
      jumpMemoMisses: Long,
      storageNanos: Long = 0L,
      storageReads: Long = 0L,
      txNanos: Long = 0L,
      txPersistNanos: Long = 0L,
      balNanos: Long = 0L,
      finalPersistNanos: Long = 0L
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
        jumpMemoMisses,
        storageNs,
        storageCount,
        txNs,
        txPersistNs,
        balNs,
        finalPersistNs
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
      jumpMemoMisses.get,
      storageNs.get,
      storageCount.get,
      txNs.get,
      txPersistNs.get,
      balNs.get,
      finalPersistNs.get
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

  /** Times one persisted-trie storage read (an SLOAD that missed the in-flight overlay), nested inside the tx time. */
  inline def storageRead[A](inline body: => A): A =
    if counting then
      val t = System.nanoTime()
      try body
      finally
        storageNs.addAndGet(System.nanoTime() - t)
        storageCount.incrementAndGet()
    else body

  private inline def timed[A](counter: AtomicLong)(inline body: => A): A =
    if counting then
      val t = System.nanoTime()
      try body
      finally counter.addAndGet(System.nanoTime() - t)
    else body

  /** One whole transaction (validation, execution, refund, delete, persist); the persist is also in [[txPersist]]. */
  inline def tx[A](inline body: => A): A = timed(txNs)(body)

  /** The per-transaction `persistState`: code, storage tries and the account trie are hashed and written to the node
    * storage after every transaction.
    */
  inline def txPersist[A](inline body: => A): A = timed(txPersistNs)(body)

  /** EIP-7928 access-list construction for one block access index. */
  inline def balBuild[A](inline body: => A): A = timed(balNs)(body)

  /** The block's closing `persistState` (after rewards, withdrawals and system calls). */
  inline def finalPersist[A](inline body: => A): A = timed(finalPersistNs)(body)

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
      s"nodes(hit=${s.nodeHits},miss=${s.nodeMisses}) readMemoHits=${s.memoHits} " +
      s"getStorage=${ms(s.storageNanos)}ms(n=${s.storageReads}) txs=${ms(s.txNanos)}ms txPersist=${ms(s.txPersistNanos)}ms " +
      s"balBuild=${ms(s.balNanos)}ms finalPersist=${ms(s.finalPersistNanos)}ms"
