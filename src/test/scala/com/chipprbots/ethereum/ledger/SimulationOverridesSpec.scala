package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** eth_simulateV1's per-call overrides, as [[BlockPreparator.executeTransactionForSimulation]] applies them: to the
  * simulate call's own transaction, and to nothing after it.
  *
  * There is no service-level eth_simulateV1 spec; this pins the preparator facade `EthSimulateService` calls. Each
  * override is shown taking effect, against block import of the same transaction on the same pre-state, and a simulate
  * call without overrides is shown to execute exactly as block import does.
  *
  * [[SimulationOverridesIsolationSpec]] covers the concurrent half: a simulate call that is still running.
  */
class SimulationOverridesSpec extends AnyFlatSpec with Matchers with SimulationOverridesFixture:

  Chains.foreach { chain =>

    s"eth_simulateV1 on ${chain.name}" should "run its own transaction with the precompile it moved" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      // Moved away, 0x01 is an empty account and answers nothing. Block import still finds ecrecover there.
      simulateWithOverrides(chain, ecrecoverTx(chain), preState).vmReturnData shouldBe ByteString.empty
      setup.prep
        .executeTransaction(ecrecoverTx(chain), senderAddress, chain.header, preState)(chain.config)
        .vmReturnData shouldBe RecoveredWord

      // Called from a contract: ecrecover answers at MovedEcrecover under the relocation, at 0x01 without it.
      simulateWithOverrides(chain, exerciseTx(chain), preState).vmReturnData shouldBe ExercisedRelocated
      setup.prep
        .executeTransaction(exerciseTx(chain), senderAddress, chain.header, preState)(chain.config)
        .vmReturnData shouldBe ExercisedPlain
    }

    it should "log its own transaction's inner value transfers when traceTransfers is set" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      syntheticLogs(simulateWithOverrides(chain, exerciseTx(chain), preState).logs) shouldBe
        Seq(syntheticTransfer(Exerciser))
      syntheticLogs(simulateWithoutOverrides(chain, exerciseTx(chain), preState).logs) shouldBe empty
    }

    if chain.blobs then
      it should "price its own transaction's blob at the blobBaseFee override" taggedAs (UnitTest, ConsensusTest) in {
        val stx = blobCallTx(chain, Sink, ByteString.empty)
        val overridden = simulateWithOverrides(chain, stx, preState)
        overridden.worldState.getBalance(senderAddress).toBigInt shouldBe
          SenderBalance - overridden.gasUsed * gasPrice(chain, stx) - BlobGas * BlobFeeOverride

        val derived = simulateWithoutOverrides(chain, stx, preState)
        headerBlobFee(chain) shouldBe BigInt(1)
        derived.worldState.getBalance(senderAddress).toBigInt shouldBe
          SenderBalance - derived.gasUsed * gasPrice(chain, stx) - BlobGas * headerBlobFee(chain)
      }

    it should "execute a transaction exactly as block import does when it has no overrides" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      importedTxs(chain).foreach { stx =>
        outcome(simulateWithoutOverrides(chain, stx, preState)) shouldBe
          outcome(setup.prep.executeTransaction(stx, senderAddress, chain.header, preState)(chain.config))
      }
    }

    s"Block import on ${chain.name}" should "execute exactly as before once an eth_simulateV1 call has returned" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val before = importBlock(chain)
      shouldCarryEveryOverride(chain, simulateWithOverrides(chain, simulateTx(chain), preState))
      importBlock(chain) shouldBe before
    }

    it should "execute exactly as before once an eth_simulateV1 call has failed mid-transaction" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val before = importBlock(chain)
      // The Pauser reads BLOCKHASH; this world fails that read, so the simulate call throws mid-transaction.
      val failing =
        withBlockHashes(preState)(_ => throw new IllegalStateException("simulate call fails mid-transaction"))
      intercept[IllegalStateException](simulateWithOverrides(chain, simulateTx(chain), failing))
      importBlock(chain) shouldBe before
    }
  }
