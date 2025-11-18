package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.scalactic.TypeCheckedTripleEquals
import org.scalamock.scalatest.MockFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum._
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.EthUserService._
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils._

class EthUserServiceSpec
    extends TestKit(ActorSystem("EthServiceSpec_ActorSystem"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers
    with ScalaFutures
    with OptionValues
    with MockFactory
    with NormalPatience
    with TypeCheckedTripleEquals {

  implicit val runtime: IORuntime = IORuntime.global

  it should "handle getCode request" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val address: Address = Address(ByteString(Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")))
    storagesInstance.storages.evmCodeStorage.put(ByteString("code hash"), ByteString("code code code")).commit()

    import MerklePatriciaTrie.defaultByteArraySerializable

    val mpt: MerklePatriciaTrie[Array[Byte], Account] =
      MerklePatriciaTrie[Array[Byte], Account](storagesInstance.storages.stateStorage.getBackingStorage(0))
        .put(
          crypto.kec256(address.bytes.toArray[Byte]),
          Account(0, UInt256(0), ByteString(""), ByteString("code hash"))
        )

    val newBlockHeader: BlockHeader = blockToRequest.header.copy(stateRoot = ByteString(mpt.getRootHash))
    val newblock: Block = blockToRequest.copy(header = newBlockHeader)
    blockchainWriter.storeBlock(newblock).commit()
    blockchainWriter.saveBestKnownBlocks(newblock.hash, newblock.number)

    val response: ServiceResponse[GetCodeResponse] = ethUserService.getCode(GetCodeRequest(address, BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(GetCodeResponse(ByteString("code code code")))
  }

  it should "handle getBalance request" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val address: Address = Address(ByteString(Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")))

    import MerklePatriciaTrie.defaultByteArraySerializable

    val mpt: MerklePatriciaTrie[Array[Byte], Account] =
      MerklePatriciaTrie[Array[Byte], Account](storagesInstance.storages.stateStorage.getBackingStorage(0))
        .put(
          crypto.kec256(address.bytes.toArray[Byte]),
          Account(0, UInt256(123), ByteString(""), ByteString("code hash"))
        )

    val newBlockHeader: BlockHeader = blockToRequest.header.copy(stateRoot = ByteString(mpt.getRootHash))
    val newblock: Block = blockToRequest.copy(header = newBlockHeader)
    blockchainWriter.storeBlock(newblock).commit()
    blockchainWriter.saveBestKnownBlocks(newblock.hash, newblock.number)

    val response: ServiceResponse[GetBalanceResponse] =
      ethUserService.getBalance(GetBalanceRequest(address, BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(GetBalanceResponse(123))
  }

  it should "handle MissingNodeException when getting balance" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val address: Address = Address(ByteString(Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")))

    val newBlockHeader = blockToRequest.header
    val newblock: Block = blockToRequest.copy(header = newBlockHeader)
    blockchainWriter.storeBlock(newblock).commit()
    blockchainWriter.saveBestKnownBlocks(newblock.hash, newblock.header.number)

    val response: ServiceResponse[GetBalanceResponse] =
      ethUserService.getBalance(GetBalanceRequest(address, BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Left(JsonRpcError.NodeNotFound)
  }
  it should "handle getStorageAt request" taggedAs (UnitTest, RPCTest) in new TestSetup {

    val address: Address = Address(ByteString(Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")))

    import MerklePatriciaTrie.defaultByteArraySerializable

    val storageMpt: MerklePatriciaTrie[BigInt, BigInt] =
      com.chipprbots.ethereum.domain.EthereumUInt256Mpt
        .storageMpt(
          ByteString(MerklePatriciaTrie.EmptyRootHash),
          storagesInstance.storages.stateStorage.getBackingStorage(0)
        )
        .put(UInt256(333), UInt256(123))

    val mpt: MerklePatriciaTrie[Array[Byte], Account] =
      MerklePatriciaTrie[Array[Byte], Account](storagesInstance.storages.stateStorage.getBackingStorage(0))
        .put(
          crypto.kec256(address.bytes.toArray[Byte]),
          Account(0, UInt256(0), ByteString(storageMpt.getRootHash), ByteString(""))
        )

    val newBlockHeader: BlockHeader = blockToRequest.header.copy(stateRoot = ByteString(mpt.getRootHash))
    val newblock: Block = blockToRequest.copy(header = newBlockHeader)
    blockchainWriter.storeBlock(newblock).commit()
    blockchainWriter.saveBestKnownBlocks(newblock.hash, newblock.number)

    val response: ServiceResponse[GetStorageAtResponse] =
      ethUserService.getStorageAt(GetStorageAtRequest(address, 333, BlockParam.Latest))
    response.unsafeRunSync().map(v => UInt256(v.value)) shouldEqual Right(UInt256(123))
  }

  it should "handle get transaction count request" taggedAs (UnitTest, RPCTest) in new TestSetup {
    val address: Address = Address(ByteString(Hex.decode("abbb6bebfa05aa13e908eaa492bd7a8343760477")))

    import MerklePatriciaTrie.defaultByteArraySerializable

    val mpt: MerklePatriciaTrie[Array[Byte], Account] =
      MerklePatriciaTrie[Array[Byte], Account](storagesInstance.storages.stateStorage.getBackingStorage(0))
        .put(crypto.kec256(address.bytes.toArray[Byte]), Account(999, UInt256(0), ByteString(""), ByteString("")))

    val newBlockHeader: BlockHeader = blockToRequest.header.copy(stateRoot = ByteString(mpt.getRootHash))
    val newblock: Block = blockToRequest.copy(header = newBlockHeader)
    blockchainWriter.storeBlock(newblock).commit()
    blockchainWriter.saveBestKnownBlocks(newblock.hash, newblock.number)

    val response: ServiceResponse[GetTransactionCountResponse] =
      ethUserService.getTransactionCount(GetTransactionCountRequest(address, BlockParam.Latest))

    response.unsafeRunSync() shouldEqual Right(GetTransactionCountResponse(BigInt(999)))
  }

  class TestSetup() extends EphemBlockchainTestSetup {
    lazy val ethUserService = new EthUserService(
      blockchain,
      blockchainReader,
      mining,
      storagesInstance.storages.evmCodeStorage,
      this
    )
    val blockToRequest: Block = Block(Fixtures.Blocks.Block3125369.header, Fixtures.Blocks.Block3125369.body)
  }

}
