package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator.intByteArraySerializable
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** #1727 PR 4 (B-130) pins block import: adding `TxResult.maxUsedGas` for eth_simulateV1 must not move a single gas
  * figure, receipt, receipts root or state root that block execution produces.
  *
  * Every golden below was recorded by running this spec on the tree BEFORE `TxResult` gained the field, and it passes
  * unchanged after. It calls only the API that block import calls (`BlockPreparator.executeTransactions`, the one
  * `BlockExecution.executeBlockTransactions` makes), and reads only `BlockResult`, so it cannot see the new field; it
  * can only see whether execution moved.
  *
  * Each scenario runs the same seven transactions, chosen so the refund and the calldata floor, the two things
  * `maxUsedGas` is derived from, each bind somewhere:
  *   - tx 0: 1,000 non-zero calldata bytes to an EOA: the floor binds where one is active (61,000 against 37,000).
  *   - tx 1: clears a storage slot, no calldata: a 4,800 refund, post-refund gas below the pre-refund peak.
  *   - tx 2: the same with 200 calldata bytes: the floor (29,000) falls BETWEEN the post-refund (24,406) and the
  *     pre-refund (29,206) figures.
  *   - tx 3: the same with 1,000 bytes: the floor (61,000) is above both.
  *   - tx 4: a REVERT, which keeps its remaining gas. tx 5: an exceptional halt, which consumes all of it.
  *   - tx 6: a plain value transfer for exactly 21,000.
  *
  * The scenarios: ETH Cancun (no floor), ETH Prague, ETH Osaka, ETH Amsterdam; ETC before Olympia (no floor) and ETC at
  * Olympia (the ECIP-1121 floor).
  */
