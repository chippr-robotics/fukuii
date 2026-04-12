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
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.StateStorage.GenesisDataLoad
import com.chipprbots.ethereum.domain._
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
) extends Logger {

  private val bloomLength = 512
  private val hashLength = 64
  private val addressLength = 40

  import Account._

  private val emptyTrieRootHash = ByteString(crypto.kec256(rlp.encode(Array.empty[Byte])))

  def loadGenesisData()(implicit blockchainConfig: BlockchainConfig): Unit = {
    log.debug("Loading genesis data")

    val genesisJson = blockchainConfig.customGenesisJsonOpt.getOrElse {
      blockchainConfig.customGenesisFileOpt match {
        case Some(customGenesisFile) =>
          log.debug(s"Trying to load custom genesis data from file: $customGenesisFile")

          Try(Source.fromFile(customGenesisFile)).recoverWith { case _: FileNotFoundException =>
            log.debug(s"Cannot load custom genesis data from file: $customGenesisFile")
            log.debug(s"Trying to load from resources: $customGenesisFile")
            Try(Source.fromResource(customGenesisFile))
          } match {
            case Success(customGenesis) =>
              log.info(s"Using custom genesis data from: $customGenesisFile")
              try customGenesis.getLines().mkString
              finally customGenesis.close()
            case Failure(ex) =>
              log.error(s"Cannot load custom genesis data from: $customGenesisFile", ex)
              throw ex
          }
        case None =>
          log.info("Using default genesis data")
          val src = Source.fromResource("blockchain/default-genesis.json")
          try src.getLines().mkString
          finally src.close()
      }
    }

    loadGenesisData(genesisJson) match {
      case Success(_) =>
        log.info("Genesis data successfully loaded")
      case Failure(ex) =>
        log.error("Unable to load genesis data", ex)
        throw ex
    }
  }

  private def loadGenesisData(genesisJson: String)(implicit blockchainConfig: BlockchainConfig): Try[Unit] = {
    import org.json4s.native.JsonMethods.parse
    import GenesisDataLoader.JsonSerializers.GenesisAccountSerializer
    implicit val formats: Formats = DefaultFormats + ByteStringJsonSerializer + UInt256JsonSerializer + GenesisAccountSerializer
    for {
      genesisData <- Try(Extraction.extract[GenesisData](parse(genesisJson)))
      _ <- loadGenesisData(genesisData)
    } yield ()
  }

  def loadGenesisData(genesisData: GenesisData)(implicit blockchainConfig: BlockchainConfig): Try[Unit] = {

    val storage = stateStorage.getReadOnlyStorage
    val initalRootHash = MerklePatriciaTrie.EmptyRootHash

    val stateMptRootHash = getGenesisStateRoot(genesisData, initalRootHash, storage)
    val header: BlockHeader = prepareHeader(genesisData, stateMptRootHash)

    log.debug(s"Prepared genesis header: $header")

    blockchainReader.getBlockHeaderByNumber(0) match {
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
          ChainWeight.totalDifficultyOnly(header.difficulty),
          saveAsBestBlock = true
        )
        Success(())
    }
  }

  private def getGenesisStateRoot(genesisData: GenesisData, initalRootHash: Array[Byte], storage: MptStorage)(implicit
      blockchainConfig: BlockchainConfig
  ) = {
    import MerklePatriciaTrie.defaultByteArraySerializable

    genesisData.alloc.zipWithIndex.foldLeft(initalRootHash) { case (rootHash, ((address, genesisAccount), _)) =>
      val mpt = MerklePatriciaTrie[Array[Byte], Account](rootHash, storage)
      val cleanAddress = if (address.startsWith("0x") || address.startsWith("0X")) address.substring(2) else address
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
            codeHash = genesisAccount.code.fold(Account.EmptyCodeHash)(codeValue => crypto.kec256(codeValue)),
            storageRoot = genesisAccount.storage.fold(Account.EmptyStorageRootHash)(computeStorageRootHash)
          )
        )
        .getRootHash
      stateRoot
    }
  }

  private def computeStorageRootHash(storage: Map[UInt256, UInt256]): ByteString = {
    val emptyTrie = EthereumUInt256Mpt.storageMpt(
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(EphemDataSource())))
    )

    val storageTrie = storage.foldLeft(emptyTrie) {
      case (trie, (_, UInt256.Zero)) => trie
      case (trie, (key, value))      => trie.put(key, value)
    }

    ByteString(storageTrie.getRootHash)
  }

  private def prepareHeader(genesisData: GenesisData, stateMptRootHash: Array[Byte])(implicit
      blockchainConfig: BlockchainConfig
  ) = {
    // Determine the fork era for the genesis block based on the genesis timestamp (0)
    val genesisTimestamp = BigInt(genesisData.timestamp.replace("0x", ""), 16).toLong
    val baseFee = genesisData.baseFeePerGas.map(s => BigInt(s.replace("0x", ""), 16))
      .getOrElse(BigInt("1000000000")) // EIP-1559 default: 1 Gwei
    // Empty trie root = keccak256(RLP("")) = keccak256(0x80) — NOT keccak of empty list
    val emptyWithdrawalsRoot = ByteString(crypto.kec256(rlp.encode(RLPValue(Array.empty[Byte]))))

    val extraFields = if (blockchainConfig.isPragueTimestamp(genesisTimestamp)) {
      val emptyRequestsHash = ByteString(java.security.MessageDigest.getInstance("SHA-256").digest(Array.empty[Byte]))
      BlockHeader.HeaderExtraFields.HefPostPrague(baseFee, emptyWithdrawalsRoot, BigInt(0), BigInt(0),
        zeros(hashLength), emptyRequestsHash)
    } else if (blockchainConfig.isCancunTimestamp(genesisTimestamp)) {
      BlockHeader.HeaderExtraFields.HefPostCancun(baseFee, emptyWithdrawalsRoot, BigInt(0), BigInt(0), zeros(hashLength))
    } else if (blockchainConfig.isShanghaiTimestamp(genesisTimestamp)) {
      BlockHeader.HeaderExtraFields.HefPostShanghai(baseFee, emptyWithdrawalsRoot)
    } else if (blockchainConfig.forkBlockNumbers.olympiaBlockNumber == 0) {
      BlockHeader.HeaderExtraFields.HefPostOlympia(baseFee)
    } else {
      BlockHeader.HeaderExtraFields.HefEmpty
    }

    BlockHeader(
      parentHash = zeros(hashLength),
      ommersHash = ByteString(crypto.kec256(rlp.encode(RLPList()))),
      beneficiary = genesisData.coinbase,
      stateRoot = ByteString(stateMptRootHash),
      transactionsRoot = emptyTrieRootHash,
      receiptsRoot = emptyTrieRootHash,
      logsBloom = zeros(bloomLength),
      difficulty = BigInt(genesisData.difficulty.replace("0x", ""), 16),
      number = 0,
      gasLimit = BigInt(genesisData.gasLimit.replace("0x", ""), 16),
      gasUsed = 0,
      unixTimestamp = BigInt(genesisData.timestamp.replace("0x", ""), 16).toLong,
      extraData = genesisData.extraData,
      mixHash = genesisData.mixHash.getOrElse(zeros(hashLength)),
      nonce = padToEightBytes(genesisData.nonce),
      extraFields = extraFields
    )
  }

  /** Ethereum block header nonce is always 8 bytes (uint64). Pad short nonces with leading zeros. */
  private def padToEightBytes(bs: ByteString): ByteString = {
    if (bs.length >= 8) bs
    else ByteString(new Array[Byte](8 - bs.length) ++ bs.toArray)
  }

  private def zeros(length: Int) =
    ByteString(Hex.decode(List.fill(length)("0").mkString))

}

