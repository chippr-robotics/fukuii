package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** #1727 PR 6 (B-132): a simulated block is paid what block import pays it, on the chain's proof-of-work rules at its
  * number.
  *
  * go-ethereum's eth_simulateV1 gives a simulated block the previous block's difficulty, or zero after the merge
  * (internal/ethapi/simulate.go:661-669, `ChainConfig.IsPostMerge`), and pays it through the engine's `Finalize`
  * (simulate.go:511), which pays the proof-of-work reward for a header with a difficulty (consensus/beacon
  * `IsPoSHeader`). core-geth has no eth_simulateV1, so on ETC the reference is block import itself: ECIP-1017's era
  * reward (5,000,000-block eras on ETC, 2,000,000 on Mordor) and, from Olympia, ECIP-1111's base fee times gas used to
  * the treasury. Before this change a simulated ETC block was paid the first era's 5 ETC at every height, and an
  * Olympia block got zero difficulty and no reward, because both were read from the header's shape.
  *
  * The balances are read inside the simulation: a later block calls a contract that returns BALANCE(address). The
  * oracle cases check the simulated state root against `BlockPreparator.prepareBlock` (the block's transactions, then
  * `payBlockReward`, as block import runs them), given the same parent state, header and transactions, signed.
  *
  * Chains: the shipped ETC, Mordor, ETH and Sepolia configs; Olympia, unscheduled on every shipped chain, is moved to
  * block 23,000,000 on ETC and 15,000,000 on Mordor.
  */
