package com.chipprbots.ethereum.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** An eth_simulateV1 call's overrides reach nothing that runs while it does.
  *
  * A node builds one [[BlockPreparator]] and shares it between block import (`BlockExecution`, which also serves
  * `engine_newPayload` and payload building), eth_simulateV1, and `StxLedger` (eth_call, eth_estimateGas, the debug and
  * trace calls), and these run concurrently. The overrides are therefore passed down the simulate call's own stack as
  * an immutable [[SimulationOverrides]], and are never state on that shared instance.
  *
  * Each test holds a simulate call mid-flight on its own thread ([[SimulationOverridesFixture.Park]]: latches, no
  * sleeps, the same interleaving on every run) and runs another execution beside it on the test thread. There are two
  * holding points:
  *   - the transaction's BLOCKHASH, reached after the top-level context is built and every override applied;
  *   - its first trie read, before any override is applied.
  *
  * Each test also checks that the held call itself ran under all of its overrides, so none passes vacuously.
  */
class SimulationOverridesIsolationSpec extends AnyFlatSpec with Matchers with SimulationOverridesFixture:

  Chains.foreach { chain =>

    s"Block import on ${chain.name}" should "execute exactly as it does alone while an eth_simulateV1 call is mid-transaction" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val alone = importBlock(chain)
      syntheticLogs(alone.logs) shouldBe empty

      val park = new Park
      val held = parkingInBlockHash(preState, park)
      val (simulated, during) =
        park.whileHeld(simulateWithOverrides(chain, simulateTx(chain), held))(importBlock(chain))

      withClue("state root: ")(during.stateRoot shouldBe alone.stateRoot)
      withClue("receipts: ")(during.receipts shouldBe alone.receipts)
      withClue("gasUsed: ")(during.gasUsed shouldBe alone.gasUsed)
      withClue("logs: ")(during.logs shouldBe alone.logs)
      during shouldBe alone
      shouldCarryEveryOverride(chain, simulated)
    }

    s"eth_call, eth_estimateGas and the debug traces on ${chain.name}" should "run as they do alone while an eth_simulateV1 call is mid-transaction" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val callAlone = ethCall(chain)
      val traceAlone = debugTrace(chain)
      callAlone.returnData shouldBe ExercisedPlain
      syntheticLogs(callAlone.logs) shouldBe empty

      val park = new Park
      val held = parkingInBlockHash(preState, park)
      val (simulated, (callDuring, traceDuring)) =
        park.whileHeld(simulateWithOverrides(chain, simulateTx(chain), held))((ethCall(chain), debugTrace(chain)))

      callDuring shouldBe callAlone
      traceDuring shouldBe traceAlone
      shouldCarryEveryOverride(chain, simulated)
    }

    s"An eth_simulateV1 call on ${chain.name}" should "keep its overrides while another eth_simulateV1 call starts and returns" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val otherAlone = outcome(simulateWithoutOverrides(chain, exerciseTx(chain), preState))

      val park = new Park
      // Built here, on the test thread, so opening the trie cannot trip the holding point before the call starts.
      val held = parkingAtFirstStateRead(preState, park)
      val (simulated, otherDuring) =
        park.whileHeld(simulateWithOverrides(chain, simulateTx(chain), held))(
          outcome(simulateWithoutOverrides(chain, exerciseTx(chain), preState))
        )

      otherDuring shouldBe otherAlone
      // The other call started and returned while this one was held, before this one had applied anything. Neither
      // its start nor its end may change the overrides this call then applies.
      shouldCarryEveryOverride(chain, simulated)
    }

    s"Block import on ${chain.name}" should "execute exactly as it does alone while an eth_simulateV1 call has its overrides but has not applied them yet" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val alone = importBlock(chain)

      val park = new Park
      val held = parkingAtFirstStateRead(preState, park)
      val (simulated, during) =
        park.whileHeld(simulateWithOverrides(chain, simulateTx(chain), held))(importBlock(chain))

      during shouldBe alone
      shouldCarryEveryOverride(chain, simulated)
    }
  }
