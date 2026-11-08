package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.ReferenceCountedStateStorage
import com.chipprbots.ethereum.db.storage.StoredNodeTestAccess
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.*
import com.chipprbots.ethereum.testing.Tags.*

class StateValidatorSpec extends AnyFlatSpec with Matchers:

  "StateValidator" should "validate a complete account trie with no missing nodes" taggedAs UnitTest in {
    // Create a simple in-memory storage
    val storage = new TestMptStorage()

    // Create a simple account trie with a few accounts
    val account1 = Account(nonce = 1, balance = 100)
    val account2 = Account(nonce = 2, balance = 200)

    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(ByteString("account1"), account1)
      .put(ByteString("account2"), account2)

    val stateRoot = ByteString(trie.getRootHash)

    // Validate the trie
    val validator = new StateValidator(storage)
    val result = validator.validateAccountTrie(stateRoot)

    // Should have no missing nodes
    result shouldBe Right(Seq.empty)
  }

  it should "detect missing nodes in account trie" taggedAs UnitTest in {
    // Create storage with a complete trie first
    val storage = new TestMptStorage()
    val account = Account(nonce = 1, balance = 100)

    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(ByteString("account1"), account)

    val stateRoot = ByteString(trie.getRootHash)

    // Now create a new storage that is missing some nodes
    val incompleteStorage = new TestMptStorage()

    // Try to validate the trie with incomplete storage - should detect missing root
    val validator = new StateValidator(incompleteStorage)
    val result = validator.validateAccountTrie(stateRoot)

    result match
      case Right(_) =>
        // Should detect the root node as missing
        fail("Expected error for missing root, but got Right with nodes")
      case Left(error) =>
        // Expected - root node is missing
        error should (include("Missing").or(include("Failed")))
  }

  it should "validate storage tries for all accounts" taggedAs UnitTest in {
    val storage = new TestMptStorage()

    // Create storage trie first
    val storageTrie = MerklePatriciaTrie[ByteString, ByteString](storage)
      .put(ByteString("slot1"), ByteString("value1"))
    val storageRoot = TrieRoot(ByteString(storageTrie.getRootHash))

    // Create account with matching storage root
    val account = Account(
      nonce = 1,
      balance = 100,
      storageRoot = storageRoot,
      codeHash = Account.EmptyCodeHash
    )

    // Create account trie
    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(ByteString("account1"), account)

    val stateRoot = ByteString(trie.getRootHash)

    val validator = new StateValidator(storage)
    val result = validator.validateAllStorageTries(stateRoot)

    // Should succeed with no missing nodes since storage trie is complete
    result shouldBe Right(Seq.empty)
  }

  it should "handle accounts with empty storage correctly" taggedAs UnitTest in {
    val storage = new TestMptStorage()

    // Create an account with empty storage root
    val account = Account(
      nonce = 1,
      balance = 100,
      storageRoot = Account.EmptyStorageRootHash,
      codeHash = Account.EmptyCodeHash
    )

    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(ByteString("account1"), account)

    val stateRoot = ByteString(trie.getRootHash)

    val validator = new StateValidator(storage)
    val result = validator.validateAllStorageTries(stateRoot)

    // Should succeed with no missing nodes (empty storage is valid)
    result shouldBe Right(Seq.empty)
  }

  it should "handle missing root node gracefully" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val nonExistentRoot = kec256(ByteString("nonexistent"))

    val validator = new StateValidator(storage)
    val result = validator.validateAccountTrie(nonExistentRoot)

    // Should return error about missing root
    result.isLeft shouldBe true
    result.left.map { error =>
      error should (include("Missing").or(include("Failed")))
    }
  }

  it should "collect all accounts from trie" taggedAs UnitTest in {
    val storage = new TestMptStorage()

    // Create multiple accounts
    val accounts = (1 to 5).map { i =>
      ByteString(s"account$i") -> Account(nonce = i, balance = i * 100)
    }

    val trie = accounts.foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { case (t, (key, account)) =>
      t.put(key, account)
    }

    val stateRoot = ByteString(trie.getRootHash)

    val validator = new StateValidator(storage)

    // Validate should traverse all accounts
    val result = validator.validateAccountTrie(stateRoot)

    // Should complete without errors
    result.isRight shouldBe true
  }

  it should "detect missing storage nodes across multiple accounts" taggedAs UnitTest in {
    val storage = new TestMptStorage()

    // Create partial storage - root exists but some child nodes missing
    val storage1Trie = MerklePatriciaTrie[ByteString, ByteString](storage)
      .put(ByteString("slot1"), ByteString("value1"))
      .put(ByteString("slot2"), ByteString("value2"))
    val storage1Root = ByteString(storage1Trie.getRootHash)

    // For account2, intentionally use a non-existent storage root
    val missingStorageRoot = kec256(ByteString("nonexistent"))

    val account1 = Account(
      nonce = 1,
      balance = 100,
      storageRoot = TrieRoot(storage1Root),
      codeHash = Account.EmptyCodeHash
    )

    val account2 = Account(
      nonce = 2,
      balance = 200,
      storageRoot = TrieRoot(missingStorageRoot),
      codeHash = Account.EmptyCodeHash
    )

    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(ByteString("account1"), account1)
      .put(ByteString("account2"), account2)

    val stateRoot = ByteString(trie.getRootHash)

    val validator = new StateValidator(storage)
    val result = validator.validateAllStorageTries(stateRoot)

    // Should detect the missing storage root for account2
    result match
      case Right(foundMissingNodes) =>
        foundMissingNodes should not be empty
        foundMissingNodes should contain(missingStorageRoot)
      case Left(foundError) =>
        fail(s"Expected to detect missing storage root, but got error: $foundError")
  }

  // spec 022 T062: a stored entry that is not its node reads as a CorruptNodeException, a missing node. Queued for healing
  // as one, it would be fetched and written through storeRawNodes, which under basic pruning (the ETC default) keeps the
  // entry already there (SerializingMptStorageCorruptSpec pins it): the next walk queues it again, a quiet spin. So
  // every walk fails on it, where an absent node stays a hole.
  private val corruptClasses: Seq[(String, Array[Byte])] =
    Seq("invalid-node" -> Array(0x01.toByte), "out-of-bounds" -> Array(0xc1.toByte), "null-node" -> Array(0x80.toByte))

  /** Basic pruning over `accounts` accounts that share one 64-slot storage trie. One account makes the state root that
    * account's leaf, which is how the heal walk reaches a storage trie: it reads the nodes it holds, and only checks
    * that their hash children are present.
    */
  private class BasicPrunedState(accounts: Int):
    val nodes: NodeStorage = new NodeStorage(EphemDataSource())
    val mpt: MptStorage =
      new ReferenceCountedStateStorage(nodes, 64, 0L, nodesMayBeDeleted = () => true).getBackingStorage(0)
    val storageRoot: ByteString = ByteString(
      (0 until 64)
        .foldLeft(MerklePatriciaTrie[ByteString, ByteString](mpt)) { (trie, i) =>
          trie.put(ByteString(kec256(BigInt(i).toByteArray)), ByteString(Array.fill[Byte](32)(i.toByte)))
        }
        .getRootHash
    )
    val stateRoot: ByteString = ByteString(
      (0 until accounts)
        .foldLeft(MerklePatriciaTrie[ByteString, Account](mpt)) { (trie, i) =>
          trie.put(
            ByteString(kec256(BigInt(1000 + i).toByteArray)),
            Account(nonce = 1, balance = 100, storageRoot = TrieRoot(storageRoot))
          )
        }
        .getRootHash
    )
    def hashChildOf(parent: ByteString): ByteString = mpt.get(parent.toArray) match
      case branch: BranchNode => branch.children.collectFirst { case HashNode(h) => ByteString(h) }.get
      case other              => fail(s"not a branch: $other")
    def corrupt(hash: ByteString, junk: Array[Byte]): Unit =
      nodes.put(hash, StoredNodeTestAccess.wrapped(junk, 1)): Unit
    def drop(hash: ByteString): Unit = nodes.remove(hash): Unit
    def validator: StateValidator = new StateValidator(mpt)

  private def failed(result: Either[String, ?]): Unit = withClue(s"got $result: ")(result.isLeft shouldBe true)

  it should "fail every walk on a corrupt node under basic pruning, never queue it for healing (spec 022 T062)" taggedAs UnitTest in {
    corruptClasses.foreach { case (name, junk) =>
      Seq("account root", "account node").foreach { position =>
        withClue(s"$name at the $position: ") {
          val s = new BasicPrunedState(32)
          s.corrupt(if position == "account root" then s.stateRoot else s.hashChildOf(s.stateRoot), junk)
          failed(s.validator.findMissingNodesWithPaths(s.stateRoot))
          failed(s.validator.validateAccountTrie(s.stateRoot))
          failed(s.validator.validateAllStorageTries(s.stateRoot))
        }
      }
      Seq("storage root", "storage node").foreach { position =>
        withClue(s"$name at the $position: ") {
          val s = new BasicPrunedState(1)
          s.corrupt(if position == "storage root" then s.storageRoot else s.hashChildOf(s.storageRoot), junk)
          failed(s.validator.findMissingNodesWithPaths(s.stateRoot))
          failed(s.validator.validateAllStorageTries(s.stateRoot))
        }
      }
    }
  }

  it should "still queue an absent node at the same positions as a hole" taggedAs UnitTest in {
    val accountsState = new BasicPrunedState(32)
    val accountNode = accountsState.hashChildOf(accountsState.stateRoot)
    accountsState.drop(accountNode)
    accountsState.validator.findMissingNodesWithPaths(accountsState.stateRoot).map(_.map(_._2)) shouldBe
      Right(Seq(accountNode))

    val storageState = new BasicPrunedState(1)
    val storageNode = storageState.hashChildOf(storageState.storageRoot)
    storageState.drop(storageNode)
    storageState.validator.findMissingNodesWithPaths(storageState.stateRoot).map(_.map(_._2)) shouldBe
      Right(Seq(storageNode))
    storageState.validator.validateAllStorageTries(storageState.stateRoot) shouldBe Right(Seq(storageNode))
  }

  /** Simple in-memory test storage for MPT nodes */
  private class TestMptStorage extends MptStorage:
    private val nodes = mutable.Map[ByteString, MptNode]()

    override def get(key: Array[Byte]): MptNode =
      val keyStr = ByteString(key)
      nodes
        .get(keyStr)
        .getOrElse {
          throw new MerklePatriciaTrie.MissingNodeException(keyStr)
        }

    def putNode(node: MptNode): Unit =
      val hash = ByteString(node.hash)
      nodes(hash) = node

    override def updateNodesInStorage(
        newRoot: Option[MptNode],
        toRemove: Seq[MptNode]
    ): Option[MptNode] =
      // Store the new root and related nodes
      newRoot.foreach { root =>
        storeNodeRecursively(root)
      }
      newRoot

    private def storeNodeRecursively(node: MptNode): Unit =
      node match
        case leaf: LeafNode =>
          putNode(leaf)
        case ext: ExtensionNode =>
          putNode(ext)
          storeNodeRecursively(ext.next)
        case branch: BranchNode =>
          putNode(branch)
          branch.children.foreach(storeNodeRecursively)
        case hash: HashNode =>
          putNode(hash)
        case NullNode =>
        // Nothing to store

    override def persist(): Unit = {
      // No-op for in-memory storage
    }
