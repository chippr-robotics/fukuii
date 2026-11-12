package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersError
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.*
import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.BlockParam
import com.chipprbots.ethereum.jsonrpc.CallErrors
import com.chipprbots.ethereum.jsonrpc.ResolveBlock
import com.chipprbots.ethereum.ledger.BlockExecutionError
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.*

/** Spec 018 Track C, C3c (T103, T105, T111, T112): a proof-of-work block is PREPARED on the unified state layout from
  * its parent's state under a layer of the build's own, to the block the hash store prepares, and import then
  * computes the state root the block carries.
  *
  * Two nodes, one on each layout, are given the same parent, transactions, ommers and clock, block after block. What
  * the generator of each makes must be the same block: the state root, the receipts root and the gas used are in its
  * hash. The blocks cross what this path has to get right on a store of rows: a contract deployed with storage, a
  * slot cleared (a storage trie that collapses), the contract destroyed and its address paid again in the same block
  * (the account's rows are dropped, and the account is there again with none), a transaction the generator leaves
  * out, ommers and their rewards, and the first block of the next ECIP-1017 era.
  *
  * Around each generation on the unified layout the datadir is compared: a block that is prepared writes nothing but
  * contract code, and the world it leaves in the generator's cache is never committed. The block is committed by its
  * import, which executes it again.
  */
