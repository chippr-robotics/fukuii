package com.chipprbots.ethereum.mpt.MptVisitors

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode

/** Builds a whole trie in memory, resolving every hash node from `source`.
  *
  * Test support, not production code (spec 018 Track C, C2b): it resolves a node by its hash alone, which only a hash
  * store can answer, and nothing in the node ever called it. It lived in the main sources until then.
  */
class MptConstructionVisitor(source: MptStorage) extends MptVisitor[MptNode]:

  def visitLeaf(leaf: LeafNode): MptNode =
    leaf

  def visitHash(hashNode: HashNode): HashNodeResult[MptNode] =
    ResolveResult(source.get(hashNode.hash))

  override def visitNull(): MptNode =
    NullNode

  override def visitExtension(extension: ExtensionNode): ExtensionVisitor[MptNode] =
    new MptExtensionVisitor(extension, source)

  override def visitBranch(value: BranchNode): BranchVisitor[MptNode] = new MptBranchVisitor(value, source)

object MptConstructionVisitor:

  /** The trie under `rootNode`, with every node resolved: what `MptTraversals.parseTrieIntoMemory` was. */
  def parseTrieIntoMemory(rootNode: MptNode, source: MptStorage): MptNode =
    com.chipprbots.ethereum.mpt.MptTraversals.dispatch(rootNode, new MptConstructionVisitor(source))

class MptBranchVisitor(branchNode: BranchNode, source: MptStorage) extends BranchVisitor[MptNode]:
  var resolvedChildren: List[MptNode] = List.empty

  override def visitChild(child: => MptNode): Unit =
    resolvedChildren = child :: resolvedChildren

  override def visitChild(): MptVisitor[MptNode] = new MptConstructionVisitor(source)

  override def visitTerminator(term: Option[NodeHash]): Unit = ()

  override def done(): MptNode =
    branchNode.copy(children = resolvedChildren.reverse.toArray)

class MptExtensionVisitor(extensionNode: ExtensionNode, source: MptStorage) extends ExtensionVisitor[MptNode]:
  var resolvedNext = extensionNode.next

  override def visitNext(): MptVisitor[MptNode] = new MptConstructionVisitor(source)

  override def visitNext(value: => MptNode): Unit =
    resolvedNext = value

  override def done(): MptNode =
    extensionNode.copy(next = resolvedNext)
