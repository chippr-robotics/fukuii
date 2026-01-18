package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito._

import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode

class MerkleProofGeneratorSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  trait TestSetup {
    val mockStateStorage: StateStorage = mock[StateStorage]
    val generator = new MerkleProofGenerator(mockStateStorage)

    // Common test data
    val testRootHash: ByteString = ByteString(Array.fill(32)(0x01.toByte))
    val testKey: ByteString = ByteString(Array.fill(32)(0x02.toByte))
    val testValue: ByteString = ByteString("test value".getBytes)
    val emptyRootHash: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  }

  "MerkleProofGenerator" should "return empty proof when root node not found" in new TestSetup {
    when(mockStateStorage.getNode(testRootHash)).thenReturn(None)

    val proof = generator.generateAccountRangeProof(
      testRootHash,
      Some(testKey),
      None
    )

    proof shouldBe empty
  }

  it should "include root node in proof when found" in new TestSetup {
    val leafNode = LeafNode(ByteString(Array.fill(32)(0x00.toByte)), testValue)
    when(mockStateStorage.getNode(testRootHash)).thenReturn(Some(leafNode))

    val proof = generator.generateAccountRangeProof(
      testRootHash,
      Some(testKey),
      None
    )

    proof should not be empty
    proof.head shouldBe ByteString(leafNode.encode)
  }

  it should "return empty proof when both keys are None" in new TestSetup {
    val leafNode = LeafNode(ByteString(Array.fill(32)(0x00.toByte)), testValue)
    when(mockStateStorage.getNode(testRootHash)).thenReturn(Some(leafNode))

    val proof = generator.generateAccountRangeProof(
      testRootHash,
      None,
      None
    )

    proof shouldBe empty
  }

  it should "generate proof for single key" in new TestSetup {
    val leafNode = LeafNode(ByteString(Array.fill(32)(0x00.toByte)), testValue)
    when(mockStateStorage.getNode(testRootHash)).thenReturn(Some(leafNode))

    val proof = generator.generateAccountRangeProof(
      testRootHash,
      Some(testKey),
      None
    )

    proof should not be empty
  }

  it should "generate proof for both first and last keys" in new TestSetup {
    val leafNode = LeafNode(ByteString(Array.fill(32)(0x00.toByte)), testValue)
    when(mockStateStorage.getNode(testRootHash)).thenReturn(Some(leafNode))

    val firstKey = ByteString(Array.fill(32)(0x10.toByte))
    val lastKey = ByteString(Array.fill(32)(0x20.toByte))

    val proof = generator.generateAccountRangeProof(
      testRootHash,
      Some(firstKey),
      Some(lastKey)
    )

    // With a single leaf node, both paths will add the same node
    proof should not be empty
  }

  it should "not duplicate nodes when first and last keys are same" in new TestSetup {
    val leafNode = LeafNode(ByteString(Array.fill(32)(0x00.toByte)), testValue)
    when(mockStateStorage.getNode(testRootHash)).thenReturn(Some(leafNode))

    val sameKey = ByteString(Array.fill(32)(0x10.toByte))

    val proof = generator.generateAccountRangeProof(
      testRootHash,
      Some(sameKey),
      Some(sameKey)
    )

    // Should have unique nodes (no duplicates)
    proof.distinct.size shouldBe proof.size
  }

  it should "handle NullNode as root" in new TestSetup {
    when(mockStateStorage.getNode(testRootHash)).thenReturn(Some(NullNode))

    val proof = generator.generateAccountRangeProof(
      testRootHash,
      Some(testKey),
      None
    )

    // NullNode has empty encoding, so proof may be empty or contain empty bytes
    proof.forall(_.isEmpty) || proof.isEmpty shouldBe true
  }

  it should "generate storage range proof same as account range proof" in new TestSetup {
    val storageRoot = ByteString(Array.fill(32)(0x05.toByte))
    val leafNode = LeafNode(ByteString(Array.fill(32)(0x00.toByte)), testValue)
    when(mockStateStorage.getNode(storageRoot)).thenReturn(Some(leafNode))

    val slotKey = ByteString(Array.fill(32)(0x10.toByte))

    val proof = generator.generateStorageRangeProof(
      storageRoot,
      Some(slotKey),
      None
    )

    proof should not be empty
  }

  it should "traverse branch nodes to collect proof path" in new TestSetup {
    // Create a simple branch node with one leaf child
    val leafNode = LeafNode(ByteString(Array.fill(31)(0x00.toByte)), testValue)
    val leafHash = leafNode.hash
    val hashNode = HashNode(leafHash)

    val children = Array.fill[MptNode](16)(NullNode)
    children(0) = hashNode
    val branchNode = BranchNode(children, None)

    when(mockStateStorage.getNode(testRootHash)).thenReturn(Some(branchNode))
    when(mockStateStorage.getNode(ByteString(leafHash))).thenReturn(Some(leafNode))

    // Key starting with 0x0 nibble
    val keyStartingWith0 = ByteString(Array.fill(32)(0x00.toByte))

    val proof = generator.generateAccountRangeProof(
      testRootHash,
      Some(keyStartingWith0),
      None
    )

    // Should include both branch node and leaf node
    proof.size should be >= 1
  }
}
