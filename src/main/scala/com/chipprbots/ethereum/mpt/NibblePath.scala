package com.chipprbots.ethereum.mpt

import java.util.Arrays

import org.apache.pekko.util.ByteString

/** The position of a node inside one trie: the nibbles walked from the trie's root to reach it, one nibble (0x0 to 0xf)
  * per byte. The root sits at [[NibblePath.Empty]]; child `i` of a branch at `p` sits at `p :+ i`; the node an
  * extension at `p` points to sits at `p ++ sharedKey`. A leaf's or an extension's own key is not part of its position
  * (the rule `StackTrie` applies when it reports a node).
  *
  * It lives in `mpt/`, not `db/`, so the trie and anything built on it alone gain no storage import (spec 018 DC-8).
  *
  * Every operation returns a fresh array and none hands out the backing one, so a path never changes once made.
  *
  * '''Equality.''' The type is an array underneath, so `==` and `hashCode` are by identity and a path must never be a
  * `Map` key or a `Set` member as it is. Compare with [[NibblePath.sameAs]]; key a collection by [[NibblePath.key]].
  */
opaque type NibblePath = Array[Byte]

object NibblePath:

  /** The position of a trie's root. */
  val Empty: NibblePath = Array.emptyByteArray

  /** A path from nibbles, one per byte. The array is copied. */
  def fromNibbles(nibbles: Array[Byte]): NibblePath =
    requireNibbles(nibbles, 0, nibbles.length)
    Arrays.copyOf(nibbles, nibbles.length)

  def apply(nibbles: Int*): NibblePath = fromNibbles(nibbles.map(_.toByte).toArray)

  /** The path a store key names: the inverse of [[toHexPrefix]]. A key with the leaf flag set is not a position. */
  def fromHexPrefix(key: Array[Byte]): NibblePath =
    require(key.nonEmpty, "a hex-prefix key has at least its flag byte")
    val flag = (key(0) >> 4) & 0xf
    require(flag == 0 || flag == 1, s"not a position key: flag nibble $flag (2 and 3 mark a leaf)")
    require(flag == 1 || (key(0) & 0xf) == 0, "the padding nibble of an even-length key is zero")
    HexPrefix.decode(key)._1

  /** No copy and no check: for nibble arrays the trie built itself and never mutates. */
  private[mpt] def wrap(nibbles: Array[Byte]): NibblePath = nibbles

  /** Trie order: nibble by nibble, a path before every path it is a prefix of. A pre-order walk meets positions in this
    * order.
    */
  given trieOrdering: Ordering[NibblePath] = (a, b) => Arrays.compare(a, b)

  /** Store order: by the bytes of [[toHexPrefix]], unsigned. This is the order of keys in a position-keyed column
    * family, and it is '''not''' trie order: `[a]` is `0x1a` and its child `[a, b]` is `0x00ab`, which sorts first.
    */
  val storeOrdering: Ordering[NibblePath] = (a, b) =>
    Arrays.compareUnsigned(HexPrefix.encode(a, isLeaf = false), HexPrefix.encode(b, isLeaf = false))

  private def requireNibbles(bytes: Array[Byte], from: Int, until: Int): Unit =
    var i = from
    while i < until do
      if (bytes(i) & 0xf0) != 0 then
        throw new IllegalArgumentException(s"not a nibble: ${bytes(i) & 0xff} at index ${i - from}")
      i += 1

  extension (path: NibblePath)

    def length: Int = path.length

    def isEmpty: Boolean = path.length == 0

    /** The nibble at `index`. */
    def apply(index: Int): Byte = path(index)

    /** The position of child `nibble` of a branch at this position. */
    def :+(nibble: Int): NibblePath =
      if (nibble & ~0xf) != 0 then throw new IllegalArgumentException(s"not a nibble: $nibble")
      val child = Arrays.copyOf(path, path.length + 1)
      child(path.length) = nibble.toByte
      child

    /** The position of the node an extension at this position points to, `sharedKey` being the extension's nibbles. */
    def ++(sharedKey: ByteString): NibblePath =
      if sharedKey.isEmpty then path
      else
        val next = Arrays.copyOf(path, path.length + sharedKey.length)
        sharedKey.copyToArray(next, path.length, sharedKey.length)
        requireNibbles(next, path.length, next.length)
        next

    def ++(other: NibblePath): NibblePath =
      if other.length == 0 then path
      else if path.length == 0 then other
      else
        val joined = Arrays.copyOf(path, path.length + other.length)
        System.arraycopy(other, 0, joined, path.length, other.length)
        joined

    /** True when `prefix` is this position or one above it on the way to the root. */
    def startsWith(prefix: NibblePath): Boolean =
      prefix.length <= path.length && Arrays.equals(path, 0, prefix.length, prefix, 0, prefix.length)

    /** Equality by content. `==` on two paths compares identity. */
    def sameAs(other: NibblePath): Boolean = Arrays.equals(path, other)

    /** The nibbles, one per byte, as a copy. */
    def toNibbles: Array[Byte] = Arrays.copyOf(path, path.length)

    /** The key of this position in a position-keyed store: `HexPrefix.encode(nibbles, isLeaf = false)`. The empty path
      * is `0x00`, `[a]` is `0x1a`, `[a, b]` is `0x00ab`, `[a, b, c]` is `0x1abc`.
      */
    def toHexPrefix: Array[Byte] = HexPrefix.encode(path, isLeaf = false)

    /** [[toHexPrefix]] as a value with equality by content: what a `Map` or a `Set` of positions is keyed by. */
    def key: ByteString = ByteString.fromArrayUnsafe(HexPrefix.encode(path, isLeaf = false))

    /** The nibbles as hex digits, for logs and failure messages: `[a, b, c]` is `"abc"`, the root is `""`. */
    def show: String =
      val digits = new Array[Char](path.length)
      var i = 0
      while i < path.length do
        digits(i) = Character.forDigit(path(i) & 0xf, 16)
        i += 1
      new String(digits)
