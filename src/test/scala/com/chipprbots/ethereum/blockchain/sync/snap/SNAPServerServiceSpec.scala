package com.chipprbots.ethereum.blockchain.sync.snap

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.{AppStateStorage, EvmCodeStorage, MptStorage}
import com.chipprbots.ethereum.domain.{Account, BlockchainReader}
import com.chipprbots.ethereum.mpt._
import com.chipprbots.ethereum.network.p2p.messages.SNAP._

class SNAPServerServiceSpec extends AnyFlatSpec with Matchers with MockFactory {

  "SNAPServerService" should "return empty AccountRange when state root not found" in {
    val blockchainReader = mock[BlockchainReader]
    val appStateStorage = mock[AppStateStorage]
    val mptStorage = mock[MptStorage]
    val evmCodeStorage = mock[EvmCodeStorage]

    val service = new SNAPServerService(
      blockchainReader,
      appStateStorage,
      mptStorage,
      evmCodeStorage
    )

    val request = GetAccountRange(
      requestId = BigInt(1),
      rootHash = ByteString("nonexistent"),
      startingHash = ByteString.empty,
      limitHash = ByteString("ff" * 32),
      responseBytes = BigInt(1024)
    )

    // Mock missing state root
    (mptStorage.get _).expects(*).throwing(new MerklePatriciaTrie.MissingNodeException(ByteString("test")))

    val response = service.handleGetAccountRange(request)

    response.requestId shouldEqual request.requestId
    response.accounts shouldBe empty
    response.proof shouldBe empty
  }

  it should "return empty StorageRanges when state root not found" in {
    val blockchainReader = mock[BlockchainReader]
    val appStateStorage = mock[AppStateStorage]
    val mptStorage = mock[MptStorage]
    val evmCodeStorage = mock[EvmCodeStorage]

    val service = new SNAPServerService(
      blockchainReader,
      appStateStorage,
      mptStorage,
      evmCodeStorage
    )

    val request = GetStorageRanges(
      requestId = BigInt(2),
      rootHash = ByteString("nonexistent"),
      accountHashes = Seq(ByteString("account1")),
      startingHash = ByteString.empty,
      limitHash = ByteString("ff" * 32),
      responseBytes = BigInt(1024)
    )

    // Mock missing state root
    (mptStorage.get _).expects(*).throwing(new MerklePatriciaTrie.MissingNodeException(ByteString("test")))

    val response = service.handleGetStorageRanges(request)

    response.requestId shouldEqual request.requestId
    response.slots shouldBe empty
    response.proof shouldBe empty
  }

  it should "return requested bytecodes from storage" in {
    val blockchainReader = mock[BlockchainReader]
    val appStateStorage = mock[AppStateStorage]
    val mptStorage = mock[MptStorage]
    val evmCodeStorage = mock[EvmCodeStorage]

    val service = new SNAPServerService(
      blockchainReader,
      appStateStorage,
      mptStorage,
      evmCodeStorage
    )

    val codeHash1 = ByteString("code1")
    val codeHash2 = ByteString("code2")
    val bytecode1 = ByteString("bytecode1")
    val bytecode2 = ByteString("bytecode2")

    val request = GetByteCodes(
      requestId = BigInt(3),
      hashes = Seq(codeHash1, codeHash2),
      responseBytes = BigInt(1024)
    )

    // Mock bytecode storage
    (evmCodeStorage.get _).expects(codeHash1).returning(Some(bytecode1))
    (evmCodeStorage.get _).expects(codeHash2).returning(Some(bytecode2))

    val response = service.handleGetByteCodes(request)

    response.requestId shouldEqual request.requestId
    response.codes should contain theSameElementsAs Seq(bytecode1, bytecode2)
  }

