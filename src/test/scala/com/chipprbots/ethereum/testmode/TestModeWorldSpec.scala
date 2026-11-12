package com.chipprbots.ethereum.testmode

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.TestInstanceConfigProvider
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*

/** The world of test mode ([[TestModeWorldStateProxy]]) is the base world plus one hook: every storage key it is given
  * is handed on, so that `debug_storageRangeAt` can name keys by their hashes. It must stay that world through
  * everything a block does to it, and keep what the base world keeps.
  *
  * Spec 018 Track C, C3c: the world carried its own copy of each mutator. They did not record the storage tries a
  * world replaces whole, which the unified state layout needs in order to delete their rows; and the base's persist
  * built a plain world, so that after the first transaction of a block the hook was gone.
  */
class TestModeWorldSpec extends AnyFlatSpec with Matchers:

  private val contract = Address(0xc0de)

  private def hashStore(): MptStorage =
    StateStorage.createTestStateStorage(EphemDataSource())._1.getBackingStorage(0)

  /** A test-mode world over `storage` at `root`, and the keys it has handed on. */
  private def world(
      storage: MptStorage,
      root: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  ): (InMemoryWorldStateProxy, collection.mutable.Set[UInt256]) =
    val handed = collection.mutable.Set.empty[UInt256]
    val codes = new com.chipprbots.ethereum.db.storage.EvmCodeStorage(EphemDataSource())
    val made = TestModeWorldStateProxy(
      evmCodeStorage = codes,
      nodesKeyValueStorage = storage,
      accountStartNonce = UInt256.Zero,
      getBlockHashByNumber = _ => None,
      stateRootHash = root,
      noEmptyAccounts = false,
      ethCompatibleStorage = true,
      saveStoragePreimage = key => handed += key
    )
    (made, handed)

  private def withSlot(w: InMemoryWorldStateProxy, slot: Int, value: Int): InMemoryWorldStateProxy =
    w.saveStorage(contract, w.getStorage(contract).store(slot, value))

  // What a block does: a transaction, a persist, the next transaction. The key the second one writes used to be lost.
  "The world of test mode" should "stay that world through a persist, and hand on a key first written after it" taggedAs
    UnitTest in {
      val (fresh, handed) = world(hashStore())
      val first = withSlot(fresh.saveAccount(contract, Account.empty(UInt256.Zero)), slot = 1, value = 7)
      handed shouldBe Set(UInt256(1))

      val persisted = InMemoryWorldStateProxy.persistState(first)
      persisted shouldBe a[TestModeWorldStateProxy]

      val second = withSlot(persisted, slot = 2, value = 9)
      handed shouldBe Set(UInt256(1), UInt256(2))
      second shouldBe a[TestModeWorldStateProxy]
      // and what it holds is what the base world holds
      second.getStorage(contract).load(1) shouldBe BigInt(7)
      second.getStorage(contract).load(2) shouldBe BigInt(9)
    }

  it should "stay that world through every other change of an account" taggedAs UnitTest in {
    val (fresh, _) = world(hashStore())
    val changed = fresh
      .saveAccount(contract, Account.empty(UInt256.Zero))
      .saveCode(contract, ByteString(1, 2, 3))
      .touchAccounts(contract)
      .clearTouchedAccounts
      .initialiseAccount(contract)
    changed shouldBe a[TestModeWorldStateProxy]
    changed.deleteAccount(contract) shouldBe a[TestModeWorldStateProxy]
  }

  // The base world notes each storage trie it replaces whole, and tells its storage at the persist (spec 018
  // data-model 3 rule 6). On the hash store that changes nothing; on the unified layout it is how the rows go.
  it should "note the storage tries it replaces whole, and have told its storage by the end of a persist" taggedAs
    UnitTest in {
      val (fresh, _) = world(hashStore())
      val stored = InMemoryWorldStateProxy.persistState(
        withSlot(fresh.saveAccount(contract, Account.empty(UInt256.Zero)), slot = 1, value = 7)
      )
      stored.storageResets shouldBe empty
      val deleted = stored.deleteAccount(contract)
      deleted.storageResets shouldBe Set(contract)
      val persisted = InMemoryWorldStateProxy.persistState(deleted)
      persisted.storageResets shouldBe empty
      persisted shouldBe a[TestModeWorldStateProxy]
      persisted.getAccount(contract) shouldBe None
    }

  // ─── On the unified state layout ───────────────────────────────────────────────────────────────────────────────────

  final private class OnPositions extends EphemBlockchainTestSetup:
    private val source = EphemDataSource()
    private trait Shared extends EphemDataSourceComponent:
      override val dataSource: EphemDataSource = source
    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new Shared with LocalPruningConfigBuilder with Storages.DefaultStorages with TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = true
    GenesisDataLoader.over(blockchainReader, blockchainWriter, storagesInstance.storages).loadGenesisData()
    val genesis = blockchainReader.getBlockHeaderByNumber(0).get
    val pathState = storagesInstance.storages.pathStateStorage.get

  // The storage a block is executed on there is the storage of the whole state, which reads and writes nothing until
  // it is asked for one trie's view of it. The world asked the base's way for a storage trie and not at all for the
  // account trie: every import in test mode would have thrown.
  it should "read and write the account trie of a block staged on the unified layout" taggedAs UnitTest in {
    val node = new OnPositions
    val staged = node.pathState.stage(
      BlockRef(node.genesis.hash.value, node.genesis.number.value, node.genesis.stateRoot.value)
    )
    val (fresh, handed) = world(staged.storage, node.genesis.stateRoot.value)
    fresh.getAccount(contract) shouldBe None
    val written = InMemoryWorldStateProxy.persistState(
      withSlot(fresh.saveAccount(contract, Account.empty(UInt256.Zero)), slot = 1, value = 7)
    )
    written shouldBe a[TestModeWorldStateProxy]
    handed shouldBe Set(UInt256(1))
    written.stateRootHash should not be node.genesis.stateRoot.value

    // the same change on the hash store is the same root
    val (onHashes, _) = world(hashStore(), ByteString(MerklePatriciaTrie.EmptyRootHash))
    if node.genesis.stateRoot.value == ByteString(MerklePatriciaTrie.EmptyRootHash) then
      InMemoryWorldStateProxy
        .persistState(withSlot(onHashes.saveAccount(contract, Account.empty(UInt256.Zero)), slot = 1, value = 7))
        .stateRootHash shouldBe written.stateRootHash
  }
