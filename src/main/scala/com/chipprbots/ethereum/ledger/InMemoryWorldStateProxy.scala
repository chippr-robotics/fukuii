package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
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
import com.chipprbots.ethereum.mpt.NibblePath
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
    // The world keeps the storage of the whole state; each trie is built over that trie's view of it. A hash-keyed
    // storage is its own view of every trie (spec 018 data-model 8, "Owner threading").
    val accountsStateTrieProxy =
      createProxiedAccountsStateTrie(nodesKeyValueStorage.forOwner(None), stateRootHash, readMemos)
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

  /** The owner of an account's storage trie in a storage that keeps tries apart: `keccak256(address)`, the account's
    * key in the account trie.
    */
  def storageOwner(address: Address): ByteString = kec256(address.bytes)

  /** The nodes of one account's storage trie: that trie's view of the world's storage, with a node that is missing from
    * it reported as that account's.
    */
  final private class AccountStorageNodes(protected val underlying: MptStorage, address: Address)
      extends MptStorageDecorator:

    override protected def decorate(scoped: MptStorage): MptStorage = new AccountStorageNodes(scoped, address)

    override protected def read(nodeId: Array[Byte], path: NibblePath | Null): MptNode =
      try super.read(nodeId, path)
      catch
        case e: MissingNodeException =>
          throw (e match
            case _: MerklePatriciaTrie.Unplaced =>
              new MissingStorageNodeException(ByteString(nodeId), address.bytes) with MerklePatriciaTrie.Unplaced
            case _ => new MissingStorageNodeException(ByteString(nodeId), address.bytes)
          )

    // A write that finds a node of this account's storage missing or corrupt (the wrapper of a node it replaces does
    // not unwrap) is said as a read is: the account's, so that the importer fetches it from the storage trie, not the
    // account trie (B-075). The trie adds the position.
    override protected def update(newRoot: Option[MptNode], removals: MptStorageDecorator.Removals): Option[MptNode] =
      try super.update(newRoot, removals)
      catch
        case e: MissingNodeException if !e.isInstanceOf[MissingStorageNodeException] =>
          throw new MissingStorageNodeException(e.hash, address.bytes, e.location)

  /** Updates state trie with current changes but does not persist them into the storages. To do so it:
    *   - Moves the code deployed since the last persist into [[InMemoryWorldStateProxy.deployedCode]] by its hash, and
    *     sets each account's code hash
    *   - Commits constract storages (to get account's contract storage root)
    *   - Updates state tree
    *
    * Code is not written to the code store here (B-230). Execution that is thrown away (an `eth_call`, an estimate, a
    * simulation, a trace, a proposer's build, a block that fails validation) persists its world too, and used to write
    * every contract it deployed to disk, without bound. The code stays in the world, read back from there, and the path
    * that commits a block's state writes it ([[deployedCodeWrite]]), in the state's batch (B-454).
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
        updatedWorldState.copyWith(
          accountsStateTrie = updatedWorldState.accountsStateTrie +
            (address -> updatedWorldState.getGuaranteedAccount(address).copy(codeHash = CodeHash(codeHashBs))),
          accountCodes = Map.empty,
          deployedCode = updatedWorldState.deployedCode + (codeHashBs -> code)
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

    // The storage tries this world replaced whole, told to the storage BEFORE anything of theirs is persisted: what
    // follows for one of those accounts is a new trie, starting empty (spec 018 data-model 3 rule 6). Only a world that
    // is persisted gets here.
    def resetReplacedStorage(worldState: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
      if worldState.storageResets.isEmpty then worldState
      else
        worldState.storageResets.foreach(address => worldState.stateStorage.resetOwner(storageOwner(address)))
        worldState.copyWith(storageResets = Set.empty)

    persistCode
      .andThen(resetReplacedStorage)
      .andThen(persistContractStorage)
      .andThen(persistAccountsStateTrie)(worldState)

  /** The write of the code `world` deployed, to its code store, or `None` when it deployed none. For the paths that
    * commit a block's state, and only them: committed BEFORE the state that refers to the code, or in the same batch,
    * never after, so that a kill between the two leaves at most code nothing refers to (content-addressed, harmless),
    * never an account whose code is missing. Expects a persisted world: code deployed since the last [[persistState]]
    * is not in it yet.
    *
    * Block import puts it IN the state's batch: execution hands it to the staged state
    * ([[com.chipprbots.ethereum.db.storage.StagedBlockState.stageCode]]), whose commit writes it with the state and the
    * block's records (B-454). It is a write of its own, ahead of the state, only where there is no state batch to join:
    * a state written straight through as it executes (`BlockExecution.executeBlockNoValidation`, a test-mode commit of
    * a build), and a forward layer on the unified layout, held in memory. That is safe because the code store and the
    * state are column families of ONE RocksDB, and every write goes through its one write-ahead log (no write bypasses
    * it: `scripts/check-wal-writes.sh`). The log is written in commit order and recovery replays a prefix of it, so a
    * crash can keep the code and lose the state after it, never the reverse.
    */
  def deployedCodeWrite(world: InMemoryWorldStateProxy): Option[DataSourceBatchUpdate] =
    deployedCodeWrite(world.deployedCode, world.evmCodeStorage)

  /** [[persistState]], then the write of the world's code ([[deployedCodeWrite]]): for a world that is a state kept
    * whole, as a pre-state or a genesis a test or a tool builds and then reads back from the stores.
    */
  def persistStateAndWriteCode(worldState: InMemoryWorldStateProxy): InMemoryWorldStateProxy =
    val persisted = persistState(worldState)
    deployedCodeWrite(persisted).foreach(_.commit())
    persisted

  /** [[deployedCodeWrite]] of code a world deployed, taken out of it (a proposer's build hands back only that). */
  def deployedCodeWrite(code: Map[ByteString, Code], to: EvmCodeStorage): Option[DataSourceBatchUpdate] =
    Option.when(code.nonEmpty)(to.update(Nil, code.toSeq))

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
      )(using Address.hashedAddressEncoder, accountSerializer),
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
    val readMemos: WorldReadMemos = new WorldReadMemos,
    // The accounts whose storage trie this world has replaced as a whole and has not yet told its storage about (spec
    // 018 data-model 3 rule 6). A value of the world, like everything it has not persisted: a world that is thrown
    // away (a call frame that reverts) takes its resets with it.
    val storageResets: Set[Address] = Set.empty,
    // Code this world has persisted ([[InMemoryWorldStateProxy.persistState]]), by its hash, and not written to
    // `evmCodeStorage`: a world is a value, and only the path that commits a block's state writes its code
    // ([[InMemoryWorldStateProxy.deployedCodeWrite]], B-230; in the state's batch, B-454). Read before the code store. Kept whole through a
    // `deleteAccount`, as the store kept it: content-addressed, and another account may have the same code.
    val deployedCode: Map[ByteString, Code] = Map.empty
) extends WorldStateProxy[InMemoryWorldStateProxy, InMemoryWorldStateProxyStorage]:

  override def getAccount(address: Address): Option[Account] =
    try ImportProfile.account(accountsStateTrie.get(address))
    catch
      case e: MissingNodeException =>
        throw (e match
          case _: MerklePatriciaTrie.Unplaced =>
            new MissingAccountNodeException(e.hash, address.bytes, e.location) with MerklePatriciaTrie.Unplaced
          case _ => new MissingAccountNodeException(e.hash, address.bytes, e.location)
        )

  override def getEmptyAccount: Account = Account.empty(accountStartNonce)

  override def getGuaranteedAccount(address: Address): Account = super.getGuaranteedAccount(address)

  override def saveAccount(address: Address, account: Account): InMemoryWorldStateProxy =
    copyWith(accountsStateTrie = accountsStateTrie.put(address, account))

  /** The account's storage trie is replaced as a whole with it, whatever becomes of the address afterwards (it may be
    * created again in the same block): a storage that keeps a trie by position has that trie's nodes to drop, where one
    * that keeps nodes by hash has nothing to do (spec 018 data-model 3 rule 6). The world notes it and tells its
    * storage when it is persisted ([[InMemoryWorldStateProxy.persistState]]), not here: a world is a value, and one
    * that is never persisted must leave no trace in the storage.
    */
  override def deleteAccount(address: Address): InMemoryWorldStateProxy =
    copyWith(
      accountsStateTrie = accountsStateTrie.remove(address),
      contractStorages = contractStorages - address,
      accountCodes = accountCodes - address,
      storageResets = storageResets + address
    )

  /** A CREATE can land on an account that exists: an address collision the create rules let through, which on ETC is an
    * account with storage and neither code nor nonce (core-geth checks those two; EIP-7610 is not ETC's). Its storage
    * is then replaced by the new contract's, whole (yellow paper, eq. 79). So the world forgets the old trie, which it
    * may have read earlier in the block and would otherwise go on reading and writing, and notes the reset as
    * [[deleteAccount]] does.
    *
    * This runs inside a call frame. A frame that reverts throws this world away, and the reset with it.
    */
  override def initialiseAccount(newAddress: Address): InMemoryWorldStateProxy =
    val hadStorage = getAccount(newAddress).exists(_.storageRoot != Account.EmptyStorageRootHash)
    val initialised = super.initialiseAccount(newAddress)
    if !hadStorage then initialised
    else
      initialised.copyWith(
        contractStorages = initialised.contractStorages - newAddress,
        storageResets = initialised.storageResets + newAddress
      )

  override def jumpDestMemo: Option[JumpDestAnalysis.BlockMemo] = readMemos.jumpDests

  /** The account's code. An account with a non-empty `codeHash` whose code is absent from `EvmCodeStorage` is a MISSING
    * DATA condition, so it throws [[MissingCodeException]] rather than return empty code: an empty answer executes the
    * contract as an EOA, and a node that then imports the block disagrees with every other client (SNAP healing used to
    * leave exactly such accounts behind — devnet-8 block 318074).
    */
  override def getCode(address: Address): ByteString = getCodeAndHash(address)._1

  /** [[getCode]] plus the account's `codeHash` when the code came from `EvmCodeStorage` under that hash, or from
    * [[deployedCode]], the code this world persisted and has not written. Code deployed earlier in the block and not
    * yet persisted (`accountCodes`) has no hash at hand, so `None`.
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
              deployedCode.get(hash).orElse(evmCodeStorage.getForExecution(hash)) match
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
              deployedCode.get(hash).map(_.length).orElse(evmCodeStorage.getSizeForExecution(hash)) match
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
    val contextStorage = new InMemoryWorldStateProxy.AccountStorageNodes(
      stateStorage.forOwner(Some(InMemoryWorldStateProxy.storageOwner(address))),
      address
    )
    createProxiedContractStorageTrie(contextStorage, storageRoot.value)

  /** This world with some of what it holds replaced: what every mutator of the world is made of.
    *
    * Protected, and not private, so that a world of another class stays one of that class through its own changes and
    * through [[InMemoryWorldStateProxy.persistState]]: the test-mode world overrides it. While it was private, the
    * first persist of a test-mode world, which is after the first transaction of a block, made it a plain one.
    */
  protected def copyWith(
      stateStorage: MptStorage = stateStorage,
      accountsStateTrie: InMemorySimpleMapProxy[Address, Account, MerklePatriciaTrie[Address, Account]] =
        accountsStateTrie,
      contractStorages: Map[Address, InMemorySimpleMapProxy[BigInt, BigInt, MerklePatriciaTrie[BigInt, BigInt]]] =
        contractStorages,
      evmCodeStorage: EvmCodeStorage = evmCodeStorage,
      accountCodes: Map[Address, Code] = accountCodes,
      touchedAccounts: Set[Address] = touchedAccounts,
      storageResets: Set[Address] = storageResets,
      deployedCode: Map[ByteString, Code] = deployedCode
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
      readMemos,
      storageResets,
      deployedCode
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