// scalastyle:off magic.number
class EthSimulateEtcRewardSpec extends AnyFlatSpec with Matchers:

  private val shippedConfigs = ConfigFactory.load()
  private def shipped(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedConfigs.getConfig(s"fukuii.blockchains.$name"))

  private val etc: BlockchainConfig = shipped("etc")
  private val mordor: BlockchainConfig = shipped("mordor")
  private val eth: BlockchainConfig = shipped("eth")
  private val sepolia: BlockchainConfig = shipped("sepolia")
  private val etcOlympia: BlockchainConfig = etc.withUpdatedForkBlocks(_.copy(olympiaBlockNumber = 23_000_000))
  private val mordorOlympia: BlockchainConfig = mordor.withUpdatedForkBlocks(_.copy(olympiaBlockNumber = 15_000_000))

  private val E = BigInt(10).pow(18)
  private val Gwei = BigInt(1_000_000_000)
  private val PowDifficulty = BigInt("14005986920576")
  private val SenderFunds = BigInt(10).pow(19)

  // hive's well-known funded key, 0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b
  private val senderKeyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(Hex.decode("45a915e4d060149eb4365960e6a7a45f334393093061116b197e3240065ff2d8"))
  private val Sender: Address = Address(senderKeyPair)
  private val SenderHex = "0x" + Hex.toHexString(Sender.bytes.toArray)

  private val Recipient = "0x5151000000000000000000000000000000005151"
  private val Caller = "0xc000000000000000000000000000000000000000"
  private val MinerA = "0xa000000000000000000000000000000000000001"
  private val MinerB = "0xa000000000000000000000000000000000000002"
  private val MinerC = "0xa000000000000000000000000000000000000003"

  /** PUSH1 0, CALLDATALOAD, BALANCE, PUSH1 0, MSTORE, PUSH1 32, PUSH1 0, RETURN: the balance of the address in the
    * calldata.
    */
  private val BalanceReader = "0xbbbb00000000000000000000000000000000bbbb"
  private val BalanceReaderCode = "0x6000353160005260206000f3"

  /** DIFFICULTY, PUSH1 0, MSTORE, PUSH1 32, PUSH1 0, RETURN: what DIFFICULTY reads in the block. */
  private val DifficultyReader = "0xdddd00000000000000000000000000000000dddd"
  private val DifficultyReaderCode = "0x4460005260206000f3"

  private def hexQuantity(n: BigInt): String = "0x" + n.toString(16)
  private def word(address: String): String = "0x" + "00" * 12 + address.drop(2)

  /** A node whose best block is `baseNumber`, with `baseDifficulty` and `baseExtraFields` (`HefPostOlympia` for a block
    * with a base fee: ETC from Olympia, ETH from London), and a state that holds only the funded sender.
    */
  private class Node(
      config: BlockchainConfig,
      baseNumber: BigInt,
      baseDifficulty: BigInt,
      baseExtraFields: HeaderExtraFields = HefEmpty,
      baseTimestamp: Long = 1_500_000_000L
  ) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    private val preState: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
      config.accountStartNonce,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = config.ethCompatibleStorage
    ).saveAccount(Sender, Account(nonce = config.accountStartNonce, balance = UInt256(SenderFunds)))

    val base: BlockHeader = Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(baseNumber),
      difficulty = Difficulty(baseDifficulty),
      beneficiary = Address(0xba5e).bytes,
      stateRoot = TrieRoot(InMemoryWorldStateProxy.persistState(preState).stateRootHash),
      gasLimit = GasAmount(8_000_000),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(baseTimestamp),
      extraFields = baseExtraFields
    )
    blockchainWriter.save(Block(base, BlockBody.empty), Nil, ChainWeight.zero, saveAsBestBlock = true)

    lazy val service = new EthSimulateService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      mining,
      config
    )

    def run(payload: String): Either[JsonRpcError, EthSimulateResponse] =
      for
        req <- EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(
          Some(JArray(List(parse(payload), JString("latest"))))
        )
        res <- service.ethSimulate(req).unsafeRunSync()
      yield res

    def blocks(payload: String): Seq[SimulateBlockResult] =
      run(payload).fold(err => fail(s"simulation failed: ${err.code} ${err.message}"), _.blocks)

    /** The state root block import's preparator gives `header`'s block with `txs` on this node's base state. */
    def preparedStateRoot(header: BlockHeader, txs: Seq[SignedTransaction]): ByteString =
      mining.blockPreparator
        .prepareBlock(storagesInstance.storages.evmCodeStorage, Block(header, BlockBody(txs, Nil)), base, None)
        .stateRootHash

  // ── Requests ────────────────────────────────────────────────────────────────

  /** A block that pays `miner` and has no calls. */
  private def emptyBlock(miner: String): String = s"""{"blockOverrides":{"feeRecipient":"$miner"}}"""

  /** A block that reads the balance of each of `addresses`, one call each, after the blocks before it were paid. */
  private def readerBlock(addresses: String*): String =
    val calls = addresses.map(a => s"""{"from":"$Caller","to":"$BalanceReader","input":"${word(a)}"}""")
    s"""{"blockOverrides":{"feeRecipient":"$MinerC"},"stateOverrides":{"$BalanceReader":{"code":"$BalanceReaderCode"}},
       |"calls":[${calls.mkString(",")}]}""".stripMargin

  private def request(blocks: String*): String = s"""{"blockStateCalls":[${blocks.mkString(",")}]}"""

  /** The sender's 21,000-gas transfer of 1 gwei to the recipient, at `gasPrice` (a legacy transaction). */
  private def transferCall(gasPrice: BigInt): String =
    s"""{"from":"$SenderHex","to":"$Recipient","value":"${hexQuantity(Gwei)}","gas":"0x5208",
       |"gasPrice":"${hexQuantity(gasPrice)}","nonce":"0x0"}""".stripMargin

  /** The same transfer, signed, as a block carries it. */
  private def signedTransfer(gasPrice: BigInt, config: BlockchainConfig): SignedTransaction =
    SignedTransaction.sign(
      LegacyTransaction(
        nonce = 0,
        gasPrice = GasPrice(gasPrice),
        gasLimit = GasAmount(21_000),
        receivingAddress = Address(Recipient),
        value = Gwei,
        payload = ByteString.empty
      ),
      senderKeyPair,
      Some(config.chainId.value)
    )

  private def balances(reader: SimulateBlockResult): Seq[BigInt] =
    reader.calls.map { call =>
      call.error shouldBe None
      BigInt(1, call.returnData.toArray)
    }

  // ── ETC and Mordor: ECIP-1017 eras ─────────────────────────────────────────

  "eth_simulateV1 on Mordor" should "pay ECIP-1017's era reward: 5 ETC at block 2,000,000, 4 ETC at 2,000,001" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node(mordor, baseNumber = 1_999_999, baseDifficulty = PowDifficulty)
    val bs = node.blocks(request(emptyBlock(MinerA), emptyBlock(MinerB), readerBlock(MinerA, MinerB)))
    bs.map(_.header.number.value) shouldBe Seq(BigInt(2_000_000), BigInt(2_000_001), BigInt(2_000_002))
    bs.map(_.header.difficulty.value).distinct shouldBe Seq(PowDifficulty)
    balances(bs(2)) shouldBe Seq(E * 5, E * 4)
  }

  "eth_simulateV1 on ETC" should "pay ECIP-1017's era reward: 5 ETC at block 5,000,000, 4 ETC at 5,000,001" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node(etc, baseNumber = 4_999_999, baseDifficulty = PowDifficulty)
    val bs = node.blocks(request(emptyBlock(MinerA), emptyBlock(MinerB), readerBlock(MinerA, MinerB)))
    bs.map(_.header.difficulty.value).distinct shouldBe Seq(PowDifficulty)
    balances(bs(2)) shouldBe Seq(E * 5, E * 4)
  }

  it should "pay era 4's 2.048 ETC at block 22,000,000" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(etc, baseNumber = 21_999_999, baseDifficulty = PowDifficulty)
    val bs = node.blocks(request(emptyBlock(MinerA), readerBlock(MinerA)))
    balances(bs(1)) shouldBe Seq(BigInt("2048000000000000000"))
  }

  // ── ETC and Mordor at Olympia ───────────────────────────────────────────────

  "eth_simulateV1 at Olympia on Mordor" should "keep the parent's difficulty, pay the era reward and credit the treasury" taggedAs (
    UnitTest,
    RPCTest,
    OlympiaTest
  ) in {
    val node =
      new Node(mordorOlympia, 15_000_000, PowDifficulty, baseExtraFields = HefPostOlympia(Gwei))
    val treasury = "0x" + Hex.toHexString(mordorOlympia.treasuryAddress.bytes.toArray)
    // block 15,000,001 (era 7): base fee 1 gwei, the transfer at 2 gwei, and nothing else, so its gasUsed is 21,000
    val olympiaBlock =
      s"""{"blockOverrides":{"feeRecipient":"$MinerA","baseFeePerGas":"${hexQuantity(Gwei)}"},
         |"calls":[${transferCall(Gwei * 2)}]}""".stripMargin
    // block 15,000,002 (base fee 0, so it credits the treasury nothing): the balances, and what DIFFICULTY reads
    val readBlock =
      s"""{"blockOverrides":{"feeRecipient":"$MinerC"},
         |"stateOverrides":{"$BalanceReader":{"code":"$BalanceReaderCode"},"$DifficultyReader":{"code":"$DifficultyReaderCode"}},
         |"calls":[{"from":"$Caller","to":"$BalanceReader","input":"${word(MinerA)}"},
         |{"from":"$Caller","to":"$BalanceReader","input":"${word(treasury)}"},
         |{"from":"$Caller","to":"$DifficultyReader"}]}""".stripMargin
    val bs = node.blocks(request(olympiaBlock, readBlock))
    val header = bs.head.header
    header.extraFields shouldBe HefPostOlympia(Gwei)
    header.gasUsed.value shouldBe BigInt(21_000)
    bs.map(_.header.difficulty.value).distinct shouldBe Seq(PowDifficulty)
    header.isPoS shouldBe false
    // miner: 5 x 0.8^7 = 1.048576 ETC, plus the 1 gwei tip on 21,000 gas; treasury: 1 gwei base fee x 21,000 gas; and
    // DIFFICULTY reads the proof-of-work difficulty (it read the zero prevRandao while the block had zero difficulty)
    balances(bs(1)) shouldBe Seq(BigInt("1048576000000000000") + Gwei * 21_000, Gwei * 21_000, PowDifficulty)
  }

  "eth_simulateV1 at Olympia on ETC" should "accept a difficulty override: an ETC block is never after the merge" taggedAs (
    UnitTest,
    RPCTest,
    OlympiaTest
  ) in {
    val node = new Node(etcOlympia, 23_000_000, PowDifficulty, baseExtraFields = HefPostOlympia(Gwei))
    val bs = node.blocks(request("""{"blockOverrides":{"difficulty":"0x1234"}}"""))
    bs.head.header.difficulty.value shouldBe BigInt(0x1234)
  }

  // ── The oracle: a simulated block's state root is block import's ───────────

  private def oracle(node: Node, gasPrice: BigInt, config: BlockchainConfig, baseFee: Option[BigInt]): Unit =
    val overrides = baseFee.fold(s""""feeRecipient":"$MinerA"""")(fee =>
      s""""feeRecipient":"$MinerA","baseFeePerGas":"${hexQuantity(fee)}""""
    )
    val bs = node.blocks(request(s"""{"blockOverrides":{$overrides},"calls":[${transferCall(gasPrice)}]}"""))
    val header = bs.head.header
    bs.head.calls.map(_.error) shouldBe Seq(None)
    header.gasUsed.value shouldBe BigInt(21_000)
    // The header block import would see: an ETC block always has a proof-of-work difficulty. With zero (and a base fee),
    // import's own `isPoS` skip would pay nothing too, and agree with a simulation that paid nothing.
    header.difficulty.value shouldBe PowDifficulty
    Hex.toHexString(header.stateRoot.value.toArray) shouldBe
      Hex.toHexString(node.preparedStateRoot(header, Seq(signedTransfer(gasPrice, config))).toArray)

  "A simulated ETC block's state root" should "be block import's on Mordor in era 1 (block 2,000,001)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    oracle(new Node(mordor, 2_000_000, PowDifficulty), Gwei, mordor, None)
  }

  it should "be block import's on Mordor at Olympia (block 15,000,001), treasury credit included" taggedAs (
    UnitTest,
    RPCTest,
    OlympiaTest
  ) in {
    val node = new Node(mordorOlympia, 15_000_000, PowDifficulty, baseExtraFields = HefPostOlympia(Gwei))
    oracle(node, Gwei * 2, mordorOlympia, Some(Gwei))
  }

  it should "be block import's on ETC at Olympia (block 23,000,001, era 4), treasury credit included" taggedAs (
    UnitTest,
    RPCTest,
    OlympiaTest
  ) in {
    val node = new Node(etcOlympia, 23_000_000, PowDifficulty, baseExtraFields = HefPostOlympia(Gwei))
    oracle(node, Gwei * 2, etcOlympia, Some(Gwei))
  }

  // ── ETH: unchanged before London and after the merge; geth's rule in between ─

  "eth_simulateV1 on ETH before London" should "pay 5 ETH, then 3 ETH from Byzantium, as before" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node(eth, baseNumber = 4_369_998, baseDifficulty = PowDifficulty)
    val bs = node.blocks(request(emptyBlock(MinerA), emptyBlock(MinerB), readerBlock(MinerA, MinerB)))
    bs.map(_.header.difficulty.value).distinct shouldBe Seq(PowDifficulty)
    balances(bs(2)) shouldBe Seq(E * 5, E * 3)
  }

  it should "pay 2 ETH from Constantinople, as before" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(eth, baseNumber = 7_279_999, baseDifficulty = PowDifficulty)
    val bs = node.blocks(request(emptyBlock(MinerA), readerBlock(MinerA)))
    balances(bs(1)) shouldBe Seq(E * 2)
  }

  "eth_simulateV1 on ETH after London and before the merge" should "keep the parent's difficulty and pay 2 ETH, as go-ethereum does" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node(eth, 12_965_000, PowDifficulty, baseExtraFields = HefPostOlympia(Gwei))
    val bs = node.blocks(request(emptyBlock(MinerA), readerBlock(MinerA)))
    bs.head.header.difficulty.value shouldBe PowDifficulty
    balances(bs(1)) shouldBe Seq(E * 2)
  }

  "eth_simulateV1 on ETH after the merge" should "give zero difficulty and pay nothing on a post-merge parent, before Shanghai" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node(eth, baseNumber = 15_600_000, baseDifficulty = 0, baseExtraFields = HefPostOlympia(Gwei))
    val bs = node.blocks(request(emptyBlock(MinerA), readerBlock(MinerA)))
    bs.map(_.header.difficulty.value).distinct shouldBe Seq(BigInt(0))
    balances(bs(1)) shouldBe Seq(BigInt(0))
  }

  it should "give zero difficulty and pay nothing from Shanghai" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(
      eth,
      baseNumber = 17_100_000,
      baseDifficulty = 0,
      baseExtraFields = HefPostShanghai(Gwei, EmptyWithdrawalsRoot),
      baseTimestamp = 1_690_000_000L
    )
    val bs = node.blocks(request(emptyBlock(MinerA), readerBlock(MinerA)))
    bs.map(_.header.difficulty.value).distinct shouldBe Seq(BigInt(0))
    balances(bs(1)) shouldBe Seq(BigInt(0))
  }

  it should "still refuse a difficulty override (-32602)" taggedAs (UnitTest, RPCTest) in {
    val node = new Node(eth, baseNumber = 15_600_000, baseDifficulty = 0, baseExtraFields = HefPostOlympia(Gwei))
    node.run(request("""{"blockOverrides":{"difficulty":"0x1"}}""")).left.map(e => (e.code, e.message)) shouldBe
      Left((-32602, "block 15600001: difficulty override is not allowed after the merge"))
  }

  "eth_simulateV1 on Sepolia" should "give the merge netsplit block zero difficulty on a proof-of-work parent" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node =
      new Node(
        sepolia,
        1_735_370,
        PowDifficulty,
        baseExtraFields = HefPostOlympia(Gwei),
        baseTimestamp = 1_600_000_000L
      )
    node.blocks(request(emptyBlock(MinerA))).head.header.difficulty.value shouldBe BigInt(0)
  }
