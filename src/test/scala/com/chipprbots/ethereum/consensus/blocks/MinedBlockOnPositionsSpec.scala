package com.chipprbots.ethereum.consensus.blocks

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.eip1559.BaseFeeCalculator
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
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
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
import com.chipprbots.ethereum.vm.EvmConfig

/** Spec 018 Track C, C3c (T103, T105, T111, T112): a proof-of-work block is PREPARED on the unified state layout from
  * its parent's state under a layer of the build's own, to the block the hash store prepares, and import then computes
  * the state root the block carries.
  *
  * Two nodes, one on each layout, are given the same parent, transactions, ommers and clock, block after block. What
  * the generator of each makes must be the same block: the state root, the receipts root and the gas used are in its
  * hash. The blocks cross what this path has to get right on a store of rows: a contract deployed with storage, a slot
  * cleared (a storage trie that collapses), the contract destroyed and its address paid again in the same block (the
  * account's rows are dropped, and the account is there again with none), a transaction the generator leaves out,
  * ommers and their rewards, and the first block of the next ECIP-1017 era.
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

  /** An ommers validator that takes any ommer: the ommers here carry no seal, and what is held is what they are paid.
    */
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

  /** The Treasury of the Olympia case (ECIP-1112): where the base fee goes. */
  private val treasury = Address(0x7ea5)

  /** @param olympiaAt
    *   the first block of Olympia (ECIP-1111/1112: a base fee, credited to the Treasury), as Mordor sets it; none
    *   before
    * @param baseFeeFloor
    *   the lowest base fee (ECIP-1111: 1 gwei on ETC and Mordor); 0, as on ETH, by default
    * @param ethCompatibleStorage
    *   how storage slots are keyed and valued in the storage tries: no shipped chain sets it false, a private net may
    * @param etcForks
    *   every ETC fork before Olympia at genesis, as a private ETC-config net sets them (Mordor's names: Atlantis to
    *   Spiral), so that Olympia's EVM is ETC's (`EvmConfig.OlympiaConfigBuilder`) and not London's
    */
  final private class Node(
      val source: EphemDataSource,
      guard: Boolean,
      olympiaAt: Option[BigInt] = None,
      baseFeeFloor: BigInt = 0,
      ethCompatibleStorage: Boolean = true,
      etcForks: Boolean = false
  ) extends EphemBlockchainTestSetup:
    private val olympia = olympiaAt.getOrElse(ForkBlockNumbers.Empty.olympiaBlockNumber)
    implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
      forkBlockNumbers =
        if etcForks then
          ForkBlockNumbers.Empty.copy(
            frontierBlockNumber = 0,
            homesteadBlockNumber = 0,
            eip150BlockNumber = 0,
            eip155BlockNumber = 0,
            eip160BlockNumber = 0,
            difficultyBombPauseBlockNumber = 0,
            difficultyBombContinueBlockNumber = 0,
            difficultyBombRemovalBlockNumber = 0,
            atlantisBlockNumber = 0,
            aghartaBlockNumber = 0,
            phoenixBlockNumber = 0,
            magnetoBlockNumber = 0,
            mystiqueBlockNumber = 0,
            spiralBlockNumber = 0,
            olympiaBlockNumber = olympia
          )
        else
          ForkBlockNumbers.Empty.copy(
            homesteadBlockNumber = 1150000,
            eip155BlockNumber = 0,
            difficultyBombPauseBlockNumber = 3000000,
            difficultyBombContinueBlockNumber = 5000000,
            difficultyBombRemovalBlockNumber = 5900000,
            olympiaBlockNumber = olympia
          )
      ,
      treasuryAddress = treasury,
      chainId = ChainId(0x3d),
      networkId = 1,
      customGenesisFileOpt = Some("test-genesis.json"),
      customGenesisJsonOpt = None,
      // an era of two blocks: block 3 is the first of era 1
      monetaryPolicyConfig =
        MonetaryPolicyConfig(2, 0.2, 5000000000000000000L, 3000000000000000000L, 2000000000000000000L),
      maxCodeSize = None,
      accountStartNonce = UInt256.Zero,
      daoForkConfig = None,
      bootstrapNodes = Set(),
      gasTieBreaker = false,
      ethCompatibleStorage = ethCompatibleStorage,
      baseFeeFloor = baseFeeFloor
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
  private val init = ByteString(
    Hex.decode("601160005560226001556033600255" + "600d601b600039" + "600d6000f3") ++ runtime
  )
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

      /** What `block` paid the miner of each of its ommers, and its own, as each layout holds it. */
      def paid(block: Block): Unit =
        (block.header.beneficiary +: block.body.uncleNodesList.map(_.beneficiary)).foreach { who =>
          val onUnified = onPositions.blockchainReader.accountAt(block.header, Address(who)).map(_.balance)
          withClue(s"block ${block.header.number}, ${Address(who)}: ") {
            onUnified.exists(_ > UInt256.Zero) shouldBe true
            onUnified shouldBe onHashes.blockchainReader.accountAt(block.header, Address(who)).map(_.balance)
          }
        }

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
      paid(second)
      storageRowsOf(contractOwner) should ((be > 0).and(be <= threeSlots))

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
      paid(fourth)
      // era 1 pays an ommer's miner less than era 0 did: the reward that is read is the era's
      val eraZero =
        onPositions.blockchainReader.accountAt(fourth.header, Address(ommerOf(second.header, 1).beneficiary))
      val eraOne = onPositions.blockchainReader.accountAt(fourth.header, Address(ommerOf(third.header, 2).beneficiary))
      eraOne.map(_.balance).exists(one => eraZero.map(_.balance).exists(_ > one)) shouldBe true
    }

  // B-219 (forge, C3c part 2): an Olympia block on the unified layout. The base fee of each transaction is credited to
  // the Treasury (ECIP-1111/1112), not burned, and the miner is paid the tip: a write to an account the block's
  // transactions do not name, which the prepared block and its import must make alike on both layouts.
  "An Olympia block prepared on the unified layout" should "be the block the hash store prepares, and pay the Treasury its base fee" taggedAs
    (UnitTest, ConsensusTest) in {
      val onHashes = new Node(EphemDataSource(), guard = false, olympiaAt = Some(1))
      val onPositions = new Node(EphemDataSource(), guard = true, olympiaAt = Some(1))
      onPositions.head.header.hash shouldBe onHashes.head.header.hash
      def treasuryAt(node: Node, header: BlockHeader): BigInt =
        node.blockchainReader.accountAt(header, treasury).map(_.balance.toBigInt).getOrElse(BigInt(0))

      // a price far above the first base fee, so the generator takes both transactions
      val price = GasPrice(BigInt(100) * BigInt(1000000000))
      def paying(nonce: Int, to: Address): SignedTransaction =
        signed(LegacyTransaction(nonce, price, GasAmount(21000), to, 1, ByteString.empty))

      (1 to 2).foreach { n =>
        val transactions = Seq(paying(2 * (n - 1), Address(42)), paying(2 * (n - 1) + 1, Address(43)))
        // read before the import: on the unified layout the state of a block the head has left is not held
        val before = treasuryAt(onPositions, onPositions.head.header)
        val reference = onHashes.prepare(transactions, Nil)
        val prepared = onPositions.prepare(transactions, Nil)
        withClue(s"block $n: ") {
          prepared.header.hash shouldBe reference.header.hash
          prepared.body.transactionList should have size 2
          val baseFee = prepared.header.baseFee.getOrElse(fail("an Olympia block carries a base fee"))
          baseFee should be > BigInt(0)
          onHashes.importOne(reference) shouldBe None
          onPositions.importOne(prepared) shouldBe None
          treasuryAt(onPositions, prepared.header) - before shouldBe baseFee * prepared.header.gasUsed.value
          treasuryAt(onPositions, prepared.header) shouldBe treasuryAt(onHashes, reference.header)
          PathStateTestKit.hashStoreRows(onPositions.source) shouldBe empty
        }
      }
    }

  // Spec 018 C6, T240 (forge): a private ETC-config net with Olympia at a low block, on the unified layout. Olympia from
  // genesis (the genesis then carries a base fee: `HefPostOlympia`, GenesisDataLoader) and from block 1; the base fee
  // floor of ECIP-1111 (1 gwei), which every block here meets, as each is far below its gas target; the Treasury paid
  // the base fee of every block, an empty one too (nothing); with both settings of `ethCompatibleStorage`, which only a
  // private net sets false. On each, the block the unified layout prepares is the hash store's, its import computes the
  // root it carries, the disk is that root's state row for row, and no record of the chain is a tombstone.
  for
    olympiaAt <- Seq(BigInt(0), BigInt(1))
    ethCompatible <- Seq(true, false)
  do
    s"An Olympia private net (Olympia at block $olympiaAt, eth-compatible storage $ethCompatible)" should
      "prepare, on the unified layout, the blocks the hash store prepares, at the base fee floor, paying the Treasury every block" taggedAs
      (UnitTest, ConsensusTest) in {
        val floor = BigInt(1000000000)
        val onHashes = new Node(EphemDataSource(), false, Some(olympiaAt), floor, ethCompatible, etcForks = true)
        val onPositions = new Node(EphemDataSource(), true, Some(olympiaAt), floor, ethCompatible, etcForks = true)
        // Olympia's EVM is ETC's: every ETC fork before it is in force
        EvmConfig.forBlock(olympiaAt, onPositions.blockchainConfig).eip6780Enabled shouldBe true
        val genesis = onPositions.head.header
        genesis.hash shouldBe onHashes.head.header.hash
        // Olympia from genesis: the genesis header carries the 1 gwei base fee; from block 1: it carries none
        if olympiaAt == 0 then
          genesis.extraFields shouldBe BlockHeader.HeaderExtraFields.HefPostOlympia(floor)
          genesis.baseFee shouldBe Some(floor)
        else
          genesis.extraFields shouldBe BlockHeader.HeaderExtraFields.HefEmpty
          genesis.baseFee shouldBe None

        val nodes = new NodeStorage(onHashes.source)
        val records = new ChangesetStorage(onPositions.source)
        def stateOf(header: BlockHeader): Disk =
          val positions = RawTrieWalk.statePositions(
            header.stateRoot.value.toArray,
            hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
          )
          (
            positions.getOrElse(None, Map.empty),
            positions
              .collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }
              .flatten
              .toMap
          )
        def disk: Disk = PathStateTestKit.allRows(new PathNodeStorage(onPositions.source))
        def storageRowsOf(owner: ByteString): Int = disk._2.keys.count(_.startsWith(owner))
        def treasuryAt(node: Node, header: BlockHeader): BigInt =
          node.blockchainReader.accountAt(header, treasury).map(_.balance.toBigInt).getOrElse(BigInt(0))

        val price = GasPrice(BigInt(100) * floor)
        def tx(nonce: Int, to: Option[Address], gas: Int, data: ByteString, value: Int = 0): SignedTransaction =
          signed(LegacyTransaction(nonce, price, GasAmount(gas), to, value, data))
        val deployed = CallErrors.createAddress(sender, 1)
        val owner = ByteString(kec256(deployed.bytes.toArray))

        var tombstones = 0
        def step(transactions: Seq[SignedTransaction], included: Int): Block =
          val parent = onPositions.head.header
          // read before the import: on the unified layout the state of a block the head has left is not held
          val before = treasuryAt(onPositions, parent)
          val reference = onHashes.prepare(transactions, Nil)
          val prepared = onPositions.prepare(transactions, Nil)
          withClue(s"block ${prepared.header.number}: ") {
            prepared.header.hash shouldBe reference.header.hash
            prepared.body.transactionList should have size included
            // every block is far below its gas target: the base fee falls, and the floor holds it
            prepared.header.baseFee shouldBe Some(floor)
            if parent.number.value >= olympiaAt then
              BaseFeeCalculator.calcBaseFee(
                parent,
                onPositions.blockchainConfig.copy(baseFeeFloor = 0)
              ) should be < floor
            onHashes.importOne(reference) shouldBe None
            onPositions.importOne(prepared) shouldBe None
            // the Treasury is paid the block's base fee, all of it: nothing for a block that used no gas
            treasuryAt(onPositions, prepared.header) - before shouldBe floor * prepared.header.gasUsed.value
            treasuryAt(onPositions, prepared.header) shouldBe treasuryAt(onHashes, reference.header)
            disk shouldBe stateOf(prepared.header)
            PathStateTestKit.hashStoreRows(onPositions.source) shouldBe empty
            val record = records.get(prepared.header.hash.value) match
              case Some(Right(diff)) => diff
              case other             => fail(s"no record of the block: $other")
            record.postStateRoot shouldBe prepared.header.stateRoot.value
            tombstones += (if record.incomplete then 1 else 0)
          }
          info(
            s"block ${prepared.header.number}: root ${prepared.header.stateRoot.toHexString} on both layouts, base fee " +
              s"${prepared.header.baseFee.getOrElse(BigInt(0))}, gas used ${prepared.header.gasUsed.value}"
          )
          prepared

        // block 1: a transfer and the contract with three slots
        step(Seq(tx(0, Some(Address(42)), 21000, ByteString.empty, 9000), tx(1, None, 300000, init)), included = 2)
        storageRowsOf(owner) should be > 0
        // block 2: nothing: the Treasury is paid nothing, and the base fee stays at the floor
        step(Nil, included = 0).header.gasUsed.value shouldBe 0
        // block 3: slot 0 cleared
        step(Seq(tx(2, Some(deployed), 100000, ByteString.empty)), included = 1)
        // block 4: the contract calls SELFDESTRUCT, and its address is paid again. Olympia carries EIP-6780 here
        // (EvmConfig.OlympiaConfigBuilder): a contract not created in the transaction keeps its code and storage, and
        // its balance goes to the caller
        val rowsBefore = storageRowsOf(owner)
        step(Seq(tx(3, Some(deployed), 100000, ByteString(1)), tx(4, Some(deployed), 21000, ByteString.empty, 1)), 2)
        storageRowsOf(owner) shouldBe rowsBefore
        tombstones shouldBe 0
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
