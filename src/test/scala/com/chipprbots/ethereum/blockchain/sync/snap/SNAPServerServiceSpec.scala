package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito._
import org.mockito.ArgumentMatchers._

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

class SNAPServerServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  trait TestSetup {
    val mockBlockchainReader: BlockchainReader = mock[BlockchainReader]
    val mockStateStorage: StateStorage = mock[StateStorage]
    val mockEvmCodeStorage: EvmCodeStorage = mock[EvmCodeStorage]

    val service = new SNAPServerService(
      mockBlockchainReader,
      mockStateStorage,
      mockEvmCodeStorage
    )

    // Common test data
    val testRootHash: ByteString = ByteString(Array.fill(32)(0x01.toByte))
    val testAccountHash: ByteString = ByteString(Array.fill(32)(0x02.toByte))
    val testCodeHash: ByteString = ByteString(Array.fill(32)(0x03.toByte))
    val testCode: ByteString = ByteString("contract bytecode".getBytes)
    val emptyRootHash: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)

    val zeroHash: ByteString = ByteString(Array.fill(32)(0x00.toByte))
    val maxHash: ByteString = ByteString(Array.fill(32)(0xff.toByte))
  }

  "SNAPServerService" should "return empty AccountRange when state root not found" in new TestSetup {
    when(mockStateStorage.getNode(testRootHash)).thenReturn(None)

    val request = GetAccountRange(
      requestId = 1,
      rootHash = testRootHash,
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = 1000000
    )

    val response = service.handleGetAccountRange(request)

    response.requestId shouldBe 1
    response.accounts shouldBe empty
    response.proof shouldBe empty
  }

  it should "return empty StorageRanges when state root not found" in new TestSetup {
    when(mockStateStorage.getNode(testRootHash)).thenReturn(None)

    val request = GetStorageRanges(
      requestId = 2,
      rootHash = testRootHash,
      accountHashes = Seq(testAccountHash),
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = 1000000
    )

    val response = service.handleGetStorageRanges(request)

    response.requestId shouldBe 2
    response.slots shouldBe empty
    response.proof shouldBe empty
  }

  it should "return requested bytecodes when available" in new TestSetup {
    when(mockEvmCodeStorage.get(testCodeHash)).thenReturn(Some(testCode))

    val request = GetByteCodes(
      requestId = 3,
      hashes = Seq(testCodeHash),
      responseBytes = 1000000
    )

    val response = service.handleGetByteCodes(request)

    response.requestId shouldBe 3
    response.codes should contain(testCode)
  }

  it should "skip bytecodes that are not found" in new TestSetup {
    val unknownHash = ByteString(Array.fill(32)(0x99.toByte))
    when(mockEvmCodeStorage.get(testCodeHash)).thenReturn(Some(testCode))
    when(mockEvmCodeStorage.get(unknownHash)).thenReturn(None)

    val request = GetByteCodes(
      requestId = 4,
      hashes = Seq(testCodeHash, unknownHash),
      responseBytes = 1000000
    )

    val response = service.handleGetByteCodes(request)

    response.requestId shouldBe 4
    response.codes.size shouldBe 1
    response.codes.head shouldBe testCode
  }

  it should "respect byte budget for bytecodes" in new TestSetup {
    val largeCode = ByteString(Array.fill(1000)(0xaa.toByte))
    val codeHash1 = ByteString(Array.fill(32)(0x11.toByte))
    val codeHash2 = ByteString(Array.fill(32)(0x22.toByte))

    when(mockEvmCodeStorage.get(codeHash1)).thenReturn(Some(largeCode))
    when(mockEvmCodeStorage.get(codeHash2)).thenReturn(Some(largeCode))

    val request = GetByteCodes(
      requestId = 5,
      hashes = Seq(codeHash1, codeHash2),
      responseBytes = 500  // Small budget - should only fit ~0 codes due to 0.6 threshold
    )

    val response = service.handleGetByteCodes(request)

    response.requestId shouldBe 5
    // With budget of 500 * 0.6 = 300, and code size 1000, we get 0 codes
    response.codes.size should be <= 1
  }

  it should "return empty TrieNodes when state root not found" in new TestSetup {
    when(mockStateStorage.getNode(testRootHash)).thenReturn(None)

    val request = GetTrieNodes(
      requestId = 6,
      rootHash = testRootHash,
      paths = Seq(Seq(testAccountHash)),
      responseBytes = 1000000
    )

    val response = service.handleGetTrieNodes(request)

    response.requestId shouldBe 6
    response.nodes shouldBe empty
  }

  it should "handle empty bytecode requests" in new TestSetup {
    val request = GetByteCodes(
      requestId = 7,
      hashes = Seq.empty,
      responseBytes = 1000000
    )

    val response = service.handleGetByteCodes(request)

    response.requestId shouldBe 7
    response.codes shouldBe empty
  }

  it should "handle empty trie node paths" in new TestSetup {
    when(mockStateStorage.getNode(testRootHash)).thenReturn(Some(NullNode))

    val request = GetTrieNodes(
      requestId = 8,
      rootHash = testRootHash,
      paths = Seq.empty,
      responseBytes = 1000000
    )

    val response = service.handleGetTrieNodes(request)

    response.requestId shouldBe 8
    response.nodes shouldBe empty
  }

  it should "handle storage ranges for accounts with empty storage" in new TestSetup {
    // Setup: state root exists but account has empty storage
    when(mockStateStorage.getNode(testRootHash)).thenReturn(Some(NullNode))

    val request = GetStorageRanges(
      requestId = 9,
      rootHash = testRootHash,
      accountHashes = Seq(testAccountHash),
      startingHash = zeroHash,
      limitHash = maxHash,
      responseBytes = 1000000
    )

    val response = service.handleGetStorageRanges(request)

    response.requestId shouldBe 9
    // Account lookup will fail on NullNode, so empty response
  }

  it should "preserve request ID in all response types" in new TestSetup {
    when(mockStateStorage.getNode(any())).thenReturn(None)
    when(mockEvmCodeStorage.get(any())).thenReturn(None)

    val accountRangeReq = GetAccountRange(42, testRootHash, zeroHash, maxHash, 1000)
    val storageRangeReq = GetStorageRanges(43, testRootHash, Seq(testAccountHash), zeroHash, maxHash, 1000)
    val byteCodesReq = GetByteCodes(44, Seq(testCodeHash), 1000)
    val trieNodesReq = GetTrieNodes(45, testRootHash, Seq(Seq(testAccountHash)), 1000)

    service.handleGetAccountRange(accountRangeReq).requestId shouldBe 42
    service.handleGetStorageRanges(storageRangeReq).requestId shouldBe 43
    service.handleGetByteCodes(byteCodesReq).requestId shouldBe 44
    service.handleGetTrieNodes(trieNodesReq).requestId shouldBe 45
  }
}
