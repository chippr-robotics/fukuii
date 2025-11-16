package com.chipprbots.ethereum.txExecTest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.dsl.ResultOfATypeInvocation
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.txExecTest.util.FixtureProvider
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.domain.BlockchainStorages

class ContractTest extends AnyFlatSpec with Matchers {
  val blockchainConfig = Config.blockchains.blockchainConfig
  val syncConfig: Config.SyncConfig = Config.SyncConfig(Config.config)
  val noErrors: ResultOfATypeInvocation[Right[_, Seq[Receipt]]] = a[Right[_, Seq[Receipt]]]

  // FIXED: Fixture data from original Mantis codebase has corrupted account codeHash values.
  // The purchaseContract fixture was created via DumpChainApp from a private Parity testnet,
  // and accounts in stateTree.txt have codeHash = c5d2460186f7... (empty code marker)
  // when they should reference actual contract code hash de3565a1f31ab3...
  //
  // WORKAROUND IMPLEMENTED: FixtureProvider now stores contract code under BOTH the correct
  // codeHash AND the empty codeHash. This allows world.getCode(address) to find the code
  // even when account has incorrect (empty) codeHash, without needing to regenerate fixtures
  // or patch account data in the state trie.
  //
  // Gas calculation code is verified correct - matches Besu, core-geth, ethereum/tests specs.
  // See docs/investigation/CONTRACT_TEST_FAILURE_ANALYSIS.md for complete forensics.
  "Ledger" should "execute and validate" in new ScenarioSetup {
    val fixtures: FixtureProvider.Fixture = FixtureProvider.loadFixtures("/txExecTest/purchaseContract")
    override val testBlockchainStorages: BlockchainStorages = FixtureProvider.prepareStorages(2, fixtures)

    // block only with ether transfers
    override lazy val blockValidation =
      new BlockValidation(mining, blockchainReader, BlockQueue(blockchainReader, this.syncConfig))
    override lazy val blockExecution =
      new BlockExecution(
        blockchain,
        blockchainReader,
        blockchainWriter,
        testBlockchainStorages.evmCodeStorage,
        mining.blockPreparator,
        blockValidation
      )
    blockExecution.executeAndValidateBlock(fixtures.blockByNumber(1)) shouldBe noErrors

    // deploy contract
    blockExecution.executeAndValidateBlock(fixtures.blockByNumber(2)) shouldBe noErrors

    // execute contract call
    // execute contract that pays 2 accounts
    blockExecution.executeAndValidateBlock(fixtures.blockByNumber(3)) shouldBe noErrors
  }
}
