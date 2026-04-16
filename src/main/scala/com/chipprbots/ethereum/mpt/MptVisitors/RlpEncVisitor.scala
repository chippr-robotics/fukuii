package com.chipprbots.ethereum.mpt.MptVisitors

import java.util

import scala.collection.immutable.ArraySeq

import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

class RlpExtensionVisitor(extensionNode: ExtensionNode) extends ExtensionVisitor[RLPEncodeable] {
  val array: Array[RLPEncodeable] = new Array[RLPEncodeable](2)

  array(0) = RLPValue(HexPrefix.encode(nibbles = extensionNode.sharedKey.toArray[Byte], isLeaf = false))

  override def visitNext(): MptVisitor[RLPEncodeable] = new RlpEncVisitor

  override def visitNext(value: => RLPEncodeable): Unit =
    array(1) = value

  override def done(): RLPEncodeable = {
    val copy = util.Arrays.copyOf[RLPEncodeable](array, 2)
    RLPList(ArraySeq.unsafeWrapArray(copy): _*)
  }
}

class RlpBranchVisitor(_branchNode: BranchNode) extends BranchVisitor[RLPEncodeable] {

  var list: List[RLPEncodeable] = List.empty[RLPEncodeable]

  override def visitChild(): MptVisitor[RLPEncodeable] = new RlpEncVisitor

  override def visitChild(child: => RLPEncodeable): Unit =
    list = child :: list

  override def visitTerminator(term: Option[NodeHash]): Unit =
    list = RLPValue(term.map(_.toArray[Byte]).getOrElse(Array.empty[Byte])) :: list

  override def done(): RLPEncodeable =
    RLPList(list.reverse: _*)
}

class RlpEncVisitor extends MptVisitor[RLPEncodeable] {

  def visitLeaf(leaf: LeafNode): RLPEncodeable =
    RLPList(
      RLPValue(HexPrefix.encode(nibbles = leaf.key.toArray[Byte], isLeaf = true)),
      RLPValue(leaf.value.toArray[Byte])
    )
  def visitHash(hashNode: HashNode): HashNodeResult[RLPEncodeable] =
    Result(RLPValue(hashNode.hashNode))

  override def visitNull(): RLPEncodeable =
    RLPValue(Array.empty[Byte])

  override def visitExtension(extension: ExtensionNode): ExtensionVisitor[RLPEncodeable] = new RlpExtensionVisitor(
    extension
  )

  override def visitBranch(value: BranchNode): BranchVisitor[RLPEncodeable] = new RlpBranchVisitor(value)
}
