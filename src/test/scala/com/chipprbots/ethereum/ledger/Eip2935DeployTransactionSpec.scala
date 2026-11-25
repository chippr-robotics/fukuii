package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.validators.std.StdSignedTransactionValidator
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.jsonrpc.CallErrors
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryServeWindow
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageAddress
import com.chipprbots.ethereum.ledger.BlockExecution.HistoryStorageCode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.NetworkType

/** B-528: on ETC the EIP-2935 history contract arrives as EIP-2935 specifies, by its deploy transaction (Nick's
  * method), not by the client. ECIP-1121 adopts EIP-2935 unmodified and no other ETC client implements Olympia, so the
  * EIP text is the reference (EIPS/eip-2935.md, "Deployment").
  *
  * The transaction is pre-EIP-155 (v = 27), so it is the same on every chain: this spec checks that it decodes to the
  * EIP's hash and sender, that ETC accepts it at Olympia, and that executed under ETC Olympia rules (PUSH0 from Spiral,
  * EIP-3860 initcode metering) it leaves the canonical code at HISTORY_STORAGE_ADDRESS, which the next block then
  * writes.
  */
class Eip2935DeployTransactionSpec extends AnyFlatSpec with Matchers:

  /** EIP-2935 "Deployment", field for field. */
  private val DeployTx: SignedTransaction = SignedTransaction(
    LegacyTransaction(
      nonce = 0,
      gasPrice = GasPrice(BigInt("e8d4a51000", 16)), // 1000 gwei
      gasLimit = GasAmount(BigInt("3d090", 16)), // 250,000
      receivingAddress = None,
      value = 0,
      payload = ByteStringUtils.string2hash(
        "60538060095f395ff33373fffffffffffffffffffffffffffffffffffffffe14604657602036036042575f35600143038111604257" +
          "611fff81430311604257611fff9006545f5260205ff35b5f5ffd5b5f35611fff60014303065500"
      )
    ),
    ECDSASignature(r = BigInt("539", 16), s = BigInt("aa12693182426612186309f02cfe8a80a0000", 16), v = BigInt(27))
  )
  private val DeployTxHash = ByteStringUtils.string2hash(
    "67139a552b0d3fffc30c0fa7d0c20d42144138c8fe07fc5691f09c1cce632e15"
  )
  private val Deployer = Address("0x3462413Af4609098e1E27A490f554f260213D685")

  private val OlympiaAt: BigInt = 25_000_000
  private val BaseFee: BigInt = BigInt(1_000_000_000)

  /** ETC mainnet as shipped, with a synthetic Olympia block (Olympia is unscheduled on every ETC network). */
  private lazy val etcOlympia: BlockchainConfig =
    Config.blockchains.blockchains("etc").withUpdatedForkBlocks(_.copy(olympiaBlockNumber = OlympiaAt))

  private def header(number: BigInt, parentHash: ByteString): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      parentHash = BlockHash(parentHash),
      gasLimit = GasAmount(8_000_000),
      gasUsed = GasAmount.Zero,
      extraFields = HefPostOlympia(BaseFee)
    )

  /** The signed transaction's RLP, as `eth_sendRawTransaction` takes it (docs/runbooks/olympia-eip2935-deployment.md).
    */
  private val DeployTxRaw = ByteStringUtils.string2hash(
    "f8838085e8d4a510008303d0908080b85c60538060095f395ff33373fffffffffffffffffffffffffffffffffffffffe146046576020" +
      "36036042575f35600143038111604257611fff81430311604257611fff9006545f5260205ff35b5f5ffd5b5f35611fff6001430306" +
      "55001b820539930aa12693182426612186309f02cfe8a80a0000"
  )

  "EIP-2935's deploy transaction" should "decode to the EIP's hash, sender and contract address" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    given BlockchainConfig = etcOlympia
    DeployTxRaw.toArray.toSignedTransaction shouldBe DeployTx
    ByteString(DeployTx.toBytes) shouldBe DeployTxRaw
    DeployTx.hash.value shouldBe DeployTxHash
    DeployTx.isChainSpecific shouldBe false
    SignedTransaction.getSender(DeployTx) shouldBe Some(Deployer)
    CallErrors.createAddress(Deployer, 0) shouldBe HistoryStorageAddress
  }

  it should "be a valid transaction on ETC at Olympia, though it carries no chain id" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    given BlockchainConfig = etcOlympia
    etcOlympia.networkType shouldBe NetworkType.ETC
    val upfront = DeployTx.tx.gasLimit.value * DeployTx.tx.gasPrice.value
    StdSignedTransactionValidator.validate(
      stx = DeployTx,
      senderAccount = Account(nonce = UInt256.Zero, balance = UInt256(upfront)),
      blockHeader = header(OlympiaAt, ByteString(new Array[Byte](32))),
      upfrontGasCost = upfront,
      accumGasUsed = 0
    ) shouldBe a[Right[?, ?]]
  }

  trait TestSetup extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = etcOlympia

    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, SyncConfig(Config.config))

    override lazy val blockValidation = new BlockValidation(
      mining.withValidators(Mocks.MockValidatorsAlwaysSucceed),
      blockchainReader,
      blockQueue
    )

    lazy val exec: BlockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )

    /** The deployer funded for the transaction (0.25 ETC at 1000 gwei), nothing at the history address. */
    val fundedWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = true,
      ethCompatibleStorage = true
    ).saveAccount(Deployer, Account(nonce = UInt256.Zero, balance = UInt256(BigInt(10).pow(18))))

  it should "leave the canonical history contract at HISTORY_STORAGE_ADDRESS, executed under ETC Olympia rules" taggedAs (
    UnitTest,
    ConsensusTest,
    OlympiaTest
  ) in new TestSetup:
    val parentHash: ByteString = ByteString(Array.fill(32)(0xab.toByte))
    val deployBlock: Block = Block(header(OlympiaAt, parentHash), BlockBody(Seq(DeployTx), Nil))
    val result: Either[BlockExecutionError, BlockResult] = exec.executeBlockTransactions(deployBlock, fundedWorld)
    result.isRight shouldBe true
    val world: InMemoryWorldStateProxy = result.toOption.get.worldState
    world.getCode(HistoryStorageAddress) shouldBe HistoryStorageCode
    world.getAccount(HistoryStorageAddress).map(_.nonce) shouldBe Some(UInt256(1))
    world.getAccount(Deployer).map(_.nonce) shouldBe Some(UInt256(1))
    // The preamble ran before the transaction, on an address with no code: this block's parent hash is not stored.
    world.getStorage(HistoryStorageAddress).load((OlympiaAt - 1) % HistoryServeWindow) shouldBe BigInt(0)

    // The next block stores its parent hash in the contract the transaction deployed.
    val nextParent: ByteString = ByteString(Array.fill(32)(0xcd.toByte))
    val next: InMemoryWorldStateProxy = exec
      .executeBlockTransactions(Block(header(OlympiaAt + 1, nextParent), BlockBody(Nil, Nil)), world)
      .toOption
      .get
      .worldState
    next.getStorage(HistoryStorageAddress).load(OlympiaAt % HistoryServeWindow) shouldBe
      UInt256(nextParent).toBigInt
