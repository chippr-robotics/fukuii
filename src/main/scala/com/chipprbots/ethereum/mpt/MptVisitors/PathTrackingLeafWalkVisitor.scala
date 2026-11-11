package com.chipprbots.ethereum.mpt.MptVisitors

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt.*

/** Visitor that walks an MPT and invokes a callback for each leaf node, providing the full key path (nibbles
  * concatenated from root to leaf).
  *
  * In the Ethereum state trie, the full path equals keccak256(address) in nibble form. Converting nibbles back to bytes
  * gives the 32-byte account hash needed by StorageTask.
  *
  * Used by the recovery scans (Bug 20 hardening) to find missing contract storage tries and bytecodes.
  *
  * It reads each node at the path it has tracked to it (spec 018 Track C): `storage` is the storage of the ONE trie
  * being walked (`forOwner`), and `pathPrefix` the nibbles from that trie's root, also when the walk starts at a
  * subtree. A store keyed by hash ignores the path.
  *
  * @param onHole
  *   what to do with a node the store does not hold, given the nibble path to it. `None` rethrows, which ends the walk,
  *   as `StorageRecoveryActor` expects. `Some` reports it and goes on over its siblings (spec 022 T050a). Only an
  *   absent node: one that does not decode, or a store that fails, ends the walk either way.
  */
class PathTrackingLeafWalkVisitor(
    storage: MptStorage,
    pathPrefix: ByteString,
    onLeaf: (ByteString, LeafNode) => Unit,
    onHole: Option[ByteString => Unit] = None
) extends MptVisitor[Unit]:

  override def visitLeaf(leaf: LeafNode): Unit =
    val fullNibblePath = pathPrefix ++ leaf.key
    // Convert nibbles to bytes: every 2 nibbles = 1 byte
    val bytes = new Array[Byte](fullNibblePath.length / 2)
    var i = 0
    while i < bytes.length do
      val hi = fullNibblePath(i * 2) & 0xff
      val lo = fullNibblePath(i * 2 + 1) & 0xff
      bytes(i) = ((hi << 4) | lo).toByte
      i += 1
    onLeaf(ByteString(bytes), leaf)

  override def visitHash(hashNode: HashNode): HashNodeResult[Unit] =
    def here = storage.getAt(NibblePath.fromNibbles(pathPrefix.toArray), hashNode.hash)
    onHole match
      case None => ResolveResult(here)
      case Some(report) =>
        try ResolveResult(here)
        catch
          // A node that does not decode reads as a CorruptNodeException, a missing node for execution's sake. Here it
          // still ends the walk: only an absent node is a hole (spec 022 T062).
          case e: MerklePatriciaTrie.CorruptNodeException => throw e
          case _: MerklePatriciaTrie.MissingNodeException =>
            report(pathPrefix)
            Result(())

  override def visitNull(): Unit = ()

  override def visitExtension(extension: ExtensionNode): ExtensionVisitor[Unit] =
    new PathTrackingExtensionVisitor(storage, pathPrefix, extension.sharedKey, onLeaf, onHole)

  override def visitBranch(branch: BranchNode): BranchVisitor[Unit] =
    new PathTrackingBranchVisitor(storage, pathPrefix, onLeaf, onHole)

private class PathTrackingBranchVisitor(
    storage: MptStorage,
    pathPrefix: ByteString,
    onLeaf: (ByteString, LeafNode) => Unit,
    onHole: Option[ByteString => Unit]
) extends BranchVisitor[Unit]:
  private var childIndex: Int = 0

  override def visitChild(): MptVisitor[Unit] =
    val idx = childIndex
    childIndex += 1
    new PathTrackingLeafWalkVisitor(storage, pathPrefix ++ ByteString(idx.toByte), onLeaf, onHole)

  override def visitChild(child: => Unit): Unit = child // force evaluation
  override def visitTerminator(term: Option[ByteString]): Unit = ()
  override def done(): Unit = ()

private class PathTrackingExtensionVisitor(
    storage: MptStorage,
    pathPrefix: ByteString,
    sharedKey: ByteString,
    onLeaf: (ByteString, LeafNode) => Unit,
    onHole: Option[ByteString => Unit]
) extends ExtensionVisitor[Unit]:
  override def visitNext(): MptVisitor[Unit] =
    new PathTrackingLeafWalkVisitor(storage, pathPrefix ++ sharedKey, onLeaf, onHole)

  override def visitNext(value: => Unit): Unit = value // force evaluation
  override def done(): Unit = ()
