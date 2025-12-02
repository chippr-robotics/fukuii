package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.{Account, UInt256}
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.testing.Tags._

import scala.collection.mutable

class StateValidatorSpec extends AnyFlatSpec with Matchers {

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
    
    result match {
      case Right(missingNodes) =>
        // Should detect the root node as missing
        fail("Expected error for missing root, but got Right with nodes")
      case Left(error) =>
        // Expected - root node is missing
        error should (include("Missing") or include("Failed"))
    }
  }

  it should "validate storage tries for all accounts" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    
    // Create storage trie first
    val storageTrie = MerklePatriciaTrie[ByteString, ByteString](storage)
      .put(ByteString("slot1"), ByteString("value1"))
    val storageRoot = ByteString(storageTrie.getRootHash)
    
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
      error should (include("Missing") or include("Failed"))
    }
  }

  it should "collect all accounts from trie" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    
    // Create multiple accounts
    val accounts = (1 to 5).map { i =>
      ByteString(s"account$i") -> Account(nonce = i, balance = i * 100)
    }
    
    val trie = accounts.foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) {
      case (t, (key, account)) => t.put(key, account)
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
      storageRoot = storage1Root,
      codeHash = Account.EmptyCodeHash
    )
    
    val account2 = Account(
      nonce = 2,
      balance = 200,
      storageRoot = missingStorageRoot,
      codeHash = Account.EmptyCodeHash
    )
    
    val trie = MerklePatriciaTrie[ByteString, Account](storage)
      .put(ByteString("account1"), account1)
      .put(ByteString("account2"), account2)
    
    val stateRoot = ByteString(trie.getRootHash)
    
    val validator = new StateValidator(storage)
    val result = validator.validateAllStorageTries(stateRoot)
    
    // Should detect the missing storage root for account2
    result match {
      case Right(missingNodes) =>
        missingNodes should not be empty
        missingNodes should contain (missingStorageRoot)
      case Left(error) =>
        fail(s"Expected to detect missing storage root, but got error: $error")
    }
  }
}

/** Simple in-memory test storage for MPT nodes */
class TestMptStorage extends MptStorage {
  private val nodes = mutable.Map[ByteString, MptNode]()
  
  override def get(key: Array[Byte]): MptNode = {
    val keyStr = ByteString(key)
    nodes.get(keyStr)
      .getOrElse {
        throw new MerklePatriciaTrie.MissingNodeException(keyStr)
      }
  }
  
  def putNode(node: MptNode): Unit = {
    val hash = ByteString(node.hash)
    nodes(hash) = node
  }
  
  override def updateNodesInStorage(
      newRoot: Option[MptNode],
      toRemove: Seq[MptNode]
  ): Option[MptNode] = {
    // Store the new root and related nodes
    newRoot.foreach { root =>
      storeNodeRecursively(root)
    }
    newRoot
  }
  
  private def storeNodeRecursively(node: MptNode): Unit = {
    node match {
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
    }
  }
  
  override def persist(): Unit = {
    // No-op for in-memory storage
  }
}
