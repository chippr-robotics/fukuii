package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MPTException
import com.chipprbots.ethereum.mpt.MptVisitors.*
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.rawDecode

object MptTraversals:

  def collapseTrie(node: MptNode): (HashNode, Seq[(ByteString, Array[Byte])]) =
    val nodeCapper = new NodeCapper(withUpdates = true)
    val nodeEncoded = encodeNode(node, Some(nodeCapper))
    val rootHash = ByteString(Node.hashFn(nodeEncoded))
    (HashNode(rootHash.toArray[Byte]), (rootHash, nodeEncoded) +: nodeCapper.getNodesToUpdate)

  /** [[collapseTrie]] for a trie whose root sits at `rootPath`, naming where each node it returns sits. The traversal,
    * the encodings and the hashes are [[collapseTrie]]'s: the same nodes come back in the same order, each with its
    * position. A node is returned when it is stored at its position, that is when its encoding is at least 32 bytes;
    * the root is returned at any size. A smaller node is embedded in its parent and has no position of its own. A
    * subtree that did not change keeps its cached encoding and is not walked, so nothing in it is returned.
    */
  def collapseTrieAt(node: MptNode, rootPath: NibblePath): (HashNode, Seq[(NibblePath, ByteString, Array[Byte])]) =
    val nodeCapper = new NodeCapper(withUpdates = true)
    val visitor = new PositionedRlpHashingVisitor(new RlpEncVisitor, 0, nodeCapper, TriePosition.root(rootPath))
    val nodeEncoded = com.chipprbots.ethereum.rlp.encode(dispatch(node, visitor))
    val rootHash = ByteString(Node.hashFn(nodeEncoded))
    (HashNode(rootHash.toArray[Byte]), (rootPath, rootHash, nodeEncoded) +: nodeCapper.getNodesToUpdateAt)

  def encodeNode(node: MptNode, nodeCapper: Option[NodeCapper] = None): Array[Byte] =
    val nodeEncoded = encode(node, nodeCapper)
    com.chipprbots.ethereum.rlp.encode(nodeEncoded)

  def encode(node: MptNode, nodeCapper: Option[NodeCapper] = None): RLPEncodeable =
    val nodeCap = nodeCapper.fold(new NodeCapper(withUpdates = false))(capper => capper)
    dispatch(node, new RlpHashingVisitor(new RlpEncVisitor, 0, nodeCap))

  def decodeNode(nodeEncoded: NodeEncoded): MptNode =
    parseMpt(decodeNodeRlp(nodeEncoded))

  def decodeNodeRlp(nodeEncoded: NodeEncoded): RLPEncodeable =
    rawDecode(nodeEncoded)

  private def parseMpt(nodeEncoded: RLPEncodeable): MptNode = nodeEncoded match
    case list @ RLPList(items*) if items.size == MerklePatriciaTrie.ListSize =>
      var i = 0
      val children = new Array[MptNode](BranchNode.numberOfChildren)
      while i < BranchNode.numberOfChildren do
        children(i) = parseMpt(items(i))
        i = i + 1
      val terminatorAsArray: ByteString = items.last match
        case RLPValue(bytes) => ByteString(bytes)
        case other =>
          throw new MPTException(
            s"Invalid Branch Node terminator: expected RLPValue but got ${other.getClass.getSimpleName}"
          )
      BranchNode(
        children = children,
        terminator = if terminatorAsArray.isEmpty then None else Some(terminatorAsArray),
        parsedRlp = Some(list)
      )

    case list @ RLPList(items*) if items.size == MerklePatriciaTrie.PairSize =>
      val (key, isLeaf) = HexPrefix.decode(items.head match
        case RLPValue(bytes) => bytes
        case _               => throw new MPTException("Invalid node key: expected RLPValue in Pair node")
      )
      if isLeaf then
        LeafNode(
          ByteString(key),
          items.last match
            case RLPValue(bytes) => ByteString(bytes)
            case _               => throw new MPTException("Invalid Leaf Node: unexpected RLP structure")
          ,
          parsedRlp = Some(list)
        )
      else ExtensionNode(ByteString(key), parseMpt(items.last), parsedRlp = Some(list))

    case RLPValue(bytes) if bytes.length == MptNode.MaxEncodedNodeLength =>
      HashNode(bytes)

    case RLPValue(bytes) if bytes.isEmpty =>
      NullNode

    case _ => throw new MPTException("Invalid Node")

  def dispatch[T](input: MptNode, visitor: MptVisitor[T]): T =
    input match
      case leaf: LeafNode =>
        visitor.visitLeaf(leaf)
      case branch: BranchNode =>
        val branchVisitor = visitor.visitBranch(branch)
        var i = 0
        while i < BranchNode.numberOfChildren do
          val subVisitor = branchVisitor.visitChild()
          branchVisitor.visitChild(dispatch(branch.children(i), subVisitor))
          i = i + 1
        branchVisitor.visitTerminator(branch.terminator)
        branchVisitor.done()

      case extension: ExtensionNode =>
        val extensionVisitor = visitor.visitExtension(extension)
        val subVisitor = extensionVisitor.visitNext()
        extensionVisitor.visitNext(dispatch(extension.next, subVisitor))
        extensionVisitor.done()

      case hashNode: HashNode =>
        val vistResult = visitor.visitHash(hashNode)
        vistResult.next(visitor)(dispatch)

      case _: NullNode.type =>
        visitor.visitNull()
