package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString
import org.apache.pekko.util.Timeout

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*

import com.typesafe.config.ConfigFactory
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.mining.TestMining
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.jsonrpc.EthUserService.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** B-230: a build writes no contract code, so a contract the pending block deploys is in the pending block's world and
  * nowhere else until the block is imported. The methods that run on "pending" run on that world (`ResolvedHeader`'s
  * `pendingState`): eth_getCode, eth_call and eth_estimateGas here; eth_createAccessList, debug_traceCall and
  * trace_call take the same world.
  *
  * The pending block is the real proof-of-work block generator's, on the shipped Mordor config. Its trie nodes are in
  * the build's own buffer, not the store, so a method that reads "pending" through the stores by the pending header
  * finds neither its state nor its code: eth_simulateV1 does (B-458).
  */
// scalastyle:off magic.number
class PendingBlockCodeSpec extends AnyFlatSpec with Matchers:

  implicit val runtime: IORuntime = IORuntime.global

  private val mordor: BlockchainConfig =
    BlockchainConfig.fromRawConfig(ConfigFactory.load().getConfig("fukuii.blockchains.mordor"))

  // hive's well-known funded key, 0xa94f5374fce5edbc8e2a8697c15331677e6ebf0b
  private val senderKeyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(Hex.decode("45a915e4d060149eb4365960e6a7a45f334393093061116b197e3240065ff2d8"))
  private val Sender: Address = Address(senderKeyPair)

  // runtime: return the word 42; the init code returns those 10 bytes
  private val Runtime: ByteString = ByteString(Hex.decode("602a60005260206000f3"))
  private val InitCode: ByteString = ByteString(Hex.decode("69" + "602a60005260206000f3" + "600052600a6016f3"))
  private val Contract: Address = CallErrors.createAddress(Sender, mordor.accountStartNonce.toBigInt)
  private val FortyTwo: ByteString = ByteString(Hex.decode("00" * 31 + "2a"))

  private class Node extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = mordor
    override lazy val validators: ValidatorsExecutor = ValidatorsExecutor(Protocol.MockedPow)
    override lazy val mining: TestMining = buildTestMining().withValidators(validators).withVM(vm)

    private val parentState: ByteString = InMemoryWorldStateProxy
      .persistState(
        InMemoryWorldStateProxy(
          storagesInstance.storages.evmCodeStorage,
          blockchain.getBackingMptStorage(-1),
          (n: BigInt) => blockchainReader.getBlockHeaderByNumber(n).map(_.hash.value),
          mordor.accountStartNonce,
          ByteString(MerklePatriciaTrie.EmptyRootHash),
          noEmptyAccounts = false,
          ethCompatibleStorage = mordor.ethCompatibleStorage
        ).saveAccount(Sender, Account(nonce = mordor.accountStartNonce, balance = UInt256(BigInt(10).pow(19))))
      )
      .stateRootHash

    val parent: Block = Block(
      Fixtures.Blocks.ValidBlock.header.copy(
        number = BlockNumber(14_999_998),
        stateRoot = TrieRoot(parentState),
        difficulty = Difficulty(1_000_000),
        gasLimit = GasAmount(8_000_000),
        gasUsed = GasAmount.Zero,
        extraFields = HefEmpty
      ),
      BlockBody.empty
    )
    blockchainWriter.save(parent, Nil, ChainWeight.totalDifficultyOnly(1_000_000), saveAsBestBlock = true)

    /** The generator builds the pending block, which deploys [[Contract]]; nothing is imported. */
    val deploy: SignedTransaction = SignedTransaction.sign(
      LegacyTransaction(0, GasPrice(2_000_000_000L), GasAmount(100_000), None, 0, InitCode),
      senderKeyPair,
      Some(mordor.chainId.value)
    )
    private val generator = mining.blockGenerator
    generator.generateBlock(parent, Seq(deploy), Address(0xa11ce), generator.emptyX, None)

    lazy val ethUserService =
      new EthUserService(blockchain, blockchainReader, mining, storagesInstance.storages.evmCodeStorage, this)
    // keyStore, sync controller and scheduler are never reached: every request names `from`
    lazy val ethInfoService = new EthInfoService(
      blockchain,
      blockchainReader,
      mordor,
      mining,
      stxLedger,
      null,
      null,
      Capability.ETH68,
      Timeout(5.seconds),
      null
    )

  private val callArgs: TxArgs = TxArgs(from = Some(Sender), to = Some(Contract))

  "A contract the pending block deploys" should "be in the pending block's world only: the code store does not have it" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node
    val pending = node.mining.blockGenerator.getPendingBlockAndState.getOrElse(fail("no pending block"))
    pending.pendingBlock.block.body.transactionList shouldBe Seq(node.deploy)
    pending.worldState.deployedCode.get(kec256(Runtime)) shouldBe Some(Runtime)
    node.storagesInstance.storages.evmCodeStorage.get(kec256(Runtime)) shouldBe None
    node.ethUserService.getCode(GetCodeRequest(Contract, BlockParam.Pending)).unsafeRunSync() shouldBe
      Right(GetCodeResponse(Runtime))
  }

  it should "answer eth_call and eth_estimateGas on pending, and not on latest" taggedAs (UnitTest, RPCTest) in {
    val node = new Node
    node.ethInfoService.call(CallRequest(callArgs, BlockParam.Pending)).unsafeRunSync() shouldBe
      Right(CallResponse(FortyTwo))
    node.ethInfoService.estimateGas(CallRequest(callArgs, BlockParam.Pending)).unsafeRunSync() match
      case Right(EstimateGasResponse(gas)) => gas should be > BigInt(21_000)
      case other                           => fail(s"eth_estimateGas on pending: $other")
    // at the head the contract does not exist: a call to it returns nothing
    node.ethInfoService.call(CallRequest(callArgs, BlockParam.Latest)).unsafeRunSync() shouldBe
      Right(CallResponse(ByteString.empty))
  }
  // B-210: the account readers read "pending" over the stores by the pending header, where the build's nodes are not, and
  // answered the pending block, which is above the best block, with no account: a zero nonce and balance for every
  // address. geth answers from the pending state.
  "The pending block's accounts" should "be read from its world: the sender's nonce and balance after the deploy" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val node = new Node
    def nonce(at: BlockParam) =
      node.ethUserService.getTransactionCount(GetTransactionCountRequest(Sender, at)).unsafeRunSync()
    def balance(address: Address, at: BlockParam) =
      node.ethUserService.getBalance(GetBalanceRequest(address, at)).unsafeRunSync().map(_.value)
    val start = mordor.accountStartNonce.toBigInt
    nonce(BlockParam.Latest) shouldBe Right(GetTransactionCountResponse(start))
    nonce(BlockParam.Pending) shouldBe Right(GetTransactionCountResponse(start + 1))
    balance(Sender, BlockParam.Latest) shouldBe Right(BigInt(10).pow(19))
    balance(Sender, BlockParam.Pending) match
      case Right(paid) => paid should ((be > BigInt(0)).and(be < BigInt(10).pow(19)))
      case other       => fail(s"eth_getBalance on pending: $other")
    // the contract the pending block deploys is an account there, with its code's hash
    val pendingWorld = node.mining.blockGenerator.getPendingBlockAndState.get.worldState
    node.ethUserService
      .getStorageRoot(GetStorageRootRequest(Contract, BlockParam.Pending))
      .unsafeRunSync()
      .map(_.storageRoot) shouldBe Right(pendingWorld.getAccount(Contract).get.storageRoot.value)

    val proofs = new EthProofService(
      node.blockchain,
      node.blockchainReader,
      node.mining.blockGenerator,
      mordor.ethCompatibleStorage
    )
    val proved = proofs
      .getProof(ProofService.GetProofRequest(Sender, Nil, BlockParam.Pending))
      .unsafeRunSync()
      .map(_.proofAccount)
    proved.map(_.nonce.toBigInt) shouldBe Right(start + 1)
    proved.map(_.balance) shouldBe balance(Sender, BlockParam.Pending)
  }
// scalastyle:on magic.number
