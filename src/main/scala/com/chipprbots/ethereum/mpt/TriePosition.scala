package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

/** Where a node sits, as a step from its parent's position: the nibbles are put together only when asked for.
  *
  * A collapse hands a position to every child it might walk into, sixteen for each branch, and asks for the nibbles of
  * the few that turn out to be new and stored. Building a [[NibblePath]] for each child would copy an array per child
  * for nothing.
  *
  * Not thread-safe: a collapse runs on one thread.
  */
final class TriePosition private (
    parent: TriePosition | Null,
    nibble: Int,
    sharedKey: ByteString | Null,
    private var known: NibblePath | Null
):

  private var childrenHandedOut = 0

  def path: NibblePath =
    val cached = known
    if cached != null then cached.nn
    else
      val above = parent.nn.path
      val built = if sharedKey != null then above ++ sharedKey else above :+ nibble
      known = built
      built

  /** The position of the next child of a branch here: child 0 on the first call, then 1, up to 15. The traversal asks
    * for a branch's children in that order, once each (`MptTraversals.dispatch`).
    */
  def nextChild(): TriePosition =
    val index = childrenHandedOut
    childrenHandedOut = index + 1
    new TriePosition(this, index, null, null)

  /** The position of the node an extension here points to. */
  def next(extensionKey: ByteString): TriePosition = new TriePosition(this, -1, extensionKey, null)

object TriePosition:
  def root(path: NibblePath): TriePosition = new TriePosition(null, -1, null, path)
