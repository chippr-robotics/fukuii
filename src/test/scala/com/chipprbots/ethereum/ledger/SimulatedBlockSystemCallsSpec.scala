package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.BlockExecution.BeaconRootContractAddress
import com.chipprbots.ethereum.ledger.BlockExecution.BuilderDepositQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.BuilderExitQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.ConsolidationQueueAddress
import com.chipprbots.ethereum.ledger.BlockExecution.EmptyRequestContract
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.BlockExecution.WithdrawalQueueAddress
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** #1727 PR 5 (B-131): the system calls of a simulated block, compared with go-ethereum v1.17.9.
  *
  *   - F13, `core/state_processor.go` `processRequestsSystemCall` (:469-472) under `PostExecution` (:204-228): a
  *     request contract whose code size is 0 fails the block with `failed to process <queue>: empty system contract: no
  *     code at <address>`, `%v` of a `common.Address` being its EIP-55 form (`common/types.go` `Format`). The queues
  *     are taken in the order withdrawals, consolidations (Prague), builder deposit, builder exit (Amsterdam), each on
  *     the world the one before it left. eth_simulateV1 alone asks for this ([[SimulatedBlockSystemCalls.requests]]);
  *     block execution keeps its skip, because a block that reaches it has passed `requireRequestPredeploysPresent`.
  *   - F15, `internal/ethapi/simulate.go:403-409`: the block's overridden precompile set is set on the EVM before
  *     `PreExecution` and still there for `PostExecution`, so a precompile a state override moved or deleted is moved
  *     or deleted for the system calls too. Relocations are written as `EthSimulateService.applyStateOverrides` returns
  *     them: `a -> t` moved, `a -> a` deleted.
  *
  * The addresses and the expected texts are literals (go-ethereum's `params.*Address`, EIP-55 checksummed), not
  * computed by the code under test.
  *
  * Chain: the Amsterdam fixture schedule (Prague 120, Osaka 180, Amsterdam 360). The contracts are stubs: what is under
  * test is whether, where and with what precompiles they are called, not what a real queue contract does.
  */
// scalastyle:off magic.number
class SimulatedBlockSystemCallsSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  // go-ethereum params.WithdrawalQueueAddress, ConsolidationQueueAddress, BuilderDepositAddress, BuilderExitAddress,
  // as `%v` prints them
  private val WithdrawalQueueEip55 = "0x00000961Ef480Eb55e80D19ad83579A64c007002"
  private val ConsolidationQueueEip55 = "0x0000BBdDc7CE488642fb579F8B00f3a590007251"
  private val BuilderDepositQueueEip55 = "0x0000bFF46984e3725691FA540a8C7589300D8282"
  private val BuilderExitQueueEip55 = "0x000064D678505ad48F8cCb093BC65613800E8282"

  private def emptyContract(queue: String, eip55: String): String =
    s"failed to process $queue: empty system contract: no code at $eip55"

  private val Identity = Address(0x04)
  private val Elsewhere = Address(0x77)

  private val AllQueues: Seq[Address] =
    Seq(WithdrawalQueueAddress, ConsolidationQueueAddress, BuilderDepositQueueAddress, BuilderExitQueueAddress)

  /** STOP: the call succeeds and returns nothing, so the contract adds no request. */
  private val Stop = ByteString(Hex.decode("00"))

  /** PUSH1 0x42, PUSH1 0, MSTORE8, PUSH1 1, PUSH1 0, RETURN: returns the single byte 0x42, so it adds the request 0x42.
    */
  private val ReturnsBang = ByteString(Hex.decode("604260005360016000f3"))

  /** STATICCALL the identity precompile (0x04) with 32 zero bytes and return RETURNDATASIZE as a word: 0x20 while 0x04
    * is the identity precompile, 0 once it is deleted or moved (a call to an account that does not exist returns
    * nothing).
    *
    * PUSH1 0, PUSH1 0, PUSH1 0x20, PUSH1 0, PUSH1 4, GAS, STATICCALL, POP, RETURNDATASIZE, PUSH1 0, MSTORE, PUSH1 0x20,
    * PUSH1 0, RETURN.
    */
  private val ProbesIdentity = ByteString(Hex.decode("600060006020600060045afa503d60005260206000f3"))

  /** The same probe, stored: SSTORE(0, RETURNDATASIZE), for the EIP-4788 and EIP-2935 calls, whose return data nothing
    * reads.
    */
  private val ProbesIdentityIntoSlot0 = ByteString(Hex.decode("600060006020600060045afa503d60005500"))

  private def request(requestType: Int, data: ByteString): ByteString = ByteString(Array(requestType.toByte)) ++ data
  private def word(n: Int): ByteString = ByteString(Array.fill[Byte](31)(0) :+ n.toByte)
  private val ZeroWord: ByteString = word(0)
  private val Bang: ByteString = ByteString(Hex.decode("42"))

  private def withCode(world: InMemoryWorldStateProxy, address: Address, code: ByteString): InMemoryWorldStateProxy =
    world
      .saveAccount(address, Account(nonce = UInt256(0), balance = UInt256(1), codeHash = CodeHash(kec256(code))))
      .saveCode(address, code)

  private def worldWith(contracts: (Address, ByteString)*): InMemoryWorldStateProxy =
    InMemoryWorldStateProxy.persistState(contracts.foldLeft(setup.emptyWorld) { case (w, (address, code)) =>
      withCode(w, address, code)
    })

  /** Every request contract of an Amsterdam block, each doing nothing, except where `instead` replaces it. */
  private def allStubs(instead: (Address, ByteString)*): InMemoryWorldStateProxy =
    val replaced = instead.toMap
    worldWith(AllQueues.map(a => a -> replaced.getOrElse(a, Stop))*)

  private def storageOf(world: InMemoryWorldStateProxy, address: Address, slot: Int): BigInt =
    world.getStorage(address).load(UInt256(slot))

  private val amsterdamBlock: BlockHeader = amsterdamHeader(46, 460)
  private val pragueBlock: BlockHeader = preAmsterdamHeader(46, 150)

  private def simulated(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      relocations: Map[Address, Address] = Map.empty,
      config: BlockchainConfig = amsterdamConfig
  ): Either[String, (InMemoryWorldStateProxy, Seq[ByteString])] =
    SimulatedBlockSystemCalls.requests(header, world, Nil, None, relocations)(using config)

  private def preamble(
      world: InMemoryWorldStateProxy,
      relocations: Map[Address, Address]
  ): InMemoryWorldStateProxy =
    SimulatedBlockSystemCalls.amsterdamPreamble(amsterdamBlock, world, None, relocations)(using amsterdamConfig)

  // ---- F13: an empty request contract fails the simulated block ----

  "SimulatedBlockSystemCalls.requests" should "fail a block whose withdrawal queue has no code, with go-ethereum's text" taggedAs (
    UnitTest,
    StateTest
  ) in {
    simulated(amsterdamBlock, setup.emptyWorld) shouldBe
      Left(emptyContract("withdrawal queue", WithdrawalQueueEip55))
  }

  it should "name each of the four queues, and its EIP-55 address, as go-ethereum does" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val cases = Seq(
      (WithdrawalQueueAddress, "withdrawal queue", WithdrawalQueueEip55),
      (ConsolidationQueueAddress, "consolidation queue", ConsolidationQueueEip55),
      (BuilderDepositQueueAddress, "builder deposit queue", BuilderDepositQueueEip55),
      (BuilderExitQueueAddress, "builder exit queue", BuilderExitQueueEip55)
    )
    for (missing, queue, eip55) <- cases do
      val present = AllQueues.filterNot(_ == missing)
      withClue(s"$queue missing: ") {
        simulated(amsterdamBlock, worldWith(present.map(_ -> Stop)*)) shouldBe Left(emptyContract(queue, eip55))
      }
  }

  it should "report the first empty queue in go-ethereum's order, withdrawals, consolidations, then the builders" taggedAs (
    UnitTest,
    StateTest
  ) in {
    simulated(amsterdamBlock, worldWith(WithdrawalQueueAddress -> Stop)) shouldBe
      Left(emptyContract("consolidation queue", ConsolidationQueueEip55))
    simulated(amsterdamBlock, worldWith(WithdrawalQueueAddress -> Stop, ConsolidationQueueAddress -> Stop)) shouldBe
      Left(emptyContract("builder deposit queue", BuilderDepositQueueEip55))
  }

  it should "count an account that exists with no code as empty, which is how a state override empties a queue" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val cleared = worldWith(AllQueues.tail.map(_ -> Stop)*)
      .saveAccount(WithdrawalQueueAddress, Account(nonce = UInt256(0), balance = UInt256(1)))
    simulated(amsterdamBlock, cleared) shouldBe Left(emptyContract("withdrawal queue", WithdrawalQueueEip55))
  }

  it should "answer a block with all four contracts deployed as block execution does" taggedAs (
    UnitTest,
    StateTest
  ) in {
    // 0x01 || 0x42 from the withdrawal queue; the other three return nothing
    val world = allStubs(WithdrawalQueueAddress -> ReturnsBang)
    simulated(amsterdamBlock, world).map(_._2) shouldBe Right(Seq(request(0x01, Bang)))
  }

  it should "check only the two Prague queues at a Prague timestamp" taggedAs (UnitTest, StateTest) in {
    simulated(pragueBlock, worldWith(WithdrawalQueueAddress -> Stop, ConsolidationQueueAddress -> Stop))
      .map(_._2) shouldBe Right(Nil)
    simulated(pragueBlock, worldWith(WithdrawalQueueAddress -> Stop)) shouldBe
      Left(emptyContract("consolidation queue", ConsolidationQueueEip55))
  }

  it should "run no system call before Prague, whatever the world holds" taggedAs (UnitTest, StateTest) in {
    val world = setup.emptyWorld
    val result = simulated(preAmsterdamHeader(46, 100), world)
    result.map(_._2) shouldBe Right(Nil)
    result.map(_._1.stateRootHash) shouldBe Right(world.stateRootHash)
  }

  // ---- Block execution keeps its skip ----

  "BlockExecution.requestSystemCalls" should "still skip a request contract with no code unless it is asked to fail" taggedAs (
    UnitTest,
    StateTest
  ) in {
    given BlockchainConfig = amsterdamConfig
    val noCode = setup.emptyWorld
    BlockExecution.requestSystemCalls(amsterdamBlock, noCode, None).map(r => (r._1.stateRootHash, r._2)) shouldBe
      Right((noCode.stateRootHash, Nil))
    // only the withdrawal queue deployed: its request is the only one, the other three are skipped
    BlockExecution
      .requestSystemCalls(amsterdamBlock, worldWith(WithdrawalQueueAddress -> ReturnsBang), None)
      .map(_._2) shouldBe Right(Seq(request(0x01, Bang)))
    BlockExecution
      .requestSystemCalls(amsterdamBlock, noCode, None, Map.empty, EmptyRequestContract.Skip)
      .map(_._2) shouldBe Right(Nil)
  }

  it should "fail an empty contract only when it is asked to" taggedAs (UnitTest, StateTest) in {
    given BlockchainConfig = amsterdamConfig
    BlockExecution
      .requestSystemCalls(amsterdamBlock, setup.emptyWorld, None, Map.empty, EmptyRequestContract.Fail)
      .map(_._2) shouldBe Left(emptyContract("withdrawal queue", WithdrawalQueueEip55))
  }

  // ---- ETC: no request system call exists, so there is nothing to fail ----

  private val etcOlympiaConfig: BlockchainConfig =
    setup.blockchainConfig
      .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
      .withUpdatedForkBlocks(
        _.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip155BlockNumber = 0,
          eip160BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          atlantisBlockNumber = 0,
          aghartaBlockNumber = 0,
          phoenixBlockNumber = 0,
          magnetoBlockNumber = 0,
          berlinBlockNumber = 0,
          mystiqueBlockNumber = 0,
          spiralBlockNumber = 900,
          olympiaBlockNumber = 1000
        )
      )

  "SimulatedBlockSystemCalls on ETC" should "never reach a request contract, so an Olympia block with none deployed is fine" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    val header = amsterdamHeader(1000, 460).copy(extraFields = BlockHeader.HeaderExtraFields.HefPostOlympia(BigInt(1)))
    val world = setup.emptyWorld
    // the Prague timestamp predicate reads None on every ETC config, at any timestamp
    for timestamp <- Seq(0L, 460L, Long.MaxValue / 2) do
      val atTime = header.copy(unixTimestamp = Timestamp(timestamp))
      withClue(s"ETC at timestamp $timestamp: ") {
        simulated(atTime, world, config = etcOlympiaConfig).map(r => (r._1.stateRootHash, r._2)) shouldBe
          Right((world.stateRootHash, Nil))
      }
  }

  // ---- F15: the block's precompile relocations reach the system calls ----

  "SimulatedBlockSystemCalls" should "run a request contract on the precompiles as they are with no relocation" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val world = allStubs(WithdrawalQueueAddress -> ProbesIdentity)
    simulated(amsterdamBlock, world).map(_._2) shouldBe Right(Seq(request(0x01, word(0x20))))
  }

  it should "run a request contract with a precompile deleted (a -> a) as a call to an empty account" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val world = allStubs(WithdrawalQueueAddress -> ProbesIdentity)
    simulated(amsterdamBlock, world, Map(Identity -> Identity)).map(_._2) shouldBe
      Right(Seq(request(0x01, ZeroWord)))
  }

  it should "run a request contract with a precompile moved away: the old address answers nothing" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val world = allStubs(WithdrawalQueueAddress -> ProbesIdentity)
    simulated(amsterdamBlock, world, Map(Identity -> Elsewhere)).map(_._2) shouldBe
      Right(Seq(request(0x01, ZeroWord)))
  }

  it should "run the precompile, not the code, at a queue contract's address when a precompile was moved there" taggedAs (
    UnitTest,
    StateTest
  ) in {
    val world = allStubs(WithdrawalQueueAddress -> ReturnsBang)
    // as it is: the contract's code answers 0x42
    simulated(amsterdamBlock, world).map(_._2) shouldBe Right(Seq(request(0x01, Bang)))
    // identity moved onto the queue's address: the call carries no input, so identity answers nothing. go-ethereum's
    // `evm.Call` looks in the precompile set first, so the code is not run, though the code size check read the account
    simulated(amsterdamBlock, world, Map(Identity -> WithdrawalQueueAddress)).map(_._2) shouldBe Right(Nil)
  }

  it should "run the EIP-4788 call on the relocated precompiles too" taggedAs (UnitTest, StateTest) in {
    val world = worldWith(BeaconRootContractAddress -> ProbesIdentityIntoSlot0)
    storageOf(preamble(world, Map.empty), BeaconRootContractAddress, 0) shouldBe BigInt(0x20)
    storageOf(preamble(world, Map(Identity -> Identity)), BeaconRootContractAddress, 0) shouldBe BigInt(0)
  }

  it should "run the EIP-2935 call on the relocated precompiles too" taggedAs (UnitTest, StateTest) in {
    val world = worldWith(HistoryStorageAddress -> ProbesIdentityIntoSlot0)
    storageOf(preamble(world, Map.empty), HistoryStorageAddress, 0) shouldBe BigInt(0x20)
    storageOf(preamble(world, Map(Identity -> Elsewhere)), HistoryStorageAddress, 0) shouldBe BigInt(0)
  }

  "BlockExecution.applyAmsterdamPreambleSystemCalls" should "run on the precompiles as they are when given no relocations" taggedAs (
    UnitTest,
    StateTest
  ) in {
    given BlockchainConfig = amsterdamConfig
    val world = worldWith(BeaconRootContractAddress -> ProbesIdentityIntoSlot0)
    val byDefault = BlockExecution.applyAmsterdamPreambleSystemCalls(amsterdamBlock, world, None)
    storageOf(byDefault, BeaconRootContractAddress, 0) shouldBe BigInt(0x20)
    byDefault.stateRootHash shouldBe
      BlockExecution.applyAmsterdamPreambleSystemCalls(amsterdamBlock, world, None, Map.empty).stateRootHash
  }
