package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO

import scala.collection.immutable.NumericRange

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.FreeSpecBase
import com.chipprbots.ethereum.SpecFixtures
import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.LegacyTransaction
import com.chipprbots.ethereum.domain.SignedTransactionWithSender
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsRequest
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsResponse
import com.chipprbots.ethereum.nodebuilder.ApisBuilder
import com.chipprbots.ethereum.nodebuilder.JSONRpcConfigBuilder
import com.chipprbots.ethereum.nodebuilder.FukuiiServiceBuilder
import com.chipprbots.ethereum.nodebuilder.PendingTransactionsManagerBuilder
import com.chipprbots.ethereum.nodebuilder.TransactionHistoryServiceBuilder
import com.chipprbots.ethereum.nodebuilder.TxPoolConfigBuilder
import com.chipprbots.ethereum.transactions.TransactionHistoryService
import com.chipprbots.ethereum.transactions.TransactionHistoryService.ExtendedTransactionData
import com.chipprbots.ethereum.transactions.TransactionHistoryService.MinedTransactionData
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.testing.Tags._

class FukuiiServiceSpec
    extends TestKit(ActorSystem("FukuiiServiceSpec"))
    with FreeSpecBase
    with SpecFixtures
    with WithActorSystemShutDown {
  class Fixture
      extends TransactionHistoryServiceBuilder.Default
      with EphemBlockchainTestSetup
      with PendingTransactionsManagerBuilder
      with TxPoolConfigBuilder
      with FukuiiServiceBuilder
      with JSONRpcConfigBuilder
      with ApisBuilder {
    lazy val pendingTransactionsManagerProbe: TestProbe = TestProbe()
    override lazy val pendingTransactionsManager: ActorRef = pendingTransactionsManagerProbe.ref
  }
  def createFixture() = new Fixture

  "Fukuii Service" - {
    "should get account's transaction history" in {
      class TxHistoryFixture extends Fixture {
        val fakeTransaction: SignedTransactionWithSender = SignedTransactionWithSender(
          LegacyTransaction(
            nonce = 0,
            gasPrice = 123,
            gasLimit = 123,
            receivingAddress = Address("0x1234"),
            value = 0,
            payload = ByteString()
          ),
          signature = ECDSASignature(0, 0, 0.toByte),
          sender = Address("0x1234")
        )

        val block: Block =
          BlockHelpers.generateBlock(BlockHelpers.genesis).copy(body = BlockBody(List(fakeTransaction.tx), Nil))

        val expectedResponse: List[ExtendedTransactionData] = List(
          ExtendedTransactionData(
            fakeTransaction.tx,
            isOutgoing = true,
            Some(MinedTransactionData(block.header, 0, 42, isCheckpointed = false))
          )
        )

        override lazy val transactionHistoryService: TransactionHistoryService =
          new TransactionHistoryService(
            blockchainReader,
            pendingTransactionsManager,
            txPoolConfig.getTransactionFromPoolTimeout
          ) {
            override def getAccountTransactions(account: Address, fromBlocks: NumericRange[BigInt])(implicit
                blockchainConfig: BlockchainConfig
            ): IO[List[ExtendedTransactionData]] =
              IO.pure(expectedResponse)
          }
      }

      customTestCaseM(new TxHistoryFixture) { fixture =>
        import fixture._

        fukuiiService
          .getAccountTransactions(GetAccountTransactionsRequest(fakeTransaction.senderAddress, BigInt(0) to BigInt(1)))
          .map(result => assert(result === Right(GetAccountTransactionsResponse(expectedResponse))))
      }
    }

    "should validate range size against configuration" in testCaseM { (fixture: Fixture) =>
      import fixture._

      fukuiiService
        .getAccountTransactions(
          GetAccountTransactionsRequest(Address(1), BigInt(0) to BigInt(jsonRpcConfig.accountTransactionsMaxBlocks + 1))
        )
        .map(result => assert(result.isLeft))
    }
  }
}
