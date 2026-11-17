package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.db.storage.StoredNodeTestAccess
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.mpt.byteStringSerializer

/** A real basic-pruning store over an in-memory source, and the trie shapes the audit's specs want. */
final class AuditTestState(history: Int = 64):
  val source: EphemDataSource = EphemDataSource()
  val nodes: NodeStorage = new NodeStorage(source)
  val state: ReferenceCountedStateStorage =
    new ReferenceCountedStateStorage(nodes, history, 1024L * 1024, nodesMayBeDeleted = () => true)
  val code: EvmCodeStorage = new EvmCodeStorage(source)
  val app: AppStateStorage = new AppStateStorage(source)
  val progress: SnapSyncProgressStorage = new SnapSyncProgressStorage(source)

  @volatile var bestHead: Option[AuditHead] = None

  /** Every batch `nodes` was asked for, and the head reads, for what a spec wants to count. */
  val batchSizes: mutable.Buffer[Int] = mutable.Buffer.empty

  val reader: AuditReader = new AuditReader:
    private val store = state.getReadOnlyStorage
    def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
      batchSizes.synchronized(batchSizes += hashes.size)
      store.rawNodes(hashes)
    def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = code.multiGetNoFill(hashes)
    def head(): Option[AuditHead] = bestHead

  def setHead(number: Int, root: ByteString, timestampSeconds: Long = 0L): AuditHead =
    val head = AuditHead(number, root, timestampSeconds)
    bestHead = Some(head)
    head

  // ---- by hand -------------------------------------------------------------------------------------------------------

  /** Stores `node` by hash, as block execution would, and gives the hash. */
  def put(node: MptNode): ByteString =
    val hash = ByteString(kec256(node.encode))
    nodes.put(hash, StoredNodeTestAccess.wrapped(node.encode, 1))
    hash

  def delete(hash: ByteString): Unit = nodes.update(Seq(hash), Nil)

  /** `hash` now holds bytes that do not hash to it. */
  def corrupt(hash: ByteString, bytes: Array[Byte]): Unit =
    nodes.put(hash, StoredNodeTestAccess.wrapped(bytes, 1))

  /** `hash` now holds an entry that does not even unwrap. */
  def garble(hash: ByteString): Unit = nodes.put(hash, Array[Byte](1, 2, 3))

  // ---- with the real trie --------------------------------------------------------------------------------------------

  /** Applies `puts` and `removes` to the trie at `root` as block `bn` would, through the reference counts, and gives
    * the new root.
    */
  def update(
      root: Option[ByteString],
      bn: BigInt,
      puts: Seq[(ByteString, Array[Byte])],
      removes: Seq[ByteString] = Nil
  ): ByteString =
    val storage: MptStorage = state.getBackingStorage(bn)
    val start = root.fold(MerklePatriciaTrie[ByteString, Array[Byte]](storage))(r =>
      MerklePatriciaTrie[ByteString, Array[Byte]](r.toArray, storage)
    )
    val afterPuts = puts.foldLeft(start) { case (trie, (k, v)) => trie.put(k, v) }
    val finished = removes.foldLeft(afterPuts)((trie, k) => trie.remove(k))
    ByteString(finished.getRootHash)

  /** Every stored node the trie at `root` holds, storage tries included, found without the walk under test. */
  def allNodes(root: ByteString): Vector[(AuditPosition, ByteString)] =
    val found = Vector.newBuilder[(AuditPosition, ByteString)]
    val store = state.getReadOnlyStorage
    def visit(hash: ByteString, at: AuditPosition): Unit =
      found += ((at, hash))
      store.get(hash.toArray) match
        case branch: BranchNode =>
          branch.children.zipWithIndex.foreach {
            case (HashNode(h), slot) => visit(ByteString(h), at.child(slot))
            case _                   =>
          }
        case ext: ExtensionNode =>
          ext.next match
            case HashNode(h) => visit(ByteString(h), at.child(ext.sharedKey))
            case _           =>
        case leaf: LeafNode if !at.isStorage =>
          val account = Account(leaf.value).get
          if account.storageRoot != Account.EmptyStorageRootHash then
            val owner = ByteString(HexPrefix.nibblesToBytes((at.nibbles ++ leaf.key).toArray))
            visit(account.storageRoot.value, AuditPosition.storageRootOf(owner))
        case _ =>
    visit(root, AuditPosition.AccountRoot)
    found.result()

object AuditTestState:

  /** A 32-byte trie key that no other seed collides with. */
  def trieKey(seed: Int): ByteString = ByteString(kec256(BigInt(seed + 1).toByteArray))

  /** A value long enough that its leaf is stored by hash and not embedded. */
  def longValue(seed: Int): Array[Byte] = Array.fill[Byte](40)((seed % 200 + 1).toByte)

  def accountBytes(
      storageRoot: Option[ByteString] = None,
      codeHash: Option[ByteString] = None,
      nonce: Long = 1L
  ): Array[Byte] =
    val account = Account(
      nonce = UInt256(nonce),
      storageRoot = storageRoot.fold(Account.EmptyStorageRootHash)(TrieRoot(_)),
      codeHash = codeHash.fold(Account.EmptyCodeHash)(CodeHash(_))
    )
    Account.accountSerializer.toBytes(account)

  /** Nibbles of `n` copies of `nibble`. */
  def nibbles(n: Int, nibble: Int): ByteString = ByteString(Array.fill[Byte](n)(nibble.toByte))

  def branch(slots: (Int, ByteString)*): BranchNode =
    val children = Array.fill[MptNode](16)(NullNode)
    slots.foreach { case (slot, hash) => children(slot) = HashNode(hash.toArray) }
    BranchNode(children, None)

  def extension(path: Seq[Int], next: ByteString): ExtensionNode =
    ExtensionNode(ByteString(path.map(_.toByte).toArray), HashNode(next.toArray))

  /** An account leaf whose whole key is 64 copies of `nibble`, sitting under a one-nibble branch slot. */
  def accountLeaf(nibble: Int, bytes: Array[Byte]): LeafNode = LeafNode(nibbles(63, nibble), ByteString(bytes))

  def accountHash(nibble: Int): ByteString =
    ByteString(HexPrefix.nibblesToBytes(nibbles(64, nibble).toArray))

  /** A storage leaf too long to embed. */
  def storageLeaf(seed: Int): LeafNode = LeafNode(nibbles(4, seed % 16), ByteString(longValue(seed)))
