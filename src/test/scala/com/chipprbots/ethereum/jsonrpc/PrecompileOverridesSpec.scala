package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.sha256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.StxLedger
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** #1727 PR 3 (B-129): state overrides that touch a precompile, shared by eth_call, eth_estimateGas,
  * eth_createAccessList and eth_simulateV1 (one `EthSimulateService.applyStateOverrides`, as go-ethereum has one
  * `override.StateOverride.Apply`). Compared with go-ethereum v1.17.9 `internal/ethapi/override/override.go`:
  *   - F10 (:92-94): ANY entry at a precompile's address deletes the precompile, an empty or null override included;
  *   - F11 (:75, and api.go:769 / simulate.go:366): the precompiles an override can name or move are those the block
  *     has (`vm.ActivePrecompiledContracts(rules)`), so 0x0a is one and a precompile of a later fork is not;
  *   - F12 (:65, :89): the entries are applied in address order, so of several moves to one target the last wins, and
  *     every source is gone;
  *   - the "is not a precompile" text carries the EIP-55 address (`addr.Hex()`).
  *
  * Chain: the Amsterdam fixture schedule (Cancun 60, Prague 120, Osaka 180, Amsterdam 360).
  */
// scalastyle:off magic.number
class PrecompileOverridesSpec extends AnyFlatSpec with Matchers with EitherValues with AmsterdamFixtureVectors:

  implicit val runtime: IORuntime = IORuntime.global

  // ── Addresses and inputs ───────────────────────────────────────────────────────────────────────────────────────

  private val Ecrecover = Address(0x01)
  private val Sha256 = Address(0x02)
  private val Rip160 = Address(0x03)
  private val Identity = Address(0x04)
  private val KzgPointEval = Address(0x0a)
  private val Bls12G1Add = Address(0x0b)
  private val P256Verify = Address(0x100)
  private val Plain = Address(0x77)
  private val Moved = Address("0xc200000000000000000000000000000000000000")
  private val Caller = Address("0xc000000000000000000000000000000000000000")
  private val Input = ByteString(Hex.decode("aabbccdd"))
  private val NoData = ByteString.empty

  /** PUSH1 0x42, PUSH1 0, MSTORE8, PUSH1 1, PUSH1 0, RETURN: returns the single byte 0x42. */
  private val ReturnsBang = ByteString(Hex.decode("604260005360016000f3"))

  /** EIP-55's own test vector, so the expected text does not depend on the code under test. */
  private val Lower = Address("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")
  private val Checksummed = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

  private def moveTo(target: Address): StateOverride = StateOverride(movePrecompileToAddress = Some(target))

  private def callArgs(to: Address, data: ByteString = Input): TxArgs =
    TxArgs(from = Some(Caller), to = Some(to), data = Some(data), gas = Some(GasAmount(1_000_000)))

  private def notAPrecompile(address: String): JsonRpcError =
    JsonRpcError.LogicError(s"account $address is not a precompile")

  // ── eth_simulateV1 node ────────────────────────────────────────────────────────────────────────────────────────

  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  private val zero32: ByteString = ByteString(new Array[Byte](32))

  /** The simulated block is at `time` (the default increment is 12 s, so the base block is 12 s earlier). */
  private class SimNode(time: Long) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = amsterdamConfig

    private val baseTime = time - 12
    blockchainWriter.save(
      Block(
        Fixtures.Blocks.ValidBlock.header.copy(
          number = BlockNumber(29),
          difficulty = Difficulty(0),
          beneficiary = ByteString(new Array[Byte](20)),
          stateRoot = TrieRoot(emptyRoot),
          gasLimit = GasAmount(30_000_000),
          gasUsed = GasAmount.Zero,
          unixTimestamp = Timestamp(baseTime),
          extraFields =
            if baseTime >= 120 then HefPostPrague(7, emptyRoot, 0, 0, zero32, zero32)
            else HefPostCancun(7, emptyRoot, 0, 0, zero32)
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

    def simulate(
        overrides: Map[Address, StateOverride],
        calls: TxArgs*
    ): Either[JsonRpcError, List[SimulateCallResult]] =
      service
        .ethSimulate(
          EthSimulateRequest(Seq(BlockStateCall(stateOverrides = Some(overrides), calls = Some(calls.toSeq))))
        )
        .unsafeRunSync()
        .map(_.blocks.head.calls.toList)

    /** What each call of each block returned. */
    def simulateBlocks(blocks: BlockStateCall*): Either[JsonRpcError, List[List[ByteString]]] =
      service
        .ethSimulate(EthSimulateRequest(blocks.toSeq))
        .unsafeRunSync()
        .map(_.blocks.toList.map(_.calls.toList.map(_.returnData)))

    /** What each call returned. Every call must succeed: an error here fails the test, it is not a result. */
    def returns(overrides: Map[Address, StateOverride], calls: TxArgs*): List[ByteString] =
      simulate(overrides, calls*) match
        case Right(results) =>
          results.foreach(r => withClue(s"call result $r: ")(r.error shouldBe None))
          results.map(_.returnData)
        case Left(err) => fail(s"expected a result, got $err")

    def json(payload: String): Either[JsonRpcError, List[JValue]] =
      for
        req <- EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(
          Some(JArray(List(parse(payload), JString("latest"))))
        )
        res <- service.ethSimulate(req).unsafeRunSync()
      yield EthSimulateJsonMethodsImplicits.eth_simulateV1.encodeJson(res) match
        case JArray(bs) => bs
        case other      => fail(s"expected an array of blocks, got $other")

  // ── eth_call / eth_estimateGas / eth_createAccessList node ─────────────────────────────────────────────────────

  private class CallNode(
      config: BlockchainConfig = preAmsterdamConfig,
      header: BlockHeader = preAmsterdamHeader(46, 300)
  ) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    lazy val ledger: StxLedger =
      new StxLedger(
        blockchain,
        blockchainReader,
        storagesInstance.storages.evmCodeStorage,
        mining.blockPreparator,
        this
      )

    val world: InMemoryWorldStateProxy =
      InMemoryWorldStateProxy.persistState(
        InMemoryWorldStateProxy(
          storagesInstance.storages.evmCodeStorage,
          blockchain.getBackingMptStorage(-1),
          (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
          UInt256.Zero,
          ByteString(MerklePatriciaTrie.EmptyRootHash),
          noEmptyAccounts = true,
          ethCompatibleStorage = true
        ).saveAccount(Caller, Account(balance = UInt256(BigInt(10).pow(24))))
      )

    val head: BlockHeader = header.copy(stateRoot = TrieRoot(world.stateRootHash))
    blockchainWriter.save(Block(head, BlockBody(Nil, Nil, Some(Nil))), Nil, ChainWeight.zero, saveAsBestBlock = true)

    // syncController / keyStore are never reached by the call methods (every request names `from`)
    lazy val service = new EthInfoService(
      blockchain,
      blockchainReader,
      config,
      mining,
      ledger,
      null,
      null,
      Capability.ETH68,
      Timeout(5.seconds),
      null
    )

    def call(
        tx: TxArgs,
        overrides: Map[Address, StateOverride],
        blockOverrides: Option[BlockOverrides] = None
    ): Either[JsonRpcError, ByteString] =
      service
        .call(CallRequest(tx, BlockParam.Latest, Some(overrides), blockOverrides))
        .unsafeRunSync()
        .map(_.returnData)

    def estimate(
        tx: TxArgs,
        overrides: Map[Address, StateOverride],
        blockOverrides: Option[BlockOverrides] = None
    ): Either[JsonRpcError, BigInt] =
      service
        .estimateGas(CallRequest(tx, BlockParam.Latest, Some(overrides), blockOverrides))
        .unsafeRunSync()
        .map(_.gas)

    def accessList(tx: TxArgs, overrides: Map[Address, StateOverride]): Either[JsonRpcError, CreateAccessListResponse] =
      service.createAccessList(CreateAccessListRequest(tx, BlockParam.Latest, Some(overrides))).unsafeRunSync()

  /** Both nodes at one block time. */
  private class AtTime(val time: Long):
    val call = new CallNode(header = preAmsterdamHeader(46, time))
    val sim = new SimNode(time)

    /** The error an endpoint answers a request for these overrides with, or None when it accepts them. The call is a
      * plain transfer to an empty account, which succeeds wherever the overrides are accepted.
      */
    def refusal(endpoint: String, overrides: Map[Address, StateOverride]): Option[JsonRpcError] =
      val tx = callArgs(Plain, NoData)
      endpoint match
        case "eth_call"             => call.call(tx, overrides).left.toOption
        case "eth_estimateGas"      => call.estimate(tx, overrides).left.toOption
        case "eth_createAccessList" => call.accessList(tx, overrides).left.toOption
        case "eth_simulateV1"       => sim.simulate(overrides, tx).left.toOption
        case other                  => fail(s"unknown endpoint $other")

  private val Endpoints = List("eth_call", "eth_estimateGas", "eth_createAccessList", "eth_simulateV1")

  // ── F10: any entry at a precompile's address deletes the precompile (override.go:92-94) ───────────────────────────

  "eth_simulateV1" should "delete a precompile when any override names its address (override.go:92-94)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new SimNode(300)
    val overrides = List(
      "balance" -> StateOverride(balance = Some(1)),
      "nonce" -> StateOverride(nonce = Some(1)),
      "stateDiff" -> StateOverride(stateDiff = Some(Map())),
      "an empty override" -> StateOverride()
    )
    for (name, ov) <- overrides do
      withClue(s"$name at the identity precompile: ") {
        node.returns(Map(Identity -> ov), callArgs(Identity)) shouldBe List(NoData)
      }
  }

  it should "read a null account override as an empty one, which also deletes the precompile" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val payload =
      s"""{"blockStateCalls":[{"stateOverrides":{"$Identity":null},"calls":[{"from":"$Caller","to":"$Identity","input":"0xaabbccdd"}]}]}"""
    (new SimNode(300).json(payload).value.head \ "calls") match
      case JArray(call :: Nil) => (call \ "returnData") shouldBe JString("0x")
      case other               => fail(s"expected one call, got $other")
  }

  it should "run the code an override puts at a precompile's address, not the precompile" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    new SimNode(300).returns(Map(Identity -> StateOverride(code = Some(ReturnsBang))), callArgs(Identity)) shouldBe
      List(ByteString(0x42))
  }

  it should "leave a precompile alone when the overrides name other addresses" taggedAs (UnitTest, RPCTest) in {
    new SimNode(300).returns(Map(Plain -> StateOverride(balance = Some(1))), callArgs(Identity)) shouldBe List(Input)
  }

  it should "move a precompile to the target and leave nothing at its own address" taggedAs (UnitTest, RPCTest) in {
    new SimNode(300).returns(Map(Identity -> moveTo(Moved)), callArgs(Moved), callArgs(Identity)) shouldBe
      List(Input, NoData)
  }

  // ── F12: sorted order, the last move to a target wins, every source is removed ───────────────────────────────

  it should "let the last of several moves to one target win, by address, and remove every source" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new SimNode(300)
    // sha256 (0x02) and identity (0x04): identity sorts last, so it answers at the target and echoes its input
    node.returns(
      Map(Sha256 -> moveTo(Moved), Identity -> moveTo(Moved)),
      callArgs(Moved),
      callArgs(Sha256),
      callArgs(Identity)
    ) shouldBe List(Input, NoData, NoData)
    // ecrecover (0x01) and sha256 (0x02): sha256 sorts last, so the target answers with the digest of the input
    node.returns(
      Map(Sha256 -> moveTo(Moved), Ecrecover -> moveTo(Moved)),
      callArgs(Moved),
      callArgs(Sha256),
      callArgs(Ecrecover)
    ) shouldBe List(ByteString(sha256(Input.toArray)), NoData, NoData)
    // three sources, 0x02 to 0x04
    node.returns(
      Map(Identity -> moveTo(Moved), Sha256 -> moveTo(Moved), Rip160 -> moveTo(Moved)),
      callArgs(Moved),
      callArgs(Sha256),
      callArgs(Rip160),
      callArgs(Identity)
    ) shouldBe List(Input, NoData, NoData, NoData)
  }

  // ── F11: the precompiles the block has, from PrecompiledContracts.getContracts ──────────────────────────────

  "a movePrecompileToAddress override" should "be accepted for each precompile the block has, 0x0a included" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val osaka = new AtTime(300)
    val cancun = new AtTime(100)
    for
      endpoint <- Endpoints
      (chain, source) <- List(
        osaka -> KzgPointEval,
        osaka -> Bls12G1Add,
        osaka -> P256Verify,
        osaka -> Identity,
        cancun -> KzgPointEval,
        cancun -> Identity
      )
    do
      withClue(s"$endpoint at block time ${chain.time}, moving $source: ") {
        chain.refusal(endpoint, Map(source -> moveTo(Moved))) shouldBe None
      }
  }

  it should "be refused for a precompile that a later fork adds, as 'not a precompile'" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val cancun = new AtTime(100)
    for
      endpoint <- Endpoints
      source <- List(Bls12G1Add, P256Verify)
    do
      withClue(s"$endpoint at block time ${cancun.time}, moving $source: ") {
        cancun.refusal(endpoint, Map(source -> moveTo(Moved))) shouldBe
          Some(notAPrecompile(EthSimulateService.checksumAddress(source)))
      }
  }

  // the node's block is at Osaka (time 300); geth builds the rules from the overridden block context
  it should "be refused when a block override puts the call's block before the precompile's fork" taggedAs (
    UnitTest,
    RPCTest
  ) in new CallNode:
    val atCancun: Option[BlockOverrides] = Some(BlockOverrides(time = Some(100)))
    val tx: TxArgs = callArgs(Plain, NoData)
    val refused: Option[JsonRpcError] = Some(notAPrecompile(EthSimulateService.checksumAddress(Bls12G1Add)))
    call(tx, Map(Bls12G1Add -> moveTo(Moved)), atCancun).left.toOption shouldBe refused
    estimate(tx, Map(Bls12G1Add -> moveTo(Moved)), atCancun).left.toOption shouldBe refused
    // the same move at the unchanged block is accepted
    call(tx, Map(Bls12G1Add -> moveTo(Moved))).isRight shouldBe true

  it should "be refused on ETC before Olympia for 0x0a, BLS12-381 and P256VERIFY" taggedAs (UnitTest, RPCTest) in {
    val etcConfig: BlockchainConfig =
      preAmsterdamConfig
        .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
        .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = BigInt(Long.MaxValue)))
    val etcHeader: BlockHeader =
      Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(100), extraFields = HefEmpty)
    val node = new CallNode(etcConfig, etcHeader)
    val tx = callArgs(Plain, NoData)
    for source <- List(KzgPointEval, Bls12G1Add, P256Verify) do
      val refused = Some(notAPrecompile(EthSimulateService.checksumAddress(source)))
      withClue(s"ETC, moving $source: ") {
        node.call(tx, Map(source -> moveTo(Moved))).left.toOption shouldBe refused
        node.estimate(tx, Map(source -> moveTo(Moved))).left.toOption shouldBe refused
        node.accessList(tx, Map(source -> moveTo(Moved))).left.toOption shouldBe refused
      }
    node.call(tx, Map(Identity -> moveTo(Moved))).isRight shouldBe true
  }

  it should "leave nothing at 0x0a once it is moved" taggedAs (UnitTest, RPCTest) in {
    // the KZG precompile itself is not run (its native library is a separate concern): only that 0x0a is now empty
    new SimNode(300).returns(Map(KzgPointEval -> moveTo(Moved)), callArgs(KzgPointEval)) shouldBe List(NoData)
  }

  // ── The "not a precompile" text carries the EIP-55 address (override.go:82, `addr.Hex()`) ─────────────────────

  it should "name the address with its EIP-55 checksum when the account is not a precompile" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    Lower.toString should not be Checksummed
    Lower.toString shouldBe Checksummed.toLowerCase
    EthSimulateService.checksumAddress(Lower) shouldBe Checksummed
    val chain = new AtTime(300)
    for endpoint <- Endpoints do
      withClue(s"$endpoint: ") {
        chain.refusal(endpoint, Map(Lower -> moveTo(Moved))) shouldBe Some(notAPrecompile(Checksummed))
      }
  }

  // ── The same overrides, through eth_call, eth_estimateGas and eth_createAccessList ────────────────────────────

  "eth_call" should "delete a precompile when any override names its address, and run the override's code" taggedAs (
    UnitTest,
    RPCTest
  ) in new CallNode:
    call(callArgs(Identity), Map()) shouldBe Right(Input)
    for ov <- List(StateOverride(balance = Some(1)), StateOverride()) do
      call(callArgs(Identity), Map(Identity -> ov)) shouldBe Right(NoData)
    call(callArgs(Identity), Map(Identity -> StateOverride(code = Some(ReturnsBang)))) shouldBe
      Right(ByteString(0x42))

  it should "let the last of several moves to one target win, and remove every source" taggedAs (
    UnitTest,
    RPCTest
  ) in new CallNode:
    val moves: Map[Address, StateOverride] = Map(Sha256 -> moveTo(Moved), Identity -> moveTo(Moved))
    call(callArgs(Moved), moves) shouldBe Right(Input)
    call(callArgs(Sha256), moves) shouldBe Right(NoData)
    call(callArgs(Identity), moves) shouldBe Right(NoData)

  it should "read a null account override, as the eth_call decoder gives it, as an empty one that deletes" taggedAs (
    UnitTest,
    RPCTest
  ) in new CallNode:
    val params: JArray = JArray(
      List(
        JObject(
          "from" -> JString(Caller.toString),
          "to" -> JString(Identity.toString),
          "data" -> JString("0xaabbccdd")
        ),
        JString("latest"),
        JObject(Identity.toString -> JNull)
      )
    )
    val req: CallRequest = EthJsonMethodsImplicits.eth_call.decodeJson(Some(params)).value
    service.call(req).unsafeRunSync().map(_.returnData) shouldBe Right(NoData)

  // a Cancun-time block has no EIP-7623 floor; 100 bytes of calldata cost 21,000 + 1,600, identity adds 15 + 3 * 4
  "eth_estimateGas and eth_createAccessList" should "charge no precompile gas once an override deletes it" taggedAs (
    UnitTest,
    RPCTest
  ) in new CallNode(header = preAmsterdamHeader(46, 100)):
    val tx: TxArgs = callArgs(Identity, ByteString(Array.fill[Byte](100)(1)))
    val deleted: Map[Address, StateOverride] = Map(Identity -> StateOverride(balance = Some(1)))
    estimate(tx, Map()).value shouldBe BigInt(22627)
    estimate(tx, deleted).value shouldBe BigInt(22600)
    accessList(tx, Map()).value.gasUsed shouldBe BigInt(22627)
    accessList(tx, deleted).value.gasUsed shouldBe BigInt(22600)

  // the target answers with identity (it sorts last), which charges 15 + 3 * 4; sha256 would charge 60 + 12 * 4
  it should "let the last of several moves to one target win" taggedAs (
    UnitTest,
    RPCTest
  ) in new CallNode(header = preAmsterdamHeader(46, 100)):
    val tx: TxArgs = callArgs(Moved, ByteString(Array.fill[Byte](100)(1)))
    val moves: Map[Address, StateOverride] = Map(Sha256 -> moveTo(Moved), Identity -> moveTo(Moved))
    estimate(tx, moves).value shouldBe BigInt(22627)
    accessList(tx, moves).value.gasUsed shouldBe BigInt(22627)

  // ── Moving onto another precompile, and the lifetime of a move ────────────────────────────────────────────────

  // identity answers at sha256's address; sha256 is gone, and the identity address answers nothing
  "a movePrecompileToAddress override" should "replace the precompile at its target, which is not cleared (override.go:76-79)" taggedAs (
    UnitTest,
    RPCTest
  ) in new CallNode:
    val moves: Map[Address, StateOverride] = Map(Identity -> moveTo(Sha256))
    call(callArgs(Sha256), moves) shouldBe Right(Input)
    call(callArgs(Identity), moves) shouldBe Right(NoData)

  it should "last for the block it is in: the next block starts from its own precompiles (simulate.go:366)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new SimNode(300)
    val afterMove = Moved
    val another = Address("0xc300000000000000000000000000000000000000")
    // block 1 deletes identity; block 2 has no override and has it again; block 3 moves it, which a block that no
    // longer has identity at its own address could not (execution-apis ethSimulate-move-ecrecover-twice-and-call)
    node.simulateBlocks(
      BlockStateCall(
        stateOverrides = Some(Map(Identity -> StateOverride(balance = Some(1)))),
        calls = Some(Seq(callArgs(Identity)))
      ),
      BlockStateCall(calls = Some(Seq(callArgs(Identity)))),
      BlockStateCall(
        stateOverrides = Some(Map(Identity -> moveTo(afterMove))),
        calls = Some(Seq(callArgs(afterMove), callArgs(Identity)))
      ),
      BlockStateCall(
        stateOverrides = Some(Map(Identity -> moveTo(another))),
        calls = Some(Seq(callArgs(afterMove), callArgs(Identity), callArgs(another)))
      )
    ) shouldBe Right(
      List(
        List(NoData),
        List(Input),
        List(Input, NoData),
        List(NoData, NoData, Input)
      )
    )
  }

  // ── The relocations handed to the EVM, and the precompile set they are checked against ────────────────────────

  "applyStateOverrides" should "encode a deletion as `a -> a`, a move as `a -> target`, and a lost move as `a -> a`" taggedAs (
    UnitTest,
    RPCTest
  ) in new CallNode:
    def relocations(overrides: Map[Address, StateOverride]): Map[Address, Address] =
      EthSimulateService
        .applyStateOverrides(world, overrides, EthSimulateService.activePrecompiles(head), UInt256.Zero)
        .value
        ._2
    relocations(Map()) shouldBe Map()
    relocations(Map(Plain -> StateOverride(balance = Some(1)))) shouldBe Map()
    relocations(Map(Identity -> StateOverride(balance = Some(1)))) shouldBe Map(Identity -> Identity)
    relocations(Map(Identity -> StateOverride())) shouldBe Map(Identity -> Identity)
    relocations(Map(Identity -> moveTo(Moved))) shouldBe Map(Identity -> Moved)
    relocations(Map(Sha256 -> moveTo(Moved), Identity -> moveTo(Moved))) shouldBe
      Map(Sha256 -> Sha256, Identity -> Moved)
    relocations(Map(Ecrecover -> moveTo(Moved), Sha256 -> moveTo(Moved), Identity -> moveTo(Moved))) shouldBe
      Map(Ecrecover -> Ecrecover, Sha256 -> Sha256, Identity -> Moved)
    // a move onto another precompile's address, which has no entry: that precompile is replaced, not deleted
    relocations(Map(Identity -> moveTo(Sha256))) shouldBe Map(Identity -> Sha256)

  it should "create no account at a precompile it deletes or moves, and keep what the override sets there" taggedAs (
    UnitTest,
    RPCTest
  ) in new CallNode:
    def overridden(overrides: Map[Address, StateOverride]): InMemoryWorldStateProxy =
      EthSimulateService
        .applyStateOverrides(world, overrides, EthSimulateService.activePrecompiles(head), UInt256.Zero)
        .value
        ._1
    overridden(Map(Identity -> StateOverride())).getAccount(Identity) shouldBe None
    overridden(Map(Identity -> moveTo(Moved))).getAccount(Identity) shouldBe None
    overridden(Map(Identity -> StateOverride(balance = Some(5)))).getAccount(Identity).map(_.balance) shouldBe
      Some(UInt256(5))

  "activePrecompiles" should "be the set the block's fork runs, 0x0a from Cancun and 0x100 from Osaka" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    given BlockchainConfig = preAmsterdamConfig
    val upTo0x09 = (1 to 9).map(Address(_)).toSet
    val cancun = upTo0x09 + KzgPointEval
    val prague = cancun ++ (0x0b to 0x11).map(Address(_))
    val osaka = prague + P256Verify
    EthSimulateService.activePrecompiles(preAmsterdamHeader(46, 100)) shouldBe cancun
    EthSimulateService.activePrecompiles(preAmsterdamHeader(46, 150)) shouldBe prague
    EthSimulateService.activePrecompiles(preAmsterdamHeader(46, 300)) shouldBe osaka
    EthSimulateService.activePrecompiles(amsterdamHeader(46, 460))(using amsterdamConfig) shouldBe osaka
  }

  it should "not include 0x0a, BLS12-381 or P256VERIFY on ETC before Olympia" taggedAs (UnitTest, RPCTest) in {
    given BlockchainConfig =
      preAmsterdamConfig
        .copy(networkType = NetworkType.ETC, forkTimestamps = ForkTimestamps())
        .withUpdatedForkBlocks(_.copy(olympiaBlockNumber = BigInt(Long.MaxValue)))
    val active = EthSimulateService.activePrecompiles(
      Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(100), extraFields = HefEmpty)
    )
    active should contain(Identity)
    active should not contain KzgPointEval
    active should not contain Bls12G1Add
    active should not contain P256Verify
  }
