package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** #1727 PR 5 (B-131): the system contracts of a simulated Amsterdam block, as go-ethereum v1.17.9 runs them
  * (`internal/ethapi/simulate.go:399-409` sets the block's overridden precompiles on the EVM that runs both system-call
  * phases; `:499-502` returns what `core.PostExecution` returns, which for an empty request contract is
  * `core/state_processor.go:469-472` under `:211-226`'s wrapper).
  *
  *   - F13: a request contract with no code ends the simulation with -32000 `failed to process <queue>: empty system
  *     contract: no code at <EIP-55 address>`. A real block never gets there (it is invalid earlier), so this is the
  *     simulator's alone; `SimulatedBlockSystemCallsSpec` has the cases without the service and the pin of block
  *     execution is `RequestSystemCallsBlockImportPinSpec`.
  *   - F15: the precompiles a state override moved or deleted are moved or deleted for the system calls too.
  *
  * Chain: the Amsterdam fixture schedule (Prague 120, Osaka 180, Amsterdam 360), on an empty state. The request
  * contracts are put there by state overrides, each as a contract that does nothing (STOP), which is what a real chain
  * has in the way that matters here: code at the four addresses.
  */
// scalastyle:off magic.number
class EthSimulateSystemContractsSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  private val zero32: ByteString = ByteString(new Array[Byte](32))

  // go-ethereum params.*Address as `%v` prints them
  private val WithdrawalQueue = Address("0x00000961Ef480Eb55e80D19ad83579A64c007002")
  private val ConsolidationQueue = Address("0x0000BBdDc7CE488642fb579F8B00f3a590007251")
  private val BuilderDepositQueue = Address("0x0000bFF46984e3725691FA540a8C7589300D8282")
  private val BuilderExitQueue = Address("0x000064D678505ad48F8cCb093BC65613800E8282")

  private def emptyContract(queue: String, eip55: String): JsonRpcError =
    JsonRpcError.LogicError(s"failed to process $queue: empty system contract: no code at $eip55")

  private val Identity = Address(0x04)

  /** STATICCALL the identity precompile with 32 zero bytes, return RETURNDATASIZE as a word: 0x20, or 0 once 0x04 is
    * deleted.
    */
  private val ProbesIdentity = ByteString(Hex.decode("600060006020600060045afa503d60005260206000f3"))

  /** The probe stored in slot 0, for the EIP-4788 call. */
  private val ProbesIdentityIntoSlot0 = ByteString(Hex.decode("600060006020600060045afa503d60005500"))

  private def word(n: Int): ByteString = ByteString(Array.fill[Byte](31)(0) :+ n.toByte)

  /** A request as the withdrawal queue's system call hands it back: the type byte, then what the contract returned. */
  private def requestsHashOf(requestType: Int, returned: ByteString): ByteString =
    BlockExecution.computeRequestsHash(Seq(ByteString(Array(requestType.toByte)) ++ returned))

  private def codeOverride(c: ByteString): StateOverride = StateOverride(code = Some(c))

  /** All four request contracts, each doing nothing. */
  private val stubs: Map[Address, StateOverride] = RequestContractStubs.overrides

  /** A node whose best block sits at `baseTimestamp`, in the shape its fork gives it. */
  private class Node(baseTimestamp: Long) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = amsterdamConfig

    private val baseExtraFields: HeaderExtraFields =
      if amsterdamConfig.isAmsterdamTimestamp(Timestamp(baseTimestamp)) then
        HefPostAmsterdam(7, emptyRoot, 0, 0, zero32, zero32, BlockAccessList.EmptyHash, slotNumber = 29)
      else HefPostPrague(7, emptyRoot, 0, 0, zero32, zero32)

    blockchainWriter.save(
      Block(
        Fixtures.Blocks.ValidBlock.header.copy(
          number = BlockNumber(29),
          difficulty = Difficulty(0),
          stateRoot = TrieRoot(emptyRoot),
          gasLimit = GasAmount(30_000_000),
          gasUsed = GasAmount.Zero,
          unixTimestamp = Timestamp(baseTimestamp),
          extraFields = baseExtraFields
        ),
        BlockBody.empty
      ),
      Nil,
      ChainWeight.zero,
      saveAsBestBlock = true
    )

    lazy val service = new EthSimulateService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      mining,
      amsterdamConfig
    )

    /** One simulated block (at the base block's time + 12), with these state overrides. */
    def simulate(overrides: Map[Address, StateOverride]): Either[JsonRpcError, EthSimulateResponse] =
      simulateBlocks(BlockStateCall(stateOverrides = Option.when(overrides.nonEmpty)(overrides)))

    def simulateBlocks(blocks: BlockStateCall*): Either[JsonRpcError, EthSimulateResponse] =
      service.ethSimulate(EthSimulateRequest(blocks.toSeq)).unsafeRunSync()

  /** An Amsterdam block: the base is at 360, so the simulated block lands on 372. */
  private def amsterdamNode = new Node(baseTimestamp = 360)

  private def header(response: Either[JsonRpcError, EthSimulateResponse]): BlockHeader =
    response.fold(err => fail(s"eth_simulateV1 failed: $err"), _.blocks.head.header)

  // ---- F13 ----

  "eth_simulateV1 at Amsterdam" should "fail with -32000 and go-ethereum's text when no request contract has code" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    amsterdamNode.simulate(Map.empty) shouldBe
      Left(emptyContract("withdrawal queue", "0x00000961Ef480Eb55e80D19ad83579A64c007002"))
  }

  it should "fail when a state override clears the withdrawal queue's code, which it used to skip" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val response = amsterdamNode.simulate(stubs.updated(WithdrawalQueue, codeOverride(ByteString.empty)))
    response shouldBe Left(emptyContract("withdrawal queue", "0x00000961Ef480Eb55e80D19ad83579A64c007002"))
    response.left.map(_.code) shouldBe Left(-32000)
  }

  it should "name the consolidation, builder deposit and builder exit queues the same way" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val cases = Seq(
      (ConsolidationQueue, "consolidation queue", "0x0000BBdDc7CE488642fb579F8B00f3a590007251"),
      (BuilderDepositQueue, "builder deposit queue", "0x0000bFF46984e3725691FA540a8C7589300D8282"),
      (BuilderExitQueue, "builder exit queue", "0x000064D678505ad48F8cCb093BC65613800E8282")
    )
    for (cleared, queue, eip55) <- cases do
      withClue(s"$queue cleared: ") {
        amsterdamNode.simulate(stubs.updated(cleared, codeOverride(ByteString.empty))) shouldBe
          Left(emptyContract(queue, eip55))
      }
  }

  it should "end the whole simulation, not only the block, and name the first empty queue" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // block 1 has every contract; block 2 clears two. The simulation is refused as one request.
    val response = amsterdamNode.simulateBlocks(
      BlockStateCall(stateOverrides = Some(stubs)),
      BlockStateCall(stateOverrides =
        Some(
          Map(ConsolidationQueue -> codeOverride(ByteString.empty), BuilderExitQueue -> codeOverride(ByteString.empty))
        )
      )
    )
    response shouldBe Left(emptyContract("consolidation queue", "0x0000BBdDc7CE488642fb579F8B00f3a590007251"))
  }

  it should "answer a block whose four request contracts have code, with the empty requests hash" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val result = header(amsterdamNode.simulate(stubs))
    result.extraFields shouldBe a[HefPostAmsterdam]
    result.requestsHash shouldBe Some(EmptyRequestsHash)
  }

  it should "leave an Osaka block alone: it runs no request system call, and so needs no request contract" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // go-ethereum runs `PostExecution` for every Prague block, so it would refuse this one too. EthSimulateService runs
    // the request system calls from Amsterdam only, and this change keeps it so; this pins the gap, not the intent.
    val result = header(new Node(baseTimestamp = 300).simulate(Map.empty))
    result.extraFields shouldBe a[HefPostPrague]
    result.requestsHash shouldBe Some(EmptyRequestsHash)
  }

  // ---- F15 ----

  it should "run a request contract on the precompiles as they are when none is overridden" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val result = header(amsterdamNode.simulate(stubs.updated(WithdrawalQueue, codeOverride(ProbesIdentity))))
    result.requestsHash shouldBe Some(requestsHashOf(0x01, word(0x20)))
  }

  it should "run a request contract with a precompile deleted by an entry at its address" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // any entry at 0x04 deletes the identity precompile (override.go:92-94), an empty one included
    val overrides = stubs.updated(WithdrawalQueue, codeOverride(ProbesIdentity)).updated(Identity, StateOverride())
    header(amsterdamNode.simulate(overrides)).requestsHash shouldBe Some(requestsHashOf(0x01, word(0)))
  }

  it should "run a request contract with a precompile moved away from its address" taggedAs (UnitTest, RPCTest) in {
    val overrides = stubs
      .updated(WithdrawalQueue, codeOverride(ProbesIdentity))
      .updated(Identity, StateOverride(movePrecompileToAddress = Some(Address(0x77))))
    header(amsterdamNode.simulate(overrides)).requestsHash shouldBe Some(requestsHashOf(0x01, word(0)))
  }

  it should "run the EIP-4788 call on the block's precompiles too, so the state root moves" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val withProbe = stubs.updated(BlockExecution.BeaconRootContractAddress, codeOverride(ProbesIdentityIntoSlot0))
    val identityKept = header(amsterdamNode.simulate(withProbe))
    val identityDeleted = header(amsterdamNode.simulate(withProbe.updated(Identity, StateOverride())))
    // slot 0 of the beacon roots contract holds 0x20 in one and 0 (never written) in the other
    identityDeleted.stateRoot should not be identityKept.stateRoot
  }
