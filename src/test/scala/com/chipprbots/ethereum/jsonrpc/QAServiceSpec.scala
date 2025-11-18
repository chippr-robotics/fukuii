package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import cats.effect.IO

import org.scalamock.scalatest.AsyncMockFactory

import com.chipprbots.ethereum._
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync.NewCheckpoint
import com.chipprbots.ethereum.consensus.blocks.CheckpointBlockGenerator
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.pow.EthashConfig
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MineBlocks
import com.chipprbots.ethereum.consensus.pow.miners.MockedMiner.MockedMinerResponses.MiningOrdered
import com.chipprbots.ethereum.crypto.ECDSASignature
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

  "QAService" should "send msg to miner and return miner's response" taggedAs (UnitTest, RPCTest) in testCaseM[IO] { fixture =>
    import fixture._
    (testMining.askMiner _)
      .expects(mineBlocksMsg)
      .returning(IO.pure(MiningOrdered))
      .atLeastOnce()

    qaService.mineBlocks(mineBlocksReq).map(_ shouldBe Right(MineBlocksResponse(MiningOrdered)))
  }

  it should "send msg to miner and return InternalError taggedAs (UnitTest, RPCTest) in case of problems" in testCaseM[IO] { fixture =>
    import fixture._
    (testMining.askMiner _)
      .expects(mineBlocksMsg)
      .returning(IO.raiseError(new ClassCastException("error")))
      .atLeastOnce()

    qaService.mineBlocks(mineBlocksReq).map(_ shouldBe Left(JsonRpcError.InternalError))
  }

  it should "generate checkpoint for block with given blockHash and send it to sync" taggedAs (UnitTest, RPCTest) in customTestCaseM(
    new Fixture with CheckpointsGenerationFixture
  ) { fixture =>
    import fixture._

    (blockchainReader.getBlockByHash _)
      .expects(req.blockHash.get)
      .returning(Some(block))
      .noMoreThanOnce()

    val result = qaService.generateCheckpoint(req)
    val checkpointBlock = checkpointBlockGenerator.generate(block, Checkpoint(signatures))

    result.map { r =>
      syncController.expectMsg(NewCheckpoint(checkpointBlock))
      r shouldBe Right(GenerateCheckpointResponse(checkpoint))
    }
  }

  it should "generate checkpoint for best block when no block hash given and send it to sync" taggedAs (UnitTest, RPCTest) in customTestCaseM(
    new Fixture with CheckpointsGenerationFixture
  ) { fixture =>
    import fixture._
    val reqWithoutBlockHash = req.copy(blockHash = None)

    (blockchainReader.getBlockByHash _)
      .expects(req.blockHash.get)
      .returning(Some(block))
      .noMoreThanOnce()

    (blockchainReader.getBestBlock _)
      .expects()
      .returning(Some(block))
      .once()

    val result: ServiceResponse[GenerateCheckpointResponse] =
      qaService.generateCheckpoint(reqWithoutBlockHash)
    val checkpointBlock = checkpointBlockGenerator.generate(block, Checkpoint(signatures))

    result.map { r =>
      syncController.expectMsg(NewCheckpoint(checkpointBlock))
      r shouldBe Right(GenerateCheckpointResponse(checkpoint))
    }
  }

  it should "return federation public keys when requesting federation members info" taggedAs (UnitTest, RPCTest) in testCaseM[IO] { fixture =>
    import fixture._
    val result: ServiceResponse[GetFederationMembersInfoResponse] =
      qaService.getFederationMembersInfo(GetFederationMembersInfoRequest())

    result.map(_ shouldBe Right(GetFederationMembersInfoResponse(blockchainConfig.checkpointPubKeys.toList)))
  }

  class Fixture extends BlockchainConfigBuilder {
    protected trait TestMining extends Mining {
      override type Config = EthashConfig
    }

    lazy val testMining: TestMining = mock[TestMining]
    lazy val blockchainReader: BlockchainReader = mock[BlockchainReader]
    lazy val blockchain: BlockchainImpl = mock[BlockchainImpl]
    lazy val syncController: TestProbe = TestProbe()
    lazy val checkpointBlockGenerator = new CheckpointBlockGenerator()

    lazy val qaService = new QAService(
      testMining,
      blockchainReader,
      checkpointBlockGenerator,
      blockchainConfig,
      syncController.ref
    )

    lazy val mineBlocksReq: MineBlocksRequest = MineBlocksRequest(1, true, None)
    lazy val mineBlocksMsg: MineBlocks =
      MineBlocks(mineBlocksReq.numBlocks, mineBlocksReq.withTransactions, mineBlocksReq.parentBlock)
    val fakeChainId: Byte = 42.toByte
  }

  trait CheckpointsGenerationFixture {
    val block = Fixtures.Blocks.ValidBlock.block
    val privateKeys: Seq[ByteString] = seqByteStringOfNItemsOfLengthMGen(3, 32).sample.get
    val signatures: Seq[ECDSASignature] = privateKeys.map(ECDSASignature.sign(block.hash, _))
    val checkpoint: Checkpoint = Checkpoint(signatures)
    val req: GenerateCheckpointRequest = GenerateCheckpointRequest(privateKeys, Some(block.hash))
  }

  def createFixture(): Fixture = new Fixture
}
