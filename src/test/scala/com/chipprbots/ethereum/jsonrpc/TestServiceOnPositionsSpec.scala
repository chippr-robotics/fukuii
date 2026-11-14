package com.chipprbots.ethereum.jsonrpc

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.MiningConfigs
import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.Block.*
import com.chipprbots.ethereum.jsonrpc.TestService.*
import com.chipprbots.ethereum.ledger.BlockQueue
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.testmode.SealEngineType
import com.chipprbots.ethereum.testmode.TestModeComponentsProvider
import com.chipprbots.ethereum.testmode.TestModeNode
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.ClearPendingTransactions
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.GetPendingTransactionsReq
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransaction
import com.chipprbots.ethereum.transactions.PendingTransactionsManager.PendingTransactionsResponse
import com.chipprbots.ethereum.utils.*

/** Spec 018 Track C, C3c (T108, T116): test mode (retesteth's `test_*` methods) on the unified state layout, in its
  * linear subset.
  *
  * Two test-mode nodes, one on each layout, are given the same calls. A chain is set, blocks are mined from the same
  * transactions, and what the unified layout holds is held to the hash store: the same block at each height (its state
  * root is in its hash), rows that are exactly the head's state, nothing in the hash store, and the same answers from
  * the two methods that read a state. The blocks cross what a store of rows has to get right: a contract created with
  * storage, a slot first written by a later transaction of a block, a storage trie that loses a leaf, a contract
  * created onto an account that has storage and no code, a contract destroyed and paid again in one block.
  *
  * What the unified layout cannot do before its overlay is held too, as a refusal that leaves the datadir as it was: a
  * branch that outweighs the head, and a rewind to a block between the genesis and the head. And what it does in place
  * of the hash store's overwrite: a second chain, or the first again, is set on a datadir that holds nothing of the
  * chain before it.
  */
class TestServiceOnPositionsSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with Matchers
    with org.scalamock.scalatest.MockFactory:

  private type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  private val keyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(BigInt(1, Hex.decode("f3202185c84325302d43887e90a2e23e7bc058d0450bb58ef2f7585765d7d48b")))
  private val sender: Address = Address(keyPair)
  private val chain = BigInt(0x3d)

  private def signed(tx: LegacyTransaction): SignedTransaction = SignedTransaction.sign(tx, keyPair, Some(chain))
  private def transfer(nonce: Int, to: Address, value: Int): SignedTransaction =
    signed(LegacyTransaction(nonce, GasPrice(1), GasAmount(21000), to, value, ByteString.empty))
  private def call(nonce: Int, to: Address, data: ByteString): SignedTransaction =
    signed(LegacyTransaction(nonce, GasPrice(1), GasAmount(100000), to, 0, data))
  private def create(nonce: Int): SignedTransaction =
    signed(LegacyTransaction(nonce, GasPrice(1), GasAmount(300000), None, 0, init))
  private def word(value: Int): ByteString = UInt256(value).bytes

  // A contract that is created with three slots. Called with no data it clears slot 0; called with data it destroys
  // itself and pays its caller.
  //   runtime: CALLDATASIZE PUSH1 0a JUMPI | PUSH1 00 PUSH1 00 SSTORE STOP | JUMPDEST CALLER SELFDESTRUCT
  private val runtime = Hex.decode("36600a57600060005500" + "5b33ff")
  //   init: SSTORE(0, 0x11) SSTORE(1, 0x22) SSTORE(2, 0x33); CODECOPY(0, 0x1b, 0x0d); RETURN(0, 0x0d)
  private val init = ByteString(
    Hex.decode("601160005560226001556033600255" + "600d601b600039" + "600d6000f3") ++ runtime
  )
  private val contract: Address = CallErrors.createAddress(sender, 1)
  private val contractOwner: ByteString = ByteString(kec256(contract.bytes.toArray))

  // A contract of the genesis that stores the second word of its call data at the first:
  //   PUSH1 20 CALLDATALOAD PUSH1 00 CALLDATALOAD SSTORE STOP
  private val keeper: Address = Address(0xa1)
  private val keeperCode = ByteString(Hex.decode("6020356000355500"))

  // An account of the genesis with storage and no code, at the address the sender's transaction 4 creates a contract
  private val squatted: Address = CallErrors.createAddress(sender, 4)
  private val squattedOwner: ByteString = ByteString(kec256(squatted.bytes.toArray))

  private val funded = GenesisAccount(None, UInt256(BigInt(10).pow(18)), None, None, None)
  private val chainA: Map[ByteString, GenesisAccount] = Map(
    sender.bytes -> funded,
    keeper.bytes -> GenesisAccount(None, UInt256.Zero, Some(keeperCode), None, Some(Map(UInt256(1) -> UInt256(7)))),
    squatted.bytes -> GenesisAccount(None, UInt256.One, None, None, Some(Map(UInt256(5) -> UInt256(9))))
  )
  private val chainB: Map[ByteString, GenesisAccount] = Map(
    sender.bytes -> funded,
    Address(0xb2).bytes ->
      GenesisAccount(None, UInt256(3), Some(keeperCode), None, Some(Map(UInt256(2) -> UInt256(3))))
  )

  private def chainParams(accounts: Map[ByteString, GenesisAccount]): ChainParams = ChainParams(
    genesis = GenesisParams(
      author = Address(0xc0ffee).bytes,
      difficulty = "0x20000",
      extraData = ByteString.empty,
      gasLimit = 8000000,
      parentHash = ByteString(new Array[Byte](32)),
      timestamp = ByteString(0.toByte),
      nonce = ByteString(new Array[Byte](8)),
      mixHash = ByteString(new Array[Byte](32))
    ),
    blockchainParams = BlockchainParams(
      EIP150ForkBlock = Some(0),
      EIP158ForkBlock = Some(0),
      accountStartNonce = 0,
      allowFutureBlocks = true,
      blockReward = 0,
      byzantiumForkBlock = Some(0),
      homesteadForkBlock = Some(0),
      maximumExtraDataSize = 32,
      constantinopleForkBlock = Some(0),
      istanbulForkBlock = Some(0),
      berlinForkBlock = None
    ),
    sealEngine = SealEngineType.NoProof,
    accounts = accounts
  )

  /** A test-mode node in memory, on the layout `guard` says: its storages, its position store when it has one, the
    * services of test mode over them, and a pending-transactions manager that hands out what a test gave it.
    */
  final private class Rig(
      guard: Boolean,
      val source: EphemDataSource = EphemDataSource(),
      inTestMode: Boolean = true
  ) extends EphemBlockchainTestSetup:

    implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
      forkBlockNumbers = ForkBlockNumbers.Empty,
      chainId = ChainId(0x3d),
      networkId = 1,
      customGenesisFileOpt = None,
      customGenesisJsonOpt = None,
      // an era of two blocks: block 3 is the first of era 1
      monetaryPolicyConfig =
        MonetaryPolicyConfig(2, 0.2, 5000000000000000000L, 3000000000000000000L, 2000000000000000000L),
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
        // what a node in test mode is built with (`NodeBuilder`)
        override protected def stateMayBeDestroyed: Boolean = inTestMode

    private val initialConfig = blockchainConfig
    val node: TestModeNode = new TestModeNode:
      override val currentBlockchainConfig: AtomicReference[BlockchainConfig] = new AtomicReference(initialConfig)
      implicit override def blockchainConfig: BlockchainConfig = currentBlockchainConfig.get()
      override val currentSealEngine: AtomicReference[SealEngineType] = new AtomicReference(SealEngineType.NoReward)
      override def sealEngine: SealEngineType = currentSealEngine.get()
      override val blockQueue: BlockQueue = BlockQueue(blockchainReader, syncConfig)
      override val genesisDataLoader: GenesisDataLoader =
        GenesisDataLoader.over(blockchainReader, blockchainWriter, storagesInstance.storages)
      override def pathStateStorage: Option[PathStateStorage] = storagesInstance.storages.pathStateStorage

    /** What the next block is mined from. The manager is told to forget it after each block, by a message that may
      * arrive after a test has set the next: so the stub counts the order and keeps what it was given.
      */
    val pending = new AtomicReference[Seq[SignedTransaction]](Nil)
    private val orders = new AtomicInteger(0)
    private val asked = testKit.createTestProbe[PendingTransactionsResponse]()

    /** How many times the manager was told to forget its transactions. The orders are messages: one more is sent and
      * answered first, so that every order sent before this was asked has been counted.
      */
    def cleared: Int =
      manager ! GetPendingTransactionsReq(asked.ref)
      asked.receiveMessage()
      orders.get()
    private val manager: ActorRef[PendingTransactionsManager.Command] = testKit.spawn(
      Behaviors.receiveMessage[PendingTransactionsManager.Command] {
        case GetPendingTransactionsReq(replyTo) =>
          replyTo ! PendingTransactionsResponse(
            pending.get().map(tx => PendingTransaction(SignedTransactionWithSender(tx, sender), 0L))
          )
          Behaviors.same
        case ClearPendingTransactions =>
          orders.incrementAndGet()
          Behaviors.same
        case _ => Behaviors.same
      }
    )

    private val provider = new TestModeComponentsProvider(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      IORuntime.global,
      MiningConfigs.miningConfig,
      new VMImpl,
      node
    )
    def newService(): TestService = new TestService(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.stateStorage,
      storagesInstance.storages.evmCodeStorage,
      manager,
      MiningConfigs.miningConfig,
      provider,
      storagesInstance.storages.transactionMappingStorage,
      node,
      system.scheduler
    )(using IORuntime.global)
    val service: TestService = newService()

    def run[A](response: ServiceResponse[A]): Either[JsonRpcError, A] = response.unsafeRunSync()(using IORuntime.global)

    def setChain(accounts: Map[ByteString, GenesisAccount]): Either[JsonRpcError, SetChainParamsResponse] =
      val set = run(service.setChainParams(SetChainParamsRequest(chainParams(accounts))))
      run(service.modifyTimestamp(ModifyTimestampRequest(1000))) shouldBe Right(ModifyTimestampResponse())
      set
    def mine(transactions: Seq[SignedTransaction], blocks: Int = 1): Either[JsonRpcError, MineBlocksResponse] =
      pending.set(transactions)
      run(service.mineBlocks(MineBlocksRequest(blocks)))
    def importRaw(block: Block): Either[JsonRpcError, ImportRawBlockResponse] =
      run(service.importRawBlock(ImportRawBlockRequest("0x" + Hex.toHexString(block.toBytes))))
    def rewind(to: Long): Either[JsonRpcError, RewindToBlockResponse] =
      run(service.rewindToBlock(RewindToBlockRequest(to)))

    def head: Block = blockchainReader.getBestBlock.get
    def pathState: PathStateStorage = storagesInstance.storages.pathStateStorage.get
    def anchor: Option[BlockRef] = pathState.anchor
    def refOf(header: BlockHeader): BlockRef = BlockRef(header.hash.value, header.number.value, header.stateRoot.value)
    def rows: Disk = PathStateTestKit.allRows(new PathNodeStorage(source))
    def storageRowsOf(owner: ByteString): Int = rows._2.keys.count(_.startsWith(owner))
    def everything: Map[ByteString, ByteString] =
      source.storage.map((key, value) => ByteString(key.array()) -> ByteString(value))

    /** The changeset record of `block` taken away, as a datadir that lost it would be: what a move back along it is
      * refused for. What was there is returned, to be put back with [[putRecordBack]].
      */
    def loseRecordOf(block: Block): Array[Byte] =
      val key = ChangesetStorage.recordKey(block.header.hash.value)
      val kept = source.getOptimized(Namespaces.StateChangesetNamespace, key).get
      source.update(Seq(DataSourceUpdateOptimized(Namespaces.StateChangesetNamespace, Seq(key), Nil)))
      kept
    def putRecordBack(block: Block, record: Array[Byte]): Unit =
      val key = ChangesetStorage.recordKey(block.header.hash.value)
      source.update(Seq(DataSourceUpdateOptimized(Namespaces.StateChangesetNamespace, Nil, Seq(key -> record))))

    /** The datadir but for contract code, which is kept by its hash and is never taken away. */
    def allButCode: Map[ByteString, ByteString] =
      everything.filter((key, _) => key.head != Namespaces.CodeNamespace.head)

    def storageAt(address: Address, block: BigInt): Either[JsonRpcError, StorageRangeResponse] =
      run(service.storageRangeAt(StorageRangeRequest(StorageRangeParams(Left(block), 0, address.bytes, 0, 100))))
    def storageAt(address: Address): StorageRangeResponse = storageAt(address, head.header.number.value).toOption.get
    def accountsAt(block: BigInt): Either[JsonRpcError, AccountsInRangeResponse] =
      run(
        service.getAccountsInRange(
          AccountsInRangeRequest(AccountsInRangeRequestParams(Left(block), 0, ByteString(new Array[Byte](32)), 100))
        )
      )
    def accountsAtHead: AccountsInRangeResponse = accountsAt(head.header.number.value).toOption.get

  /** A datadir whose writes fail when told to: `syncedWritesBeforeFailure` more synced writes are taken, the next one
    * throws, and the count is then off (-1); and the next write that is not synced throws while `failingUnsynced`. An
    * in-memory source and not a wrapper of one: the storages ask a source whether it is the in-memory one, to know the
    * shape of its keys.
    */
  final private class Faulty extends EphemDataSource(Map.empty):
    import com.chipprbots.ethereum.db.dataSource.DataUpdate
    @volatile var syncedWritesBeforeFailure: Int = -1
    @volatile var failingUnsynced = false
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      if failingUnsynced then throw new java.io.IOException("the disk is full")
      super.update(dataSourceUpdates)
    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      val left = syncedWritesBeforeFailure
      if left >= 0 then syncedWritesBeforeFailure = left - 1
      if left == 0 then throw new java.io.IOException("the disk is full")
      super.update(dataSourceUpdates)

  /** The state of `header` as the hash store of `reference` holds it, position by position. */
  private def stateOf(reference: Rig, header: BlockHeader): Disk =
    val nodes = new NodeStorage(reference.source)
    val positions = RawTrieWalk.statePositions(
      header.stateRoot.value.toArray,
      hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
    )
    (
      positions.getOrElse(None, Map.empty),
      positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
    )

  /** The two nodes, on the same chain. */
  private class Pair(accounts: Map[ByteString, GenesisAccount]):
    val onHashes = new Rig(guard = false)
    val onPositions = new Rig(guard = true)
    Seq(onHashes, onPositions).foreach(_.setChain(accounts) shouldBe Right(SetChainParamsResponse()))
    held()

    /** The unified layout holds the head the hash store has, with its state as rows and nothing in the hash store. */
    def held(): Unit =
      val head = onPositions.head.header
      withClue(s"block ${head.number}: ") {
        head.hash shouldBe onHashes.head.header.hash
        onPositions.anchor shouldBe Some(onPositions.refOf(head))
        onPositions.rows shouldBe stateOf(onHashes, head)
        PathStateTestKit.hashStoreRows(onPositions.source) shouldBe empty
      }

    def step(transactions: Seq[SignedTransaction], blocks: Int = 1): Block =
      val from = onPositions.head.header.number.value
      Seq(onHashes, onPositions).foreach(_.mine(transactions, blocks) shouldBe Right(MineBlocksResponse()))
      onHashes.head.header.number.value shouldBe from + blocks
      onPositions.head.header.number.value shouldBe from + blocks
      onPositions.head.body.transactionList should have size (if blocks == 1 then transactions.size else 0)
      held()
      onPositions.head

    /** Both nodes give the same storage of `address` at the head; it is returned. */
    def storageAt(address: Address): StorageRangeResponse =
      val onUnified = onPositions.storageAt(address)
      onUnified shouldBe onHashes.storageAt(address)
      onUnified

  private def keysOf(storage: StorageRangeResponse): Set[String] = storage.storage.values.map(_.key).toSet
  private def slot(key: Int): String = UInt256(key).toHexString

  "Test mode on the unified layout" should "mine the chain the hash store mines, and hold each head as its rows" taggedAs
    (UnitTest, ConsensusTest) in {
      val pair = new Pair(chainA)
      import pair.*
      onPositions.storageRowsOf(squattedOwner) should be > 0

      // block 1: a transfer, then the contract and a call that writes a slot the keeper never had. The last two are
      // not the first transaction of their block: what they write is known to the storage range all the same.
      step(Seq(transfer(0, Address(42), 9000), create(1), call(2, keeper, word(0xaa) ++ word(0xbb))))
      onPositions.storageRowsOf(contractOwner) should be > 0
      val threeSlots = onPositions.storageRowsOf(contractOwner)
      keysOf(storageAt(keeper)) shouldBe Set(slot(1), slot(0xaa))
      keysOf(storageAt(contract)) shouldBe Set(slot(0), slot(1), slot(2))

      // block 2: slot 0 is cleared, so the contract's storage trie loses a leaf; and a contract is created onto the
      // account that has storage and no code, which takes the storage that was there away
      step(Seq(call(3, contract, ByteString.empty), create(4)))
      onPositions.storageRowsOf(contractOwner) should ((be > 0).and(be <= threeSlots))
      keysOf(storageAt(contract)) shouldBe Set(slot(1), slot(2))
      val created = onPositions.blockchainReader.accountAt(onPositions.head.header, squatted).get
      created.codeHash.value shouldBe ByteString(kec256(runtime))
      keysOf(storageAt(squatted)) shouldBe Set(slot(0), slot(1), slot(2))

      // block 3, the first of era 1: the contract destroys itself, and its address is paid again in the same block.
      // The account is there, with no storage: none of the old trie's rows may be left under it.
      val third = step(Seq(call(5, contract, ByteString(1)), transfer(6, contract, 1)))
      third.header.number.value shouldBe 3
      onPositions.storageRowsOf(contractOwner) shouldBe 0
      onPositions.blockchainReader.accountAt(third.header, contract).map(_.balance) shouldBe Some(UInt256.One)
      storageAt(contract).storage shouldBe empty

      // two blocks in one call, with no transaction: each is the head before the next is made
      step(Nil, blocks = 2).header.number.value shouldBe 5

      val accounts = onPositions.accountsAtHead
      accounts shouldBe onHashes.accountsAtHead
      accounts.addressMap.values.toSet should contain.allOf(sender.bytes, keeper.bytes, squatted.bytes)

      // The state of a block below the head: the hash store holds it, and answers. The unified layout holds the
      // head's alone, and says so; the answer is an error of the method, and no fault thrown at its caller.
      onHashes.storageAt(contract, 2).map(keysOf) shouldBe Right(Set(slot(1), slot(2)))
      onHashes.accountsAt(2).map(_.addressMap.values.toSet.contains(contract.bytes)) shouldBe Right(false)
      onPositions.storageAt(contract, 2).left.map(_.message).left.getOrElse("") should include(
        TestService.HeadStateOnly
      )
      onPositions.accountsAt(2).left.map(_.message).left.getOrElse("") should include(TestService.HeadStateOnly)
      // a block neither has is no error on either
      Seq(onHashes, onPositions).foreach(
        _.storageAt(contract, 99) shouldBe Right(StorageRangeResponse(false, Map.empty, None))
      )
    }

  it should "set a second chain on a datadir that holds nothing of the first" taggedAs UnitTest in {
    val pair = new Pair(chainA)
    import pair.*
    step(Seq(transfer(0, Address(42), 9000), create(1), call(2, keeper, word(0xaa) ++ word(0xbb))))
    step(Seq(call(3, contract, ByteString.empty)))
    val firstGenesis = onPositions.blockchainReader.genesisHeader.hash
    val cleared = onPositions.cleared

    Seq(onHashes, onPositions).foreach(_.setChain(chainB) shouldBe Right(SetChainParamsResponse()))
    onPositions.head.header.number.value shouldBe 0
    onPositions.head.header.hash should not be firstGenesis
    held()

    // the datadir is the one a node that was only ever given the second chain has: no row, no changeset record, no
    // block, receipt, weight or number of the first
    val fresh = new Rig(guard = true)
    fresh.setChain(chainB) shouldBe Right(SetChainParamsResponse())
    onPositions.allButCode shouldBe fresh.allButCode
    onPositions.pathState.clock.atRest should not be empty
    onPositions.cleared shouldBe cleared

    // and the chain goes on from it, as it does on the hash store
    step(Seq(transfer(0, Address(42), 1), call(1, Address(0xb2), word(4) ++ word(5))))
    keysOf(storageAt(Address(0xb2))) shouldBe Set(slot(2), slot(4))
  }

  it should "refuse a chain whose genesis it cannot commit, with the datadir as it was" taggedAs UnitTest in {
    val rig = new Rig(guard = true)
    rig.setChain(chainA) shouldBe Right(SetChainParamsResponse())
    rig.mine(Seq(transfer(0, Address(42), 9000))) shouldBe Right(MineBlocksResponse())
    val before = rig.everything
    val head = rig.head.header.hash

    // one account, written two ways: the whole address, and the address without the zeros it begins with
    val twice = chainA + (ByteString(0xa1.toByte) -> funded)
    val refused = rig.run(rig.service.setChainParams(SetChainParamsRequest(chainParams(twice))))
    refused.left.map(_.message).left.getOrElse("") should include("more than once")
    rig.everything shouldBe before
    rig.head.header.hash shouldBe head
    rig.anchor shouldBe Some(rig.refOf(rig.head.header))

    // nothing of the service changed either: the chain goes on under the rules it was set with, and a rewind to the
    // genesis sets the chain that was set
    rig.mine(Seq(transfer(1, Address(43), 1))) shouldBe Right(MineBlocksResponse())
    rig.head.header.number.value shouldBe 2
    rig.rewind(0) shouldBe Right(RewindToBlockResponse())
    val fresh = new Rig(guard = true)
    fresh.setChain(chainA) shouldBe Right(SetChainParamsResponse())
    rig.allButCode shouldBe fresh.allButCode
  }

  it should "refuse a chain when it would not destroy the state there is, before a block is taken away" taggedAs
    UnitTest in {
      val first = new Rig(guard = true)
      first.setChain(chainA) shouldBe Right(SetChainParamsResponse())
      first.mine(Seq(transfer(0, Address(42), 9000))) shouldBe Right(MineBlocksResponse())

      // the same datadir under a store that was not built for test mode
      val rig = new Rig(guard = true, first.source, inTestMode = false)
      val before = rig.everything
      val refused = rig.run(rig.service.setChainParams(SetChainParamsRequest(chainParams(chainB))))
      refused.left.map(_.message).left.getOrElse("") should include("only in test mode")
      rig.everything shouldBe before
      rig.head.header.hash shouldBe first.head.header.hash
      rig.anchor shouldBe Some(rig.refOf(rig.head.header))
    }

  it should "answer a write that fails with an error, and take the chain when it is set again" taggedAs
    UnitTest in {
      val fresh = new Rig(guard = true)
      fresh.setChain(chainB) shouldBe Right(SetChainParamsResponse())

      // The state of the chain before is destroyed in one synced write, and the genesis is committed in the next.
      // Either may fail: the answer is an error of the method, and the chain is set by asking again.
      Seq(0, 1).foreach { syncedWrites =>
        withClue(s"the synced write after $syncedWrites: ") {
          val faulty = new Faulty
          val rig = new Rig(guard = true, faulty)
          rig.setChain(chainA) shouldBe Right(SetChainParamsResponse())
          rig.mine(Seq(transfer(0, Address(42), 9000))) shouldBe Right(MineBlocksResponse())
          val held = rig.anchor

          faulty.syncedWritesBeforeFailure = syncedWrites
          val failed = rig.run(rig.service.setChainParams(SetChainParamsRequest(chainParams(chainB))))
          failed.left.map(_.message).left.getOrElse("") should include(TestService.ChainNotSet)
          failed.left.map(_.message).left.getOrElse("") should include("the disk is full")
          faulty.syncedWritesBeforeFailure shouldBe -1
          // the blocks of the first are gone; its state is there when its destruction failed, and gone when the
          // genesis commit did; nothing of the second is there
          rig.blockchainReader.getBestBlock shouldBe None
          if syncedWrites == 0 then rig.anchor shouldBe held
          else
            rig.anchor shouldBe None
            rig.rows shouldBe (Map.empty, Map.empty)
          // no chain is set: a rewind to the genesis has none to set, and takes nothing away
          val after = rig.everything
          rig.rewind(0) shouldBe Right(RewindToBlockResponse())
          rig.everything shouldBe after

          rig.setChain(chainB) shouldBe Right(SetChainParamsResponse())
          rig.allButCode shouldBe fresh.allButCode
        }
      }

      // The first write of all, the removal of the head: the chain before is whole on disk, and it is no longer the
      // chain this service was given, whose rules it has dropped. So there is none to rewind to, and it says so.
      val faulty = new Faulty
      val rig = new Rig(guard = true, faulty)
      rig.setChain(chainA) shouldBe Right(SetChainParamsResponse())
      rig.mine(Seq(transfer(0, Address(42), 9000))) shouldBe Right(MineBlocksResponse())
      val before = rig.everything
      faulty.failingUnsynced = true
      val failed = rig.run(rig.service.setChainParams(SetChainParamsRequest(chainParams(chainB))))
      faulty.failingUnsynced = false
      failed.left.map(_.message).left.getOrElse("") should include(TestService.ChainNotSet)
      rig.everything shouldBe before
      rig.rewind(0).left.map(_.message).left.getOrElse("") should include("test_setChainParams")
      rig.everything shouldBe before
      rig.setChain(chainB) shouldBe Right(SetChainParamsResponse())
      rig.allButCode shouldBe fresh.allButCode
    }

  it should "say so when a block it mined was not made the head, or its head's state is not the one held" taggedAs
    UnitTest in {
      // the block the node is about to mine, already in its queue: import calls it a duplicate and imports nothing
      val twin = new Rig(guard = true)
      twin.setChain(chainA) shouldBe Right(SetChainParamsResponse())
      twin.mine(Seq(transfer(0, Address(42), 9000))) shouldBe Right(MineBlocksResponse())

      Seq(false, true).foreach { guard =>
        withClue(s"guard = $guard: ") {
          val rig = new Rig(guard)
          rig.setChain(chainA) shouldBe Right(SetChainParamsResponse())
          rig.node.blockQueue.enqueueBlock(twin.head, 0) should not be empty
          val before = rig.everything
          val mined = rig.mine(Seq(transfer(0, Address(42), 9000)))
          // the hash store's answer is the one it always gave
          if guard then mined.left.map(_.message).left.getOrElse("") should include("was not imported as the head")
          else mined shouldBe Right(MineBlocksResponse())
          rig.head.header.number.value shouldBe 0
          rig.everything shouldBe before
        }
      }

      // a head whose state is not the one the disk holds
      val rig = new Rig(guard = true)
      rig.setChain(chainA) shouldBe Right(SetChainParamsResponse())
      rig.mine(Seq(transfer(0, Address(42), 9000))) shouldBe Right(MineBlocksResponse())
      // a block can no longer be taken away on this layout: that moves the best block without the state
      an[IllegalStateException] should be thrownBy rig.blockchain.removeBlock(rig.head.header.hash)
      rig.head.header.number.value shouldBe 1
      // a writer that moves the best block alone all the same (a defect the guards of the head's writers are there to
      // stop)
      val appState = rig.storagesInstance.storages.appStateStorage
      appState
        .putBestBlockInfo(
          com.chipprbots.ethereum.domain.appstate.BlockInfo(rig.blockchainReader.genesisHeader.hash.value, 0)
        )
        .commit()
      rig.head.header.number.value shouldBe 0
      val before = rig.everything
      val mined = rig.mine(Seq(transfer(1, Address(43), 1)))
      mined.left.map(_.message).left.getOrElse("") should include(TestService.HeadStateOnly)
      rig.everything shouldBe before
    }

  it should "keep a side block, and refuse the branch that outweighs the head with the datadir as it was" taggedAs
    UnitTest in {
      // a branch of two blocks beside the chain, mined by a node that pays another miner
      val other = new Rig(guard = false)
      other.setChain(chainA) shouldBe Right(SetChainParamsResponse())
      other.run(other.service.setEtherbase(SetEtherbaseRequest(Address(0xdead)))) shouldBe Right(SetEtherbaseResponse())
      // A block mined in test mode carries no seal, and a block that is handed over as bytes has one: a mix hash and a
      // nonce of their lengths. Nothing in test mode checks either, and the second block is built on the sealed first.
      def sealedOn(parent: Option[Block], block: Block): Block =
        block.copy(header =
          block.header.copy(
            parentHash = parent.fold(block.header.parentHash)(_.header.hash),
            mixHash = BlockHash(ByteString(new Array[Byte](32))),
            nonce = ByteString(new Array[Byte](8))
          )
        )
      other.mine(Nil) shouldBe Right(MineBlocksResponse())
      val sideOne = sealedOn(None, other.head)
      other.mine(Nil) shouldBe Right(MineBlocksResponse())
      val sideTwo = sealedOn(Some(sideOne), other.head)
      sideTwo.header.number.value shouldBe 2
      def hex(block: Block): String = "0x" + ByteStringUtils.hash2string(block.header.hash.value)

      val pair = new Pair(chainA)
      import pair.*
      val own = step(Seq(transfer(0, Address(42), 9000)))
      sideOne.header.hash should not be own.header.hash
      val before = onPositions.everything

      // a block beside the head, no heavier than it: kept in the queue, on both layouts, and the head stays
      Seq(onHashes, onPositions).foreach(_.importRaw(sideOne) shouldBe Right(ImportRawBlockResponse(hex(sideOne))))
      onHashes.head.header.hash shouldBe own.header.hash
      onPositions.head.header.hash shouldBe own.header.hash
      onPositions.everything shouldBe before

      // its child makes the branch the heavier one. The hash store reorganises to it. The unified layout holds the
      // state of the head alone: it says so, and nothing is written.
      onHashes.importRaw(sideTwo) shouldBe Right(ImportRawBlockResponse(hex(sideTwo)))
      onHashes.head.header.hash shouldBe sideTwo.header.hash
      val refused = onPositions.importRaw(sideTwo)
      refused.left.map(_.message).left.getOrElse("") should include(TestService.HeadStateOnly)
      onPositions.everything shouldBe before
      onPositions.head.header.hash shouldBe own.header.hash
      onPositions.anchor shouldBe Some(onPositions.refOf(own.header))
      onPositions.pathState.clock.atRest should not be empty

      // and the chain goes on from its head
      onPositions.mine(Seq(transfer(1, Address(43), 1))) shouldBe Right(MineBlocksResponse())
      onPositions.head.header.parentHash shouldBe own.header.hash
      onPositions.anchor shouldBe Some(onPositions.refOf(onPositions.head.header))
    }

  it should "rewind to its head, to a block of its chain by moving its head back, and to its genesis" taggedAs
    UnitTest in {
      val pair = new Pair(chainA)
      import pair.*
      val first = Seq(transfer(0, Address(42), 9000), create(1), call(2, keeper, word(0xaa) ++ word(0xbb)))
      val firstBlock = step(first)
      val secondBlock = step(Seq(call(3, contract, ByteString.empty)))
      val before = onPositions.everything
      val cleared = onPositions.cleared

      // the head, and a block above it: no block to take away; the pending transactions go, as on the hash store
      Seq(2L, 7L).foreach(to => onPositions.rewind(to) shouldBe Right(RewindToBlockResponse()))
      onPositions.everything shouldBe before
      onPositions.cleared shouldBe cleared + 2

      // a block between the genesis and the head, with the record of the block above it lost: the head cannot move back
      // along it. Refused before anything is written, and before the pending transactions are taken away.
      val record = onPositions.loseRecordOf(secondBlock)
      val damaged = onPositions.everything
      val refused = onPositions.rewind(1)
      refused.left.map(_.message).left.getOrElse("") should startWith(TestService.RewindRefused)
      onPositions.everything shouldBe damaged
      onPositions.cleared shouldBe cleared + 2
      onPositions.putRecordBack(secondBlock, record)
      onPositions.everything shouldBe before

      // with it: the head and the state move back to the block, as the hash store's head does by removing the block above
      Seq(onHashes, onPositions).foreach(_.rewind(1) shouldBe Right(RewindToBlockResponse()))
      held()
      onPositions.head.header.hash shouldBe firstBlock.header.hash
      onPositions.blockchainReader.getBlockHeaderByNumber(2) shouldBe None
      onPositions.cleared shouldBe cleared + 3
      // the block it left is known by its hash still, with its record: the way back up
      onPositions.blockchainReader.getBlockByHash(secondBlock.header.hash) shouldBe Some(secondBlock)
      new ChangesetStorage(onPositions.source).get(secondBlock.header.hash.value).map(_.isRight) shouldBe Some(true)

      // and mined again on it, at its time, it is executed again to the block it was, on both: on the unified layout a
      // block that is known by its hash and has a record is committed in place as any other is
      Seq(onHashes, onPositions).foreach { rig =>
        rig.run(rig.service.modifyTimestamp(ModifyTimestampRequest(secondBlock.header.unixTimestamp.toLong))) shouldBe
          Right(ModifyTimestampResponse())
      }
      step(secondBlock.body.transactionList.toSeq)
      onPositions.head.header.hash shouldBe secondBlock.header.hash
      onPositions.blockchainReader.getBlockHeaderByNumber(2).map(_.hash) shouldBe Some(secondBlock.header.hash)
      val again = onPositions.everything

      // a service that was never given the chain (the node was started again) has no genesis to set
      val restarted = onPositions.newService()
      onPositions.run(restarted.rewindToBlock(RewindToBlockRequest(0))).left.map(_.message).left.getOrElse("") should
        include("test_setChainParams")
      onPositions.everything shouldBe again
      onPositions.cleared shouldBe cleared + 4

      // the genesis: the chain's genesis is set again, on a datadir that holds nothing of the blocks above it
      onPositions.rewind(0) shouldBe Right(RewindToBlockResponse())
      onPositions.head.header.number.value shouldBe 0
      val fresh = new Rig(guard = true)
      fresh.setChain(chainA) shouldBe Right(SetChainParamsResponse())
      onPositions.head.header.hash shouldBe fresh.head.header.hash
      onPositions.allButCode shouldBe fresh.allButCode
      onPositions.anchor shouldBe Some(onPositions.refOf(onPositions.head.header))
      onPositions.cleared shouldBe cleared + 5

      // and the first block is mined again, to the block it was
      onPositions.run(onPositions.service.modifyTimestamp(ModifyTimestampRequest(1000))) shouldBe
        Right(ModifyTimestampResponse())
      onPositions.mine(first) shouldBe Right(MineBlocksResponse())
      onPositions.head.header.hash shouldBe firstBlock.header.hash
      onPositions.rows shouldBe stateOf(onHashes, firstBlock.header)

      // the hash store holds the state of every block: it rewinds to any
      onHashes.rewind(1) shouldBe Right(RewindToBlockResponse())
      onHashes.head.header.hash shouldBe firstBlock.header.hash
    }

  // C4d part 0 (beacon): a state that failed closed takes no write, a new genesis included, until test mode destroys it;
  // the fault belonged to the state that is gone.
  it should "set a new genesis over a state that failed closed, and go on from it" taggedAs UnitTest in {
    val rig = new Rig(guard = true)
    rig.setChain(chainA) shouldBe Right(SetChainParamsResponse())
    rig.mine(Seq(transfer(0, Address(42), 9000))) shouldBe Right(MineBlocksResponse())
    // a finalized block named on the state that goes (C4d part 1)
    val finalized = com.chipprbots.ethereum.db.storage.pathstate.FinalizedBlock(1, rig.head.header.hash.value)
    rig.blockchainWriter.moveHeadInPlace(rig.head.header, rig.blockchainReader, finalized = Some(finalized))
    rig.pathState.finalizedBlock shouldBe Some(finalized)
    rig.pathState.failClosed("a test's corrupt record")
    rig.mine(Seq(transfer(1, Address(43), 1))).isLeft shouldBe true
    rig.setChain(chainA) shouldBe Right(SetChainParamsResponse())
    rig.pathState.hasFailedClosed shouldBe false
    rig.pathState.finalizedBlock shouldBe None
    rig.storagesInstance.storages.appStateStorage.getStateFinalized() shouldBe None
    rig.head.header.number.value shouldBe 0
    rig.mine(Seq(transfer(0, Address(42), 9000))) shouldBe Right(MineBlocksResponse())
    rig.anchor shouldBe Some(rig.refOf(rig.head.header))
  }

  // What a refusal is to the harness that drives these methods: the error of its request, through the controller that
  // every transport hands a request to. Not a fault thrown past it, and not the answer an invalid block gets.
  "The controller" should "answer what test mode refuses on the unified layout as the error of the request" taggedAs
    (UnitTest, RPCTest) in {
      import org.apache.pekko.actor.typed.scaladsl.adapter.*
      import org.json4s.JsonAST.JBool
      import org.json4s.JsonAST.JInt
      given org.apache.pekko.actor.ActorSystem = system.toClassic
      given org.scalamock.scalatest.MockFactory = this
      given org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit = testKit
      val fixture = new JsonRpcControllerFixture
      val rig = new Rig(guard = true)
      rig.setChain(chainA) shouldBe Right(SetChainParamsResponse())
      rig.mine(Seq(transfer(0, Address(42), 9000))) shouldBe Right(MineBlocksResponse())
      rig.mine(Seq(transfer(1, Address(43), 1))) shouldBe Right(MineBlocksResponse())
      val controller = fixture.jsonRpcController.copy(testServiceOpt = Some(rig.service)).forApis(Seq("test"))
      def rewind(to: Int): JsonRpcResponse =
        controller
          .handleRequest(fixture.newJsonRpcRequest("test_rewindToBlock", JInt(to) :: Nil))
          .unsafeRunSync()(using IORuntime.global)
      // a datadir that lost the record of the head: the head cannot move back along it
      rig.loseRecordOf(rig.head)
      val before = rig.everything

      val refused = rewind(1)
      refused.error.map(_.message).getOrElse("") should startWith(TestService.RewindRefused)
      refused.error.map(_.code) shouldBe Some(-32000)
      refused.result shouldBe None
      rig.everything shouldBe before

      // and what it does is answered as it is on the hash store
      val taken = rewind(2)
      taken.error shouldBe None
      taken.result shouldBe Some(JBool(true))
      rig.everything shouldBe before
    }
