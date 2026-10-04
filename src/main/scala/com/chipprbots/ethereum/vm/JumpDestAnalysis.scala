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
    // Truncate at the last word holding a JUMPDEST. A position past the last valid destination can never be one, and
    // `BitSet.contains` answers false for any word beyond the array, so the answers are bit-identical to the full scan
    // while a padded contract (a 64 KB synthetic one is mostly zeros) shrinks from 8 KiB to a few words.
    var words = bits.length
    while words > 0 && bits(words - 1) == 0L do words -= 1
    BitSet.fromBitMaskNoCopy(if words == bits.length then bits else java.util.Arrays.copyOf(bits, words))

  /** Words an analysis occupies (`nwords` is not public): up to and including the one holding the last JUMPDEST. */
  def words(bits: BitSet): Int = bits.lastOption.fold(0)(last => (last >>> 6) + 1)

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

    private def weight(bits: BitSet): Long = words(bits) * 8L + entryOverhead

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
          val w = weight(computed)
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

  /** The analyses one block's execution has already needed, in front of [[Cache]]: a strong-reference map by code hash
    * that lives exactly as long as the world that owns it (one block's execution; `WorldReadMemos`), so it is dropped
    * with the block and cannot be shared between blocks, worlds, `eth_call`s or traces (each builds its own world).
    *
    * Why it exists: a heavy block makes 100k+ scans of a few thousand distinct contracts, cycling through more code
    * than the LRU holds, so the LRU sees almost no hits; within one block each distinct code is analysed once. This is
    * the scope of go-ethereum's `Contract.jumpdests` map (one top-level call tree there, one block here).
    *
    * Consensus-neutral for the same reason the LRU is: the value is a pure function of the code, the key is the hash
    * the world state holds for exactly that code. Entries are the analyses themselves, truncated at the last JUMPDEST
    * (see [[analyse]]), never code bytes. Once `maxBytes` of entries are held, further analyses are simply not
    * remembered here and come from the LRU or a fresh scan, as without the memo.
    */
  final class BlockMemo(maxBytes: Long, fallback: Cache):
    private val entryOverhead = 96L
    private val map = new java.util.concurrent.ConcurrentHashMap[ByteString, BitSet]
    private val used = new java.util.concurrent.atomic.AtomicLong

    def getOrCompute(codeHash: ByteString, code: ByteString): BitSet =
      val known = map.get(codeHash)
      if known != null then
        ImportProfile.jumpMemoHit()
        known
      else
        ImportProfile.jumpMemoMiss()
        val bits = fallback.getOrCompute(codeHash, code)
        val w = words(bits) * 8L + entryOverhead
        if used.addAndGet(w) <= maxBytes && map.putIfAbsent(codeHash, bits) == null then ()
        else used.addAndGet(-w)
        bits

    def sizeBytes: Long = used.get
    def entries: Int = map.size

  object BlockMemo:
    /** A memo for one block, over the process-wide LRU; `None` when the configured budget is 0 (memo off). */
    def forBlock(): Option[BlockMemo] =
      val budget = StateReadCacheConfig.jumpDestBlockMemoBytes
      Option.when(budget > 0)(new BlockMemo(budget, shared))

  lazy val shared: Cache = new Cache(StateReadCacheConfig.jumpDestCacheBytes)
