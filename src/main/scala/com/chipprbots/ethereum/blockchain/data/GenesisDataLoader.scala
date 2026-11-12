package com.chipprbots.ethereum.blockchain.data

import java.io.FileNotFoundException

import org.apache.pekko.util.ByteString

import scala.io.Source
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.bouncycastle.util.encoders.Hex
import org.json4s.CustomSerializer
import org.json4s.DefaultFormats
import org.json4s.Extraction
import org.json4s.Formats
import org.json4s.JObject
import org.json4s.JString
import org.json4s.JValue

import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader.JsonSerializers.ByteStringJsonSerializer
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader.JsonSerializers.UInt256JsonSerializer
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.MptStorageDecorator
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.StateStorage.GenesisDataLoad
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateLayout
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.jsonrpc.JsonMethodsImplicits
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger

class GenesisDataLoader(
    blockchainReader: BlockchainReader,
    blockchainWriter: BlockchainWriter,
    evmCodeStorage: com.chipprbots.ethereum.db.storage.EvmCodeStorage,
    stateStorage: StateStorage,
    // Called once the genesis has been stored on a datadir that held none: the one moment it is known nothing else is
    // there (spec 022 D7, `HealInvariant.isEmpty`). Crash before it, and the datadir only counts as not empty: one walk.
    // Its argument is whether every reference count the load wrote is exact (#1692, `GenesisDataLoader.countsExact`):
    // only then may the datadir be stamped as such. A crash before the callback leaves it unstamped, which is safe.
    onFirstStored: Boolean => Unit = _ => (),
    // The unified state layout (spec 018 Track C), when this chain's state lives there: the genesis is then committed
    // to it, in one batch with the genesis block, and `stateStorage` is used only to say what the header is.
    pathState: Option[PathStateStorage] = None,
    // The datadir's layout stamp (`AppStateStorage.getStateLayout`): what says which layout wrote it.
    stateLayout: () => Option[String] = () => None
) extends Logger:

  private val addressLength = GenesisDataLoader.AddressLength

  import Account.*

  def loadGenesisData()(implicit blockchainConfig: BlockchainConfig): Unit =
    log.debug("Loading genesis data")

    val genesisJson = blockchainConfig.customGenesisJsonOpt.getOrElse {
      blockchainConfig.customGenesisFileOpt match
        case Some(customGenesisFile) =>
          log.debug(s"Trying to load custom genesis data from file: $customGenesisFile")

          Try(Source.fromFile(customGenesisFile)).recoverWith { case _: FileNotFoundException =>
            log.debug(s"Cannot load custom genesis data from file: $customGenesisFile")
            log.debug(s"Trying to load from resources: $customGenesisFile")
            Try(Source.fromResource(customGenesisFile))
          } match
            case Success(customGenesis) =>
              log.info(s"Using custom genesis data from: $customGenesisFile")
              try customGenesis.getLines().mkString
              finally customGenesis.close()
            case Failure(ex) =>
              log.error(s"Cannot load custom genesis data from: $customGenesisFile", ex)
              throw ex
        case None =>
          log.info("Using default genesis data")
          val src = Source.fromResource("blockchain/default-genesis.json")
          try src.getLines().mkString
          finally src.close()
    }

    loadGenesisData(genesisJson) match
      case Success(_) =>
        log.info("Genesis data successfully loaded")
      case Failure(ex) =>
        log.error("Unable to load genesis data", ex)
        throw ex

  private def loadGenesisData(genesisJson: String)(implicit blockchainConfig: BlockchainConfig): Try[Unit] =
    for
      genesisData <- GenesisDataLoader.parseGenesisData(genesisJson)
      _ <- loadGenesisData(genesisData)
    yield ()

  def loadGenesisData(genesisData: GenesisData)(implicit blockchainConfig: BlockchainConfig): Try[Unit] =
    val existing = blockchainReader.getBlockHeaderByNumber(0)
    GenesisDataLoader
      .layoutRefusal(pathLive = pathState.isDefined, stamp = stateLayout(), genesisStored = existing.isDefined)
      .orElse(pathState.flatMap(_ => GenesisDataLoader.unifiedLayoutRefusal(genesisData, addressLength))) match
      case Some(refusal) => Failure(new IllegalStateException(refusal))
      case None          => loadOnLayout(genesisData, existing)

  private def loadOnLayout(genesisData: GenesisData, existing: Option[BlockHeader])(implicit
      blockchainConfig: BlockchainConfig
  ): Try[Unit] =
    // On the unified layout the tries are built on a layer of their own, which the commit writes as rows. On the hash
    // store they are built into a buffer over the store.
    val genesisLayer = pathState.map(_.genesisLayer())
    val storage: MptStorage =
      genesisLayer.fold[MptStorage](GenesisDataLoader.AppendOnlyMptStorage(stateStorage.getReadOnlyStorage))(_.storage)
    val initalRootHash = MerklePatriciaTrie.EmptyRootHash

    val stateMptRootHash = getGenesisStateRoot(genesisData, initalRootHash, storage)
    val header: BlockHeader = prepareHeader(genesisData, stateMptRootHash)

    log.debug(s"Prepared genesis header: $header")

    // A genesis with a withdrawalsRoot (Shanghai at genesis: Ephemery, hive, devnets) has an EMPTY withdrawals
    // list, `Some(Nil)`: `BlockBody(Nil, Nil)` left it `None`, so the stored genesis encoded as a 3-item body (615
    // bytes on Ephemery where go-ethereum/Erigon serve 616). ETC genesis has no withdrawalsRoot: unchanged. One block
    // for both layouts, so what they store cannot drift apart.
    val block = Block(header, BlockBody(Nil, Nil, withdrawals = header.withdrawalsRoot.map(_ => Nil)))

    existing match
      case Some(existingGenesisHeader) if existingGenesisHeader.hash == header.hash =>
        log.debug("Genesis data already in the database")
        Success(())
      case Some(_) =>
        Failure(
          new RuntimeException(
            "Genesis data present in the database does not match genesis block from file." +
              " Use different directory for running private blockchains."
          )
        )
      case None if genesisLayer.isDefined =>
        // One batch, synced: the genesis block, its weight and the best-block pointer, the rows of its state, the
        // marks of a datadir this build created, the layout stamp and the state anchor (spec 018 data-model 5.4).
        // What the commit refuses, and a write that fails, are this load's failure like any other.
        Try(
          pathState.get.commitGenesis(
            genesisLayer.get,
            BlockRef(header.hash.value, header.number.value, header.stateRoot.value),
            Some(
              blockchainWriter.saveBatch(
                block,
                Nil,
                ChainWeight.totalDifficultyOnly(header.difficulty.value),
                saveAsBestBlock = true
              )
            )
          )
        )
      case None =>
        storage.persist()
        stateStorage.forcePersist(GenesisDataLoad)
        blockchainWriter.save(
          block,
          Nil,
          ChainWeight.totalDifficultyOnly(header.difficulty.value),
          saveAsBestBlock = true
        )
        val countsExact = GenesisDataLoader.countsExact(genesisData)
        if !countsExact then
          log.info(
            "The genesis has more than one account with storage. The storage tries are built through a buffer that " +
              "writes each distinct node once, so a subtree two of them share is counted once. This datadir is not " +
              "stamped as having exact reference counts, and pruning keeps every trie node (#1692)."
          )
        onFirstStored(countsExact)
        Success(())

  private def getGenesisStateRoot(genesisData: GenesisData, initalRootHash: Array[Byte], storage: MptStorage)(implicit
      blockchainConfig: BlockchainConfig
  ) =
    import MerklePatriciaTrie.defaultByteArraySerializable

    // Each trie is built over its own view of the storage: the account trie's, and for an account's storage trie the
    // view its owner names, `keccak256(address)` (spec 018 data-model 5.4). A hash-keyed storage is its own view of
    // every trie.
    val accountTrieNodes = storage.forOwner(None)

    genesisData.alloc.zipWithIndex.foldLeft(initalRootHash) { case (rootHash, ((address, genesisAccount), _)) =>
      val mpt = MerklePatriciaTrie[Array[Byte], Account](rootHash, accountTrieNodes)
      val cleanAddress = if address.startsWith("0x") || address.startsWith("0X") then address.substring(2) else address
      val paddedAddress = cleanAddress.reverse.padTo(addressLength, "0").reverse.mkString
      val accountHash = crypto.kec256(Hex.decode(paddedAddress))

      // Store contract code in EVM code storage if present
      genesisAccount.code.foreach { code =>
        val codeHash = ByteString(crypto.kec256(code))
        evmCodeStorage.put(codeHash, code).commit()
      }

      val stateRoot = mpt
        .put(
          accountHash,
          Account(
            nonce = genesisAccount.nonce
              .getOrElse(blockchainConfig.accountStartNonce),
            balance = genesisAccount.balance,
            codeHash = genesisAccount.code.fold(Account.EmptyCodeHash)(codeValue => CodeHash(crypto.kec256(codeValue))),
            storageRoot = genesisAccount.storage.fold(Account.EmptyStorageRootHash)(s =>
              TrieRoot(computeStorageRootHash(s, storage.forOwner(Some(ByteString(accountHash)))))
            )
          )
        )
        .getRootHash
      stateRoot
    }

  /** Compute the storage MPT root for a genesis account AND persist its trie nodes so the EVM can read them at runtime.
    * Previously this used an ephemeral DataSource, causing every genesis-deployed contract with storage to throw
    * MPTException on first SLOAD.
    */
  private def computeStorageRootHash(storage: Map[UInt256, UInt256], storageTrieNodes: MptStorage): ByteString =
    val emptyTrie = EthereumUInt256Mpt.storageMpt(
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      storageTrieNodes
    )

    val storageTrie = storage.foldLeft(emptyTrie) {
      case (trie, (_, UInt256.Zero)) => trie
      case (trie, (key, value))      => trie.put(key, value)
    }

    ByteString(storageTrie.getRootHash)

  /** The header of the genesis block over the state whose root is `stateMptRootHash`.
    *
    * Protected, and not private, for one reason: a harness that replays a chain it was handed with its genesis block
    * (the execution-spec-tests fixtures) loads that chain's allocation through this loader, so that its genesis is
    * stored the way a node stores one on either state layout, and gives the header it was handed in place of this one,
    * whose fields it does not all produce. Nothing in the node overrides it.
    */
  protected def prepareHeader(genesisData: GenesisData, stateMptRootHash: Array[Byte])(implicit
      blockchainConfig: BlockchainConfig
  ): BlockHeader =
    GenesisDataLoader.headerOver(genesisData, stateMptRootHash)

