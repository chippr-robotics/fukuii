package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import scala.collection.immutable.BitSet

import com.chipprbots.ethereum.utils.StateReadCacheConfig

/** The valid-JUMPDEST analysis of a contract's code (Yellow Paper section 9.4.3), and a bounded cache of it.
  *
  * A position is a valid jump destination when it holds JUMPDEST (0x5b) and is not inside the data of a PUSH1..PUSH32
  * (0x60..0x7f). That is the only thing the analysis decides; it is a pure function of the code bytes, so caching it by
  * anything that determines the code (its hash) cannot change execution.
  */
object JumpDestAnalysis:

  private val JumpDest = 0x5b
  private val Push1 = 0x60
  private val Push32 = 0x7f

  /** The bit set of valid destinations in `code`: bit `pos` is set when `code(pos)` is a JUMPDEST outside PUSH data.
    *
    * A tight loop over the raw bytes: no per-byte `ByteString.apply`, no `Option[OpCode]` lookup, no type test. The old
    * scan did exactly this decision through `FrontierOpCodes.opCodeFor`, whose PUSH width is `pushOp.i + 2` (PUSH1 has
    * `i == 0`), i.e. `byte - 0x5e` here. A PUSH whose data runs past the end of the code just ends the scan.
    */
  def analyse(code: ByteString): BitSet =
    val length = code.length
    val bits = new Array[Long]((length + 63) >>> 6)
    // Read-only use: `toArrayUnsafe` hands back the backing array of a plain ByteString without copying 64 KB.
    val bytes = code.toArrayUnsafe()
    var pos = 0
    while pos < length do
      val b = bytes(pos) & 0xff
      if b == JumpDest then
        bits(pos >>> 6) |= 1L << pos
        pos += 1
      else if b >= Push1 && b <= Push32 then pos += b - 0x5e
      else pos += 1
    BitSet.fromBitMaskNoCopy(bits) // `bits` never escapes otherwise

  /** Process-wide cache of analyses by code hash, bounded by an approximate byte budget (LRU).
    *
    * Keyed by the code hash the world state holds for the account, never by something computed from the code (hashing
    * 64 KB costs more than the scan it would save). Code with no known hash (init code, code that is not yet persisted)
    * is analysed directly and never enters the cache: it is used once, and caching it would only evict useful entries.
    */
  final class Cache(maxBytes: Long):
    private val entryOverhead = 96L // BitSet wrapper, array header, map entry, key reference
    private var usedBytes = 0L
    final private case class Entry(bits: BitSet, weight: Long)
    private val map = new java.util.LinkedHashMap[ByteString, Entry](256, 0.75f, true)

    private def weight(codeLength: Int): Long = ((codeLength + 63) >>> 6) * 8L + entryOverhead

    /** Analysis of `code`, from the cache when `codeHash` was analysed before. */
    def getOrCompute(codeHash: ByteString, code: ByteString): BitSet =
      if maxBytes <= 0 then ImportProfile.scan(code.length)(analyse(code))
      else
        val cached = synchronized(map.get(codeHash))
        if cached != null then
          ImportProfile.scanHit()
          cached.bits
        else
          val computed = ImportProfile.scan(code.length)(analyse(code))
          val w = weight(code.length)
          if w <= maxBytes then
            synchronized {
              if !map.containsKey(codeHash) then
                map.put(codeHash, Entry(computed, w))
                usedBytes += w
                val it = map.entrySet.iterator
                while usedBytes > maxBytes && it.hasNext do
                  val eldest = it.next()
                  if eldest.getKey != codeHash then
                    usedBytes -= eldest.getValue.weight
                    it.remove()
            }
          computed

    def sizeBytes: Long = synchronized(usedBytes)
    def entries: Int = synchronized(map.size)

  lazy val shared: Cache = new Cache(StateReadCacheConfig.jumpDestCacheBytes)
