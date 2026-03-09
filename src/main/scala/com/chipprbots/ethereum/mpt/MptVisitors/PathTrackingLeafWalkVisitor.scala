package com.chipprbots.ethereum.mpt.MptVisitors

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt._

/** Visitor that walks an MPT and invokes a callback for each leaf node,
  * providing the full key path (nibbles concatenated from root to leaf).
  *
  * In the Ethereum state trie, the full path equals keccak256(address)
  * in nibble form. Converting nibbles back to bytes gives the 32-byte
  * account hash needed by StorageTask.
  *
  * Used by StorageRecoveryActor (Bug 20 hardening) to recover missing
  * contract storage tries.
  */
class PathTrackingLeafWalkVisitor(
    storage: MptStorage,
    pathPrefix: ByteString,
    onLeaf: (ByteString, LeafNode) => Unit
) extends MptVisitor[Unit] {

  override def visitLeaf(leaf: LeafNode): Unit = {
    val fullNibblePath = pathPrefix ++ leaf.key
    // Convert nibbles to bytes: every 2 nibbles = 1 byte
    val bytes = new Array[Byte](fullNibblePath.length / 2)
    var i = 0
    while (i < bytes.length) {
      val hi = fullNibblePath(i * 2) & 0xff
      val lo = fullNibblePath(i * 2 + 1) & 0xff
      bytes(i) = ((hi << 4) | lo).toByte
      i += 1
    }
    onLeaf(ByteString(bytes), leaf)
  }

  override def visitHash(hashNode: HashNode): HashNodeResult[Unit] =
    ResolveResult(storage.get(hashNode.hash))

  override def visitNull(): Unit = ()

  override def visitExtension(extension: ExtensionNode): ExtensionVisitor[Unit] =
    new PathTrackingExtensionVisitor(storage, pathPrefix, extension.sharedKey, onLeaf)

  override def visitBranch(branch: BranchNode): BranchVisitor[Unit] =
    new PathTrackingBranchVisitor(storage, pathPrefix, onLeaf)
}

private class PathTrackingBranchVisitor(
    storage: MptStorage,
    pathPrefix: ByteString,
    onLeaf: (ByteString, LeafNode) => Unit
) extends BranchVisitor[Unit] {
  private var childIndex: Int = 0

  override def visitChild(): MptVisitor[Unit] = {
    val idx = childIndex
    childIndex += 1
    new PathTrackingLeafWalkVisitor(storage, pathPrefix ++ ByteString(idx.toByte), onLeaf)
  }

  override def visitChild(child: => Unit): Unit = child // force evaluation
  override def visitTerminator(term: Option[ByteString]): Unit = ()
  override def done(): Unit = ()
}

private class PathTrackingExtensionVisitor(
    storage: MptStorage,
    pathPrefix: ByteString,
    sharedKey: ByteString,
    onLeaf: (ByteString, LeafNode) => Unit
) extends ExtensionVisitor[Unit] {
  override def visitNext(): MptVisitor[Unit] =
    new PathTrackingLeafWalkVisitor(storage, pathPrefix ++ sharedKey, onLeaf)

  override def visitNext(value: => Unit): Unit = value // force evaluation
  override def done(): Unit = ()
}
