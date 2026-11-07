package com.chipprbots.ethereum.testing

/** A `Seq` that fails the test on any random access (`apply`, and so `lift`, `isDefinedAt`-then-`apply` and
  * `lastOption` on a non-indexed path). A guard for code that receives a decoded list: until #1675 the RLP decoder
  * built lists as immutable `Queue`s, where `items(i)` is O(i), so code that indexes a received list per element is
  * quadratic in a peer-controlled length. Code that walks the list sequentially, or copies it to an indexed collection
  * first (which goes through `iterator`), passes.
  */
final class NoRandomAccessSeq[A](underlying: Seq[A]) extends scala.collection.immutable.Seq[A]:
  def apply(i: Int): A = throw new AssertionError(s"random access at index $i into a sequential-only Seq")
  def length: Int = underlying.length
  def iterator: Iterator[A] = underlying.iterator

object NoRandomAccessSeq:
  def apply[A](items: A*): NoRandomAccessSeq[A] = new NoRandomAccessSeq(items.toVector)
  def from[A](items: Iterable[A]): NoRandomAccessSeq[A] = new NoRandomAccessSeq(items.toVector)
