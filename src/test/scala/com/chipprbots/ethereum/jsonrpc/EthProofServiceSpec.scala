package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import com.softwaremill.diffx.scalatest.DiffMatcher
import org.bouncycastle.util.encoders.Hex
import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.*
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager
import com.chipprbots.ethereum.consensus.engine.ForkChoiceState
import com.chipprbots.ethereum.consensus.pow.blocks.PoWBlockGenerator
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.BlockParam.WithHash
import com.chipprbots.ethereum.jsonrpc.EthUserService.GetBalanceRequest
import com.chipprbots.ethereum.jsonrpc.EthUserService.GetBalanceResponse
import com.chipprbots.ethereum.jsonrpc.EthUserService.GetStorageAtRequest
import com.chipprbots.ethereum.jsonrpc.EthUserService.GetTransactionCountRequest
import com.chipprbots.ethereum.jsonrpc.EthUserService.GetTransactionCountResponse
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofResponse
import com.chipprbots.ethereum.jsonrpc.ProofService.ProofAccount
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.nodebuilder.ApisBuilder
import com.chipprbots.ethereum.testing.Tags.*

class EthProofServiceSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with EitherValues
    with MockFactory
    with NormalPatience
    with TypeCheckedTripleEquals
    with DiffMatcher:

  implicit val runtime: IORuntime = IORuntime.global

  "EthProofService" should "handle getStorageAt request" taggedAs (UnitTest, RPCTest) in new TestSetup:
    val request: GetProofRequest = GetProofRequest(address, storageKeys, blockNumber)
    val result: cats.effect.IO[Either[JsonRpcError, GetProofResponse]] = ethGetProof.getProof(request)

    val balanceResponse: GetBalanceResponse = ethUserService
      .getBalance(GetBalanceRequest(address, BlockParam.Latest))
      .unsafeRunSync()
      .getOrElse(fail("ethUserService.getBalance did not get valid response"))

    val transactionCountResponse: GetTransactionCountResponse = ethUserService
      .getTransactionCount(GetTransactionCountRequest(address, BlockParam.Latest))
      .unsafeRunSync()
      .getOrElse(fail("ethUserService.getTransactionCount did not get valid response"))

    val storageValues: Seq[ByteString] = storageKeys.map { position =>
      ethUserService
        .getStorageAt(GetStorageAtRequest(address, position.v, BlockParam.Latest))
        .unsafeRunSync()
        .getOrElse(fail("ethUserService.getStorageAt did not get valid response"))
        .value
    }

    val givenResult: ProofAccount = result
      .unsafeRunSync()
      .getOrElse(fail())
      .proofAccount

    val givenAddress = givenResult.address
    givenAddress shouldBe address
    givenResult.codeHash shouldBe account.codeHash.value
    givenResult.storageHash shouldBe account.storageRoot.value

    givenResult.nonce shouldBe UInt256(transactionCountResponse.value)

    givenResult.balance shouldBe balanceResponse.value

    givenResult.storageProof.map(_.key) shouldBe storageKeys
    givenResult.storageProof.map(_.value.toString) shouldBe storageValues.map(_.mkString)
    givenResult.storageProof.map(_.proof).foreach { p =>
      p should not be empty
    }

  "EthProofService" should "prove a non-existing account absent, with zero hashes and empty storage proofs (get-account-proof-missing-account)" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val wrongAddress: Address = Address(666)
    val keys: Seq[StorageProofKey] = Seq(StorageProofKey(0), StorageProofKey(1, fixed32 = true))
    val result: ProofAccount = fetchProof(wrongAddress, keys, blockNumber).unsafeRunSync().value.proofAccount
    result.address shouldBe wrongAddress
    // the proof of absence starts at the state root, as any account proof does
    result.accountProof should not be empty
    ByteString(crypto.kec256(result.accountProof.head.toArray[Byte])) shouldBe ByteString(mpt.getRootHash)
    result.balance shouldBe BigInt(0)
    result.nonce shouldBe UInt256(0)
    result.codeHash shouldBe ByteString(new Array[Byte](32))
    result.storageHash shouldBe ByteString(new Array[Byte](32))
    // one entry per key, in request order, each keeping the key as requested
    result.storageProof.map(sp => (sp.key, sp.value, sp.proof)) shouldBe
      Seq((keys.head, BigInt(0), Seq.empty), (keys(1), BigInt(0), Seq.empty))

  "EthProofService" should "answer each storage key with the key as the request wrote it" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val keys: Seq[StorageProofKey] = Seq(StorageProofKey(key), StorageProofKey(key, fixed32 = true))
    val result: ProofAccount = fetchProof(address, keys, blockNumber).unsafeRunSync().value.proofAccount
    result.storageProof.map(_.key) shouldBe keys
    result.storageProof.map(_.value) shouldBe Seq(BigInt(value), BigInt(value))

  "eth_getProof storage keys" should "decode as geth does (get-storage-proof-key-*, -invalid-key*)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    import org.json4s.JsonAST.*
    val addr = "0x7dcd17433742f4c0ca53122ab541d0ba67fc27df"
    def decode(key: String): Either[JsonRpcError, Seq[StorageProofKey]] =
      EthProofJsonMethodsImplicits.eth_getProof
        .decodeJson(Some(JArray(List(JString(addr), JArray(List(JString(key))), JString("latest")))))
        .map(_.storageKeys)
    def keyOf(digits: Int): String = "0x" + "0" * (digits - 1) + "1"
    // 0x and 0x00 are slot 0; a short key keeps no width; 63 and 64 digits are 32 bytes
    decode("0x") shouldBe Right(Seq(StorageProofKey(0)))
    decode("0x0") shouldBe Right(Seq(StorageProofKey(0)))
    decode("0x00") shouldBe Right(Seq(StorageProofKey(0)))
    decode("0x01") shouldBe Right(Seq(StorageProofKey(1)))
    decode(keyOf(62)) shouldBe Right(Seq(StorageProofKey(1)))
    decode(keyOf(63)) shouldBe Right(Seq(StorageProofKey(1, fixed32 = true)))
    decode(keyOf(64)) shouldBe Right(Seq(StorageProofKey(1, fixed32 = true)))
    // 65 digits is 33 bytes
    val tooLong = keyOf(66)
    decode(tooLong) shouldBe Left(
      JsonRpcError(-32602, s"storage key too long (want at most 32 bytes): \"$tooLong\"", None)
    )
    decode("0xzz") shouldBe Left(JsonRpcError(-32602, "invalid hex in storage key: \"0xzz\"", None))
  }

  "eth_getProof storage keys" should "encode as 32 bytes when written with 63 or 64 digits, else as a quantity" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    import org.json4s.JsonAST.*
    import org.json4s.jvalue2monadic
    val proof = ProofAccount(
      Address(1),
      Seq(ByteString(1)),
      BigInt(0),
      ByteString(new Array[Byte](32)),
      UInt256(0),
      ByteString(new Array[Byte](32)),
      Seq(
        ProofService.EmptyStorageValueProof(StorageProofKey(0)),
        ProofService.EmptyStorageValueProof(StorageProofKey(1)),
        ProofService.EmptyStorageValueProof(StorageProofKey(1, fixed32 = true))
      )
    )
    val json = EthProofJsonMethodsImplicits.eth_getProof.encodeJson(GetProofResponse(proof))
    val entries = (json \ "storageProof").children
    val keys = entries.map(e => e \ "key")
    keys shouldBe List(JString("0x0"), JString("0x1"), JString("0x" + "00" * 31 + "01"))
  }

  "EthProofService" should "answer a block whose state is not held with the missing-node error, as eth_getBalance does (B-210)" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    // a block stored by hash whose state root names no node: a hash-store block whose state was pruned or never synced
    val missingRoot: TrieRoot = TrieRoot(ByteString(crypto.kec256("no such state".getBytes)))
    val orphan: Block =
      newblock.copy(header = newblock.header.copy(stateRoot = missingRoot))
    blockchainWriter.storeBlock(orphan).commit()
    val byHash: WithHash = BlockParam.WithHash(orphan.hash.value)
    fetchProof(address, storageKeys, byHash).unsafeRunSync() shouldBe Left(JsonRpcError.NodeNotFound)
    ethUserService.getBalance(GetBalanceRequest(address, byHash)).unsafeRunSync() shouldBe Left(
      JsonRpcError.NodeNotFound
    )

  "EthProofService" should "name \"safe\" and \"finalized\" by the consensus client's fork choice, as the other state reads do" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    // no fork choice (a PoW chain, or before the first forkchoiceUpdated): not the head, but not available
    fetchProof(address, storageKeys, BlockParam.Safe).unsafeRunSync() shouldBe
      Left(JsonRpcError.InvalidParams("safe block not available"))
    fetchProof(address, storageKeys, BlockParam.Finalized).unsafeRunSync() shouldBe
      Left(JsonRpcError.InvalidParams("finalized block not available"))
    val forkChoice = new ForkChoiceManager(blockchainReader, blockchainWriter, blockchain)
    val head = newblock.hash.value
    forkChoice.applyForkChoiceState(ForkChoiceState(head, head, head)) shouldBe Right(())
    val withForkChoice = new EthProofService(
      blockchain,
      blockchainReader,
      blockGenerator,
      blockchainConfig.ethCompatibleStorage,
      Some(forkChoice)
    )
    for tag <- Seq(BlockParam.Safe, BlockParam.Finalized) do
      val proof = withForkChoice.getProof(GetProofRequest(address, storageKeys, tag)).unsafeRunSync().value.proofAccount
      ByteString(crypto.kec256(proof.accountProof.head.toArray[Byte])) shouldBe ByteString(mpt.getRootHash)
      proof.balance shouldBe balance.toBigInt

  "EthProofService" should "return the proof with empty value for non-existing storage key" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val wrongStorageKey: Seq[StorageProofKey] = Seq(StorageProofKey(321))
    val result: Either[JsonRpcError, GetProofResponse] =
      fetchProof(address, wrongStorageKey, blockNumber).unsafeRunSync()
    result.isRight shouldBe true
    result.fold(
      l => l,
      r =>
        val accountProof = r.proofAccount
        val accProofAddr = accountProof.address; accProofAddr shouldBe address
        accountProof.accountProof.foreach { p =>
          p should not be empty
        }
        // The root proof element is now the full resolved node (since fix
        // 0596810a9), so compare via keccak256(first-element) against the trie's
        // root hash rather than the old raw-hash-reference encoding.
        ByteString(crypto.kec256(accountProof.accountProof.head.toArray[Byte])) shouldBe ByteString(mpt.getRootHash)
        accountProof.balance shouldBe balance.toBigInt
        accountProof.codeHash shouldBe account.codeHash.value
        accountProof.nonce shouldBe UInt256(nonce)
        accountProof.storageHash shouldBe account.storageRoot.value
        accountProof.storageProof.map { v =>
          v.proof.nonEmpty shouldBe true
          v.value shouldBe BigInt(0)
        }
    )

  "EthProofService" should "return the proof and value for existing storage key" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val storageKey: Seq[StorageProofKey] = Seq(StorageProofKey(key))
    val result: Either[JsonRpcError, GetProofResponse] = fetchProof(address, storageKey, blockNumber).unsafeRunSync()
    result.isRight shouldBe true
    result.fold(
      l => l,
      r =>
        val accountProof = r.proofAccount
        val accProofAddr = accountProof.address; accProofAddr shouldBe address
        accountProof.accountProof.foreach { p =>
          p should not be empty
        }
        // The root proof element is now the full resolved node (since fix
        // 0596810a9), so compare via keccak256(first-element) against the trie's
        // root hash rather than the old raw-hash-reference encoding.
        ByteString(crypto.kec256(accountProof.accountProof.head.toArray[Byte])) shouldBe ByteString(mpt.getRootHash)
        accountProof.balance shouldBe balance.toBigInt
        accountProof.codeHash shouldBe account.codeHash.value
        accountProof.nonce shouldBe UInt256(nonce)
        accountProof.storageHash shouldBe account.storageRoot.value
        r.proofAccount.storageProof.map { v =>
          v.proof.nonEmpty shouldBe true
          v.value shouldBe BigInt(value)
        }
    )

  "EthProofService" should "return the proof and value for multiple existing storage keys" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val storageKey: Seq[StorageProofKey] = Seq(StorageProofKey(key), StorageProofKey(key2))
    val expectedValueStorageKey: Seq[BigInt] = Seq(BigInt(value), BigInt(value2))
    val result: Either[JsonRpcError, GetProofResponse] = fetchProof(address, storageKey, blockNumber).unsafeRunSync()
    result.isRight shouldBe true
    result.fold(
      l => l,
      r =>
        val accountProof = r.proofAccount
        val accProofAddr = accountProof.address; accProofAddr shouldBe address
        accountProof.accountProof.foreach { p =>
          p should not be empty
        }
        // The root proof element is now the full resolved node (since fix
        // 0596810a9), so compare via keccak256(first-element) against the trie's
        // root hash rather than the old raw-hash-reference encoding.
        ByteString(crypto.kec256(accountProof.accountProof.head.toArray[Byte])) shouldBe ByteString(mpt.getRootHash)
        accountProof.balance shouldBe balance.toBigInt
        accountProof.codeHash shouldBe account.codeHash.value
        accountProof.nonce shouldBe UInt256(nonce)
        accountProof.storageHash shouldBe account.storageRoot.value
        accountProof.storageProof.size shouldBe 2
        accountProof.storageProof.map { v =>
          v.proof.nonEmpty shouldBe true
          expectedValueStorageKey should contain(v.value)
        }
    )

  "EthProofService" should "return the proof for all storage keys provided, but value should be returned only for the existing ones" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val wrongStorageKey: StorageProofKey = StorageProofKey(321)
    val storageKey: Seq[StorageProofKey] = Seq(StorageProofKey(key), StorageProofKey(key2)) :+ wrongStorageKey
    val expectedValueStorageKey: Seq[BigInt] = Seq(BigInt(value), BigInt(value2), BigInt(0))
    val result: Either[JsonRpcError, GetProofResponse] = fetchProof(address, storageKey, blockNumber).unsafeRunSync()
    result.isRight shouldBe true
    result.fold(
      l => l,
      r =>
        val accountProof = r.proofAccount
        val accProofAddr = accountProof.address; accProofAddr shouldBe address
        accountProof.accountProof.foreach { p =>
          p should not be empty
        }
        // The root proof element is now the full resolved node (since fix
        // 0596810a9), so compare via keccak256(first-element) against the trie's
        // root hash rather than the old raw-hash-reference encoding.
        ByteString(crypto.kec256(accountProof.accountProof.head.toArray[Byte])) shouldBe ByteString(mpt.getRootHash)
        accountProof.balance shouldBe balance.toBigInt
        accountProof.codeHash shouldBe account.codeHash.value
        accountProof.nonce shouldBe UInt256(nonce)
        accountProof.storageHash shouldBe account.storageRoot.value
        accountProof.storageProof.size shouldBe 3
        expectedValueStorageKey.forall(accountProof.storageProof.map(_.value).contains) shouldBe true
    )

  "EthProofService" should "return account proof and account details, with empty storage proof" taggedAs (
    UnitTest,
    RPCTest
  ) in new TestSetup:
    val result: Either[JsonRpcError, GetProofResponse] = fetchProof(address, Seq.empty, blockNumber).unsafeRunSync()
    result.isRight shouldBe true
    result.fold(
      l => l,
      r =>
        val accountProof = r.proofAccount
        val accProofAddr = accountProof.address; accProofAddr shouldBe address
        accountProof.accountProof.foreach { p =>
          p should not be empty
        }
        // The root proof element is now the full resolved node (since fix
        // 0596810a9), so compare via keccak256(first-element) against the trie's
        // root hash rather than the old raw-hash-reference encoding.
        ByteString(crypto.kec256(accountProof.accountProof.head.toArray[Byte])) shouldBe ByteString(mpt.getRootHash)
        accountProof.balance shouldBe balance.toBigInt
        accountProof.codeHash shouldBe account.codeHash.value
        accountProof.nonce shouldBe UInt256(nonce)
        accountProof.storageHash shouldBe account.storageRoot.value
        accountProof.storageProof.size shouldBe 0
    )

  class TestSetup() extends EphemBlockchainTestSetup with ApisBuilder:
    val blockGenerator: PoWBlockGenerator = mock[PoWBlockGenerator]
    val address: Address = Address(ByteString(Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")))
    val balance: UInt256 = UInt256(0)
    val nonce = 0

    val key = 333
    val value = 123
    val key1 = 334
    val value1 = 124
    val key2 = 335
    val value2 = 125

    val storageMpt: MerklePatriciaTrie[BigInt, BigInt] = EthereumUInt256Mpt
      .storageMpt(
        ByteString(MerklePatriciaTrie.EmptyRootHash),
        storagesInstance.storages.stateStorage.getBackingStorage(0)
      )
      .put(UInt256(key), UInt256(value))
      .put(UInt256(key1), UInt256(value1))
      .put(UInt256(key2), UInt256(value2))

    val account: Account = Account(
      nonce = nonce,
      balance = balance,
      storageRoot = TrieRoot(ByteString(storageMpt.getRootHash))
    )

    val mpt: MerklePatriciaTrie[Array[Byte], Account] =
      MerklePatriciaTrie[Array[Byte], Account](storagesInstance.storages.stateStorage.getBackingStorage(0))
        .put(
          crypto.kec256(address.bytes.toArray[Byte]),
          account
        )

    val blockToRequest: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
    val newBlockHeader: BlockHeader = blockToRequest.header.copy(stateRoot = TrieRoot(ByteString(mpt.getRootHash)))
    val newblock: Block = blockToRequest.copy(header = newBlockHeader)
    blockchainWriter.storeBlock(newblock).commit()
    blockchainWriter.saveBestKnownBlocks(newblock.hash, newblock.number.value)

    val ethGetProof =
      new EthProofService(blockchain, blockchainReader, blockGenerator, blockchainConfig.ethCompatibleStorage)

    val storageKeys: Seq[StorageProofKey] = Seq(StorageProofKey(key))
    val blockNumber = BlockParam.Latest

    def fetchProof(
        address: Address,
        storageKeys: Seq[StorageProofKey],
        blockNumber: BlockParam
    ): ServiceResponse[ProofService.GetProofResponse] =
      val request = GetProofRequest(address, storageKeys, blockNumber)
      val retrievedAccountProof: ServiceResponse[ProofService.GetProofResponse] = ethGetProof.getProof(request)
      retrievedAccountProof

    val ethUserService = new EthUserService(
      blockchain,
      blockchainReader,
      mining,
      storagesInstance.storages.evmCodeStorage,
      this
    )
