package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.UInt256

object Memory:

  def empty: Memory = new Memory(ByteString(), 0)

  /** One page of zeros, shared by every zero run a load returns — process-wide, so it must never be written.
    *
    * The backing array is one byte longer than the page, so no view handed out is the whole array: Pekko's
    * `toArrayUnsafe()` and `compact` return the backing array itself only for a full-length ByteString, and a view of
    * exactly `ZeroPageSize` bytes would otherwise be one. Every other ByteString accessor copies (`toArray`,
    * `copyToArray`) or is read-only (`asByteBuffer`).
    */
  private val ZeroPageSize = 1 << 20
  private val zeroPage: ByteString =
    ByteString.fromArrayUnsafe(new Array[Byte](ZeroPageSize + 1), 0, ZeroPageSize)

  /** `size` zero bytes as views of the shared page: no byte array is allocated, whatever the size.
    *
    * Why: a CALL's input is a load of the caller's memory, and it stays reachable for as long as the callee runs.
    * ethereum/tests `static_Call1MB1024Calldepth` (d1) passes 1,000,000 bytes of never-written memory down every level
    * of a ~550-deep STATICCALL recursion; materialising each input kept ~550 MB live and killed hive's `-Xmx512m`
    * client with `OutOfMemoryError` at chain import. The content is the same zero bytes either way.
    */
  private def zeros(size: Int): ByteString =
    if size <= ZeroPageSize then zeroPage.take(size)
    else
      val pages = ByteString.newBuilder
      var left = size
      while left > 0 do
        val n = math.min(left, ZeroPageSize)
        pages ++= zeroPage.take(n)
        left -= n
      pages.result()

/** Volatile memory with 256 bit address space. Every mutating operation on a Memory returns a new updated copy of it.
  *
  * Memory is `size` bytes long, but only `underlying` is materialised: every byte in `[underlying.length, size)` is
  * zero and is never allocated until something is WRITTEN there. Expansion (a read past the end, `expand` for CALL
  * output regions, MSIZE growth) therefore costs O(1) heap, while its gas is charged exactly as before from `size`.
  *
  * Why: YP/core-geth charge memory expansion quadratically, but with a large enough gas limit a transaction can
  * legitimately pay for a gigabyte-scale region. ethereum/tests `randomStatetest94` does exactly that — SHA3 over ~1.5
  * GB of never-written memory, paid for and expected to SUCCEED (post-state stores the hash) — and the eager
  * representation, which zero-filled, copied and re-copied that region, died with OutOfMemoryError at chain import.
  * Contents, MSIZE and gas are identical to the eager representation; only the heap cost of untouched zeros changes.
  *
  * Related reading:
  * https://solidity.readthedocs.io/en/latest/frequently-asked-questions.html#what-is-the-memory-keyword-what-does-it-do
  * https://github.com/ethereum/go-ethereum/blob/master/core/vm/memory.go
  */
class Memory private (private val underlying: ByteString, val size: Int):

  import Memory.zeros

  def store(offset: UInt256, b: Byte): Memory = store(offset, ByteString(b))

  def store(offset: UInt256, uint: UInt256): Memory = store(offset, uint.bytes)

  def store(offset: UInt256, bytes: Array[Byte]): Memory = store(offset, ByteString(bytes))

  /** Stores data at the given offset. The memory is automatically expanded to accommodate new data - filling empty
    * regions with zeroes if necessary. Writing materialises memory up to the end of the write.
    */
  def store(offset: UInt256, data: ByteString): Memory =
    if data.isEmpty then this
    else
      val idx: Int = offset.toInt
      val currentLength = underlying.length
      val dataLength = data.length
      val newMaxLength = idx + dataLength

      val newLen = math.max(newMaxLength, currentLength)

      // newLen is at least as long as current length; the gap (if any) stays zero.
      val newData = new Array[Byte](newLen)
      underlying.copyToArray(newData, 0, currentLength)
      data.copyToArray(newData, idx, dataLength)

      // It is safe to call unsafe as newData array won't be modified.
      new Memory(ByteString.fromArrayUnsafe(newData), math.max(size, newMaxLength))

  def load(offset: UInt256): (UInt256, Memory) =
    doLoad(offset, UInt256.Size) match
      case (bs, memory) => (UInt256(bs), memory)

  def load(offset: UInt256, size: UInt256): (ByteString, Memory) = doLoad(offset, size.toInt)

  /** The region `[offset, offset + size)` WITHOUT materialising its unwritten tail: the bytes that are stored, followed
    * by a count of zero bytes. Expands memory exactly as [[load]] does. For consumers that can stream the region (SHA3)
    * rather than hold it.
    */
  def loadZeroPadded(offset: UInt256, size: UInt256): (ByteString, Int, Memory) =
    val n = size.toInt
    if n <= 0 then (ByteString.empty, 0, this)
    else
      val start: Int = offset.toInt
      val end: Int = start + n
      val stored = underlying.length
      val prefix = if start >= stored then ByteString.empty else underlying.slice(start, math.min(end, stored))
      (prefix, n - prefix.length, new Memory(underlying, math.max(this.size, end)))

  /** Returns a ByteString of a given size starting at the given offset of the Memory. The memory is automatically
    * expanded when reading previously uninitialised regions. Nothing is copied: the stored part is a slice of
    * `underlying` (never written after construction — `store` builds a new array), and the unwritten part is a view of
    * the shared zero page.
    */
  private def doLoad(offset: UInt256, size: Int): (ByteString, Memory) =
    if size <= 0 then (ByteString.empty, this)
    else
      val start: Int = offset.toInt
      val end: Int = start + size
      val stored = underlying.length
      val bytes =
        if end <= stored then underlying.slice(start, end)
        else if start >= stored then zeros(size)
        else underlying.slice(start, stored) ++ zeros(end - stored)
      (bytes, new Memory(underlying, math.max(this.size, end)))

  /** This function will expand the Memory size as if storing data given the `offset` and `size`. If the memory is
    * already initialised at that region it will not be modified, otherwise it reads as zeroes. This is required to
    * satisfy memory expansion semantics for *CALL* opcodes.
    */
  def expand(offset: UInt256, size: UInt256): Memory =
    val totalSize = (offset + size).toInt
    if this.size >= totalSize || size.isZero then this
    else new Memory(underlying, totalSize)

  /** Equality is on CONTENT (size and bytes), not on how much of it happens to be materialised. */
  override def equals(that: Any): Boolean = // §3h: FORGE-confirmed — java.lang.Object.equals signature is fixed by JVM
    that match
      case that: Memory => this.size == that.size && this.trimmed == that.trimmed
      case _            => false

  override def hashCode: Int = 31 * size + trimmed.hashCode()

  // Never materialises the zero tail: a memory this class can represent cheaply must not become an OOM when logged.
  override def toString: String =
    val tail = size - underlying.length
    s"${this.getClass.getSimpleName}(${Hex
        .toHexString(underlying.toArray[Byte])}${if tail > 0 then s" ++ $tail zero bytes" else ""})"

  // The stored bytes without trailing zeros: equal for any two materialisations of the same content.
  private def trimmed: ByteString =
    var end = underlying.length
    while end > 0 && underlying(end - 1) == 0 do end -= 1
    underlying.take(end)
