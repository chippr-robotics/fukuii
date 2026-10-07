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
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.StateStorage.GenesisDataLoad
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
    stateStorage: StateStorage
) extends Logger:

  private val bloomLength = 512
  private val hashLength = 64
  private val addressLength = 40

  import Account.*

  private val emptyTrieRootHash = ByteString(crypto.kec256(rlp.encode(Array.empty[Byte])))

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
    import org.json4s.native.JsonMethods.parse
    import GenesisDataLoader.JsonSerializers.GenesisAccountSerializer
    given formats: Formats =
      DefaultFormats + ByteStringJsonSerializer + UInt256JsonSerializer + GenesisAccountSerializer
    for
      genesisData <- Try(Extraction.extract[GenesisData](parse(genesisJson)))
      _ <- loadGenesisData(genesisData)
    yield ()

  def loadGenesisData(genesisData: GenesisData)(implicit blockchainConfig: BlockchainConfig): Try[Unit] =

    val storage = GenesisDataLoader.AppendOnlyMptStorage(stateStorage.getReadOnlyStorage)
    val initalRootHash = MerklePatriciaTrie.EmptyRootHash

    val stateMptRootHash = getGenesisStateRoot(genesisData, initalRootHash, storage)
    val header: BlockHeader = prepareHeader(genesisData, stateMptRootHash)

    log.debug(s"Prepared genesis header: $header")

    blockchainReader.getBlockHeaderByNumber(0) match
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
      case None =>
        storage.persist()
        stateStorage.forcePersist(GenesisDataLoad)
        blockchainWriter.save(
          Block(header, BlockBody(Nil, Nil)),
          Nil,
          ChainWeight.totalDifficultyOnly(header.difficulty.value),
          saveAsBestBlock = true
        )
        Success(())

  private def getGenesisStateRoot(genesisData: GenesisData, initalRootHash: Array[Byte], storage: MptStorage)(implicit
      blockchainConfig: BlockchainConfig
  ) =
    import MerklePatriciaTrie.defaultByteArraySerializable

    genesisData.alloc.zipWithIndex.foldLeft(initalRootHash) { case (rootHash, ((address, genesisAccount), _)) =>
      val mpt = MerklePatriciaTrie[Array[Byte], Account](rootHash, storage)
      val cleanAddress = if address.startsWith("0x") || address.startsWith("0X") then address.substring(2) else address
      val paddedAddress = cleanAddress.reverse.padTo(addressLength, "0").reverse.mkString

      // Store contract code in EVM code storage if present
      genesisAccount.code.foreach { code =>
        val codeHash = ByteString(crypto.kec256(code))
        evmCodeStorage.put(codeHash, code).commit()
      }

      val stateRoot = mpt
        .put(
          crypto.kec256(Hex.decode(paddedAddress)),
          Account(
            nonce = genesisAccount.nonce
              .getOrElse(blockchainConfig.accountStartNonce),
            balance = genesisAccount.balance,
            codeHash = genesisAccount.code.fold(Account.EmptyCodeHash)(codeValue => CodeHash(crypto.kec256(codeValue))),
            storageRoot = genesisAccount.storage.fold(Account.EmptyStorageRootHash)(s =>
              TrieRoot(computeStorageRootHash(s, storage))
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
  private def computeStorageRootHash(storage: Map[UInt256, UInt256], sharedStorage: MptStorage): ByteString =
    val emptyTrie = EthereumUInt256Mpt.storageMpt(
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      sharedStorage
    )

    val storageTrie = storage.foldLeft(emptyTrie) {
      case (trie, (_, UInt256.Zero)) => trie
      case (trie, (key, value))      => trie.put(key, value)
    }

    ByteString(storageTrie.getRootHash)

  private def prepareHeader(genesisData: GenesisData, stateMptRootHash: Array[Byte])(implicit
      blockchainConfig: BlockchainConfig
  ) =
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

object GenesisDataLoader:

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
  final private[data] case class AppendOnlyMptStorage(underlying: MptStorage) extends MptStorage:
    override def get(nodeId: Array[Byte]): com.chipprbots.ethereum.mpt.MptNode = underlying.get(nodeId)
    override def updateNodesInStorage(
        newRoot: Option[com.chipprbots.ethereum.mpt.MptNode],
        toRemove: Seq[com.chipprbots.ethereum.mpt.MptNode]
    ): Option[com.chipprbots.ethereum.mpt.MptNode] = underlying.updateNodesInStorage(newRoot, Nil)
    override def persist(): Unit = underlying.persist()
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