object GenesisDataLoader:

  private val bloomLength = 512
  private val hashLength = 64
  private val emptyTrieRootHash = ByteString(crypto.kec256(rlp.encode(Array.empty[Byte])))

  /** A genesis file (its JSON text) read the way the loader reads it. */
  def parseGenesisData(genesisJson: String): Try[GenesisData] =
    import org.json4s.native.JsonMethods.parse
    import JsonSerializers.GenesisAccountSerializer
    given formats: Formats =
      DefaultFormats + ByteStringJsonSerializer + UInt256JsonSerializer + GenesisAccountSerializer
    Try(Extraction.extract[GenesisData](parse(genesisJson)))

  /** The header of block 0 for `genesisData`, computed without a node: the state root over a throwaway in-memory trie
    * ([[pureStateRoot]]), then the header logic of [[headerOver]]. It is the function a release check hashes with (spec
    * 028 R3), and for every genesis a node loads it equals the header the loader stores.
    *
    * It is not what [[GenesisDataLoader.loadGenesisData]] calls: the loader takes the state root from the tries it
    * built on its own layout, and passes it to its overridable `prepareHeader`, which delegates to [[headerOver]].
    */
  def genesisHeader(genesisData: GenesisData)(using blockchainConfig: BlockchainConfig): BlockHeader =
    headerOver(genesisData, pureStateRoot(genesisData))

  /** The state root of a genesis allocation, computed with no side effect: the tries are built over an in-memory,
    * hash-keyed store that nothing else sees, and no code is written anywhere (the code hash needs only
    * `keccak256(code)`). Never goes through the loader's state storage, `pathState` or `genesisLayer`.
    *
    * The same fold as the loader's `getGenesisStateRoot`, whose persistence stays where it is; the two are pinned equal
    * by `GenesisHeaderExtractionSpec`. The store drops removals ([[AppendOnlyMptStorage]]), so tries that share a node
    * do not corrupt each other.
    */
  def pureStateRoot(genesisData: GenesisData)(using blockchainConfig: BlockchainConfig): Array[Byte] =
    import MerklePatriciaTrie.defaultByteArraySerializable
    import Account.*

    val storage: MptStorage = AppendOnlyMptStorage(StateStorage.getReadOnlyStorage(EphemDataSource()))
    val accountTrieNodes = storage.forOwner(None)

    genesisData.alloc.foldLeft(MerklePatriciaTrie.EmptyRootHash) { case (rootHash, (address, genesisAccount)) =>
      val mpt = MerklePatriciaTrie[Array[Byte], Account](rootHash, accountTrieNodes)
      val cleanAddress = if address.startsWith("0x") || address.startsWith("0X") then address.substring(2) else address
      val paddedAddress = cleanAddress.reverse.padTo(AddressLength, "0").reverse.mkString
      val accountHash = crypto.kec256(Hex.decode(paddedAddress))
      mpt
        .put(
          accountHash,
          Account(
            nonce = genesisAccount.nonce.getOrElse(blockchainConfig.accountStartNonce),
            balance = genesisAccount.balance,
            codeHash = genesisAccount.code.fold(Account.EmptyCodeHash)(codeValue => CodeHash(crypto.kec256(codeValue))),
            storageRoot = genesisAccount.storage.fold(Account.EmptyStorageRootHash)(slots =>
              TrieRoot(pureStorageRoot(slots, storage.forOwner(Some(ByteString(accountHash)))))
            )
          )
        )
        .getRootHash
    }

  private def pureStorageRoot(slots: Map[UInt256, UInt256], storageTrieNodes: MptStorage): ByteString =
    val emptyTrie = EthereumUInt256Mpt.storageMpt(ByteString(MerklePatriciaTrie.EmptyRootHash), storageTrieNodes)
    val storageTrie = slots.foldLeft(emptyTrie) {
      case (trie, (_, UInt256.Zero)) => trie
      case (trie, (key, value))      => trie.put(key, value)
    }
    ByteString(storageTrie.getRootHash)

  /** The header of the genesis block over the state whose root is `stateMptRootHash`: the logic of the loader's
    * `prepareHeader`, which delegates here, and of [[genesisHeader]].
    */
  private[data] def headerOver(genesisData: GenesisData, stateMptRootHash: Array[Byte])(using
      blockchainConfig: BlockchainConfig
  ): BlockHeader =
    // Determine the fork era for the genesis block based on the genesis timestamp (0)
    val genesisTimestamp = Timestamp(BigInt(genesisData.timestamp.replace("0x", ""), 16).toLong)
    val baseFee = genesisData.baseFeePerGas
      .map(s => BigInt(s.replace("0x", ""), 16))
      .getOrElse(BigInt("1000000000")) // EIP-1559 default: 1 Gwei
    // Empty trie root = keccak256(RLP("")) = keccak256(0x80) — NOT keccak of empty list
    val emptyWithdrawalsRoot = ByteString(crypto.kec256(rlp.encode(RLPValue(Array.empty[Byte]))))

    // EIP-7685: sha256 of the empty request list, the genesis requestsHash from Prague on.
    lazy val emptyRequestsHash =
      ByteString(java.security.MessageDigest.getInstance("SHA-256").digest(Array.empty[Byte]))

    // Amsterdam active at the genesis timestamp (go-ethereum core/genesis.go `IsAmsterdam`; every EEST
    // `for_amsterdam` fixture): the Prague fields plus the 23-field header's two. `blockAccessListHash` commits
    // to the EMPTY access list, since genesis executes nothing (keccak256(0xc0), BlockAccessList.EmptyHash);
    // `slotNumber` is the genesis file's, 0 when absent. Built with the Prague shape instead, the genesis hash
    // was wrong. Chains that activate Amsterdam later (Sepolia, Platåberget, hive's devp2p fixture) and every
    // ETC chain never enter this branch.
    val extraFields =
      if blockchainConfig.isAmsterdamTimestamp(genesisTimestamp) then
        BlockHeader.HeaderExtraFields.HefPostAmsterdam(
          baseFee,
          emptyWithdrawalsRoot,
          parseOptQuantity(genesisData.blobGasUsed),
          parseOptQuantity(genesisData.excessBlobGas),
          zeros(hashLength),
          emptyRequestsHash,
          BlockAccessList.EmptyHash,
          parseOptQuantity(genesisData.slotNumber)
        )
      else if blockchainConfig.isPragueTimestamp(genesisTimestamp) then
        BlockHeader.HeaderExtraFields.HefPostPrague(
          baseFee,
          emptyWithdrawalsRoot,
          parseOptQuantity(genesisData.blobGasUsed),
          parseOptQuantity(genesisData.excessBlobGas),
          zeros(hashLength),
          emptyRequestsHash
        )
      else if blockchainConfig.isCancunTimestamp(genesisTimestamp) then
        BlockHeader.HeaderExtraFields.HefPostCancun(
          baseFee,
          emptyWithdrawalsRoot,
          parseOptQuantity(genesisData.blobGasUsed),
          parseOptQuantity(genesisData.excessBlobGas),
          zeros(hashLength)
        )
      else if blockchainConfig.isShanghaiTimestamp(genesisTimestamp) then
        BlockHeader.HeaderExtraFields.HefPostShanghai(baseFee, emptyWithdrawalsRoot)
      else if blockchainConfig.forkBlockNumbers.olympiaBlockNumber == 0 then
        BlockHeader.HeaderExtraFields.HefPostOlympia(baseFee)
      else BlockHeader.HeaderExtraFields.HefEmpty

    BlockHeader(
      parentHash = BlockHash(zeros(hashLength)),
      ommersHash = BlockHash(ByteString(crypto.kec256(rlp.encode(RLPList())))),
      beneficiary = genesisData.coinbase,
      stateRoot = TrieRoot(ByteString(stateMptRootHash)),
      transactionsRoot = TrieRoot(emptyTrieRootHash),
      receiptsRoot = TrieRoot(emptyTrieRootHash),
      logsBloom = BloomFilter(zeros(bloomLength)),
      difficulty = Difficulty(BigInt(genesisData.difficulty.replace("0x", ""), 16)),
      number = BlockNumber.Zero,
      gasLimit = GasAmount(BigInt(genesisData.gasLimit.replace("0x", ""), 16)),
      gasUsed = GasAmount.Zero,
      unixTimestamp = Timestamp(BigInt(genesisData.timestamp.replace("0x", ""), 16).toLong),
      extraData = genesisData.extraData,
      mixHash = BlockHash(genesisData.mixHash.getOrElse(zeros(hashLength))),
      nonce = padToEightBytes(genesisData.nonce),
      extraFields = extraFields
    )

  /** Ethereum block header nonce is always 8 bytes (uint64). Pad short nonces with leading zeros. */
  private def padToEightBytes(bs: ByteString): ByteString =
    if bs.length >= 8 then bs
    else ByteString(new Array[Byte](8 - bs.length) ++ bs.toArray)

  /** Parse an optional hex-prefixed quantity (per eth_getBlockByNumber spec). */
  private def parseOptQuantity(v: Option[String]): BigInt = v match
    case Some(s) =>
      val stripped = s.replace("0x", "")
      if stripped.isEmpty then BigInt(0) else BigInt(stripped, 16)
    case None => BigInt(0)

  private def zeros(length: Int) =
    ByteString(Hex.decode(List.fill(length)("0").mkString))

  /** The loader of a node's storages: on the hash store, or on the unified layout when the storages hold it. */
  def over(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      storages: com.chipprbots.ethereum.domain.BlockchainStorages
  ): GenesisDataLoader =
    new GenesisDataLoader(
      blockchainReader,
      blockchainWriter,
      storages.evmCodeStorage,
      storages.stateStorage,
      // Who created the datadir, and, when the genesis load counted every position (#1692), that its counts are exact:
      // one synced write. On the unified layout the same marks go in the genesis commit's own batch.
      countsExact => storages.appStateStorage.markDatadirCreated(countsExact).commitSync(),
      storages.pathStateStorage,
      () => storages.appStateStorage.getStateLayout()
    )

  /** Why this build may not open the datadir, by its layout stamp (spec 018 data-model 5.3); `None` when it may.
    *
    * The stamp is written with the genesis of a datadir the unified layout creates, and by nothing else. So a datadir
    * with a genesis and no stamp was written by the hash layout, and one with a stamp by the unified layout. Neither
    * can be read as the other: there is no adoption and no migration. The check is here while the in-development guard
    * exists, since this is the first thing that writes the stamp; the node's own guard at start is C5a's.
    */
  def layoutRefusal(pathLive: Boolean, stamp: Option[String], genesisStored: Boolean): Option[String] =
    (pathLive, stamp) match
      case (false, Some(found)) =>
        Some(
          s"This data directory holds its state in the unified layout (state.layout = '$found'), and " +
            "fukuii.state.path-live is off: this build would read a state store that holds nothing of it. Turn " +
            "fukuii.state.path-live on, or use another data directory."
        )
      case (true, Some(StateLayout.PathLive1)) => None
      case (true, Some(other)) =>
        Some(s"This data directory was written by a state layout this build does not know (state.layout = '$other').")
      case (true, None) if genesisStored =>
        Some(
          "This data directory was written by an earlier storage layout and cannot be opened with " +
            "fukuii.state.path-live on. Stop the node, move or delete the rocksdb directory under the data " +
            "directory, and start again."
        )
      case _ => None

  /** The hex digits of an address, as an allocation's key is padded to. */
  val AddressLength: Int = 40

  /** What a genesis may not have on the unified layout; `None` when it has none of it.
    *
    *   - '''One address twice.''' The allocation is keyed by the address as it is written, so `0xAB…` and `ab…` are two
    *     entries for one account. On the hash store the second overwrites the first and the first's storage is left as
    *     unreachable nodes. Here the first's storage would be rows under an owner whose account names none, which
    *     nothing would ever delete, or a second trie built over the first.
    *   - '''Storage on a chain whose storage keys are not hashed the Ethereum way.''' The genesis storage tries are
    *     always built that way, and execution on such a chain builds them another, so the rows would be at positions
    *     execution never looks at. No shipped chain is one.
    */
  def unifiedLayoutRefusal(genesisData: GenesisData, addressLength: Int)(implicit
      blockchainConfig: BlockchainConfig
  ): Option[String] =
    def normalised(address: String): String =
      val clean = if address.startsWith("0x") || address.startsWith("0X") then address.substring(2) else address
      clean.reverse.padTo(addressLength, "0").reverse.mkString.toLowerCase
    val twice = genesisData.alloc.keys.toSeq.groupBy(normalised).collectFirst {
      case (address, written) if written.sizeIs > 1 => address
    }
    twice
      .map(address =>
        s"The genesis allocates the account 0x$address more than once (the same address written in different ways). " +
          "Give each account one entry."
      )
      .orElse(
        Option.when(!blockchainConfig.ethCompatibleStorage && genesisData.alloc.values.exists(hasStorage))(
          "The genesis has accounts with storage, and this chain's storage is not Ethereum-compatible " +
            "(eth-compatible-storage = false): genesis storage cannot be placed on the unified state layout for " +
            "such a chain."
        )
      )

  /** Whether every reference count a genesis load writes is exact (#1692). The tries are built into one buffer that
    * keeps each distinct node once (see [[AppendOnlyMptStorage]]), and `persist()` writes each at one reference. That
    * is exact for the account trie, where no two positions hold the same node, and for the storage trie of one account.
    * Two accounts that both have storage can share a subtree (identical storage, or a shared leaf: EEST
    * `stEIP2930/variedContext`), and a node two positions use is then counted once. So the datadir may be stamped as
    * having exact counts only while at most one account has storage. A slot of zero is not stored, and does not count.
    * The shipped `default-genesis.json` has no storage at all; `custom-genesis-file` is the operator's.
    */
  def countsExact(genesisData: GenesisData): Boolean =
    genesisData.alloc.valuesIterator.count(hasStorage) <= 1

  private def hasStorage(account: GenesisAccount): Boolean =
    account.storage.exists(_.valuesIterator.exists(_ != UInt256.Zero))

  /** Genesis tries are built into ONE shared, content-addressed node buffer (`ReadOnlyNodeStorage`), and every MPT
    * `put` reports the nodes it replaced as removals — which that buffer applies with `buffer -= hash`. Two accounts
    * whose storage tries share a node therefore corrupt each other: EEST `stEIP2930/variedContext` gives 0x..f114
    * storage {0: 0x0bad} (a single leaf, which is its root) and 0x..f115 {0: 0x0bad, 0x60a7: 0xdead}; building f115's
    * trie creates that same leaf, then replaces it and "removes" it, deleting f114's storage root before `persist()`.
    * The genesis state root is still right (it is computed, not read back), so the genesis hash matched and the first
    * block that wrote f114's storage failed with MissingStorageNodeException -> newPayload `SYNCING`/ACCEPTED.
    *
    * Nothing a genesis build replaces can be garbage that must be deleted — the buffer's removals are never persisted
    * anyway (`ReadOnlyNodeStorage.persist` only sees upserts) — so dropping removals here is exact.
    */
  final private[data] case class AppendOnlyMptStorage(underlying: MptStorage) extends MptStorageDecorator:
    override protected def decorate(scoped: MptStorage): MptStorage = AppendOnlyMptStorage(scoped)
    override protected def update(
        newRoot: Option[com.chipprbots.ethereum.mpt.MptNode],
        removals: MptStorageDecorator.Removals
    ): Option[com.chipprbots.ethereum.mpt.MptNode] = super.update(newRoot, removals.none)
  object JsonSerializers:

    def deserializeByteString(jv: JValue): ByteString = jv match
      case JString(s) =>
        val noPrefix = s.replace("0x", "")
        val inp =
          if noPrefix.length % 2 == 0 then noPrefix
          else "0" ++ noPrefix
        Try(ByteString(Hex.decode(inp))) match
          case Success(bs) => bs
          case Failure(_)  => throw new RuntimeException("Cannot parse hex string: " + s)
      case other => throw new RuntimeException("Expected hex string, but got: " + other)

    object ByteStringJsonSerializer
        extends CustomSerializer[ByteString](_ =>
          (
            { case jv => deserializeByteString(jv) },
            PartialFunction.empty
          )
        )

    def deserializeUint256String(jv: JValue): UInt256 = jv match
      case JString(s) =>
        val parsed =
          if s.startsWith("0x") || s.startsWith("0X") then Try(UInt256(BigInt(s.substring(2), 16)))
          else Try(UInt256(BigInt(s)))
        parsed match
          case Failure(_)     => throw new RuntimeException("Cannot parse numeric string: " + s)
          case Success(value) => value
      case other => throw new RuntimeException("Expected hex string, but got: " + other)

    object UInt256JsonSerializer
        extends CustomSerializer[UInt256](_ => ({ case jv => deserializeUint256String(jv) }, PartialFunction.empty))

    private def parseStorageMap(jv: JValue): Option[Map[UInt256, UInt256]] = jv match
      case JObject(fields) if fields.nonEmpty =>
        Some(fields.map { case (key, value) =>
          deserializeUint256String(JString(key)) -> deserializeUint256String(value)
        }.toMap)
      case _ => None

    object GenesisAccountSerializer
        extends CustomSerializer[GenesisAccount](_ =>
          (
            { case JObject(fields) =>
              val map = fields.toMap
              GenesisAccount(
                precompiled = None,
                balance = map.get("balance").map(deserializeUint256String).getOrElse(UInt256.Zero),
                code = map.get("code").map(deserializeByteString),
                nonce = map.get("nonce").map(deserializeUint256String),
                storage = map.get("storage").flatMap(parseStorageMap)
              )
            },
            PartialFunction.empty
          )
        )

object Implicits extends JsonMethodsImplicits
