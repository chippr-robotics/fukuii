package com.chipprbots.ethereum.extvm

import org.apache.pekko.util.ByteString

import org.scalamock.scalatest.MockFactory
import org.scalatest.Ignore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalapb.GeneratedMessageCompanion

import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.extvm.msg.CallContext
import com.chipprbots.ethereum.extvm.msg.EthereumConfig
import com.chipprbots.ethereum.extvm.msg.Hello
import com.chipprbots.ethereum.extvm.msg.VMQuery

/** HIBERNATED: External VM features are currently in hibernation. These features are experimental and not core to
  * fukuii's functioning. Tests are ignored to prevent blocking development until the feature is fully developed.
  */
@Ignore
class VMServerSpec extends AnyFlatSpec with Matchers with MockFactory {

  import com.chipprbots.ethereum.Fixtures.Blocks._
  import Implicits._

  "VMServer" should "start and await hello message" in new TestSetup {
    inSequence {
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.Hello])).expects(*).returns(helloMsg)
      (messageHandler
        .awaitMessage(_: GeneratedMessageCompanion[msg.CallContext]))
        .expects(*)
        .throwing(new RuntimeException) // connection closed
      (messageHandler.close _).expects()
    }
    vmServer.run()
    vmServer.processingThread.join()
  }

  it should "handle incoming call context msg and respond with a call result" in new TestSetup {
    val blockHeader = Block3125369.header
    val blockHeaderMsg = msg.BlockHeader(
      beneficiary = blockHeader.beneficiary,
      difficulty = blockHeader.difficulty,
      number = blockHeader.number,
      gasLimit = blockHeader.gasLimit,
      unixTimestamp = blockHeader.unixTimestamp
    )

    val callContextMsg = msg.CallContext(
      callerAddr = Address("0x1001").bytes,
      recipientAddr = Address("0x1002").bytes,
      inputData = ByteString(),
      callValue = ByteString(BigInt(10).toByteArray),
      gasPrice = ByteString(BigInt(0).toByteArray),
      gasProvided = ByteString(BigInt(1000).toByteArray),
      blockHeader = Some(blockHeaderMsg),
      config = CallContext.Config.Empty
    )

    val expectedModifiedAccount1 = msg.ModifiedAccount(
      address = Address("0x1001").bytes,
      nonce = ByteString(BigInt(0).toByteArray),
      balance = ByteString(BigInt(90).toByteArray),
      storageUpdates = Nil,
      code = ByteString()
    )

    val expectedModifiedAccount2 = msg.ModifiedAccount(
      address = Address("0x1002").bytes,
      nonce = ByteString(BigInt(0).toByteArray),
      balance = ByteString(BigInt(210).toByteArray),
      storageUpdates = Nil,
      code = ByteString()
    )

    val expectedCallResultMsg = msg.VMQuery(query =
      msg.VMQuery.Query.CallResult(
        msg.CallResult(
          returnData = ByteString(),
          returnCode = ByteString(),
          gasRemaining = ByteString(BigInt(1000).toByteArray),
          gasRefund = ByteString(BigInt(0).toByteArray),
          error = false,
          modifiedAccounts = Seq(expectedModifiedAccount1, expectedModifiedAccount2),
          deletedAccounts = Nil,
          touchedAccounts = Seq(Address("0x1001").bytes, Address("0x1002").bytes),
          logs = Nil
        )
      )
    )

    inSequence {
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.Hello])).expects(*).returns(helloMsg)
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.CallContext])).expects(*).returns(callContextMsg)
      expectAccountQuery(Address("0x1001"), response = Account(0, 100))
      expectAccountQuery(Address("0x1002"), response = Account(0, 200))
      expectCodeQuery(Address("0x1002"), response = ByteString())
      expectCodeQuery(Address("0x1001"), response = ByteString())
      (messageHandler.sendMessage _).expects(expectedCallResultMsg)
      (messageHandler
        .awaitMessage(_: GeneratedMessageCompanion[msg.CallContext]))
        .expects(*)
        .throwing(new RuntimeException) // connection closed
      (messageHandler.close _).expects()
    }

    vmServer.run()
    vmServer.processingThread.join()
  }

  trait TestSetup {
    val blockchainConfig = com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig
    val forkBlockNumbers = blockchainConfig.forkBlockNumbers
    val ethereumConfig: EthereumConfig = msg.EthereumConfig(
      frontierBlockNumber = forkBlockNumbers.frontierBlockNumber,
      homesteadBlockNumber = forkBlockNumbers.homesteadBlockNumber,
      eip150BlockNumber = forkBlockNumbers.eip150BlockNumber,
      eip160BlockNumber = forkBlockNumbers.eip160BlockNumber,
      eip161BlockNumber = forkBlockNumbers.eip161BlockNumber,
      byzantiumBlockNumber = forkBlockNumbers.byzantiumBlockNumber,
      constantinopleBlockNumber = forkBlockNumbers.constantinopleBlockNumber,
      petersburgBlockNumber = forkBlockNumbers.petersburgBlockNumber,
      istanbulBlockNumber = forkBlockNumbers.istanbulBlockNumber,
      maxCodeSize = ByteString(),
      accountStartNonce = blockchainConfig.accountStartNonce,
      chainId = ByteString(blockchainConfig.chainId)
    )
    val ethereumConfigMsg: Hello.Config.EthereumConfig = msg.Hello.Config.EthereumConfig(ethereumConfig)
    val helloMsg: Hello = msg.Hello(version = "2.2", config = ethereumConfigMsg)

    // MIGRATION: Scala 3 requires explicit type ascription for mock with complex parameterized types
    val messageHandler: MessageHandler = mock[MessageHandler].asInstanceOf[MessageHandler]
    val vmServer = new VMServer(messageHandler)

    def expectAccountQuery(address: Address, response: Account): Unit = {
      val expectedQueryMsg = msg.VMQuery(VMQuery.Query.GetAccount(msg.GetAccount(address.bytes)))
      (messageHandler.sendMessage _).expects(expectedQueryMsg)
      val accountMsg =
        msg.Account(ByteString(response.nonce.toBigInt.toByteArray), ByteString(response.balance.toBigInt.toByteArray))
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.Account])).expects(*).returns(accountMsg)
    }

    def expectCodeQuery(address: Address, response: ByteString): Unit = {
      val expectedQueryMsg = msg.VMQuery(VMQuery.Query.GetCode(msg.GetCode(address.bytes)))
      (messageHandler.sendMessage _).expects(expectedQueryMsg)
      val codeMsg = msg.Code(response)
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.Code])).expects(*).returns(codeMsg)
    }
  }

}
