package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString

import scala.util.Success

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.PositionTestKit.*
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2a (T040): the genesis loader says which trie each trie is (data-model 5.4, "Owner-scoped"). It
  * used to build every account's storage trie into one storage with no owner.
  */
class GenesisDataLoaderOwnerSpec extends AnyFlatSpec with Matchers:

  private def genesis(alloc: Map[String, GenesisAccount]): GenesisData =
    GenesisData(
      nonce = ByteString(Array.fill[Byte](8)(0)),
      mixHash = None,
      difficulty = "0x1",
      extraData = ByteString(Array[Byte](1)),
      gasLimit = "0x1000000",
      coinbase = ByteString(Array.fill[Byte](20)(0)),
      timestamp = "0x0",
      alloc = alloc
    )

  private def account(storage: Option[Map[UInt256, UInt256]]): GenesisAccount =
    GenesisAccount(precompiled = None, balance = UInt256(1), code = None, nonce = None, storage = storage)

  private def address(n: Int): String = f"$n%02x" * 20

  private def owner(n: Int): Option[ByteString] = Some(ByteString(kec256(Hex.decode(address(n)))))

  private val slots: Option[Map[UInt256, UInt256]] =
    Some((1 to 40).map(i => UInt256(i) -> UInt256(i * 7919)).toMap)

  /** A state storage whose genesis load goes to `nodes`; nothing else of it is used by the loader. */
  private class StateOn(nodes: MptStorage) extends StateStorage:
    override def getBackingStorage(bn: BigInt): MptStorage = nodes
    override def getReadOnlyStorage: MptStorage = nodes
    override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit = ()
    override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
      ()
    override def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit = ()
    override def forcePersist(reason: StateStorage.FlushSituation): Boolean = true

  /** One genesis loaded onto a position store and onto a hash-keyed store. */
  private class Loaded(alloc: Map[String, GenesisAccount]) extends EphemBlockchainTestSetup:
    // The loader reports no node as replaced (`AppendOnlyMptStorage`), so each new node must land on the row of the
    // node it replaces: the store takes upserts, and the rows are held to a walk of the finished tries.
    val positions = new OwnerPositionStore(upsertOnly = true)
    val reference = new ReferenceStore

    private def load(nodes: MptStorage): ByteString =
      val datadir = getNewStorages
      val reader = com.chipprbots.ethereum.domain.BlockchainReader(datadir.storages)
      val writer = com.chipprbots.ethereum.domain.BlockchainWriter(datadir.storages)
      val loader = new GenesisDataLoader(reader, writer, datadir.storages.evmCodeStorage, new StateOn(nodes))
      loader.loadGenesisData(genesis(alloc))(using blockchainConfig) shouldBe a[Success[?]]
      reader.getBlockHeaderByNumber(0).get.stateRoot.value

    val root: ByteString = load(positions)
    load(reference) shouldBe root

    def walk(trieRoot: ByteString): Rows = RawTrieWalk.storedPositions(trieRoot.toArray, reference.load)

    def storageRoot(n: Int): ByteString =
      MerklePatriciaTrie[Address, Account](root.toArray, reference)(using
        Address.hashedAddressEncoder,
        Account.accountSerializer
      ).get(Address(ByteString(Hex.decode(address(n))))).get.storageRoot.value

  "The genesis loader" should "build each account's storage trie under that account's owner" taggedAs UnitTest in (
    new Loaded(Map(address(1) -> account(slots), address(2) -> account(None), address(3) -> account(slots))):
      positions.owners shouldBe Set(None, owner(1), owner(3))
      positions.rowsOf(None) shouldBe walk(root)
      positions.rowsOf(owner(1)) shouldBe walk(storageRoot(1))
      // the same storage in two accounts: one trie each, where a hash-keyed store holds a single copy
      storageRoot(3) shouldBe storageRoot(1)
      positions.rowsOf(owner(3)) shouldBe positions.rowsOf(owner(1))
      positions.rowsOf(owner(2)) shouldBe empty
      positions.resets shouldBe empty
  )

  it should "write no row for an empty alloc" taggedAs UnitTest in new Loaded(Map.empty):
    root shouldBe ByteString(MerklePatriciaTrie.EmptyRootHash)
    positions.owners shouldBe empty

  it should "write no storage rows for an account whose storage is all zeroes" taggedAs UnitTest in (
    new Loaded(Map(address(1) -> account(Some(Map(UInt256(1) -> UInt256.Zero))))):
      positions.owners shouldBe Set(None)
      storageRoot(1) shouldBe Account.EmptyStorageRootHash.value
  )
