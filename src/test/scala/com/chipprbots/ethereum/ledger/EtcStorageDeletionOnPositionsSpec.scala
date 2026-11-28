package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.blocks.BlockTimestampProvider
import com.chipprbots.ethereum.consensus.blocks.TestBlockGenerator
import com.chipprbots.ethereum.crypto.*
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.CallErrors
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.*

/** Spec 018 Track C, C6 (T239): what deletes storage on ETC, executed under each ETC fork's rules on the hash store and
  * on the unified state layout, to the same state.
  *
  *   - '''SELFDESTRUCT.''' Before Olympia no ETC fork has EIP-6780: a contract that destroys itself is deleted with all
  *     its storage, whenever it was created. From Olympia (ECIP-1112) EIP-6780 applies, as on ETH from Cancun: it is
  *     deleted only when it was created in the same transaction; otherwise only its balance moves.
  *   - '''EIP-161 touch-deletion''', which ETC takes at Atlantis (ECIP-1054): an account that is empty (no code, no
  *     nonce, no balance) and is touched is deleted, with the storage an empty account can still hold when it was
  *     created before EIP-161 by init code that wrote storage and returned no code.
  *   - '''CREATE2 recreation''' (from Agharta): a contract destroyed and created again at the same address, in the same
  *     block and in a later one. The new contract's storage starts empty: no row of the old trie may be left under it.
  *
  * Two nodes, one on each layout, are given the same transactions block after block. Each block is prepared by the
  * node's generator, so its state root is what that node computes; the two blocks must be the same block (the state
  * root is in its hash). Then each node imports it, and on the unified layout the rows on disk after each block are
  * exactly the block's state as the hash store holds it, the block's record is whole (no tombstone) and valid, and at
  * the end the records take the rows back, block by block, to the genesis state.
  */