  it should "respect byte limits when returning bytecodes" in {
    val blockchainReader = mock[BlockchainReader]
    val appStateStorage = mock[AppStateStorage]
    val mptStorage = mock[MptStorage]
    val evmCodeStorage = mock[EvmCodeStorage]

    val service = new SNAPServerService(
      blockchainReader,
      appStateStorage,
      mptStorage,
      evmCodeStorage
    )

    val codeHash1 = ByteString("code1")
    val codeHash2 = ByteString("code2")
    val bytecode1 = ByteString("x" * 100)
    val bytecode2 = ByteString("y" * 100)

    val request = GetByteCodes(
      requestId = BigInt(4),
      hashes = Seq(codeHash1, codeHash2),
      responseBytes = BigInt(150) // Only enough for first bytecode
    )

    // Mock bytecode storage - second should not be called if limit reached
    (evmCodeStorage.get _).expects(codeHash1).returning(Some(bytecode1))
    // The second get may or may not be called depending on implementation details

    val response = service.handleGetByteCodes(request)

    response.requestId shouldEqual request.requestId
    // Should only return first bytecode due to byte limit
    response.codes.size shouldBe 1
    response.codes.head shouldEqual bytecode1
  }

  it should "omit missing bytecodes from response" in {
    val blockchainReader = mock[BlockchainReader]
    val appStateStorage = mock[AppStateStorage]
    val mptStorage = mock[MptStorage]
    val evmCodeStorage = mock[EvmCodeStorage]

    val service = new SNAPServerService(
      blockchainReader,
      appStateStorage,
      mptStorage,
      evmCodeStorage
    )

    val codeHash1 = ByteString("code1")
    val codeHash2 = ByteString("code2")
    val codeHash3 = ByteString("code3")
    val bytecode1 = ByteString("bytecode1")
    val bytecode3 = ByteString("bytecode3")

    val request = GetByteCodes(
      requestId = BigInt(5),
      hashes = Seq(codeHash1, codeHash2, codeHash3),
      responseBytes = BigInt(1024)
    )

    // Mock bytecode storage - second code is missing
    (evmCodeStorage.get _).expects(codeHash1).returning(Some(bytecode1))
    (evmCodeStorage.get _).expects(codeHash2).returning(None)
    (evmCodeStorage.get _).expects(codeHash3).returning(Some(bytecode3))

    val response = service.handleGetByteCodes(request)

    response.requestId shouldEqual request.requestId
    // Should omit missing bytecode, not include empty value
    response.codes should contain theSameElementsAs Seq(bytecode1, bytecode3)
  }

  it should "return empty TrieNodes when state root not found" in {
    val blockchainReader = mock[BlockchainReader]
    val appStateStorage = mock[AppStateStorage]
    val mptStorage = mock[MptStorage]
    val evmCodeStorage = mock[EvmCodeStorage]

    val service = new SNAPServerService(
      blockchainReader,
      appStateStorage,
      mptStorage,
      evmCodeStorage
    )

    val request = GetTrieNodes(
      requestId = BigInt(6),
      rootHash = ByteString("nonexistent"),
      paths = Seq(Seq(ByteString("path1"))),
      responseBytes = BigInt(1024)
    )

    // Mock missing state root
    (mptStorage.get _).expects(*).throwing(new MerklePatriciaTrie.MissingNodeException(ByteString("test")))

    val response = service.handleGetTrieNodes(request)

    response.requestId shouldEqual request.requestId
    response.nodes shouldBe empty
  }

  it should "handle configuration with custom limits" in {
    val blockchainReader = mock[BlockchainReader]
    val appStateStorage = mock[AppStateStorage]
    val mptStorage = mock[MptStorage]
    val evmCodeStorage = mock[EvmCodeStorage]

    val customConfig = SNAPServerService.SNAPServerConfig(
      maxResponseBytes = 1024,
      maxAccountsPerResponse = 100,
      maxStorageSlotsPerResponse = 200,
      maxByteCodesPerResponse = 50,
      maxTrieNodesPerResponse = 100
    )

    val service = new SNAPServerService(
      blockchainReader,
      appStateStorage,
      mptStorage,
      evmCodeStorage,
      customConfig
    )

    // Verify service was created with custom config
    service should not be null
  }
}
