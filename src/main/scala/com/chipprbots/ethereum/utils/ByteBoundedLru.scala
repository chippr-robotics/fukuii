package com.chipprbots.ethereum.utils

import java.lang.ref.ReferenceQueue
import java.lang.ref.SoftReference

/** A thread-safe LRU map bounded by an approximate byte budget, whose values the garbage collector may reclaim.
  *
  * Entries are weighed by `weigh` and the least recently used are evicted while the total exceeds `maxBytes`; a value
  * heavier than the whole budget is not stored; `maxBytes <= 0` stores nothing, which is how a cache is switched off.
  *
  * Values are held through [[SoftReference]]s. The byte budget alone is only as good as the weights, and a cache that
  * is allowed to fill a fixed number of bytes is a heap commitment the JVM cannot take back: it is what ran the EEST
  * payload-builder corpus out of heap in CI while the caches sat full of other tests' nodes. A soft reference is
  * cleared before the JVM throws `OutOfMemoryError`, so under heap pressure these caches shrink instead of failing the
  * process, and they stay bounded by the budget when there is room. A cleared entry reads as a miss, is dropped from
  * the map and returns its weight to the budget.
  *
  * `strongValues = true` holds the values strongly instead, still bounded by the byte budget: for a cache whose size
  * the operator set explicitly, the collector must not empty it during heavy blocks, and the operator took on the heap
  * commitment.
  *
  * Callers decide what is safe to put in: these caches hold only values that are immutable under their key.
  */
final class ByteBoundedLru[K, V](val maxBytes: Long, weigh: V => Long, val strongValues: Boolean = false):

  /** In soft mode the value lives only behind the [[SoftReference]]; in strong mode (`strongValues`: an operator-sized
    * cache the collector must not shrink) it is held by `strong` and the reference is empty and never enqueued.
    */
  final private class Entry(val key: K, value: V, val weight: Long, queue: ReferenceQueue[V])
      extends SoftReference[V](if strongValues then null.asInstanceOf[V] else value, queue):
    val strong: V = if strongValues then value else null.asInstanceOf[V]
    def held(): V = if strongValues then strong else this.get()

  private val queue = new ReferenceQueue[V]
  private val map = new java.util.LinkedHashMap[K, Entry](256, 0.75f, true)
  private var used = 0L

  /** Drops the entries the collector has cleared. Caller holds the lock. */
  private def reap(): Unit =
    var cleared = queue.poll()
    while cleared != null do
      val e = cleared.asInstanceOf[Entry]
      // A newer entry may have replaced it under the same key; only remove our own.
      if map.get(e.key) eq e then
        map.remove(e.key)
        used -= e.weight
      cleared = queue.poll()

  def get(key: K): Option[V] = Option(getOrNull(key))

  /** The value for `key`, or null (absent, evicted, or reclaimed by the collector; strong caches are never reclaimed).
    */
  def getOrNull(key: K): V = synchronized {
    val e = map.get(key)
    if e == null then null.asInstanceOf[V]
    else
      val v = e.held()
      if v == null then
        map.remove(key)
        used -= e.weight
      v
  }

  def put(key: K, value: V): Unit =
    val w = weigh(value)
    if maxBytes > 0 && w <= maxBytes then
      synchronized {
        reap()
        val old = map.put(key, new Entry(key, value, w, queue))
        if old != null then used -= old.weight
        used += w
        val it = map.entrySet.iterator
        while used > maxBytes && it.hasNext do
          val eldest = it.next()
          used -= eldest.getValue.weight
          it.remove()
      }

  def remove(key: K): Unit = synchronized {
    val old = map.remove(key)
    if old != null then used -= old.weight
  }

  def clear(): Unit = synchronized {
    map.clear()
    used = 0L
  }

  def sizeBytes: Long = synchronized {
    reap()
    used
  }

  def entries: Int = synchronized {
    reap()
    map.size
  }