class EtcStorageDeletionOnPositionsSpec extends AnyFlatSpec with Matchers:

  private type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  private val keyPair: AsymmetricCipherKeyPair =
    keyPairFromPrvKey(BigInt(1, Hex.decode("f3202185c84325302d43887e90a2e23e7bc058d0450bb58ef2f7585765d7d48b")))
  private val sender: Address = Address(keyPair)
  private val miner = Address(0xc0ffee)
  private val treasury = Address(0x7ea5)

  /** An ETC fork schedule: the named fork and every fork before it active from the genesis on (Olympia from block 1:
    * the genesis carries no base fee), the forks after it never. ETH-only forks are never active, as on ETC.
    */
  final private case class Fork(name: String, numbers: ForkBlockNumbers):
    def eip155: Boolean = numbers.eip155BlockNumber == 0
    def olympia: Boolean = numbers.olympiaBlockNumber <= 1
    def create2: Boolean = numbers.aghartaBlockNumber == 0

  private val base: ForkBlockNumbers = ForkBlockNumbers.Empty.copy(
    // ECIP-1041: no difficulty bomb on ETC
    difficultyBombPauseBlockNumber = 0,
    difficultyBombContinueBlockNumber = 0,
    difficultyBombRemovalBlockNumber = 0
  )

  private val forks: List[Fork] =
    List[(String, ForkBlockNumbers => ForkBlockNumbers)](
      "Frontier" -> identity,
      "Homestead" -> (_.copy(homesteadBlockNumber = 0)),
      "EIP-150" -> (_.copy(eip150BlockNumber = 0)),
      "EIP-155/160" -> (_.copy(eip155BlockNumber = 0, eip160BlockNumber = 0)),
      "Atlantis" -> (_.copy(atlantisBlockNumber = 0)),
      "Agharta" -> (_.copy(aghartaBlockNumber = 0)),
      "Phoenix" -> (_.copy(phoenixBlockNumber = 0)),
      "Thanos" -> (_.copy(ecip1099BlockNumber = 0)),
      "Magneto" -> (_.copy(magnetoBlockNumber = 0)),
      "Mystique" -> (_.copy(mystiqueBlockNumber = 0)),
      "Spiral" -> (_.copy(spiralBlockNumber = 0)),
      "Olympia" -> (_.copy(olympiaBlockNumber = 1))
    ).scanLeft(Fork("", base)) { case (before, (name, next)) => Fork(name, next(before.numbers)) }.tail

  final private class Clock extends BlockTimestampProvider:
    @volatile var now: Long = 0L
    override def getEpochSecond: Long = now

  final private class Node(val source: EphemDataSource, guard: Boolean, numbers: ForkBlockNumbers)
      extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = BlockchainConfig(
      forkBlockNumbers = numbers,
      treasuryAddress = treasury,
      chainId = ChainId(0x3d),
      networkId = 1,
      customGenesisFileOpt = Some("test-genesis.json"),
      customGenesisJsonOpt = None,
      monetaryPolicyConfig =
        MonetaryPolicyConfig(5000000, 0.2, 5000000000000000000L, 5000000000000000000L, 5000000000000000000L),
      maxCodeSize = None,
      accountStartNonce = UInt256.Zero,
      daoForkConfig = None,
      bootstrapNodes = Set(),
      ethCompatibleStorage = true
    )
    private trait Shared extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new Shared with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = guard

    GenesisDataLoader.over(blockchainReader, blockchainWriter, storagesInstance.storages).loadGenesisData()

    val clock = new Clock
    lazy val generator: TestBlockGenerator = mining.blockGenerator.withBlockTimestampProvider(clock)
    // The blocks carry no seal: what is held here is the state they leave, not their proof of work.
    lazy val execution = new HeaderTrustingBlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      new BlockValidation(mining, blockchainReader, BlockQueue(blockchainReader, syncConfig))
    )

    def head: Block = blockchainReader.getBestBlock.get
    def prepare(transactions: Seq[SignedTransaction]): Block =
      clock.now = head.header.unixTimestamp.toLong + 15
      generator.generateBlock(head, transactions, miner, generator.emptyX, None).pendingBlock.block
    def importOne(block: Block): Option[BlockExecutionError] =
      val weight = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true)._2
    def account(address: Address): Option[Account] = blockchainReader.accountAt(head.header, address)
    def slot(address: Address, position: Int): BigInt =
      account(address).fold(BigInt(0)) { found =>
        val bytes = blockchain.getAccountStorageAt(
          blockchainReader.stateAt(head.header),
          address,
          found.storageRoot.value,
          position,
          ethCompatibleStorage = true
        )
        BigInt(1, bytes.toArray)
      }

  // CALLDATASIZE PUSH1 05 JUMPI STOP | JUMPDEST CALLER SELFDESTRUCT: called with data, it destroys itself
  private val victimRuntime = "3660055700" + "5b33ff"
  // SSTORE(0, 0x11) SSTORE(1, NUMBER); CODECOPY(0, 0x15, 8); RETURN(0, 8)
  private val victimInit = ByteString(
    Hex.decode("6011600055" + "43600155" + "6008601560003960086000f3" + victimRuntime)
  )
  // CODECOPY(0, 0x12, 0x1d) the victim's init; CREATE2(0, 0, 0x1d, salt 0); POP STOP
  private val factoryRuntime = "601d6012600039" + "6000601d60006000f5" + "5000" + Hex.toHexString(victimInit.toArray)
  // CODECOPY(0, 0x0c, 0x2f); RETURN(0, 0x2f)
  private val factoryInit = ByteString(Hex.decode("602f600c600039602f6000f3" + factoryRuntime))
  // SSTORE(0, 0x11); CALLER SELFDESTRUCT: a contract destroyed by its own creation
  private val shortLivedInit = ByteString(Hex.decode("6011600055" + "33ff"))
  // SSTORE(0, 0x11); STOP: no code. Before EIP-161 the account has no nonce either: empty, with storage.
  private val emptyWithStorageInit = ByteString(Hex.decode("6011600055" + "00"))

  private def create2Address(creator: Address, initCode: ByteString): Address =
    Address(kec256(ByteString(0xff.toByte) ++ creator.bytes ++ UInt256.Zero.bytes ++ kec256(initCode)))

  /** The two nodes of one schedule, and one block at a time made and imported on both. */
  final private class Pair(fork: Fork):
    val onHashes = new Node(EphemDataSource(), guard = false, fork.numbers)
    val onPositions = new Node(EphemDataSource(), guard = true, fork.numbers)
    onPositions.head.header.hash shouldBe onHashes.head.header.hash
    private val genesis = onPositions.head.header
    private val nodes = new NodeStorage(onHashes.source)
    private val rows = new PathNodeStorage(onPositions.source)
    private val records = new ChangesetStorage(onPositions.source)
    private val pathState = onPositions.storagesInstance.storages.pathStateStorage.get
    private var nonce = 0
    private var chain = Vector(genesis)
    var tombstones = 0
    var storageRowsDeleted = 0

    def stateOf(header: BlockHeader): Disk =
      val positions = RawTrieWalk.statePositions(
        header.stateRoot.value.toArray,
        hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
      )
      (
        positions.getOrElse(None, Map.empty),
        positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
      )
    def disk: Disk = PathStateTestKit.allRows(rows)
    def storageRowsOf(address: Address): Int =
      val owner = PathStateTestKit.owner(address)
      disk._2.keys.count(_.startsWith(owner))

    private def signed(tx: LegacyTransaction): SignedTransaction =
      val stx = SignedTransaction.sign(tx, keyPair, Option.when(fork.eip155)(BigInt(0x3d)))
      nonce += 1
      stx
    private val price = GasPrice(BigInt(100) * BigInt(1000000000))
    def create(init: ByteString): (SignedTransaction, Address) =
      val address = CallErrors.createAddress(sender, nonce)
      (signed(LegacyTransaction(nonce, price, GasAmount(300000), None, 0, init)), address)
    def call(to: Address, data: ByteString = ByteString.empty, value: Int = 0): SignedTransaction =
      signed(LegacyTransaction(nonce, price, GasAmount(300000), to, value, data))

    /** One block of `transactions`, prepared and imported on both nodes, every check on the unified layout's disk. */
    def block(transactions: SignedTransaction*): Block =
      val reference = onHashes.prepare(transactions)
      val prepared = onPositions.prepare(transactions)
      withClue(s"${fork.name}, block ${prepared.header.number}: ") {
        prepared.header.hash shouldBe reference.header.hash
        prepared.body.transactionList should have size transactions.size
        onHashes.importOne(reference) shouldBe None
        onPositions.importOne(prepared) shouldBe None
        pathState.anchor shouldBe
          Some(BlockRef(prepared.header.hash.value, prepared.header.number.value, prepared.header.stateRoot.value))
        disk shouldBe stateOf(prepared.header)
        PathStateTestKit.hashStoreRows(onPositions.source) shouldBe empty
        val record = records.get(prepared.header.hash.value).flatMap(_.toOption)
        record.map(_.validate) shouldBe Some(Nil)
        record.foreach { diff =>
          tombstones += (if diff.incomplete then 1 else 0)
          storageRowsDeleted += diff.changes.count((p, c) => p.owner.isDefined && c.next.isEmpty)
        }
      }
      chain = chain :+ prepared.header
      prepared

    /** The same account on both layouts, at the head. */
    def account(address: Address): Option[Account] =
      val found = onPositions.account(address)
      found shouldBe onHashes.account(address)
      found
    def slot(address: Address, position: Int): BigInt =
      val found = onPositions.slot(address, position)
      found shouldBe onHashes.slot(address, position)
      found

    /** The records take the rows back, block by block, to the genesis state; and no record was a tombstone. */
    def undoAll(): Unit =
      tombstones shouldBe 0
      chain.zip(chain.tail).reverse.foreach { (parent, header) =>
        val record = records.get(header.hash.value).flatMap(_.toOption).get
        onPositions.source.update(rows.rowUpdates(record.changes.view.mapValues(_.prior)))
        withClue(s"${fork.name}, block ${header.number} undone: ")(disk shouldBe stateOf(parent))
      }

    def summary: String =
      s"${fork.name}: ${chain.size - 1} blocks, head root 0x${Hex.toHexString(chain.last.stateRoot.value.toArray)}, " +
        s"$storageRowsDeleted storage rows deleted, $tombstones tombstones"

  "SELFDESTRUCT on every ETC fork" should "delete the contract and its storage before Olympia, and from Olympia (EIP-6780) only in the transaction that created it, alike on both layouts" taggedAs
    (UnitTest, ConsensusTest, VMTest) in {
      forks.map(_.name) shouldBe List(
        "Frontier",
        "Homestead",
        "EIP-150",
        "EIP-155/160",
        "Atlantis",
        "Agharta",
        "Phoenix",
        "Thanos",
        "Magneto",
        "Mystique",
        "Spiral",
        "Olympia"
      )
      forks.foreach { fork =>
        val pair = new Pair(fork)
        withClue(s"${fork.name}: ") {
          // block 1: a contract with two slots, and one that destroys itself in its own creation
          val (deployVictim, victim) = pair.create(victimInit)
          val (deployShortLived, shortLived) = pair.create(shortLivedInit)
          pair.block(deployVictim, deployShortLived)
          pair.account(victim).map(_.codeHash.value) shouldBe Some(kec256(ByteString(Hex.decode(victimRuntime))))
          pair.storageRowsOf(victim) should be > 0
          val victimRows = pair.storageRowsOf(victim)
          // created and destroyed in one transaction: gone on every fork, EIP-6780 included
          pair.account(shortLived) shouldBe None
          pair.storageRowsOf(shortLived) shouldBe 0

          // block 2: the contract destroys itself, and its address is paid in the same block
          pair.block(pair.call(victim, ByteString(1)), pair.call(victim, value = 1))
          val after = pair.account(victim)
          if fork.olympia then
            // EIP-6780: not created in this transaction, so nothing is deleted
            after.map(_.codeHash.value) shouldBe Some(kec256(ByteString(Hex.decode(victimRuntime))))
            after.map(_.balance) shouldBe Some(UInt256(1))
            pair.storageRowsOf(victim) shouldBe victimRows
            pair.slot(victim, 0) shouldBe BigInt(0x11)
          else
            // deleted whole, and an account again with the payment alone: no code, no storage, no row left
            after.map(_.balance) shouldBe Some(UInt256(1))
            after.map(_.codeHash) shouldBe Some(Account.EmptyCodeHash)
            after.map(_.storageRoot) shouldBe Some(Account.EmptyStorageRootHash)
            pair.storageRowsOf(victim) shouldBe 0
            pair.slot(victim, 0) shouldBe BigInt(0)
          pair.undoAll()
          info(pair.summary)
        }
      }
    }

  "CREATE2 on the ETC forks that have it" should "recreate a destroyed contract at its address with no storage of the old one, in the block that destroyed it and in a later one" taggedAs
    (UnitTest, ConsensusTest, VMTest) in {
      val withCreate2 = forks.filter(_.create2)
      withCreate2.map(_.name) shouldBe List("Agharta", "Phoenix", "Thanos", "Magneto", "Mystique", "Spiral", "Olympia")
      withCreate2.foreach { fork =>
        val pair = new Pair(fork)
        withClue(s"${fork.name}: ") {
          // block 1: the factory; block 2: the contract, at the address CREATE2 gives it, with slot 1 = 2
          val (deployFactory, factory) = pair.create(factoryInit)
          pair.block(deployFactory)
          val made = create2Address(factory, victimInit)
          pair.block(pair.call(factory))
          pair.slot(made, 0) shouldBe BigInt(0x11)
          pair.slot(made, 1) shouldBe BigInt(2)
          val firstRows = pair.storageRowsOf(made)
          firstRows should be > 0

          // block 3: destroyed, then made again, in one block
          pair.block(pair.call(made, ByteString(1)), pair.call(factory))
          if fork.olympia then
            // EIP-6780: not destroyed, so the second CREATE2 collides and the first contract stays as it was
            pair.slot(made, 1) shouldBe BigInt(2)
            pair.storageRowsOf(made) shouldBe firstRows
          else
            // the contract of block 3: its own slot 1, over a storage trie that starts empty
            pair.slot(made, 1) shouldBe BigInt(3)
            pair.slot(made, 0) shouldBe BigInt(0x11)

          // block 4: destroyed; block 5: made again
          pair.block(pair.call(made, ByteString(1)))
          if fork.olympia then pair.storageRowsOf(made) shouldBe firstRows
          else
            pair.account(made) shouldBe None
            pair.storageRowsOf(made) shouldBe 0
          pair.block(pair.call(factory))
          pair.slot(made, 1) shouldBe (if fork.olympia then BigInt(2) else BigInt(5))
          pair.account(made).map(_.codeHash.value) shouldBe Some(kec256(ByteString(Hex.decode(victimRuntime))))
          pair.undoAll()
          info(pair.summary)
        }
      }
    }

  "EIP-161 at Atlantis" should "delete a touched empty account, with the storage it holds, and only from Atlantis on" taggedAs
    (UnitTest, ConsensusTest, VMTest) in {
      // ETC's own order: Homestead, EIP-150, EIP-155/160, then Atlantis (ETC's EIP-161) at block 3
      val dieHard = forks.find(_.name == "EIP-155/160").get
      val fork = Fork("EIP-155/160 to Atlantis at 3", dieHard.numbers.copy(atlantisBlockNumber = 3))
      val pair = new Pair(fork)
      val touchedEmpty = Address(0x161)
      val neverThere = Address(0x162)

      // block 1: init code that writes a slot and returns no code; a payment of nothing to an address with no account
      val (deployEmpty, emptyWithStorage) = pair.create(emptyWithStorageInit)
      pair.block(deployEmpty, pair.call(touchedEmpty))
      // before EIP-161 the contract is created with no nonce: an empty account, that holds storage
      val created = pair.account(emptyWithStorage)
      created.map(_.nonce) shouldBe Some(UInt256.Zero)
      created.map(_.balance) shouldBe Some(UInt256.Zero)
      created.map(_.codeHash) shouldBe Some(Account.EmptyCodeHash)
      created.exists(_.isEmpty(UInt256.Zero)) shouldBe true
      pair.slot(emptyWithStorage, 0) shouldBe BigInt(0x11)
      val rows = pair.storageRowsOf(emptyWithStorage)
      rows should be > 0
      // and the payment of nothing made an empty account
      pair.account(touchedEmpty).exists(_.isEmpty(UInt256.Zero)) shouldBe true

      // block 2, still before Atlantis: touched again, kept
      pair.block(pair.call(emptyWithStorage), pair.call(touchedEmpty))
      pair.account(emptyWithStorage) should not be empty
      pair.storageRowsOf(emptyWithStorage) shouldBe rows
      pair.account(touchedEmpty) should not be empty

      // block 3, Atlantis: each touched, each deleted, the storage with it; a new address paid nothing is not made
      pair.block(pair.call(emptyWithStorage), pair.call(touchedEmpty), pair.call(neverThere))
      pair.account(emptyWithStorage) shouldBe None
      pair.storageRowsOf(emptyWithStorage) shouldBe 0
      pair.account(touchedEmpty) shouldBe None
      pair.account(neverThere) shouldBe None
      pair.storageRowsDeleted should be >= rows
      pair.undoAll()
      info(pair.summary)
    }
