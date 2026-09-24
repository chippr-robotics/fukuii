package com.chipprbots.ethereum.vm

import com.chipprbots.ethereum.domain.UInt256

object Stack:

  /** Stack max size as defined in the YP (9.1)
    */
  val DefaultMaxSize = 1024

  def empty(maxSize: Int = DefaultMaxSize): Stack =
    new Stack(Nil, 0, maxSize)

/** Stack for the EVM. Instruction pop their arguments from it and push their results to it. The Stack doesn't handle
  * overflow and underflow errors. Any operations that trascend given stack bounds will return the stack unchanged. Pop
  * will always return zeroes in such case.
  *
  * Held as an immutable list with the top of the stack at its head, and its length alongside, so that no operation
  * walks the list to size it. Every instruction pushes or pops, and on a list that is one cell allocated or none; DUP
  * and SWAP reach at most 16 deep. (It was a Vector, top at the end: each push or pop copied the whole backing array.)
  */
class Stack private (private val underlying: List[UInt256], val size: Int, val maxSize: Int):

  def pop(): (UInt256, Stack) = underlying match
    case word :: rest => (word, new Stack(rest, size - 1, maxSize))
    case Nil          => (UInt256.Zero, this)

  /** Pop n elements from the stack. The first element in the resulting sequence will be the top-most element in the
    * current stack
    */
  def pop(n: Int): (Seq[UInt256], Stack) =
    if n >= 0 && n <= size then (underlying.take(n), new Stack(underlying.drop(n), size - n, maxSize))
    else (Seq.fill(n)(UInt256.Zero), this)

  def push(word: UInt256): Stack =
    if size < maxSize then new Stack(word :: underlying, size + 1, maxSize)
    else this

  /** Push a sequence of elements to the stack. That last element of the sequence will be the top-most element in the
    * resulting stack
    */
  def push(words: Seq[UInt256]): Stack =
    val n = words.length
    if size + n > maxSize then this
    else new Stack(words.foldLeft(underlying)((stack, word) => word :: stack), size + n, maxSize)

  /** Duplicate i-th element of the stack, pushing it to the top. i=0 is the top-most element.
    */
  def dup(i: Int): Stack =
    if i < 0 || i >= size || size >= maxSize then this
    else new Stack(underlying(i) :: underlying, size + 1, maxSize)

  /** Swap i-th and the top-most elements of the stack. i=0 is the top-most element (and that would be a no-op)
    */
  def swap(i: Int): Stack =
    if i <= 0 || i >= size then this
    else
      val top = underlying.head
      // The elements from the top down to the i-th are rebuilt with the two ends exchanged; below that the list is
      // shared. `rest` starts at depth k.
      def exchange(rest: List[UInt256], k: Int): List[UInt256] =
        if k == i then top :: rest.tail
        else rest.head :: exchange(rest.tail, k + 1)
      new Stack(underlying(i) :: exchange(underlying.tail, 1), size, maxSize)

  /** @return
    *   the elements of the stack as a sequence, with the top-most element of the stack as the first element in the
    *   sequence
    */
  def toSeq: Seq[UInt256] = underlying

  override def equals(that: Any): Boolean =
    that match // §3h: FORGE-confirmed — java.lang.Object.equals signature is fixed by JVM
      case that: Stack => this.underlying == that.underlying
      case _           => false

  // Bottom-first, as the Vector this replaced was held: the same value as before for the same elements.
  override def hashCode(): Int = underlying.reverse.hashCode

  override def toString: String =
    underlying.mkString("Stack(", ",", ")")