// scalastyle:off magic.number
class TxResultBlockImportPinSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  // ── The world ───────────────────────────────────────────────────────────────

  private val Payee = Address(0xe1)
  private val Clearers = Seq(Address(0xc1), Address(0xc2), Address(0xc3))
  private val Reverter = Address(0xd1)
  private val Spinner = Address(0xd2)

  /** PUSH1 0, PUSH1 0, SSTORE, STOP: clears slot 0, which is preset to 1. */
  private val ClearerCode = ByteString(Hex.decode("600060005500"))

  /** PUSH1 0, PUSH1 0, REVERT. */
  private val ReverterCode = ByteString(Hex.decode("60006000fd"))

  /** JUMPDEST, PUSH1 0, JUMP: spins until the gas is gone. */
  private val SpinnerCode = ByteString(Hex.decode("5b600056"))

  private def withCode(world: InMemoryWorldStateProxy, address: Address, code: ByteString) =
    world
      .saveAccount(address, Account(nonce = UInt256(0), balance = UInt256(0), codeHash = CodeHash(kec256(code))))
      .saveCode(address, code)

  private def world: InMemoryWorldStateProxy =
    val base = setup.emptyWorld
      .saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt(10).pow(24))))
      .saveAccount(Payee, Account(nonce = UInt256(0), balance = UInt256(1)))
    val withClearers = Clearers.foldLeft(base) { (w, address) =>
      val coded = withCode(w, address, ClearerCode)
      coded.saveStorage(address, coded.getStorage(address).store(UInt256(0), UInt256(1)))
    }
    InMemoryWorldStateProxy.persistState(
      withCode(withCode(withClearers, Reverter, ReverterCode), Spinner, SpinnerCode)
    )

  private def nonZeros(n: Int): ByteString = ByteString(Array.fill[Byte](n)(0x01))

  private def transactions(config: BlockchainConfig): Seq[SignedTransaction] =
    val calls = Seq(
      (Payee, BigInt(0), nonZeros(1000), BigInt(100000)),
      (Clearers(0), BigInt(0), ByteString.empty, BigInt(100000)),
      (Clearers(1), BigInt(0), nonZeros(200), BigInt(100000)),
      (Clearers(2), BigInt(0), nonZeros(1000), BigInt(100000)),
      (Reverter, BigInt(0), ByteString.empty, BigInt(100000)),
      (Spinner, BigInt(0), ByteString.empty, BigInt(100000)),
      (Payee, BigInt(1), ByteString.empty, BigInt(21000))
    )
    calls.zipWithIndex.map { case ((to, value, payload, gas), nonce) =>
      legacyTx(Some(to), value, gas, payload, nonce = BigInt(nonce), config = config)
    }

  // ── What is pinned ──────────────────────────────────────────────────────────

  private def receiptsRoot(receipts: Seq[Receipt]): String =
    val storage = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
    val trie = receipts.zipWithIndex.foldLeft(
      MerklePatriciaTrie[Int, Receipt](storage)(using intByteArraySerializable, Receipt.byteArraySerializable)
    ) { case (t, (receipt, index)) => t.put(index, receipt) }
    Hex.toHexString(trie.getRootHash)

  private def render(result: BlockResult): String =
    val receipts = result.receipts.map { receipt =>
      val outcome = receipt.postTransactionStateHash match
        case SuccessOutcome => "ok"
        case FailureOutcome => "fail"
        case HashOutcome(h) => "root-" + Hex.toHexString(h.toArray)
      s"$outcome/${receipt.cumulativeGasUsed}"
    }
    s"""receipts: ${receipts.mkString(" ")}
       |gasUsed: ${result.gasUsed} execution: ${result.executionGasUsed} state: ${result.stateGasUsed}
       |receiptsRoot: ${receiptsRoot(result.receipts)}
       |stateRoot: ${Hex.toHexString(result.worldState.stateRootHash.toArray)}""".stripMargin

  private def run(header: BlockHeader, config: BlockchainConfig): String =
    render(executeBlock(transactions(config), header, world, config))

  // ── ETH: the Amsterdam fixture schedule (Cancun 60, Prague 120, Osaka 180, Amsterdam 360) ───────────────────────

  "Block import on ETH Cancun" should "charge, refund and commit exactly what it did before maxUsedGas" taggedAs (
    UnitTest,
    StateTest
  ) in {
    run(preAmsterdamHeader(46, 60), amsterdamConfig) shouldBe
      """receipts: ok/37000 ok/58206 ok/82612 ok/119818 fail/140824 fail/240824 ok/261824
        |gasUsed: 261824 execution: 261824 state: 0
        |receiptsRoot: 973474d3579db9a0efce55638987cd52c90a53b2aeb1f6a6f6c127f70b1b2353
        |stateRoot: 62ea0e43dcef4fa6bfac9d4443b2925794a0a32740ee339763df4a5d6c5c3c09""".stripMargin
  }

  "Block import on ETH Prague" should "charge, refund and commit exactly what it did before maxUsedGas" taggedAs (
    UnitTest,
    StateTest
  ) in {
    run(preAmsterdamHeader(46, 120), amsterdamConfig) shouldBe
      """receipts: ok/61000 ok/82206 ok/111206 ok/172206 fail/193212 fail/293212 ok/314212
        |gasUsed: 314212 execution: 314212 state: 0
        |receiptsRoot: 45d45fc731969b9f490bf3978db15943723525497685699c56c5b5921fcfce09
        |stateRoot: a4d46ccfb7d0e1b0ec9dc7b2826394c0c152bdf89e3a77d04943c7f4b097a242""".stripMargin
  }

  "Block import on ETH Osaka" should "charge, refund and commit exactly what it did before maxUsedGas" taggedAs (
    UnitTest,
    StateTest
  ) in {
    run(preAmsterdamHeader(46, 300), amsterdamConfig) shouldBe
      """receipts: ok/61000 ok/82206 ok/111206 ok/172206 fail/193212 fail/293212 ok/314212
        |gasUsed: 314212 execution: 314212 state: 0
        |receiptsRoot: 45d45fc731969b9f490bf3978db15943723525497685699c56c5b5921fcfce09
        |stateRoot: a4d46ccfb7d0e1b0ec9dc7b2826394c0c152bdf89e3a77d04943c7f4b097a242""".stripMargin
  }

  "Block import on ETH Amsterdam" should "charge, refund and commit exactly what it did before maxUsedGas" taggedAs (
    UnitTest,
    StateTest
  ) in {
    run(amsterdamHeader(46, 460), amsterdamConfig) shouldBe
      """receipts: ok/79000 ok/100685 ok/128485 ok/207485 fail/222491 fail/322491 ok/343491
        |gasUsed: 351418 execution: 351418 state: 0
        |receiptsRoot: f0e0c3b2f89690a04ec91fd551bc9ebcf513781affc9c29b32ec6f8cb4656990
        |stateRoot: fa61fe21963a4ec60167210e2ae2f39f65dd7974066076734b19879966b8b865""".stripMargin
  }

  // ── ETC: Olympia at block 1,000 (ECIP-1121), Spiral at 900 ──────────────────────────────────────────────────────

  private val OlympiaBlock = BigInt(1000)

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
          olympiaBlockNumber = OlympiaBlock
        )
      )

  private def etcHeader(number: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      gasLimit = GasAmount(30_000_000),
      gasUsed = GasAmount.Zero,
      extraFields = if number >= OlympiaBlock then HefPostOlympia(BigInt(1)) else HefEmpty
    )

  "Block import on ETC before Olympia" should "charge, refund and commit exactly what it did before maxUsedGas" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    run(etcHeader(OlympiaBlock - 1), etcOlympiaConfig) shouldBe
      """receipts: ok/37000 ok/58206 ok/82612 ok/119818 fail/140824 fail/240824 ok/261824
        |gasUsed: 261824 execution: 261824 state: 0
        |receiptsRoot: 973474d3579db9a0efce55638987cd52c90a53b2aeb1f6a6f6c127f70b1b2353
        |stateRoot: c09b21440a10e28bc0e5f06b8a09d4ef56905b05fd2f94cdeedd2e19f3fa0617""".stripMargin
  }

  "Block import on ETC at Olympia" should "charge, refund and commit exactly what it did before maxUsedGas" taggedAs (
    UnitTest,
    StateTest,
    OlympiaTest
  ) in {
    run(etcHeader(OlympiaBlock), etcOlympiaConfig) shouldBe
      """receipts: ok/61000 ok/82206 ok/111206 ok/172206 fail/193212 fail/293212 ok/314212
        |gasUsed: 314212 execution: 314212 state: 0
        |receiptsRoot: 45d45fc731969b9f490bf3978db15943723525497685699c56c5b5921fcfce09
        |stateRoot: 3059d91167de737a82c99f3dcee2b986703f65bacff797fa94b9b3f89eae1f66""".stripMargin
  }
