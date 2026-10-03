package com.chipprbots.ethereum.ledger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockAccessList
import com.chipprbots.ethereum.domain.BlockAccessList.BalanceChange
import com.chipprbots.ethereum.domain.BlockAccessList.NonceChange
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*

/** [[SimulationOverridesIsolationSpec]] at Amsterdam, where block import also builds the block's EIP-7928 access list.
  *
  * On this branch `executeTransaction` carries the transaction's `accessRecorder` (and a trace replay's `tracer`) into
  * the private execution body that eth_simulateV1's overrides also go through. The access list is therefore part of
  * what must not move while a simulate call runs, and the recorder must reach that body at all: a recorder that is not
  * passed on records nothing, and the transaction's accounts then drop out of the list.
  */
class SimulationOverridesAmsterdamSpec extends AnyFlatSpec with Matchers with SimulationOverridesFixture:

  /** ETH at Amsterdam (fixture schedule, Amsterdam at 360). */
  private val EthAmsterdam: Chain = Chain("ETH Amsterdam", amsterdamConfig, amsterdamHeader(46, 360), blobs = true)

  private val Coinbase: Address = Address(0xcafe)

  /** Block import as `BlockExecution` runs an Amsterdam block: with an access-list builder, one index per transaction.
    */
  private def importWithAccessList(): (BlockOutcome, BlockAccessList) =
    val builder = new BlockAccessListBuilder
    setup.prep.executeTransactions(
      importedTxs(EthAmsterdam),
      preState,
      EthAmsterdam.header,
      accessList = Some(builder)
    )(EthAmsterdam.config) match
      case Right(result) =>
        val outcome = BlockOutcome(
          result.worldState.stateRootHash,
          result.receipts,
          result.gasUsed,
          result.executionGasUsed,
          result.stateGasUsed,
          result.receipts.flatMap(_.logs)
        )
        (outcome, builder.build)
      case Left(error) => fail(s"block execution failed: ${error.reason}")

  "Block import on ETH Amsterdam" should "record every transaction's accesses in the block access list" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val (_, accessList) = importWithAccessList()
    def changes(address: Address): BlockAccessList.AccountChanges =
      accessList.accounts.find(_.address == address).getOrElse(fail(s"$address is missing from the access list"))

    // The sender's nonce moves at each of the block's three transactions (indices 1 to 3).
    changes(senderAddress).nonceChanges shouldBe Seq(NonceChange(1, 1), NonceChange(2, 2), NonceChange(3, 3))
    // The first transaction's inner 1-wei transfer.
    changes(Exerciser).balanceChanges shouldBe Seq(BalanceChange(1, UInt256(999)))
    changes(Sink).balanceChanges shouldBe Seq(BalanceChange(1, UInt256(2)))
    // Every transaction reads the coinbase, whose zero priority fee changes nothing.
    changes(Coinbase).balanceChanges shouldBe empty
  }

  it should "execute exactly as it does alone, access list included, while an eth_simulateV1 call is mid-transaction" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val alone = importWithAccessList()

    val park = new Park
    val held = parkingInBlockHash(preState, park)
    val (simulated, during) =
      park.whileHeld(simulateWithOverrides(EthAmsterdam, simulateTx(EthAmsterdam), held))(importWithAccessList())

    during shouldBe alone
    shouldCarryEveryOverride(EthAmsterdam, simulated)
  }

  it should "execute exactly as it does alone, access list included, while an eth_simulateV1 call has its overrides but has not applied them yet" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val alone = importWithAccessList()

    val park = new Park
    val held = parkingAtFirstStateRead(preState, park)
    val (simulated, during) =
      park.whileHeld(simulateWithOverrides(EthAmsterdam, simulateTx(EthAmsterdam), held))(importWithAccessList())

    during shouldBe alone
    shouldCarryEveryOverride(EthAmsterdam, simulated)
  }

  "An eth_simulateV1 call on ETH Amsterdam" should "keep its overrides while another eth_simulateV1 call starts and returns" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val otherAlone = outcome(simulateWithoutOverrides(EthAmsterdam, exerciseTx(EthAmsterdam), preState))

    val park = new Park
    val held = parkingAtFirstStateRead(preState, park)
    val (simulated, otherDuring) =
      park.whileHeld(simulateWithOverrides(EthAmsterdam, simulateTx(EthAmsterdam), held))(
        outcome(simulateWithoutOverrides(EthAmsterdam, exerciseTx(EthAmsterdam), preState))
      )

    otherDuring shouldBe otherAlone
    shouldCarryEveryOverride(EthAmsterdam, simulated)
  }
