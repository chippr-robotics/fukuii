package com.chipprbots.ethereum.ledger

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import scala.util.control.NonFatal

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.PrefetchNodeReader
import com.chipprbots.ethereum.domain
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.StateReadCacheConfig
import com.chipprbots.ethereum.vm.ImportProfile

/** BAL-driven prefetch of one Amsterdam block's state reads (EIP-7928), started when the block begins to execute.
  *
  * The block executes on one thread and its reads are serial and dependent: an account is an MPT walk of several nodes,
  * each a database read of tens to thousands of microseconds, and a contract's code is a 64 KB read behind it. The
  * access list names every account and slot the block touches, so those reads can be issued ahead, in parallel, at the
  * PARENT state root:
  *
  *   - stage 1: each listed address is read from the parent account trie and each listed slot from the account's
  *     storage trie, through [[PrefetchNodeReader]] into the decoded-node cache the execution reads through (what
  *     execution actually hits: its walks start at the same hashes, and the nodes are content-addressed);
  *   - stage 2: as an account comes back with a non-empty `codeHash`, its code is read into the execution code cache
  *     (geth and Besu prefetch no code and lean on parallel transaction workers; this node executes serially, so code
  *     is the read that must be overlapped).
  *
  * Consensus-neutral by construction: it only fills caches that hold values immutable under their key, never writes,
  * never feeds execution anything, and execution reads and decides exactly as without it. It never throws into
  * execution (every task catches, a missing node or code is skipped and execution meets it as it does today), a bogus
  * or surplus list entry costs only IO (EIP-7928's `gasLimit / 2000` item bound caps the work, and the list is checked
  * against the header's `blockAccessListHash` before any of it is used), and [[Run.finish]] cancels the remaining work
  * and drains the in-flight reads before returning, so nothing outlives the block (see [[PrefetchNodeReader]] on why
  * that makes the cache inserts safe against pruning).
  */
