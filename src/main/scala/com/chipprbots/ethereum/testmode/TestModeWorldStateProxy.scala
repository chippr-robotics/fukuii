package com.chipprbots.ethereum.testmode

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage.Code
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Account.accountSerializer
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.ledger.InMemorySimpleMapProxy
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxyStorage
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie

/** The world of test mode: [[InMemoryWorldStateProxy]], which also hands every storage key it is given to
  * `saveStoragePreimage`, so that `debug_storageRangeAt` can name the keys behind their hashes.
  *
  * It is the base world in everything else. It makes its copies through the base's own mutators, by overriding the one
  * thing they are made of ([[copyWith]]): so it stays a test-mode world through every change and through a persist, and
  * what the base keeps for the unified state layout (the storage tries it has replaced whole, `storageResets`) is kept
  * here too. It used to carry its own copy of each mutator, which kept neither: the first persist of a block made the
  * world a plain one, and a key first written by a later transaction of the block was never handed over.
  */
case class TestModeWorldStateProxy(
    override val stateStorage: MptStorage,
    override val accountsStateTrie: InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]],
    // format: off
    override val contractStorages: Map[Address, InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]]],
    // format: on
    override val evmCodeStorage: EvmCodeStorage,
    override val accountCodes: Map[Address, Code],
    override val getBlockByNumber: (BigInt) => Option[ByteString],
    override val accountStartNonce: UInt256,
    override val touchedAccounts: Set[Address],
    override val noEmptyAccountsCond: Boolean,
    override val ethCompatibleStorage: Boolean,
    saveStoragePreimage: (UInt256) => Unit,
    override val storageResets: Set[Address] = Set.empty
) extends InMemoryWorldStateProxy(
      stateStorage,
      accountsStateTrie,
      contractStorages,
      evmCodeStorage,
      accountCodes,
      getBlockByNumber,
      accountStartNonce,
      touchedAccounts,
      noEmptyAccountsCond,
      ethCompatibleStorage,
      storageResets = storageResets
    ):

  override protected def copyWith(
      stateStorage: MptStorage,
      accountsStateTrie: InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]],
      contractStorages: Map[Address, InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]]],
      evmCodeStorage: EvmCodeStorage,
      accountCodes: Map[Address, Code],
      touchedAccounts: Set[Address],
      storageResets: Set[Address]
  ): InMemoryWorldStateProxy =
    copy(
      stateStorage = stateStorage,
      accountsStateTrie = accountsStateTrie,
      contractStorages = contractStorages,
      evmCodeStorage = evmCodeStorage,
      accountCodes = accountCodes,
      touchedAccounts = touchedAccounts,
      storageResets = storageResets
    )

  override def saveStorage(address: Address, storage: InMemoryWorldStateProxyStorage): InMemoryWorldStateProxy =
    storage.wrapped.cache.foreach { case (key, _) => saveStoragePreimage(UInt256(key)) }
    super.saveStorage(address, storage)

object TestModeWorldStateProxy:
  def apply(
      evmCodeStorage: EvmCodeStorage,
      nodesKeyValueStorage: MptStorage,
      accountStartNonce: UInt256,
      getBlockHashByNumber: BigInt => Option[ByteString],
      stateRootHash: ByteString,
      noEmptyAccounts: Boolean,
      ethCompatibleStorage: Boolean,
      saveStoragePreimage: (UInt256) => Unit
  ): TestModeWorldStateProxy =
    new TestModeWorldStateProxy(
      stateStorage = nodesKeyValueStorage,
      // the account trie's own view of the storage, as the base world takes it: a hash-keyed storage answers with
      // itself, and the storage of the unified state layout reads and writes nothing without one
      accountsStateTrie = createProxiedAccountsStateTrie(nodesKeyValueStorage.forOwner(None), stateRootHash),
      contractStorages = Map.empty,
      evmCodeStorage = evmCodeStorage,
      accountCodes = Map.empty,
      getBlockByNumber = getBlockHashByNumber,
      accountStartNonce = accountStartNonce,
      touchedAccounts = Set.empty,
      noEmptyAccountsCond = noEmptyAccounts,
      ethCompatibleStorage = ethCompatibleStorage,
      saveStoragePreimage = saveStoragePreimage
    )

  private def createProxiedAccountsStateTrie(
      accountsStorage: MptStorage,
      stateRootHash: ByteString
  ): InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]] =
    InMemorySimpleMapProxy.wrap[Address, Account, MerklePatriciaTrie[Address, Account]](
      MerklePatriciaTrie[Address, Account](
        stateRootHash.toArray[Byte],
        accountsStorage
      )(using Address.hashedAddressEncoder, accountSerializer)
    )
