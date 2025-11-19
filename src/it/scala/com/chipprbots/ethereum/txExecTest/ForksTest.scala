package com.chipprbots.ethereum.txExecTest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.dsl.ResultOfATypeInvocation
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.txExecTest.util.FixtureProvider
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.MonetaryPolicyConfig
import com.chipprbots.ethereum.domain.BlockchainStorages

import com.chipprbots.ethereum.testing.Tags._

class ForksTest extends AnyFlatSpec with Matchers {

  trait TestSetup extends ScenarioSetup {
    implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
      forkBlockNumbers = ForkBlockNumbers.Empty.copy(
        frontierBlockNumber = 0,
        homesteadBlockNumber = 3,
        eip150BlockNumber = 5,
        eip160BlockNumber = 7,
        eip155BlockNumber = 0
      ),
      chainId = 0x3d.toByte,
      monetaryPolicyConfig = MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L, 3000000000000000000L),
      // unused
      bootstrapNodes = Set(),
      networkId = 1,
      maxCodeSize = None,
      customGenesisFileOpt = None,
      customGenesisJsonOpt = None,
      accountStartNonce = UInt256.Zero,
      daoForkConfig = None,
      gasTieBreaker = false,
      ethCompatibleStorage = true,
      treasuryAddress = Address(0)
    )
    val noErrors: ResultOfATypeInvocation[Right[_, Seq[Receipt]]] = a[Right[_, Seq[Receipt]]]
  }

  "Ledger" should "execute blocks with respect to forks" taggedAs (IntegrationTest, VMTest, SlowTest) in new TestSetup {
    val fixtures: FixtureProvider.Fixture = FixtureProvider.loadFixtures("/txExecTest/forksTest")

    val startBlock = 1
    val endBlock = 11

    protected val testBlockchainStorages: BlockchainStorages = FixtureProvider.prepareStorages(endBlock, fixtures)

    (startBlock to endBlock).foreach { blockToExecute =>
      val storages = FixtureProvider.prepareStorages(blockToExecute - 1, fixtures)
      val blockchainReader = BlockchainReader(storages)
      val blockchainWriter = BlockchainWriter(storages)
      val blockchain = BlockchainImpl(storages, blockchainReader)
      val blockValidation =
        new BlockValidation(mining, blockchainReader, BlockQueue(blockchainReader, syncConfig))
      val blockExecution =
        new BlockExecution(
          blockchain,
          blockchainReader,
          blockchainWriter,
          testBlockchainStorages.evmCodeStorage,
          mining.blockPreparator,
          blockValidation
        )
      blockExecution.executeAndValidateBlock(fixtures.blockByNumber(blockToExecute)) shouldBe noErrors
    }
  }

}
