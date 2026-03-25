package com.chipprbots.ethereum.jsonrpc

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.json4s.JsonAST._
import org.json4s.JsonDSL._
import org.scalamock.handlers.CallHandler1
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.ByteGenerators
import com.chipprbots.ethereum.NormalPatience
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MineBlocks
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponse
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.jsonrpc.QAService.MineBlocksResponse.MinerResponseType._
import com.chipprbots.ethereum.jsonrpc.QAService._
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.nodebuilder.ApisBuilder
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.Config

class QaJRCSpec
    extends AnyWordSpec
    with Matchers
    with PatienceConfiguration
    with NormalPatience
    with JsonMethodsImplicits
    with org.scalamock.scalatest.MockFactory {

  implicit val runtime: IORuntime = IORuntime.global

  "QaJRC" should {
    "request block mining and return valid response with correct message" when {
      "mining ordered" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MockedMinerResponses.MiningOrdered)

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).unsafeRunSync()

        response should haveObjectResult(responseType(MiningOrdered), nullMessage)
      }

      "miner is working" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MockedMinerResponses.MinerIsWorking)

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).unsafeRunSync()

        response should haveObjectResult(responseType(MinerIsWorking), nullMessage)
      }

      "miner doesn't exist" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MockedMinerResponses.MinerNotExist)

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).unsafeRunSync()

        response should haveObjectResult(responseType(MinerNotExist), nullMessage)
      }

      "miner not support current msg" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MockedMinerResponses.MinerNotSupported(MineBlocks(1, true)))

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).unsafeRunSync()

        response should haveObjectResult(responseType(MinerNotSupport), msg("MineBlocks(1,true,None)"))
      }

      "miner return error" in new TestSetup {
        mockSuccessfulMineBlocksBehaviour(MockedMinerResponses.MiningError("error"))

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).unsafeRunSync()

        response should haveObjectResult(responseType(MiningError), msg("error"))
      }
    }

    "request block mining and return InternalError" when {
      "communication with miner failed" in new TestSetup {
        (qaService.mineBlocks _)
          .expects(mineBlocksReq)
          .returning(IO.raiseError(new ClassCastException("error")))

        val response: JsonRpcResponse = jsonRpcController.handleRequest(mineBlocksRpcRequest).unsafeRunSync()

        response should haveError(JsonRpcError.InternalError)
      }
    }

  }

  // SCALA 3 MIGRATION: Changed TestSetup from trait with self-type to class
  // that receives mocks from the outer class via constructor-like pattern
  class TestSetup extends JRCMatchers with ByteGenerators with BlockchainConfigBuilder with ApisBuilder {
    def config: JsonRpcConfig = JsonRpcConfig(Config.config, available)

    val appStateStorage: AppStateStorage = mock[AppStateStorage]
    val web3Service: Web3Service = mock[Web3Service]
    // MIGRATION: Scala 3 mock cannot infer AtomicReference type parameter - create real instance
    implicit val testSystem: org.apache.pekko.actor.ActorSystem = org.apache.pekko.actor.ActorSystem("QaJRCSpec-test")
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
    val personalService: PersonalServiceAPI = mock[PersonalServiceAPI]
    val debugService: DebugService = mock[DebugService]
    val ethService: EthInfoService = mock[EthInfoService]
    val ethMiningService: EthMiningService = mock[EthMiningService]
    val ethBlocksService: EthBlocksService = mock[EthBlocksService]
    val ethTxService: EthTxService = mock[EthTxService]
    val ethUserService: EthUserService = mock[EthUserService]
    val ethFilterService: EthFilterService = mock[EthFilterService]
    val fukuiiService: FukuiiService = mock[FukuiiService]
    val mcpService: McpService = {
      implicit val testSystem: org.apache.pekko.actor.ActorSystem =
        org.apache.pekko.actor.ActorSystem("QaJRCSpec-mcp")
      new McpService(
        org.apache.pekko.testkit.TestProbe().ref,
        org.apache.pekko.testkit.TestProbe().ref,
        null,
        null,
        new java.util.concurrent.atomic.AtomicReference[com.chipprbots.ethereum.utils.NodeStatus](),
        null
      )(scala.concurrent.ExecutionContext.global)
    }
    val qaService: QAService = mock[QAService]

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
        fukuiiService,
        mcpService,
        ProofServiceDummy,
        mock[TxPoolService],
        null.asInstanceOf[AdminService],
        null.asInstanceOf[TraceService],
        config
      )

    val mineBlocksReq: MineBlocksRequest = MineBlocksRequest(1, withTransactions = true, None)

    val mineBlocksRpcRequest: JsonRpcRequest = JsonRpcRequest(
      "2.0",
      "qa_mineBlocks",
      Some(
        JArray(
          List(
            JInt(1),
            JBool(true)
          )
        )
      ),
      Some(JInt(1))
    )

    def msg(str: String): JField = "message" -> JString(str)
    val nullMessage: JField = "message" -> JNull

    def responseType(expectedType: MineBlocksResponse.MinerResponseType): JField =
      "responseType" -> JString(expectedType.entryName)

    def mockSuccessfulMineBlocksBehaviour(
        resp: MockedMinerResponse
    ): CallHandler1[MineBlocksRequest, IO[Either[JsonRpcError, MineBlocksResponse]]] =
      (qaService.mineBlocks _)
        .expects(mineBlocksReq)
        .returning(IO.pure(Right(MineBlocksResponse(resp))))

    val fakeChainId: Byte = 42.toByte
  }
}
