package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO

import org.json4s.Extraction
import org.json4s.JArray
import org.json4s.JBool
import org.json4s.JInt
import org.json4s.JLong
import org.json4s.JObject
import org.json4s.JString
import org.scalamock.scalatest.AsyncMockFactory

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.FreeSpecBase
import com.chipprbots.ethereum.SpecFixtures
import com.chipprbots.ethereum.jsonrpc.FukuiiService.GetAccountTransactionsResponse
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.nodebuilder.ApisBuilder
import com.chipprbots.ethereum.transactions.TransactionHistoryService.ExtendedTransactionData
import com.chipprbots.ethereum.transactions.TransactionHistoryService.MinedTransactionData
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.testing.Tags._

class FukuiiJRCSpec extends FreeSpecBase with SpecFixtures with AsyncMockFactory with JRCMatchers {
  import com.chipprbots.ethereum.jsonrpc.serialization.JsonSerializers.formats

  class Fixture extends ApisBuilder {
    def config: JsonRpcConfig = JsonRpcConfig(Config.config, available)

    val web3Service: Web3Service = mock[Web3Service]
    // MIGRATION: Scala 3 mock cannot infer AtomicReference type parameter - create real instance
    implicit val testSystem: org.apache.pekko.actor.ActorSystem =
      org.apache.pekko.actor.ActorSystem("FukuiiJRCSpec-test")
    val netService: NetService = new NetService(
      new java.util.concurrent.atomic.AtomicReference(
        com.chipprbots.ethereum.utils.NodeStatus(
          com.chipprbots.ethereum.crypto.generateKeyPair(new java.security.SecureRandom),
          com.chipprbots.ethereum.utils.ServerStatus.NotListening,
          com.chipprbots.ethereum.utils.ServerStatus.NotListening
        )
      ),
      org.apache.pekko.testkit.TestProbe().ref,
      com.chipprbots.ethereum.blockchain.sync.CacheBasedBlacklist.empty(100),
      com.chipprbots.ethereum.jsonrpc.NetService.NetServiceConfig(scala.concurrent.duration.DurationInt(5).seconds)
    )
    val personalService: PersonalService = mock[PersonalService]
    val debugService: DebugService = mock[DebugService]
    val ethService: EthInfoService = mock[EthInfoService]
    val ethMiningService: EthMiningService = mock[EthMiningService]
    val ethBlocksService: EthBlocksService = mock[EthBlocksService]
    val ethTxService: EthTxService = mock[EthTxService]
    val ethUserService: EthUserService = mock[EthUserService]
    val ethFilterService: EthFilterService = mock[EthFilterService]
    val qaService: QAService = mock[QAService]
    val checkpointingService: CheckpointingService = mock[CheckpointingService]
    val fukuiiService: FukuiiService = mock[FukuiiService]
    val mcpService: McpService = mock[McpService]

    val jsonRpcController =
      new JsonRpcController(
        web3Service,
        netService,
        ethService,
        ethMiningService,
        ethBlocksService,
        ethTxService,
        ethUserService,
        ethFilterService,
        personalService,
        None,
        debugService,
        qaService,
        checkpointingService,
        fukuiiService,
        mcpService,
        ProofServiceDummy,
        config
      )

  }
  def createFixture() = new Fixture

  "Fukuii JRC" - {
    "should handle fukuii_getAccountTransactions" in testCaseM[IO] { fixture =>
      import fixture._
      val block = Fixtures.Blocks.Block3125369
      val sentTx = block.body.transactionList.head
      val receivedTx = block.body.transactionList.last

      (fukuiiService.getAccountTransactions _)
        .expects(*)
        .returning(
          IO.pure(
            Right(
              GetAccountTransactionsResponse(
                List(
                  ExtendedTransactionData(
                    sentTx,
                    isOutgoing = true,
                    Some(MinedTransactionData(block.header, 0, 42, false))
                  ),
                  ExtendedTransactionData(
                    receivedTx,
                    isOutgoing = false,
                    Some(MinedTransactionData(block.header, 1, 21, true))
                  )
                )
              )
            )
          )
        )

      val request: JsonRpcRequest = JsonRpcRequest(
        "2.0",
        "fukuii_getAccountTransactions",
        Some(
          JArray(
            List(
              JString(s"0x7B9Bc474667Db2fFE5b08d000F1Acc285B2Ae47D"),
              JInt(100),
              JInt(200)
            )
          )
        ),
        Some(JInt(1))
      )

      val expectedTxs = Seq(
        JObject(
          Extraction
            .decompose(TransactionResponse(sentTx, Some(block.header), Some(0)))
            .asInstanceOf[JObject]
            .obj ++ List(
            "isPending" -> JBool(false),
            "isCheckpointed" -> JBool(false),
            "isOutgoing" -> JBool(true),
            "timestamp" -> JLong(block.header.unixTimestamp),
            "gasUsed" -> JString(s"0x${BigInt(42).toString(16)}")
          )
        ),
        JObject(
          Extraction
            .decompose(TransactionResponse(receivedTx, Some(block.header), Some(1)))
            .asInstanceOf[JObject]
            .obj ++ List(
            "isPending" -> JBool(false),
            "isCheckpointed" -> JBool(true),
            "isOutgoing" -> JBool(false),
            "timestamp" -> JLong(block.header.unixTimestamp),
            "gasUsed" -> JString(s"0x${BigInt(21).toString(16)}")
          )
        )
      )

      for {
        response <- jsonRpcController.handleRequest(request)
      } yield response should haveObjectResult("transactions" -> JArray(expectedTxs.toList))
    }
  }
}
