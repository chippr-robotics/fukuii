package com.chipprbots.ethereum.mpt.MptVisitors

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.Node
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPValue

// C1: ArrayBuffer instead of linked-list cons cells — contiguous storage reduces GC object count.
// Mirrors go-ethereum triedb/hashdb/database.go: Cap() flushes oldest nodes when dirtiesSize > limit.
class NodeCapper(withUpdates: Boolean) {
  private val nodesToUpdate = mutable.ArrayBuffer.empty[(NodeHash, NodeEncoded)]

  def capNode(nodeEncoded: RLPEncodeable, depth: Int): RLPEncodeable =
    if (depth > 0)
      capNode(nodeEncoded)
    else
      nodeEncoded

  private def capNode(nodeEncoded: RLPEncodeable): RLPEncodeable = {
    val asArray = com.chipprbots.ethereum.rlp.encode(nodeEncoded)
    if (asArray.length < MptNode.MaxEncodedNodeLength)
      nodeEncoded
    else {
      val hash = Node.hashFn(asArray)
      if (withUpdates) {
        nodesToUpdate += ((ByteString(hash), asArray))
      }
      RLPValue(hash)
    }
  }

  def getNodesToUpdate: Seq[(NodeHash, NodeEncoded)] = nodesToUpdate.toSeq
}

class RlpHashingVisitor(downstream: MptVisitor[RLPEncodeable], depth: Int, nodeCapper: NodeCapper)
    extends MptVisitor[RLPEncodeable] {
  def visitLeaf(value: LeafNode): RLPEncodeable =
    value.parsedRlp.getOrElse {
      val leafEncoded = downstream.visitLeaf(value)
      nodeCapper.capNode(leafEncoded, depth)
    }

  def visitExtension(value: ExtensionNode): ExtensionVisitor[RLPEncodeable] =
    new RlpHashingExtensionVisitor(downstream.visitExtension(value), depth, value.parsedRlp, nodeCapper)

  def visitBranch(value: BranchNode): BranchVisitor[RLPEncodeable] =
    new RlpHashingBranchVisitor(downstream.visitBranch(value), depth, value.parsedRlp, nodeCapper)

  def visitHash(value: HashNode): HashNodeResult[RLPEncodeable] =
    downstream.visitHash(value)

  def visitNull(): RLPEncodeable =
    downstream.visitNull()
}

class RlpHashingBranchVisitor(
    downstream: BranchVisitor[RLPEncodeable],
    depth: Int,
    parsedRlp: Option[RLPEncodeable],
    nodeCapper: NodeCapper
) extends BranchVisitor[RLPEncodeable] {
  override def done(): RLPEncodeable =
    parsedRlp.getOrElse {
      val branchEncoded = downstream.done()
      nodeCapper.capNode(branchEncoded, depth)
    }

  override def visitChild(): MptVisitor[RLPEncodeable] =
    new RlpHashingVisitor(downstream.visitChild(), depth + 1, nodeCapper)

  override def visitChild(child: => RLPEncodeable): Unit =
    if (parsedRlp.isEmpty)
      downstream.visitChild(child)

  override def visitTerminator(term: Option[NodeHash]): Unit =
    if (parsedRlp.isEmpty)
      downstream.visitTerminator(term)
}

class RlpHashingExtensionVisitor(
    downstream: ExtensionVisitor[RLPEncodeable],
    depth: Int,
    parsedRlp: Option[RLPEncodeable],
    nodeCapper: NodeCapper
) extends ExtensionVisitor[RLPEncodeable] {
  override def visitNext(value: => RLPEncodeable): Unit =
    if (parsedRlp.isEmpty)
      downstream.visitNext(value)

  override def visitNext(): MptVisitor[RLPEncodeable] =
    new RlpHashingVisitor(downstream.visitNext(), depth + 1, nodeCapper)

  override def done(): RLPEncodeable =
    parsedRlp.getOrElse {
      val extensionNodeEncoded = downstream.done()
      nodeCapper.capNode(extensionNodeEncoded, depth)
    }
}
