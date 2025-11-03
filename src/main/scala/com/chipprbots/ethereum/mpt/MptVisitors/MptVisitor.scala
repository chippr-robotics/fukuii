package com.chipprbots.ethereum.mpt.MptVisitors

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode

sealed abstract class HashNodeResult[T] {
  def next(visitor: MptVisitor[T])(f: (MptNode, MptVisitor[T]) => T): T = this match {
    case Result(value)       => value
    case ResolveResult(node) => f(node, visitor)
  }
}
case class Result[T](t: T) extends HashNodeResult[T]
case class ResolveResult[T](mptNode: MptNode) extends HashNodeResult[T]

abstract class MptVisitor[T] {
  def visitLeaf(value: LeafNode): T
  def visitExtension(value: ExtensionNode): ExtensionVisitor[T]
  def visitBranch(value: BranchNode): BranchVisitor[T]
  def visitHash(value: HashNode): HashNodeResult[T]
  def visitNull(): T
}

abstract class BranchVisitor[T] {
  def visitChild(): MptVisitor[T]
  def visitChild(child: => T): Unit
  def visitTerminator(term: Option[ByteString]): Unit
  def done(): T
}

abstract class ExtensionVisitor[T] {
  def visitNext(): MptVisitor[T]
  def visitNext(value: => T): Unit
  def done(): T
}
