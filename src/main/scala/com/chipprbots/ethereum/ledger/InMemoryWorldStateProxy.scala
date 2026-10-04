package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.*
import com.chipprbots.ethereum.db.storage.EvmCodeStorage.Code
import com.chipprbots.ethereum.domain
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingAccountNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.vm.ImportProfile
import com.chipprbots.ethereum.vm.JumpDestAnalysis
import com.chipprbots.ethereum.vm.Storage
import com.chipprbots.ethereum.vm.WorldStateProxy

object InMemoryWorldStateProxy:

  import Account.*

  def apply(
      evmCodeStorage: EvmCodeStorage,
      mptStorage: MptStorage,
      getBlockHashByNumber: BigInt => Option[ByteString],
      accountStartNonce: UInt256,
      stateRootHash: ByteString,
      noEmptyAccounts: Boolean,
      ethCompatibleStorage: Boolean,
      flatSlotStorage: Option[FlatSlotStorage] = None
  ): InMemoryWorldStateProxy = InMemoryWorldStateProxy.apply(
    evmCodeStorage,
    mptStorage,
    accountStartNonce,
    getBlockHashByNumber,
    stateRootHash,
    noEmptyAccounts,
    ethCompatibleStorage,
    flatSlotStorage
  )

  private def apply(
      evmCodeStorage: EvmCodeStorage,
      nodesKeyValueStorage: MptStorage,
      accountStartNonce: UInt256,
      getBlockHashByNumber: BigInt => Option[ByteString],
      stateRootHash: ByteString,
      noEmptyAccounts: Boolean,
      ethCompatibleStorage: Boolean,
      flatSlotStorage: Option[FlatSlotStorage]
  ): InMemoryWorldStateProxy =
    val readMemos = new WorldReadMemos
    val accountsStateTrieProxy = createProxiedAccountsStateTrie(nodesKeyValueStorage, stateRootHash, readMemos)
    new InMemoryWorldStateProxy(
      nodesKeyValueStorage,
      accountsStateTrieProxy,
      Map.empty,
      evmCodeStorage,
      Map.empty,
      getBlockHashByNumber,
      accountStartNonce,
      Set.empty,
      noEmptyAccounts,
      ethCompatibleStorage,
      flatSlotStorage,
      readMemos
    )

  /** Updates state trie with current changes but does not persist them into the storages. To do so it:
    *   - Commits code (to get account's code hashes)
    *   - Commits constract storages (to get account's contract storage root)
    *   - Updates state tree
    *
    * @param worldState
    *   Proxy to commit
    * @return
    *   Updated world
    */
  def persistState(worldState: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
    def persistCode(worldState: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
      worldState.accountCodes.foldLeft(worldState) { case (updatedWorldState, (address, code)) =>
        val codeHashBs = kec256(code)
        updatedWorldState.evmCodeStorage.put(codeHashBs, code).commit()
        updatedWorldState.copyWith(
          accountsStateTrie = updatedWorldState.accountsStateTrie +
            (address -> updatedWorldState.getGuaranteedAccount(address).copy(codeHash = CodeHash(codeHashBs))),
          accountCodes = Map.empty
        )
      }

    def persistContractStorage(worldState: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
      worldState.contractStorages.foldLeft(worldState) { case (updatedWorldState, (address, storageTrie)) =>
        val persistedStorage = storageTrie.persist()
        val newStorageRootHash = persistedStorage.inner.getRootHash

        updatedWorldState.copyWith(
          contractStorages = updatedWorldState.contractStorages + (address -> persistedStorage),
          accountsStateTrie = updatedWorldState.accountsStateTrie +
            (address -> updatedWorldState
              .getGuaranteedAccount(address)
              .copy(storageRoot = TrieRoot(ByteString(newStorageRootHash))))
        )
      }

    def persistAccountsStateTrie(worldState: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
      worldState.copyWith(accountsStateTrie = worldState.accountsStateTrie.persist())

    persistCode.andThen(persistContractStorage).andThen(persistAccountsStateTrie)(worldState)

  /** Returns an [[InMemorySimpleMapProxy]] of the accounts state trie "The world state (state), is a mapping between
    * Keccak 256-bit hashes of the addresses (160-bit identifiers) and account states (a data structure serialised as
    * RLP [...]). Though not stored on the blockchain, it is assumed that the implementation will maintain this mapping
    * in a modified Merkle Patricia tree [...])."
    *
    * * See [[http://paper.gavwood.com YP 4.1]]
    *
    * @param accountsStorage
    *   Accounts Storage where trie nodes are saved
    * @param stateRootHash
    *   State trie root hash
    * @return
    *   Proxied Accounts State Trie
    */
  private def createProxiedAccountsStateTrie(
      accountsStorage: MptStorage,
      stateRootHash: ByteString,
      readMemos: WorldReadMemos
  ): InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]] =
    InMemorySimpleMapProxy.wrap[Address, Account, MerklePatriciaTrie[Address, Account]](
      MerklePatriciaTrie[Address, Account](
        stateRootHash.toArray[Byte],
        accountsStorage
      )(Address.hashedAddressEncoder, accountSerializer),
      readMemos.accounts[Address, Account]
    )

class InMemoryWorldStateProxyStorage(
    val wrapped: InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]],
    val flatSlotStorage: Option[FlatSlotStorage] = None,
    val accountHash: Option[ByteString] = None
) extends Storage[InMemoryWorldStateProxyStorage]:

  override def store(addr: BigInt, value: BigInt): InMemoryWorldStateProxyStorage =
    val newWrapped =
      if value == 0 then wrapped - addr
      else wrapped + (addr -> value)
    new InMemoryWorldStateProxyStorage(newWrapped, flatSlotStorage, accountHash)

  override def load(addr: BigInt): BigInt =
    // 1. Check dirty state first (in-memory modifications from current transaction)
    wrapped.cache.get(addr) match
      case Some(optValue) => optValue.getOrElse(0)
      case None           =>
        // 2. Try flat storage O(1) lookup before O(log n) MPT traversal
        flatLookup(addr) match
          case Some(value) => value
          case None        =>
            // 3. Fall back to MPT traversal (handles pre-sync data, missing flat entries)
            ImportProfile.storageRead(wrapped.get(addr)).getOrElse(0)

  /** O(1) flat storage lookup: accountHash ++ keccak256(pad32(slotIndex)) → RLP(value) */
  private def flatLookup(addr: BigInt): Option[BigInt] =
    (flatSlotStorage, accountHash) match
      case (Some(flat), Some(acctHash)) =>
        // Compute slot hash: keccak256(pad32(slotIndex)) — matches SNAP protocol key format
        val slotKey = domain.EthereumUInt256Mpt.byteArrayBigIntSerializer.toBytes(addr)
        val slotHash = kec256(ByteString(slotKey))
        flat.getSlot(acctHash, slotHash).flatMap { rawValue =>
          // Decode RLP-encoded BigInt value
          try Some(com.chipprbots.ethereum.rlp.decode[BigInt](rawValue.toArray))
          catch case _: Exception => None // Malformed data — fall through to MPT
        }
      case _ => None