object BalPrefetcher extends Logger:

  private lazy val pool: ThreadPoolExecutor =
    val counter = new AtomicInteger
    val threads = StateReadCacheConfig.balPrefetchThreads
    val executor = new ThreadPoolExecutor(
      threads,
      threads,
      30L,
      TimeUnit.SECONDS,
      new LinkedBlockingQueue[Runnable],
      new ThreadFactory:
        override def newThread(r: Runnable): Thread =
          val t = new Thread(r, s"bal-prefetch-${counter.incrementAndGet()}")
          t.setDaemon(true)
          t
    )
    executor.allowCoreThreadTimeOut(true)
    executor

  /** Test hook: true switches the prefetch off whatever the configuration says (equivalence tests run a block both
    * ways).
    */
  @volatile private[ethereum] var disabled: Boolean = false

  /** Runs started since the JVM began (tests assert the prefetch actually ran). */
  private[ethereum] val runsStarted = new AtomicLong

  /** Starts the prefetch for `header`'s block, or `None` when there is nothing to do: disabled, not an Amsterdam block
    * with a list, or no reader. Never throws.
    *
    * @param committedHash
    *   the header's `blockAccessListHash`; the list is used only if it hashes to it.
    * @param balCandidate
    *   the list that arrived with the block, not yet trusted: the prefetch uses it only if it is the list the header
    *   commits to and fits the block (checked on the pool, off the import thread).
    */
  def start(
      balCandidate: Option[BlockAccessList],
      committedHash: Option[ByteString],
      gasLimit: BigInt,
      transactionCount: Int,
      parentStateRoot: ByteString,
      reader: Option[PrefetchNodeReader],
      evmCodeStorage: EvmCodeStorage,
      ethCompatibleStorage: Boolean,
      keys: Run.NodeKeys
  ): Option[Run] =
    try
      if disabled || !StateReadCacheConfig.balPrefetchEnabled then None
      else
        for
          bal <- balCandidate if bal.accounts.nonEmpty
          r <- reader
        yield
          val run = new Run(
            bal,
            committedHash,
            gasLimit,
            transactionCount,
            parentStateRoot,
            r,
            evmCodeStorage,
            ethCompatibleStorage,
            keys,
            StateReadCacheConfig.balPrefetchBatchSize,
            StateReadCacheConfig.balPrefetchCodeBudgetBytes
          )
          runsStarted.incrementAndGet()
          run.begin()
          run
    catch
      case NonFatal(e) =>
        log.warn(s"BAL prefetch not started: ${e.getClass.getSimpleName}: ${e.getMessage}")
        None

  object Run:
    /** The node hashes the prefetch inserted, shared with the reader's `onLoaded`, for hit attribution. */
    final class NodeKeys:
      val set: java.util.Set[ByteString] = ConcurrentHashMap.newKeySet[ByteString]()
      def add(hash: ByteString): Unit = set.add(hash)

  final class Run private[BalPrefetcher] (
      bal: BlockAccessList,
      committedHash: Option[ByteString],
      gasLimit: BigInt,
      transactionCount: Int,
      parentStateRoot: ByteString,
      reader: PrefetchNodeReader,
      evmCodeStorage: EvmCodeStorage,
      ethCompatibleStorage: Boolean,
      nodeKeys: Run.NodeKeys,
      batchSize: Int,
      codeBudgetBytes: Long
  ):
    @volatile private var cancelled = false
    private var pending = 0
    private val lock = new Object
    private val startNanos = System.nanoTime()
    @volatile private var lastWorkNanos = startNanos

    private val accounts, slots, codes, codeBytes, codesCached, errors = new AtomicLong
    private val codeKeys = ConcurrentHashMap.newKeySet[ByteString]()
    private val seenCodes = ConcurrentHashMap.newKeySet[ByteString]()
    private val codeSpent = new AtomicLong
    @volatile private var rejected = false

    /** The hashes this run brought into the caches, for attributing the block's cache hits to it. */
    val attribution: ImportProfile.PrefetchedKeys = new ImportProfile.PrefetchedKeys:
      override def code(hash: ByteString): Boolean = codeKeys.contains(hash)
      override def node(hash: ByteString): Boolean = nodeKeys.set.contains(hash)

    private def stop: Boolean = cancelled || reader.exhausted || rejected

    private def submit(task: => Unit): Unit =
      lock.synchronized(pending += 1)
      try
        pool.execute { () =>
          try
            if !cancelled then
              try task
              finally lastWorkNanos = System.nanoTime()
          catch case NonFatal(_) => errors.incrementAndGet()
          finally
            lock.synchronized:
              pending -= 1
              if pending == 0 then lock.notifyAll()
        }
      catch
        case _: RejectedExecutionException =>
          rejected = true
          lock.synchronized:
            pending -= 1
            if pending == 0 then lock.notifyAll()

    private[BalPrefetcher] def begin(): Unit = submit(fanOut())

    /** Validates the list, then fans the account batches out. Runs on the pool: hashing a large list stays off the
      * import thread, and a list that does not check out simply prefetches nothing.
      */
    private def fanOut(): Unit =
      val valid =
        committedHash.contains(bal.hash) && bal.validateFor(transactionCount, gasLimit).isRight
      if valid then
        val accountTrie = MerklePatriciaTrie[Address, Account](parentStateRoot.toArray[Byte], reader)(
          Address.hashedAddressEncoder,
          Account.accountSerializer
        )
        // Distinct addresses with the distinct slots listed for each (a slot may appear as read and as changed).
        val wanted =
          scala.collection.mutable.LinkedHashMap.empty[Address, scala.collection.mutable.LinkedHashSet[BigInt]]
        bal.accounts.foreach { a =>
          val set = wanted.getOrElseUpdate(a.address, scala.collection.mutable.LinkedHashSet.empty)
          a.storageReads.foreach(s => set += s.toBigInt)
          a.storageChanges.foreach(c => set += c.slot.toBigInt)
        }
        wanted.toSeq.grouped(batchSize).foreach { batch =>
          submit {
            batch.foreach { case (address, slotSet) =>
              if !stop then readAccount(accountTrie, address, slotSet.toSeq)
            }
          }
        }

    private def readAccount(
        accountTrie: MerklePatriciaTrie[Address, Account],
        address: Address,
        slotKeys: Seq[BigInt]
    ): Unit =
      try
        val account = accountTrie.get(address)
        accounts.incrementAndGet()
        account.foreach { acc =>
          val codeHash = acc.codeHash.value
          if acc.codeHash != Account.EmptyCodeHash && seenCodes.add(codeHash) then submit(readCode(codeHash))
          if slotKeys.nonEmpty && acc.storageRoot != Account.EmptyStorageRootHash then
            val root = acc.storageRoot.value
            slotKeys.grouped(batchSize).foreach(batch => submit(readSlots(root, batch)))
        }
      catch case _: MerklePatriciaTrie.MPTException => errors.incrementAndGet()

    private def readSlots(root: ByteString, keys: Seq[BigInt]): Unit =
      val trie =
        if ethCompatibleStorage then domain.EthereumUInt256Mpt.storageMpt(root, reader)
        else domain.ArbitraryIntegerMpt.storageMpt(root, reader)
      val it = keys.iterator
      while it.hasNext && !stop do
        try
          trie.get(it.next())
          slots.incrementAndGet()
        catch case _: MerklePatriciaTrie.MPTException => errors.incrementAndGet()

    private def readCode(hash: ByteString): Unit =
      if !cancelled then
        evmCodeStorage.prefetchForExecution(hash, length => codeSpent.addAndGet(length.toLong) <= codeBudgetBytes) match
          case EvmCodeStorage.PrefetchOutcome.AlreadyCached => codesCached.incrementAndGet()
          case EvmCodeStorage.PrefetchOutcome.Absent        => errors.incrementAndGet()
          case EvmCodeStorage.PrefetchOutcome.Loaded(length, retained) =>
            codes.incrementAndGet()
            codeBytes.addAndGet(length.toLong)
            if retained then codeKeys.add(hash)

    /** Test hook: waits (up to `timeoutMs`) until every task issued so far has finished, without cancelling. */
    private[ethereum] def awaitIdle(timeoutMs: Long): Boolean =
      val deadline = System.nanoTime() + timeoutMs * 1000000L
      lock.synchronized:
        while pending > 0 && System.nanoTime() < deadline do lock.wait(10L)
        pending == 0

    /** Cancels the work not yet started and waits for the reads in flight, so nothing of this run outlives the block.
      * Reads are single point lookups, so the wait is a few milliseconds; the drain also runs every cancelled task
      * (which returns at once). Never throws.
      */
    def finish(): ImportProfile.PrefetchStats =
      cancelled = true
      lock.synchronized:
        while pending > 0 do lock.wait(50L)
      ImportProfile.PrefetchStats(
        accounts = accounts.get,
        slots = slots.get,
        codes = codes.get,
        codeBytes = codeBytes.get,
        codesCached = codesCached.get,
        nodes = reader.nodesLoaded,
        errors = errors.get,
        stoppedEarly = reader.exhausted || rejected,
        wallNanos = lastWorkNanos - startNanos
      )
