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
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.mpt.Node
import com.chipprbots.ethereum.mpt.TriePosition
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPValue

// C1: ArrayBuffer instead of linked-list cons cells — contiguous storage reduces GC object count.
// Mirrors go-ethereum triedb/hashdb/database.go: Cap() flushes oldest nodes when dirtiesSize > limit.
class NodeCapper(withUpdates: Boolean):
  private val nodesToUpdate = mutable.ArrayBuffer.empty[(NodeHash, NodeEncoded)]

  // Where each node of `nodesToUpdate` sits, entry for entry. Only a collapse that tracks positions fills it
  // (`MptTraversals.collapseTrieAt`); for every other it stays null.
  private var positions: mutable.ArrayBuffer[NibblePath] | Null = null

  def capNode(nodeEncoded: RLPEncodeable, depth: Int): RLPEncodeable = capNodeAt(nodeEncoded, depth, null)

  /** [[capNode]] for a node that sits at `at`, or at an unknown position when `at` is null. Where the node sits changes
    * nothing in what is returned: it is only noted beside a node that is stored.
    */
  def capNodeAt(nodeEncoded: RLPEncodeable, depth: Int, at: TriePosition | Null): RLPEncodeable =
    if depth > 0 then cap(nodeEncoded, at)
    else nodeEncoded

  private def cap(nodeEncoded: RLPEncodeable, at: TriePosition | Null): RLPEncodeable =
    val asArray = com.chipprbots.ethereum.rlp.encode(nodeEncoded)
    if asArray.length < MptNode.MaxEncodedNodeLength then nodeEncoded
    else
      val hash = Node.hashFn(asArray)
      if withUpdates then
        nodesToUpdate += ((ByteString(hash), asArray))
        if at != null then notePosition(at.path)
      RLPValue(hash)

  private def notePosition(path: NibblePath): Unit =
    val noted = positions
    if noted != null then noted += path
    else positions = mutable.ArrayBuffer(path)

  def getNodesToUpdate: Seq[(NodeHash, NodeEncoded)] = nodesToUpdate.toSeq

  /** [[getNodesToUpdate]] with the position of each node, for a collapse that gave every node its position. */
  def getNodesToUpdateAt: Seq[(NibblePath, NodeHash, NodeEncoded)] =
    val noted = positions
    if noted == null then
      require(nodesToUpdate.isEmpty, "this collapse did not track positions")
      Seq.empty
    else
      require(noted.length == nodesToUpdate.length, "this collapse tracked the position of some nodes only")
      nodesToUpdate.indices.map { i =>
        val (hash, encoded) = nodesToUpdate(i)
        (noted(i), hash, encoded)
      }

class RlpHashingVisitor(downstream: MptVisitor[RLPEncodeable], depth: Int, nodeCapper: NodeCapper)
    extends MptVisitor[RLPEncodeable]:

  /** Where the visited node sits. Null when the traversal does not track positions, which is every traversal except
    * `MptTraversals.collapseTrieAt`: nothing is computed or kept for a position then.
    */
  protected def at: TriePosition | Null = null

  def visitLeaf(value: LeafNode): RLPEncodeable =
    value.parsedRlp.getOrElse {
      val leafEncoded = downstream.visitLeaf(value)
      nodeCapper.capNodeAt(leafEncoded, depth, at)
    }

  def visitExtension(value: ExtensionNode): ExtensionVisitor[RLPEncodeable] =
    new RlpHashingExtensionVisitor(
      downstream.visitExtension(value),
      depth,
      value.parsedRlp,
      nodeCapper,
      at,
      value.sharedKey
    )

  def visitBranch(value: BranchNode): BranchVisitor[RLPEncodeable] =
    new RlpHashingBranchVisitor(downstream.visitBranch(value), depth, value.parsedRlp, nodeCapper, at)

  def visitHash(value: HashNode): HashNodeResult[RLPEncodeable] =
    downstream.visitHash(value)

  def visitNull(): RLPEncodeable =
    downstream.visitNull()

/** [[RlpHashingVisitor]] for a node whose position is tracked. */
final class PositionedRlpHashingVisitor(
    downstream: MptVisitor[RLPEncodeable],
    depth: Int,
    nodeCapper: NodeCapper,
    position: TriePosition
) extends RlpHashingVisitor(downstream, depth, nodeCapper):
  override protected def at: TriePosition | Null = position

class RlpHashingBranchVisitor(
    downstream: BranchVisitor[RLPEncodeable],
    depth: Int,
    parsedRlp: Option[RLPEncodeable],
    nodeCapper: NodeCapper,
    at: TriePosition | Null = null
) extends BranchVisitor[RLPEncodeable]:
  override def done(): RLPEncodeable =
    parsedRlp.getOrElse {
      val branchEncoded = downstream.done()
      nodeCapper.capNodeAt(branchEncoded, depth, at)
    }

  // A branch that keeps its cached encoding is not walked into, so its children need no position.
  override def visitChild(): MptVisitor[RLPEncodeable] =
    if at == null || parsedRlp.isDefined then new RlpHashingVisitor(downstream.visitChild(), depth + 1, nodeCapper)
    else new PositionedRlpHashingVisitor(downstream.visitChild(), depth + 1, nodeCapper, at.nextChild())

  override def visitChild(child: => RLPEncodeable): Unit =
    if parsedRlp.isEmpty then downstream.visitChild(child)

  override def visitTerminator(term: Option[NodeHash]): Unit =
    if parsedRlp.isEmpty then downstream.visitTerminator(term)

class RlpHashingExtensionVisitor(
    downstream: ExtensionVisitor[RLPEncodeable],
    depth: Int,
    parsedRlp: Option[RLPEncodeable],
    nodeCapper: NodeCapper,
    at: TriePosition | Null = null,
    sharedKey: ByteString | Null = null
) extends ExtensionVisitor[RLPEncodeable]:
  override def visitNext(value: => RLPEncodeable): Unit =
    if parsedRlp.isEmpty then downstream.visitNext(value)

  // As for a branch: an extension that keeps its cached encoding is not walked into.
  override def visitNext(): MptVisitor[RLPEncodeable] =
    if at == null || sharedKey == null || parsedRlp.isDefined then
      new RlpHashingVisitor(downstream.visitNext(), depth + 1, nodeCapper)
    else new PositionedRlpHashingVisitor(downstream.visitNext(), depth + 1, nodeCapper, at.next(sharedKey))

  override def done(): RLPEncodeable =
    parsedRlp.getOrElse {
      val extensionNodeEncoded = downstream.done()
      nodeCapper.capNodeAt(extensionNodeEncoded, depth, at)
    }