object GenesisDataLoader {
  object JsonSerializers {

    def deserializeByteString(jv: JValue): ByteString = jv match {
      case JString(s) =>
        val noPrefix = s.replace("0x", "")
        val inp =
          if (noPrefix.length % 2 == 0) noPrefix
          else "0" ++ noPrefix
        Try(ByteString(Hex.decode(inp))) match {
          case Success(bs) => bs
          case Failure(_)  => throw new RuntimeException("Cannot parse hex string: " + s)
        }
      case other => throw new RuntimeException("Expected hex string, but got: " + other)
    }

    object ByteStringJsonSerializer
        extends CustomSerializer[ByteString](_ =>
          (
            { case jv => deserializeByteString(jv) },
            PartialFunction.empty
          )
        )

    def deserializeUint256String(jv: JValue): UInt256 = jv match {
      case JString(s) =>
        val parsed = if (s.startsWith("0x") || s.startsWith("0X")) {
          Try(UInt256(BigInt(s.substring(2), 16)))
        } else {
          Try(UInt256(BigInt(s)))
        }
        parsed match {
          case Failure(_)     => throw new RuntimeException("Cannot parse numeric string: " + s)
          case Success(value) => value
        }
      case other => throw new RuntimeException("Expected hex string, but got: " + other)
    }

    object UInt256JsonSerializer
        extends CustomSerializer[UInt256](_ => ({ case jv => deserializeUint256String(jv) }, PartialFunction.empty))

    private def parseStorageMap(jv: JValue): Option[Map[UInt256, UInt256]] = jv match {
      case JObject(fields) if fields.nonEmpty =>
        Some(fields.map { case (key, value) =>
          deserializeUint256String(JString(key)) -> deserializeUint256String(value)
        }.toMap)
      case _ => None
    }

    object GenesisAccountSerializer
        extends CustomSerializer[GenesisAccount](_ =>
          (
            {
              case JObject(fields) =>
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
  }
}

object Implicits extends JsonMethodsImplicits
