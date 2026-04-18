package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit

import cats.effect.IO

import org.scalamock.scalatest.AsyncMockFactory

import com.chipprbots.ethereum._
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.pow.EthashConfig
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MineBlocks
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MiningOrdered
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.jsonrpc.QAService._
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder
import com.chipprbots.ethereum.testing.Tags._

class QAServiceSpec
    extends TestKit(ActorSystem("QAServiceSpec_ActorSystem"))
    with FlatSpecBase
    with WithActorSystemShutDown
    with SpecFixtures
    with ByteGenerators
    with AsyncMockFactory {

  "QAService" should "send msg to miner and return miner's response" taggedAs (UnitTest, RPCTest) in testCaseM[IO] {
    fixture =>
      import fixture._
      (testMining.askMiner _)
        .expects(mineBlocksMsg)
        .returning(IO.pure(MiningOrdered))
        .atLeastOnce()

      qaService.mineBlocks(mineBlocksReq).map(_ shouldBe Right(MineBlocksResponse(MiningOrdered)))
  }

  it should "send msg to miner and return InternalError taggedAs (UnitTest, RPCTest) in case of problems" in testCaseM[
    IO
  ] { fixture =>
    import fixture._
    (testMining.askMiner _)
      .expects(mineBlocksMsg)
      .returning(IO.raiseError(new ClassCastException("error")))
      .atLeastOnce()

    qaService.mineBlocks(mineBlocksReq).map(_ shouldBe Left(JsonRpcError.InternalError))
  }

  class Fixture extends BlockchainConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider {
    protected trait TestMining extends Mining {
      override type Config = EthashConfig
    }

    lazy val testMining: TestMining = mock[TestMining]

    lazy val qaService = new QAService(
      testMining
    )

    lazy val mineBlocksReq: MineBlocksRequest = MineBlocksRequest(1, true, None)
    lazy val mineBlocksMsg: MineBlocks =
      MineBlocks(mineBlocksReq.numBlocks, mineBlocksReq.withTransactions, mineBlocksReq.parentBlock)
    val fakeChainId: Byte = 42.toByte
  }

  def createFixture(): Fixture = new Fixture
}
