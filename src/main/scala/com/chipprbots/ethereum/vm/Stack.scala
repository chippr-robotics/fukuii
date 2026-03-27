package com.chipprbots.ethereum.vm

import com.chipprbots.ethereum.domain.UInt256

object Stack {

  /** Stack max size as defined in the YP (9.1)
    */
  val DefaultMaxSize = 1024

  def empty(maxSize: Int = DefaultMaxSize): Stack =
    new Stack(maxSize)
}

/** Mutable, array-backed EVM stack. Matches geth/Besu pattern for O(1) push/pop/dup/swap.
  *
  * Operations that exceed stack bounds are no-ops (no exception). Pop returns UInt256.Zero on
  * underflow. This preserves the original Fukuii contract where overflow/underflow are checked
  * by OpCode.execute() before calling exec().
  *
  * Mutating methods (pop, push, dup, swap, set) modify the stack in-place. Gas calculation
  * methods (varGas) must use peek/peekN to avoid corrupting the stack before exec runs.
  */
class Stack private (val maxSize: Int) {
  private val entries: Array[UInt256] = new Array[UInt256](maxSize)
  private var top: Int = -1

  // === Mutating (exec only) ===

  def pop(): UInt256 =
    if (top < 0)
      UInt256.Zero
    else {
      val word = entries(top)
      entries(top) = null // help GC
      top -= 1
      word
    }

  /** Pop n elements from the stack. The first element in the resulting sequence will be the
    * top-most element in the current stack.
    */
  def pop(n: Int): Seq[UInt256] =
    if (top + 1 < n)
      Seq.fill(n)(UInt256.Zero)
    else {
      val result = new Array[UInt256](n)
      var i = 0
      while (i < n) {
        result(i) = entries(top - i)
        entries(top - i) = null // help GC
        i += 1
      }
      top -= n
      result.toSeq
    }

  def push(word: UInt256): Unit = {
    val nextTop = top + 1
    if (nextTop < maxSize) {
      entries(nextTop) = word
      top = nextTop
    }
  }

  /** Push a sequence of elements to the stack. The last element of the sequence will be the
    * top-most element in the resulting stack.
    */
  def push(words: Seq[UInt256]): Unit = {
    if (top + 1 + words.size <= maxSize) {
      words.foreach { w =>
        top += 1
        entries(top) = w
      }
    }
  }

  /** Duplicate i-th element of the stack, pushing it to the top. i=0 is the top-most element.
    */
  def dup(i: Int): Unit = {
    if (i >= 0 && i <= top && top + 1 < maxSize) {
      val value = entries(top - i)
      top += 1
      entries(top) = value
    }
  }

  /** Swap i-th and the top-most elements of the stack. i=0 is the top-most element (and that
    * would be a no-op).
    */
  def swap(i: Int): Unit = {
    val j = top - i
    if (i > 0 && j >= 0) {
      val tmp = entries(top)
      entries(top) = entries(j)
      entries(j) = tmp
    }
  }

  // === In-place mutation (exec hot path — geth pattern) ===

  /** Overwrite the i-th element from the top. i=0 is the top-most element.
    * No-op if out of bounds. Used by exec methods to avoid pop+push overhead
    * on unary/binary ops (matches geth's peek-and-modify pattern).
    */
  def set(i: Int, value: UInt256): Unit =
    if (i >= 0 && i <= top) entries(top - i) = value

  // === Non-mutating (varGas, bounds checks) ===

  /** Read the i-th element from the top without removing it. i=0 is the top-most element.
    * Returns UInt256.Zero if out of bounds.
    */
  def peek(i: Int): UInt256 =
    if (i < 0 || i > top) UInt256.Zero
    else entries(top - i)

  /** Read the top n elements without removing them. First element is top-most.
    * Returns a sequence of UInt256.Zero if insufficient elements.
    */
  def peekN(n: Int): Seq[UInt256] =
    if (top + 1 < n)
      Seq.fill(n)(UInt256.Zero)
    else {
      val result = new Array[UInt256](n)
      var i = 0
      while (i < n) {
        result(i) = entries(top - i)
        i += 1
      }
      result.toSeq
    }

  // === Accessors ===

  def size: Int = top + 1

  /** @return
    *   the elements of the stack as a sequence, with the top-most element of the stack as the
    *   first element in the sequence
    */
  def toSeq: Seq[UInt256] = {
    val result = new Array[UInt256](top + 1)
    var i = 0
    while (i <= top) {
      result(i) = entries(top - i)
      i += 1
    }
    result.toSeq
  }

  override def equals(that: Any): Boolean = that match {
    case that: Stack =>
      this.size == that.size && {
        var i = 0
        var eq = true
        while (i <= top && eq) {
          eq = this.entries(i) == that.entries(i)
          i += 1
        }
        eq
      }
    case _ => false
  }

  override def hashCode(): Int = {
    var h = 1
    var i = 0
    while (i <= top) {
      h = 31 * h + entries(i).hashCode()
      i += 1
    }
    h
  }

  /** Deep copy of this stack. Used by tests to snapshot state before mutable operations. */
  def copy(): Stack = {
    val s = new Stack(maxSize)
    System.arraycopy(entries, 0, s.entries, 0, top + 1)
    s.top = top
    s
  }

  override def toString: String = toSeq.mkString("Stack(", ",", ")")
}
