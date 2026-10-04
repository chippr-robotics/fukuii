package com.chipprbots.ethereum.utils

/** A thread-safe LRU map bounded by an approximate byte budget rather than an entry count: entries are weighed by
  * `weigh` and the least recently used are evicted while the total exceeds `maxBytes`. A value heavier than the whole
  * budget is not stored. `maxBytes <= 0` stores nothing, which is how a cache is switched off.
  *
  * Callers decide what is safe to put in: these caches hold only values that are immutable under their key.
  */
final class ByteBoundedLru[K, V](val maxBytes: Long, weigh: V => Long):

  final private case class Entry(value: V, weight: Long)
  private val map = new java.util.LinkedHashMap[K, Entry](256, 0.75f, true)
  private var used = 0L

  def get(key: K): Option[V] = synchronized {
    val e = map.get(key)
    if e == null then None else Some(e.value)
  }

  /** The value for `key`, or null: no `Option` allocation on the hit path. */
  def getOrNull(key: K): V = synchronized {
    val e = map.get(key)
    if e == null then null.asInstanceOf[V] else e.value
  }

  def put(key: K, value: V): Unit =
    val w = weigh(value)
    if maxBytes > 0 && w <= maxBytes then
      synchronized {
        val old = map.put(key, Entry(value, w))
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

  def sizeBytes: Long = synchronized(used)
  def entries: Int = synchronized(map.size)
