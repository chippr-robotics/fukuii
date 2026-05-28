package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.{ArchiveNodeStorage, MptStorage, NodeStorage, SerializingMptStorage}
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
      case Right(_) =>
        // Should detect the root node as missing
        fail("Expected error for missing root, but got Right with nodes")
      case Left(error) =>
        // Expected - root node is missing
        error should (include("Missing").or(include("Failed")))
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
      case Right(foundMissingNodes) =>
        foundMissingNodes should not be empty
        foundMissingNodes should contain(missingStorageRoot)
      case Left(foundError) =>
        fail(s"Expected to detect missing storage root, but got error: $foundError")
    }
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Streaming walk checkpoint tests (Phase 4 C4+A8)
  // ──────────────────────────────────────────────────────────────────────────

  it should "call onCheckpoint after each batch flush when missing nodes fill the buffer" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    val checkpoints = mutable.ArrayBuffer[ByteString]()

    val missingRoot = kec256(ByteString("nonexistent-storage-root"))
    val trie = (0 to 2).foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { (t, i) =>
      t.put(
        ByteString(s"acct$i"),
        Account(nonce = i, balance = i * 100, storageRoot = missingRoot, codeHash = Account.EmptyCodeHash)
      )
    }
    val stateRoot = ByteString(trie.getRootHash)

    val result = new StateValidator(storage).findMissingNodesStreaming(
      stateRoot,
      batchSize = 1,
      onBatch = _ => (),
      onCheckpoint = h => checkpoints += h
    )

    // 3 accounts × 1 missing storage root each → 3 flushes → 3 checkpoints
    result shouldBe Right(3)
    checkpoints.size shouldBe 3
  }

  it should "skip accounts at or before the resumeFrom cursor" taggedAs UnitTest in {
    val storage = new TestMptStorage()

    val missingRoot = kec256(ByteString("nonexistent-storage-root-2"))
    val trie = (0 to 2).foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { (t, i) =>
      t.put(
        ByteString(s"acct$i"),
        Account(nonce = i, balance = i * 100, storageRoot = missingRoot, codeHash = Account.EmptyCodeHash)
      )
    }
    val stateRoot = ByteString(trie.getRootHash)

    // First pass: discover the hash of the first account the walk visits
    val firstPassCheckpoints = mutable.ArrayBuffer[ByteString]()
    new StateValidator(storage).findMissingNodesStreaming(
      stateRoot,
      batchSize = 1,
      onBatch = _ => (),
      onCheckpoint = h => firstPassCheckpoints += h
    )
    firstPassCheckpoints.size shouldBe 3

    // Second pass: resume from the first account hash (DFS visits accounts in ascending order,
    // so cursor == firstPassCheckpoints(0) skips exactly 1 account and processes 2)
    val cursor = firstPassCheckpoints(0)
    val secondPassResult = new StateValidator(storage).findMissingNodesStreaming(
      stateRoot,
      batchSize = 1,
      onBatch = _ => (),
      resumeFrom = Some(cursor)
    )

    secondPassResult shouldBe Right(2)
  }

  it should "process all accounts when resumeFrom is None" taggedAs UnitTest in {
    val storage = new TestMptStorage()

    val missingRoot = kec256(ByteString("nonexistent-root-3"))
    val trie = (0 to 2).foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { (t, i) =>
      t.put(
        ByteString(s"acct$i"),
        Account(nonce = i, balance = i * 100, storageRoot = missingRoot, codeHash = Account.EmptyCodeHash)
      )
    }
    val stateRoot = ByteString(trie.getRootHash)

    val result = new StateValidator(storage).findMissingNodesStreaming(
      stateRoot,
      batchSize = 100,
      onBatch = _ => ()
      // resumeFrom defaults to None
    )

    result shouldBe Right(3)
  }

  it should "not call onCheckpoint when trie has no missing nodes" taggedAs UnitTest in {
    val storage = new TestMptStorage()
    var checkpointCallCount = 0

    // Accounts with default EmptyStorageRootHash → no storage trie to walk, no missing nodes
    val trie = (0 to 1).foldLeft(MerklePatriciaTrie[ByteString, Account](storage)) { (t, i) =>
      t.put(ByteString(s"account$i"), Account(nonce = i, balance = i * 100))
    }
    val stateRoot = ByteString(trie.getRootHash)

    val result = new StateValidator(storage).findMissingNodesStreaming(
      stateRoot,
      batchSize = 1,
      onBatch = _ => (),
      onCheckpoint = _ => checkpointCallCount += 1
    )

    result shouldBe Right(0)
    checkpointCallCount shouldBe 0
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Gap A regression: walkAccountTrieDFS HashNode traversal (Fix 1)
  //
  // TestMptStorage stores nodes inline as MptNode objects — it never produces
  // HashNode references, so it cannot trigger Gap A. These tests use
  // SerializingMptStorage + EphemDataSource which correctly collapses nodes
  // >= 32 bytes into HashNode references inside parent BranchNodes, matching
  // the production code path.
  //
  // T1.1 and T1.3 are regression tests: they FAIL before Fix 1 (Right(0)) and
  // PASS after Fix 1 (Right(n > 0)). T1.4 is a correctness test that passes
  // both before and after (no false positives introduced by the fix).
  // ──────────────────────────────────────────────────────────────────────────

  it should "report missing storage nodes when account trie has HashNode intermediates (Gap A regression, T1.1 / T5.2)" taggedAs UnitTest in {
    val serStorage = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))

    // A storage root hash that is intentionally absent from the backing store.
    val missingStorageRoot = kec256(ByteString("gap-a-missing-storage-root"))

    // Two distinct accounts so their LeafNode encodings (path + value) differ,
    // giving different hashes and thus distinct HashNode references in the BranchNode.
    // If both accounts were identical AND shared the same remaining nibble path, both
    // children would hash to the same value — the visited set would skip the second.
    val account1 = Account(nonce = 1, balance = 1000, storageRoot = missingStorageRoot, codeHash = Account.EmptyCodeHash)
    val account2 = Account(nonce = 2, balance = 2000, storageRoot = missingStorageRoot, codeHash = Account.EmptyCodeHash)

    // Two keys with different first nibbles produce a BranchNode at the trie root.
    // SerializingMptStorage collapses each child LeafNode (>> 32 bytes with a 32-byte
    // storageRoot + 32-byte codeHash) into a HashNode reference inside the BranchNode.
    // walkAccountTrieDFS must resolve the HashNode and push the result back onto the
    // DFS stack — the Gap A bug discards the resolved node, so account LeafNodes are
    // never reached and totalFound == 0.
    val trie = MerklePatriciaTrie[ByteString, Account](serStorage)
      .put(ByteString(Array[Byte](0x00.toByte, 0x01.toByte)), account1)
      .put(ByteString(Array[Byte](0x10.toByte, 0x01.toByte)), account2)

    val stateRoot = ByteString(trie.getRootHash)

    val missing = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()
    val result = new StateValidator(serStorage).findMissingNodesStreaming(
      stateRoot,
      batchSize = 512,
      onBatch = batch => missing.addAll(batch)
    )

    // Before Fix 1: Right(0) — HashNode discarded, account LeafNodes never reached.
    // After Fix 1:  Right(2) — two missing storageRoot entries, one per account.
    result shouldBe Right(2)
    missing.map(_._2).toSet should contain(missingStorageRoot)
  }

  it should "not report missing entries for accounts with EmptyStorageRootHash (Gap A mixed accounts, T1.3)" taggedAs UnitTest in {
    val serStorage = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))

    val missingStorageRoot = kec256(ByteString("gap-a-mixed-missing-root"))

    // Distinct nonces so each LeafNode gets a unique hash (avoids visited-set dedup).
    val accountWithStorage1 = Account(nonce = 1, balance = 100, storageRoot = missingStorageRoot, codeHash = Account.EmptyCodeHash)
    val accountWithStorage2 = Account(nonce = 2, balance = 200, storageRoot = missingStorageRoot, codeHash = Account.EmptyCodeHash)
    // Default storageRoot == EmptyStorageRootHash — no storage trie to walk.
    val accountNoStorage = Account(nonce = 3, balance = 300)

    // 2 accounts with a missing storage root + 1 with no storage.
    // After Fix 1 the walk must reach each LeafNode and skip the empty-storage account.
    val trie = MerklePatriciaTrie[ByteString, Account](serStorage)
      .put(ByteString(Array[Byte](0x00.toByte, 0x01.toByte)), accountWithStorage1)
      .put(ByteString(Array[Byte](0x10.toByte, 0x01.toByte)), accountWithStorage2)
      .put(ByteString(Array[Byte](0x20.toByte, 0x01.toByte)), accountNoStorage)

    val stateRoot = ByteString(trie.getRootHash)

    val missing = mutable.ArrayBuffer[(Seq[ByteString], ByteString)]()
    val result = new StateValidator(serStorage).findMissingNodesStreaming(
      stateRoot,
      batchSize = 512,
      onBatch = batch => missing.addAll(batch)
    )

    // Before Fix 1: Right(0). After Fix 1: Right(2) — only the 2 non-empty accounts.
    result shouldBe Right(2)
    missing.map(_._2).toSet should contain(missingStorageRoot)
    missing.map(_._2) should not contain Account.EmptyStorageRootHash
  }

  it should "return Right(0) when all account and storage trie nodes are present (no false positives, T1.4)" taggedAs UnitTest in {
    val serStorage = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))

    // Build a storage trie whose root IS present in serStorage.
    val storageTrie = MerklePatriciaTrie[ByteString, ByteString](serStorage)
      .put(ByteString("slot1"), ByteString("value1"))
    val presentStorageRoot = ByteString(storageTrie.getRootHash)

    val account = Account(
      nonce = 1,
      balance = 500,
      storageRoot = presentStorageRoot,
      codeHash = Account.EmptyCodeHash
    )

    val trie = MerklePatriciaTrie[ByteString, Account](serStorage)
      .put(ByteString(Array[Byte](0x00.toByte, 0x01.toByte)), account)
      .put(ByteString(Array[Byte](0x10.toByte, 0x01.toByte)), account)

    val stateRoot = ByteString(trie.getRootHash)

    val result = new StateValidator(serStorage).findMissingNodesStreaming(
      stateRoot,
      batchSize = 512,
      onBatch = _ => ()
    )

    // Before Fix 1: Right(0) due to bug. After Fix 1: Right(0) because all nodes present.
    // This confirms Fix 1 does not introduce false positives.
    result shouldBe Right(0)
  }

  /** Simple in-memory test storage for MPT nodes */
  private class TestMptStorage extends MptStorage {
    private val nodes = mutable.Map[ByteString, MptNode]()

    override def get(key: Array[Byte]): MptNode = {
      val keyStr = ByteString(key)
      nodes
        .get(keyStr)
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

    private def storeNodeRecursively(node: MptNode): Unit =
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

    override def persist(): Unit = {
      // No-op for in-memory storage
    }
  }
}
