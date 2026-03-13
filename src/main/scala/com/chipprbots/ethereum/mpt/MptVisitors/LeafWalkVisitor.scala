package com.chipprbots.ethereum.mpt.MptVisitors

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt._

/** Visitor that walks an MPT and invokes a callback for each leaf node. Does not accumulate results in memory —
  * suitable for walking large tries (e.g. 85M+ accounts in Ethereum state trie).
  *
  * Used by BytecodeRecoveryActor (Bug 20 hardening) to scan for missing bytecodes.
  */
class LeafWalkVisitor(storage: MptStorage, onLeaf: LeafNode => Unit) extends MptVisitor[Unit] {

  override def visitLeaf(leaf: LeafNode): Unit =
    onLeaf(leaf)

  override def visitHash(hashNode: HashNode): HashNodeResult[Unit] =
    ResolveResult(storage.get(hashNode.hash))

  override def visitNull(): Unit = ()

  override def visitExtension(extension: ExtensionNode): ExtensionVisitor[Unit] =
    new LeafWalkExtensionVisitor(storage, onLeaf)

  override def visitBranch(branch: BranchNode): BranchVisitor[Unit] =
    new LeafWalkBranchVisitor(storage, onLeaf)
}

private class LeafWalkBranchVisitor(storage: MptStorage, onLeaf: LeafNode => Unit) extends BranchVisitor[Unit] {
  override def visitChild(): MptVisitor[Unit] = new LeafWalkVisitor(storage, onLeaf)
  override def visitChild(child: => Unit): Unit = child // force evaluation (triggers traversal)
  override def visitTerminator(term: Option[ByteString]): Unit = ()
  override def done(): Unit = ()
}

private class LeafWalkExtensionVisitor(storage: MptStorage, onLeaf: LeafNode => Unit) extends ExtensionVisitor[Unit] {
  override def visitNext(): MptVisitor[Unit] = new LeafWalkVisitor(storage, onLeaf)
  override def visitNext(value: => Unit): Unit = value // force evaluation
  override def done(): Unit = ()
}