class InMemoryWorldStateProxy(
    // State MPT proxied nodes storage needed to construct the storage MPT when calling [[getStorage]].
    // Accounts state and accounts storage states are saved within the same storage
    val stateStorage: MptStorage,
    val accountsStateTrie: InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]],
    // Contract Storage Proxies by Address
    val contractStorages: Map[Address, InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]]],
    // It's easier to use the storage instead of the blockchain here (because of proxy wrapping). We might need to reconsider this
    val evmCodeStorage: EvmCodeStorage,
    // Account's code by Address
    val accountCodes: Map[Address, Code],
    val getBlockByNumber: (BigInt) => Option[ByteString],
    override val accountStartNonce: UInt256,
    // touchedAccounts and noEmptyAccountsCond are introduced by EIP161 to track accounts touched during the transaction
    // execution. Touched account are only added to Set if noEmptyAccountsCond == true, otherwise all other operations
    // operate on empty set.
    val touchedAccounts: Set[Address],
    val noEmptyAccountsCond: Boolean,
    val ethCompatibleStorage: Boolean,
    // Optional flat slot storage for O(1) SLOAD lookups (populated by SNAP sync).
    // When present, storage reads check flat storage before MPT traversal.
    val flatSlotStorage: Option[FlatSlotStorage] = None,
    // Memos of reads of the persisted base tries, shared by every copy of this world (see [[TrieReadMemo]]).
    val readMemos: WorldReadMemos = new WorldReadMemos
) extends WorldStateProxy[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]:

  override def getAccount(address: Address): Option[Account] =
    try ImportProfile.account(accountsStateTrie.get(address))
    catch
      case e: MissingNodeException =>
        throw new MissingAccountNodeException(e.hash, address.bytes, e.location)

  override def getEmptyAccount: Account = Account.empty(accountStartNonce)

  override def getGuaranteedAccount(address: Address): Account = super.getGuaranteedAccount(address)

  override def saveAccount(address: Address, account: Account): InMemoryWorldStateProxy =
    copyWith(accountsStateTrie = accountsStateTrie.put(address, account))

  override def deleteAccount(address: Address): InMemoryWorldStateProxy =
    copyWith(
      accountsStateTrie = accountsStateTrie.remove(address),
      contractStorages = contractStorages - address,
      accountCodes = accountCodes - address
    )

  override def jumpDestMemo: Option[JumpDestAnalysis.BlockMemo] = readMemos.jumpDests

  /** The account's code. An account with a non-empty `codeHash` whose code is absent from `EvmCodeStorage` is a MISSING
    * DATA condition, so it throws [[MissingCodeException]] rather than return empty code: an empty answer executes the
    * contract as an EOA, and a node that then imports the block disagrees with every other client (SNAP healing used to
    * leave exactly such accounts behind — devnet-8 block 318074).
    */
  override def getCode(address: Address): ByteString = getCodeAndHash(address)._1

  /** [[getCode]] plus the account's `codeHash` when the code came from `EvmCodeStorage` under that hash. Code deployed
    * earlier in the block and not yet persisted (`accountCodes`) has no hash at hand, so `None`.
    */
  override def getCodeAndHash(address: Address): (ByteString, Option[ByteString]) =
    ImportProfile.code {
      accountCodes.get(address) match
        case Some(dirty) => (dirty, None)
        case None =>
          getAccount(address) match
            case None => (ByteString.empty, None)
            case Some(account) =>
              val hash = account.codeHash.value
              evmCodeStorage.getForExecution(hash) match
                case Some(code) =>
                  ImportProfile.codeBytesRead(code.length)
                  (code, Some(hash))
                case None if account.codeHash == Account.EmptyCodeHash => (ByteString.empty, None)
                case None => throw new MissingCodeException(hash, address.bytes)
    }

  /** [[getCode]]'s length, answered from the code-size cache when the account's code was read before. An empty code
    * hash is 0 whether or not an (empty) code row exists, so it never reads. Missing code still throws.
    */
  override def getCodeSize(address: Address): Int =
    accountCodes.get(address) match
      case Some(dirty) => dirty.length
      case None =>
        getAccount(address) match
          case None                                                       => 0
          case Some(account) if account.codeHash == Account.EmptyCodeHash => 0
          case Some(account) =>
            ImportProfile.code {
              val hash = account.codeHash.value
              evmCodeStorage.getSizeForExecution(hash) match
                case Some(size) => size
                case None       => throw new MissingCodeException(hash, address.bytes)
            }

  override def getStorage(address: Address): InMemoryWorldStateProxyStorage =
    val proxy = contractStorages.getOrElse(address, getStorageForAddress(address, stateStorage))
    new InMemoryWorldStateProxyStorage(
      proxy,
      flatSlotStorage,
      flatSlotStorage.map(_ => kec256(address.bytes))
    )

  override def saveCode(address: Address, code: ByteString): InMemoryWorldStateProxy =
    copyWith(accountCodes = accountCodes + (address -> code))

  override def saveStorage(address: Address, storage: InMemoryWorldStateProxyStorage): InMemoryWorldStateProxy =
    copyWith(contractStorages = contractStorages + (address -> storage.wrapped))

  override def touchAccounts(addresses: Address*): InMemoryWorldStateProxy =
    if noEmptyAccounts then copyWith(touchedAccounts = touchedAccounts ++ addresses.toSet)
    else this

  override def clearTouchedAccounts: InMemoryWorldStateProxy =
    copyWith(touchedAccounts = touchedAccounts.empty)

  override def noEmptyAccounts: Boolean = noEmptyAccountsCond

  override def keepPrecompileTouched(world: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
    if world.touchedAccounts.contains(ripmdContractAddress) then
      copyWith(touchedAccounts = touchedAccounts + ripmdContractAddress)
    else this

  /** Returns world state root hash. This value is only updated after persist.
    */
  def stateRootHash: ByteString = ByteString(accountsStateTrie.inner.getRootHash)

  private def getStorageForAddress(address: Address, stateStorage: MptStorage) =
    val storageRoot = getAccount(address)
      .map(account => account.storageRoot)
      .getOrElse(Account.EmptyStorageRootHash)
    val contextStorage = new MptStorage:
      override def get(nodeId: Array[Byte]): MptNode =
        try stateStorage.get(nodeId)
        catch
          case _: MissingNodeException =>
            throw new MissingStorageNodeException(ByteString(nodeId), address.bytes)
      override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
        stateStorage.updateNodesInStorage(newRoot, toRemove)
      override def persist(): Unit = stateStorage.persist()
      override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit = stateStorage.storeRawNodes(nodes)
    createProxiedContractStorageTrie(contextStorage, storageRoot.value)

  private def copyWith(
      stateStorage: MptStorage = stateStorage,
      accountsStateTrie: InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]] =
        accountsStateTrie,
      contractStorages: Map[Address, InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]]] =
        contractStorages,
      evmCodeStorage: EvmCodeStorage = evmCodeStorage,
      accountCodes: Map[Address, Code] = accountCodes,
      touchedAccounts: Set[Address] = touchedAccounts
  ): InMemoryWorldStateProxy =
    new InMemoryWorldStateProxy(
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
      flatSlotStorage,
      readMemos
    )

  override def getBlockHash(number: UInt256): Option[UInt256] = getBlockByNumber(number).map(UInt256(_))

  /** Returns an [[InMemorySimpleMapProxy]] of the contract storage, for `ethCompatibleStorage` defined as "trie as a
    * map-ping from the Keccak 256-bit hash of the 256-bit integer keys to the RLP-encoded256-bit integer values." See
    * [[http://paper.gavwood.com YP 4.1]]
    *
    * @param contractStorage
    *   Storage where trie nodes are saved
    * @param storageRoot
    *   Trie root
    * @return
    *   Proxied Contract Storage Trie
    */
  private def createProxiedContractStorageTrie(
      contractStorage: MptStorage,
      storageRoot: ByteString
  ): InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]] =
    val mpt =
      if ethCompatibleStorage then domain.EthereumUInt256Mpt.storageMpt(storageRoot, contractStorage)
      else domain.ArbitraryIntegerMpt.storageMpt(storageRoot, contractStorage)

    InMemorySimpleMapProxy.wrap[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]](
      mpt,
      readMemos.storageFor(storageRoot)
    )
