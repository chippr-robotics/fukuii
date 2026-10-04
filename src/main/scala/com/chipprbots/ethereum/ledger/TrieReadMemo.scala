package com.chipprbots.ethereum.ledger

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.utils.StateReadCacheConfig
import com.chipprbots.ethereum.vm.ImportProfile

/** The entries one world (one block's execution) may spend on [[TrieReadMemo]]s, summed over all of them. Once spent,
  * reads simply stop being memoised: nothing is evicted and nothing is wrong, the world just reads the trie again.
  */
final class ReadMemoBudget(entries: Int):
  private val remaining = new AtomicInteger(entries)
  def tryTake(): Boolean = remaining.getAndDecrement() > 0

object ReadMemoBudget:
  def fromConfig(): ReadMemoBudget = new ReadMemoBudget(StateReadCacheConfig.worldReadMemoEntries)

/** Memo of the answers a trie gave, for one immutable trie (one root).
  *
  * A trie value is immutable: the same trie answers the same key the same way forever, a missing key included. So an
  * answer, `None` too, can be kept for the life of that trie and never needs invalidating. What it must not do is cross
  * to another trie: [[InMemorySimpleMapProxy]] makes a new memo whenever its trie changes (`persist`), and shares this
  * one across the copies that differ only in their uncommitted overlay (`put`, `remove`, `rollback`), which read the
  * same base trie. A read that throws (a missing node) is never stored.
  */
final class TrieReadMemo[K, V](budget: ReadMemoBudget):
  private val answers = new ConcurrentHashMap[K, Option[V]]

  /** An empty memo for a different trie, drawing on the same budget. */
  def successor: TrieReadMemo[K, V] = new TrieReadMemo[K, V](budget)

  def getOrLoad(key: K)(load: => Option[V]): Option[V] =
    val known = answers.get(key)
    if known != null then
      ImportProfile.memoHit()
      known
    else
      val loaded = load
      if budget.tryTake() then answers.putIfAbsent(key, loaded)
      loaded

/** The read memos of one world: the account trie's (made by the world) and one per storage-trie root, shared by every
  * copy of the world, which is what lets a read-only SLOAD loop stop walking its storage trie. A storage memo is keyed
  * by the trie's ROOT, so an account that is deleted and recreated, or whose storage was committed to a new root, can
  * never see another trie's answers.
  */
final class WorldReadMemos(val budget: ReadMemoBudget = ReadMemoBudget.fromConfig()):
  private val maxStorageMemos = 16384
  private val storage = new ConcurrentHashMap[ByteString, TrieReadMemo[BigInt, BigInt]]

  def accounts[K, V]: TrieReadMemo[K, V] = new TrieReadMemo[K, V](budget)

  def storageFor(root: ByteString): TrieReadMemo[BigInt, BigInt] =
    val known = storage.get(root)
    if known != null then known
    else if storage.size < maxStorageMemos then
      val fresh = new TrieReadMemo[BigInt, BigInt](budget)
      val raced = storage.putIfAbsent(root, fresh)
      if raced != null then raced else fresh
    else new TrieReadMemo[BigInt, BigInt](budget)