class MinedBlockOnPositionsSpec extends AnyFlatSpec with Matchers:

  private type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  private val keyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(BigInt(1, Hex.decode("f3202185c84325302d43887e90a2e23e7bc058d0450bb58ef2f7585765d7d48b")))
  private val sender: Address = Address(keyPair)
  private val chain = BigInt(0x3d)
  private val miner = Address(0xc0ffee)

  /** An ommers validator that takes any ommer: the ommers here carry no seal, and what is held is what they are paid. */
  private object AnyOmmer extends OmmersValidator:
    override def validate(
        parentHash: ByteString,
        blockNumber: BigInt,
        ommers: Seq[BlockHeader],
        getBlockByHash: GetBlockHeaderByHash,
        getNBlocksBack: GetNBlocksBack
    )(implicit blockchainConfig: BlockchainConfig): Either[OmmersError, OmmersValid] = Right(OmmersValid)

  final private class Clock extends BlockTimestampProvider:
    @volatile var now: Long = 0L
    override def getEpochSecond: Long = now

  final private class Node(val source: EphemDataSource, guard: Boolean) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
      forkBlockNumbers = ForkBlockNumbers.Empty.copy(
        homesteadBlockNumber = 1150000,
        eip155BlockNumber = 0,
        difficultyBombPauseBlockNumber = 3000000,
        difficultyBombContinueBlockNumber = 5000000,
        difficultyBombRemovalBlockNumber = 5900000
      ),
      chainId = ChainId(0x3d),
      networkId = 1,
      customGenesisFileOpt = Some("test-genesis.json"),
      customGenesisJsonOpt = None,
      // an era of two blocks: block 3 is the first of era 1
      monetaryPolicyConfig = MonetaryPolicyConfig(2, 0.2, 5000000000000000000L, 3000000000000000000L, 2000000000000000000L),
      maxCodeSize = None,
      accountStartNonce = UInt256.Zero,
      daoForkConfig = None,
      bootstrapNodes = Set(),
      gasTieBreaker = false,
      ethCompatibleStorage = true
    )
    private trait Shared extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new Shared with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = guard

    override lazy val validators: ValidatorsExecutor = new ValidatorsExecutor:
      override def ommersValidator: OmmersValidator = AnyOmmer
      override def blockValidator: BlockValidator = powValidators.blockValidator
      override def blockHeaderValidator: BlockHeaderValidator = powValidators.blockHeaderValidator
      override def signedTransactionValidator: SignedTransactionValidator = powValidators.signedTransactionValidator

    GenesisDataLoader.over(blockchainReader, blockchainWriter, storagesInstance.storages).loadGenesisData()

    val clock = new Clock
    lazy val generator: TestBlockGenerator = mining.blockGenerator.withBlockTimestampProvider(clock)
    lazy val execution = new HeaderTrustingBlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      new BlockValidation(mining, blockchainReader, BlockQueue(blockchainReader, syncConfig))
    )

    def head: Block = blockchainReader.getBestBlock.get
    def prepare(transactions: Seq[SignedTransaction], ommers: Seq[BlockHeader]): Block =
      clock.now = head.header.unixTimestamp.toLong + 15
      // the ommers of a proof-of-work block are headers: what `X` is for the generator this node's mining gives
      generator.generateBlock(head, transactions, miner, ommers.asInstanceOf[generator.X], None).pendingBlock.block
    def importOne(block: Block): Option[BlockExecutionError] =
      val weight = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true)._2

    def allButCode: Map[ByteString, ByteString] = source.storage.collect {
      case (key, value) if key.array().head != Namespaces.CodeNamespace.head =>
        ByteString(key.array()) -> ByteString(value)
    }

  private def signed(tx: LegacyTransaction): SignedTransaction = SignedTransaction.sign(tx, keyPair, Some(chain))
  private def transfer(nonce: Int, to: Address, value: Int): SignedTransaction =
    signed(LegacyTransaction(nonce, GasPrice(1), GasAmount(21000), to, value, ByteString.empty))
  private def call(nonce: Int, to: Address, data: ByteString): SignedTransaction =
    signed(LegacyTransaction(nonce, GasPrice(1), GasAmount(100000), to, 0, data))

  // A contract that is created with three slots. Called with no data it clears slot 0; called with data it destroys
  // itself and pays its caller.
  //   runtime: CALLDATASIZE PUSH1 0a JUMPI | PUSH1 00 PUSH1 00 SSTORE STOP | JUMPDEST CALLER SELFDESTRUCT
  private val runtime = Hex.decode("36600a57600060005500" + "5b33ff")
  //   init: SSTORE(0, 0x11) SSTORE(1, 0x22) SSTORE(2, 0x33); CODECOPY(0, 0x1b, 0x0d); RETURN(0, 0x0d)
  private val init = ByteString(Hex.decode("601160005560226001556033600255" + "600d601b600039" + "600d6000f3") ++ runtime)
  private val deploy: SignedTransaction =
    signed(LegacyTransaction(1, GasPrice(1), GasAmount(300000), None, 0, init))
  private val contract: Address = CallErrors.createAddress(sender, 1)
  private val contractOwner: ByteString = ByteString(kec256(contract.bytes.toArray))

  /** A header beside `of`'s: an ommer, with a miner of its own to be paid. */
  private def ommerOf(of: BlockHeader, salt: Int): BlockHeader =
    of.copy(beneficiary = ByteString(Array.fill[Byte](20)(salt.toByte)), extraData = ByteString(s"ommer $salt"))

  "A block prepared on the unified layout" should "be the block the hash store prepares, write nothing, and import to the root it carries" taggedAs
    (UnitTest, ConsensusTest) in {
      init.size shouldBe 0x1b + runtime.length
      val onHashes = new Node(EphemDataSource(), guard = false)
      val onPositions = new Node(EphemDataSource(), guard = true)
      onPositions.head.header.hash shouldBe onHashes.head.header.hash
      val nodes = new NodeStorage(onHashes.source)
      val pathState = onPositions.storagesInstance.storages.pathStateStorage.get
      def stateOf(header: BlockHeader): Disk =
        val positions = RawTrieWalk.statePositions(
          header.stateRoot.value.toArray,
          hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
        )
        (
          positions.getOrElse(None, Map.empty),
          positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
        )
      def disk: Disk = PathStateTestKit.allRows(new PathNodeStorage(onPositions.source))
      def storageRowsOf(owner: ByteString): Int = disk._2.keys.count(_.startsWith(owner))

      /** One block, on both layouts: prepared, compared, and imported. */
      def step(transactions: Seq[SignedTransaction], ommers: BlockHeader => Seq[BlockHeader], included: Int): Block =
        val parent = onPositions.head.header
        val reference = onHashes.prepare(transactions, ommers(parent))
        val before = onPositions.allButCode
        val anchor = pathState.anchor
        val epoch = pathState.clock.epoch
        val prepared = onPositions.prepare(transactions, ommers(parent))
        withClue(s"block ${prepared.header.number}: ") {
          // the same block: its state root, its receipts root and its gas used are in its hash
          prepared.header.hash shouldBe reference.header.hash
          prepared.body.transactionList should have size included
          prepared.body.uncleNodesList shouldBe ommers(parent)
          // a block that is prepared writes nothing, and its world, in the generator's cache, is not the disk's
          onPositions.allButCode shouldBe before
          pathState.anchor shouldBe anchor
          pathState.clock.epoch shouldBe epoch
          onPositions.generator.getPendingBlockAndState.map(_.pendingBlock.block.header.hash) shouldBe
            Some(prepared.header.hash)
          // import executes it again, to the root it carries, and commits it
          onHashes.importOne(reference) shouldBe None
          onPositions.importOne(prepared) shouldBe None
          pathState.anchor shouldBe
            Some(BlockRef(prepared.header.hash.value, prepared.header.number.value, prepared.header.stateRoot.value))
          disk shouldBe stateOf(prepared.header)
          PathStateTestKit.hashStoreRows(onPositions.source) shouldBe empty
        }
        prepared

      // block 1: a transfer, the contract, and a transaction with a nonce far ahead, which the generator leaves out
      step(Seq(transfer(0, Address(42), 9000), deploy, transfer(9, Address(43), 1)), _ => Nil, included = 2)
      storageRowsOf(contractOwner) should be > 0
      val threeSlots = storageRowsOf(contractOwner)

      // block 2: slot 0 is cleared, so the contract's storage trie loses a leaf; an ommer is paid
      val second = step(Seq(call(2, contract, ByteString.empty)), parent => Seq(ommerOf(parent, 1)), included = 1)
      second.body.uncleNodesList should have size 1
      storageRowsOf(contractOwner) should (be > 0 and be <= threeSlots)

      // block 3, the first of era 1: the contract destroys itself, and its address is paid again in the same block.
      // The account is there, with no storage: none of the old trie's rows may be left under it.
      val third =
        step(Seq(call(3, contract, ByteString(1)), transfer(4, contract, 1)), _ => Nil, included = 2)
      third.header.number.value shouldBe 3
      storageRowsOf(contractOwner) shouldBe 0
      onPositions.blockchainReader.accountAt(third.header, contract).map(_.balance) shouldBe Some(UInt256(1))

      // block 4: no transaction, two ommers
      val fourth = step(Nil, parent => Seq(ommerOf(parent, 2), ommerOf(parent, 3)), included = 0)
      fourth.body.uncleNodesList should have size 2
    }

  // The generator keeps the last blocks it prepared, each with the world its preparation left. On the unified layout
  // that world reads through a view of a state the disk moves away from, and nothing holds the pending block's own
  // state: "pending" is the head there.
  "The pending block" should "be the head on the unified layout, and the generator's last block on the hash store" taggedAs
    UnitTest in {
      Seq(false, true).foreach { guard =>
        withClue(s"guard = $guard: ") {
          val node = new Node(EphemDataSource(), guard)
          val resolver = new ResolveBlock:
            override def blockchain: Blockchain = node.blockchain
            override def blockchainReader: BlockchainReader = node.blockchainReader
            override def mining = node.mining
          // with nothing prepared, pending is the head on both
          resolver.resolveBlock(BlockParam.Pending).map(_.block.header.hash) shouldBe Right(node.head.header.hash)
          given BlockchainConfig = node.blockchainConfig
          val mined = node.mining.blockGenerator
          val pending = mined.generateBlock(node.head, Nil, miner, mined.emptyX, None).pendingBlock.block
          val resolved = resolver.resolveBlock(BlockParam.Pending).toOption.get
          if guard then
            resolved.block.header.hash shouldBe node.head.header.hash
            resolved.pendingState shouldBe None
          else
            resolved.block.header.hash shouldBe pending.header.hash
            resolved.pendingState should not be empty
        }
      }
    }
