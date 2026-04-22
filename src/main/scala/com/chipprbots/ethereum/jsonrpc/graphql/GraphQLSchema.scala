package com.chipprbots.ethereum.jsonrpc.graphql

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.concurrent.Future

import sangria.schema.{
  Argument,
  Field,
  InputField,
  InputObjectType,
  ListInputType,
  ListType,
  ObjectType,
  OptionInputType,
  OptionType,
  Schema,
  fields
}

import com.chipprbots.ethereum.consensus.engine.BlobGasUtils
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.{
  AccessListItem,
  Address,
  Block,
  BlockHeader,
  BlobTransaction,
  FailureOutcome,
  HashOutcome,
  LegacyTransaction,
  Receipt,
  SetCodeTransaction,
  SignedTransaction,
  SuccessOutcome,
  Transaction,
  TransactionWithAccessList,
  TransactionWithDynamicFee,
  TxLogEntry,
  UInt256,
  Withdrawal
}
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.jsonrpc.{BlockParam, EthInfoService}
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList

import com.chipprbots.ethereum.jsonrpc.graphql.GraphQLTypes._
import com.chipprbots.ethereum.jsonrpc.graphql.GraphQLScalars._

/** Sangria schema implementing EIP-1767, adapted from geth's `graphql/schema.go`.
  *
  * Resolvers delegate to the existing JSON-RPC services (EthBlocksService, EthTxService, etc.) so we do not duplicate
  * block/state access logic. See `/src/main/resources/graphql/schema.graphql` for the canonical SDL this schema
  * matches.
  */
object GraphQLSchema {

  // Guard: we don't want a caller to request billions of blocks in one query.
  val MaxBlocksPerRange: Int = 1024
  // Guard: query depth limit (matches geth/graphql/service.go:33).
  val MaxQueryDepth: Int = 20

  private implicit val ioRuntime: IORuntime = IORuntime.global

  // SignedTransaction.getSender needs an implicit BlockchainConfig (for EIP-155 chainId
  // extraction from the v signature field). We inherit from the global Config — same pattern
  // as EthTxService and TransactionResponse.
  private implicit val blockchainConfigImplicit: com.chipprbots.ethereum.utils.BlockchainConfig =
    com.chipprbots.ethereum.utils.Config.blockchains.blockchainConfig

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def rlpEncodeHeader(h: BlockHeader): ByteString =
    ByteString(rlp.encode(BlockHeaderEnc(h).toRLPEncodable))

  private def rlpEncodeBlock(b: Block): ByteString = {
    import com.chipprbots.ethereum.domain.Block.BlockEnc
    ByteString(rlp.encode(BlockEnc(b).toRLPEncodable))
  }

  private def rlpEncodeReceipt(r: Receipt): ByteString = {
    import com.chipprbots.ethereum.network.p2p.messages.ETH63.ReceiptImplicits.given
    ByteString(r.toBytes)
  }

  private def rlpEncodeTransaction(stx: SignedTransaction): ByteString = {
    import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions.SignedTransactionEnc
    ByteString(SignedTransactionEnc(stx).toBytes)
  }

  /** Compute the CREATE-contract address for a sender + nonce. Matches
    * [[com.chipprbots.ethereum.jsonrpc.TransactionReceiptResponse]]'s formula.
    */
  private def createContractAddress(sender: Address, nonce: BigInt): Address = {
    import com.chipprbots.ethereum.rlp.RLPImplicitConversions.toEncodeable
    import com.chipprbots.ethereum.rlp.RLPImplicits.byteStringEncDec
    import com.chipprbots.ethereum.rlp.UInt256RLPImplicits.UInt256Enc
    val hash = kec256(
      rlp.encode(RLPList(toEncodeable(sender.bytes), UInt256(nonce).toRLPEncodable))
    )
    Address(hash)
  }

  private def txType(tx: Transaction): Long = tx match {
    case _: LegacyTransaction          => 0L
    case _: TransactionWithAccessList  => 1L
    case _: TransactionWithDynamicFee  => 2L
    case _: BlobTransaction            => 3L
    case _: SetCodeTransaction         => 4L
  }

  private def txAccessList(tx: Transaction): Option[List[AccessListItem]] = tx match {
    case t: TransactionWithAccessList => Some(t.accessList)
    case t: TransactionWithDynamicFee => Some(t.accessList)
    case t: BlobTransaction           => Some(t.accessList)
    case t: SetCodeTransaction        => Some(t.accessList)
    case _: LegacyTransaction         => None
  }

  private def txBlobVersionedHashes(tx: Transaction): Option[List[ByteString]] = tx match {
    case t: BlobTransaction => Some(t.blobVersionedHashes)
    case _                  => None
  }

  private def txMaxFeePerGas(tx: Transaction): Option[BigInt] = tx match {
    case t: TransactionWithDynamicFee => Some(t.maxFeePerGas)
    case t: BlobTransaction           => Some(t.maxFeePerGas)
    case t: SetCodeTransaction        => Some(t.maxFeePerGas)
    case _                            => None
  }

  private def txMaxPriorityFeePerGas(tx: Transaction): Option[BigInt] = tx match {
    case t: TransactionWithDynamicFee => Some(t.maxPriorityFeePerGas)
    case t: BlobTransaction           => Some(t.maxPriorityFeePerGas)
    case t: SetCodeTransaction        => Some(t.maxPriorityFeePerGas)
    case _                            => None
  }

  private def txMaxFeePerBlobGas(tx: Transaction): Option[BigInt] = tx match {
    case t: BlobTransaction => Some(t.maxFeePerBlobGas)
    case _                  => None
  }

  private def effectiveTip(tx: Transaction, baseFee: Option[BigInt]): BigInt =
    tx match {
      case t: TransactionWithDynamicFee =>
        val bf = baseFee.getOrElse(BigInt(0))
        t.maxPriorityFeePerGas.min(t.maxFeePerGas - bf).max(BigInt(0))
      case t: BlobTransaction =>
        val bf = baseFee.getOrElse(BigInt(0))
        t.maxPriorityFeePerGas.min(t.maxFeePerGas - bf).max(BigInt(0))
      case t: SetCodeTransaction =>
        val bf = baseFee.getOrElse(BigInt(0))
        t.maxPriorityFeePerGas.min(t.maxFeePerGas - bf).max(BigInt(0))
      case other => other.gasPrice - baseFee.getOrElse(BigInt(0))
    }

  private def txStatus(r: Receipt): Long =
    r.postTransactionStateHash match {
      case SuccessOutcome  => 1L
      case FailureOutcome  => 0L
      case HashOutcome(_)  => 1L // pre-byzantium: no status field in receipt; treat state root as success
    }

  private def receiptBundle(ctx: GraphQLContext, gtx: GTransaction): Option[GReceiptBundle] =
    for {
      info <- gtx.blockInfo
      receipts <- ctx.blockchainReader.getReceiptsByHash(info.block.header.hash)
      receipt <- receipts.lift(info.txIndex)
    } yield {
      val gasUsed =
        if (info.txIndex == 0) receipt.cumulativeGasUsed
        else receipt.cumulativeGasUsed - receipts(info.txIndex - 1).cumulativeGasUsed
      val baseLogIndex = receipts.take(info.txIndex).map(_.logs.size).sum
      GReceiptBundle(info.block, info.txIndex, receipt, gasUsed, receipt.cumulativeGasUsed, baseLogIndex)
    }

  private def worldStateAt(ctx: GraphQLContext, blockNumber: BigInt): Option[InMemoryWorldStateProxy] =
    ctx.blockchainReader.getBlockByNumber(ctx.blockchainReader.getBestBranch(), blockNumber).map { b =>
      InMemoryWorldStateProxy(
        ctx.evmCodeStorage,
        ctx.blockchain.getBackingMptStorage(b.header.number),
        (n: BigInt) => ctx.blockchainReader.getBlockHeaderByNumber(n).map(_.hash),
        ctx.blockchainConfig.accountStartNonce,
        b.header.stateRoot,
        noEmptyAccounts = false,
        ethCompatibleStorage = ctx.blockchainConfig.ethCompatibleStorage
      )
    }

  private def logMatches(
      log: TxLogEntry,
      addresses: Seq[ByteString],
      topics: Seq[Seq[ByteString]]
  ): Boolean = {
    val addrOk = addresses.isEmpty || addresses.exists(_ == log.loggerAddress.bytes)
    val topicsOk = topics.isEmpty || {
      topics.zipWithIndex.forall { case (allowed, idx) =>
        allowed.isEmpty || log.logTopics.lift(idx).exists(t => allowed.exists(_ == t))
      }
    }
    addrOk && topicsOk
  }

  // Build a GBlock wrapper, fetching total difficulty if available.
  private def buildGBlock(ctx: GraphQLContext, block: Block): GBlock = {
    val td = ctx.blockchainReader.getChainWeightByHash(block.header.hash).map(_.totalDifficulty)
    GBlock(block, td)
  }

  // ---------------------------------------------------------------------------
  // Input types
  // ---------------------------------------------------------------------------

  val CallDataInputType: InputObjectType[Map[String, Any]] = InputObjectType[Map[String, Any]](
    name = "CallData",
    fields = List(
      InputField("from", OptionInputType(AddressType)),
      InputField("to", OptionInputType(AddressType)),
      InputField("gas", OptionInputType(LongType)),
      InputField("gasPrice", OptionInputType(BigIntType)),
      InputField("maxFeePerGas", OptionInputType(BigIntType)),
      InputField("maxPriorityFeePerGas", OptionInputType(BigIntType)),
      InputField("value", OptionInputType(BigIntType)),
      InputField("data", OptionInputType(BytesType))
    )
  )

  val BlockFilterCriteriaInputType: InputObjectType[Map[String, Any]] = InputObjectType[Map[String, Any]](
    name = "BlockFilterCriteria",
    fields = List(
      InputField("addresses", OptionInputType(ListInputType(AddressType))),
      InputField("topics", OptionInputType(ListInputType(ListInputType(Bytes32Type))))
    )
  )

  val FilterCriteriaInputType: InputObjectType[Map[String, Any]] = InputObjectType[Map[String, Any]](
    name = "FilterCriteria",
    fields = List(
      InputField("fromBlock", OptionInputType(LongType)),
      InputField("toBlock", OptionInputType(LongType)),
      InputField("addresses", OptionInputType(ListInputType(AddressType))),
      InputField("topics", OptionInputType(ListInputType(ListInputType(Bytes32Type))))
    )
  )

  // Common arguments — explicit result types needed for Scala 3 FromInput derivation.
  private val BlockNumberArg: Argument[Option[Long]]       = Argument("block", OptionInputType(LongType))
  private val NumberArg: Argument[Option[Long]]            = Argument("number", OptionInputType(LongType))
  private val HashArg: Argument[Option[ByteString]]        = Argument("hash", OptionInputType(Bytes32Type))
  private val AddressArg: Argument[ByteString]             = Argument("address", AddressType)
  private val SlotArg: Argument[ByteString]                = Argument("slot", Bytes32Type)
  private val IndexArg: Argument[Long]                     = Argument("index", LongType)
  private val FromArg: Argument[Option[Long]]              = Argument("from", OptionInputType(LongType))
  private val ToArg: Argument[Option[Long]]                = Argument("to", OptionInputType(LongType))
  private val CallDataArg: Argument[Map[String, Any]]      = Argument("data", CallDataInputType)
  private val BlockFilterArg: Argument[Map[String, Any]]   = Argument("filter", BlockFilterCriteriaInputType)
  private val FilterArg: Argument[Map[String, Any]]        = Argument("filter", FilterCriteriaInputType)
  private val TxHashArg: Argument[ByteString]              = Argument("hash", Bytes32Type)
  private val RawDataArg: Argument[ByteString]             = Argument("data", BytesType)

  // Convert a CallData input map to EthInfoService.CallTx.
  private def toCallTx(m: Map[String, Any]): EthInfoService.CallTx = {
    val from     = m.get("from").flatMap(asOption[ByteString])
    val to       = m.get("to").flatMap(asOption[ByteString])
    val gas      = m.get("gas").flatMap(asOption[Long]).map(BigInt(_))
    val gasPrice = m.get("gasPrice").flatMap(asOption[BigInt]).getOrElse(BigInt(0))
    val maxFee   = m.get("maxFeePerGas").flatMap(asOption[BigInt])
    val value    = m.get("value").flatMap(asOption[BigInt]).getOrElse(BigInt(0))
    val data     = m.get("data").flatMap(asOption[ByteString]).getOrElse(ByteString.empty)
    // If dynamic fee fields are present but no legacy gasPrice, synthesise the legacy field to
    // maxFeePerGas so the existing stxLedger.simulateTransaction path can run unchanged.
    val effectiveGasPrice = if (m.get("gasPrice").flatMap(asOption[BigInt]).isDefined) gasPrice else maxFee.getOrElse(gasPrice)
    EthInfoService.CallTx(
      from = from,
      to = to,
      gas = gas,
      gasPrice = effectiveGasPrice,
      value = value,
      data = data,
      gasPriceExplicit = m.get("gasPrice").flatMap(asOption[BigInt]).isDefined
    )
  }

  private def asOption[A](v: Any): Option[A] = v match {
    case null       => None
    case None       => None
    case Some(x)    => Some(x.asInstanceOf[A])
    case other      => Some(other.asInstanceOf[A])
  }

  // ---------------------------------------------------------------------------
  // CallResult / AccessTuple / Withdrawal
  // ---------------------------------------------------------------------------

  val CallResultType: ObjectType[GraphQLContext, GCallResult] = ObjectType(
    "CallResult",
    fields[GraphQLContext, GCallResult](
      Field("data", BytesType, resolve = _.value.data),
      Field("gasUsed", LongType, resolve = _.value.gasUsed),
      Field("status", LongType, resolve = _.value.status)
    )
  )

  val AccessTupleType: ObjectType[GraphQLContext, AccessListItem] = ObjectType(
    "AccessTuple",
    fields[GraphQLContext, AccessListItem](
      Field("address", AddressType, resolve = _.value.address.bytes),
      Field(
        "storageKeys",
        ListType(Bytes32Type),
        resolve = _.value.storageKeys.map { n =>
          // Left-pad BigInt key to 32 bytes.
          val bytes = com.chipprbots.ethereum.utils.ByteUtils.bigIntToUnsignedByteArray(n)
          val padded =
            if (bytes.length >= 32) bytes.takeRight(32)
            else Array.fill[Byte](32 - bytes.length)(0) ++ bytes
          ByteString(padded)
        }
      )
    )
  )

  val WithdrawalType: ObjectType[GraphQLContext, Withdrawal] = ObjectType(
    "Withdrawal",
    fields[GraphQLContext, Withdrawal](
      Field("index", LongType, resolve = _.value.index.toLong),
      Field("validator", LongType, resolve = _.value.validatorIndex.toLong),
      Field("address", AddressType, resolve = _.value.address.bytes),
      Field("amount", BigIntType, resolve = _.value.amount)
    )
  )

  val SyncStateType: ObjectType[GraphQLContext, GSyncState] = ObjectType(
    "SyncState",
    fields[GraphQLContext, GSyncState](
      Field("startingBlock", LongType, resolve = _.value.startingBlock),
      Field("currentBlock", LongType, resolve = _.value.currentBlock),
      Field("highestBlock", LongType, resolve = _.value.highestBlock)
    )
  )

  // ---------------------------------------------------------------------------
  // Account
  // ---------------------------------------------------------------------------

  lazy val AccountType: ObjectType[GraphQLContext, GAccount] = ObjectType(
    "Account",
    () =>
      fields[GraphQLContext, GAccount](
        Field("address", AddressType, resolve = _.value.address),
        Field(
          "balance",
          BigIntType,
          resolve = c =>
            resolveAccount(c.ctx, c.value.address, c.value.blockNumber).map(_.balance.toBigInt).getOrElse(BigInt(0))
        ),
        Field(
          "transactionCount",
          LongType,
          resolve = c =>
            resolveAccount(c.ctx, c.value.address, c.value.blockNumber)
              .map(_.nonce.toBigInt.toLong)
              .getOrElse(0L)
        ),
        Field(
          "code",
          BytesType,
          resolve = { c =>
            worldStateAt(c.ctx, c.value.blockNumber)
              .map(_.getCode(Address(c.value.address)))
              .getOrElse(ByteString.empty)
          }
        ),
        Field(
          "storage",
          Bytes32Type,
          arguments = List(SlotArg),
          resolve = { c =>
            val slotBytes = c.arg(SlotArg)
            val slotBigInt = BigInt(1, slotBytes.toArray[Byte])
            resolveAccount(c.ctx, c.value.address, c.value.blockNumber) match {
              case Some(acct) =>
                val v = c.ctx.blockchain.getAccountStorageAt(
                  acct.storageRoot,
                  slotBigInt,
                  c.ctx.blockchainConfig.ethCompatibleStorage
                )
                // EIP-1767: Bytes32 — pad to 32 bytes.
                val arr = v.toArray[Byte]
                val padded =
                  if (arr.length >= 32) arr.takeRight(32)
                  else Array.fill[Byte](32 - arr.length)(0) ++ arr
                ByteString(padded)
              case None =>
                ByteString(new Array[Byte](32))
            }
          }
        )
      )
  )

  private def resolveAccount(
      ctx: GraphQLContext,
      address: ByteString,
      blockNumber: BigInt
  ): Option[com.chipprbots.ethereum.domain.Account] =
    try
      ctx.blockchainReader
        .getAccount(ctx.blockchainReader.getBestBranch(), Address(address), blockNumber)
    catch {
      case _: MissingNodeException => None
    }

  // ---------------------------------------------------------------------------
  // Log
  // ---------------------------------------------------------------------------

  lazy val LogType: ObjectType[GraphQLContext, GLog] = ObjectType(
    "Log",
    () =>
      fields[GraphQLContext, GLog](
        Field("index", LongType, resolve = _.value.logIndex.toLong),
        Field(
          "account",
          AccountType,
          arguments = List(BlockNumberArg),
          resolve = { c =>
            val blockNum = c.arg(BlockNumberArg) match {
              case Some(n) => BigInt(n)
              case None =>
                c.value.parent.blockInfo.map(_.block.header.number).getOrElse(c.ctx.blockchainReader.getBestBlockNumber())
            }
            GAccount(c.value.log.loggerAddress.bytes, blockNum)
          }
        ),
        Field("topics", ListType(Bytes32Type), resolve = _.value.log.logTopics),
        Field("data", BytesType, resolve = _.value.log.data),
        Field("transaction", TransactionType, resolve = _.value.parent)
      )
  )

  // ---------------------------------------------------------------------------
  // Transaction
  // ---------------------------------------------------------------------------

  lazy val TransactionType: ObjectType[GraphQLContext, GTransaction] = ObjectType(
    "Transaction",
    () =>
      fields[GraphQLContext, GTransaction](
        Field("hash", Bytes32Type, resolve = _.value.stx.hash),
        Field("nonce", LongType, resolve = _.value.stx.tx.nonce.toLong),
        Field("index", OptionType(LongType), resolve = _.value.blockInfo.map(_.txIndex.toLong)),
        Field(
          "from",
          AccountType,
          arguments = List(BlockNumberArg),
          resolve = { c =>
            val blockNum = c.arg(BlockNumberArg) match {
              case Some(n) => BigInt(n)
              case None =>
                c.value.blockInfo.map(_.block.header.number).getOrElse(c.ctx.blockchainReader.getBestBlockNumber())
            }
            val sender = SignedTransaction.getSender(c.value.stx).getOrElse(Address(0))
            GAccount(sender.bytes, blockNum)
          }
        ),
        Field(
          "to",
          OptionType(AccountType),
          arguments = List(BlockNumberArg),
          resolve = { c =>
            c.value.stx.tx.receivingAddress.map { addr =>
              val blockNum = c.arg(BlockNumberArg) match {
                case Some(n) => BigInt(n)
                case None =>
                  c.value.blockInfo
                    .map(_.block.header.number)
                    .getOrElse(c.ctx.blockchainReader.getBestBlockNumber())
              }
              GAccount(addr.bytes, blockNum)
            }
          }
        ),
        Field("value", BigIntType, resolve = _.value.stx.tx.value),
        Field("gasPrice", BigIntType, resolve = _.value.stx.tx.gasPrice),
        Field("maxFeePerGas", OptionType(BigIntType), resolve = c => txMaxFeePerGas(c.value.stx.tx)),
        Field("maxPriorityFeePerGas", OptionType(BigIntType), resolve = c => txMaxPriorityFeePerGas(c.value.stx.tx)),
        Field("maxFeePerBlobGas", OptionType(BigIntType), resolve = c => txMaxFeePerBlobGas(c.value.stx.tx)),
        Field(
          "effectiveTip",
          OptionType(BigIntType),
          resolve = c => c.value.blockInfo.map(bi => effectiveTip(c.value.stx.tx, bi.block.header.baseFee))
        ),
        Field("gas", LongType, resolve = _.value.stx.tx.gasLimit.toLong),
        Field("inputData", BytesType, resolve = _.value.stx.tx.payload),
        Field("block", OptionType(BlockType), resolve = c => c.value.blockInfo.map(bi => buildGBlock(c.ctx, bi.block))),
        Field(
          "status",
          OptionType(LongType),
          resolve = c => receiptBundle(c.ctx, c.value).map(rb => txStatus(rb.receipt))
        ),
        Field(
          "gasUsed",
          OptionType(LongType),
          resolve = c => receiptBundle(c.ctx, c.value).map(_.gasUsedByTx.toLong)
        ),
        Field(
          "cumulativeGasUsed",
          OptionType(LongType),
          resolve = c => receiptBundle(c.ctx, c.value).map(_.cumulativeGasUsed.toLong)
        ),
        Field(
          "effectiveGasPrice",
          OptionType(BigIntType),
          resolve = c =>
            c.value.blockInfo.map(bi => Transaction.effectiveGasPrice(c.value.stx.tx, bi.block.header.baseFee))
        ),
        Field(
          "blobGasUsed",
          OptionType(LongType),
          resolve = c =>
            c.value.stx.tx match {
              case bt: BlobTransaction =>
                Some((BigInt(bt.blobVersionedHashes.size) * BlobGasUtils.GAS_PER_BLOB).toLong)
              case _ => None
            }
        ),
        Field(
          "blobGasPrice",
          OptionType(BigIntType),
          resolve = c =>
            for {
              bi  <- c.value.blockInfo
              ebg <- bi.block.header.excessBlobGas
            } yield BlobGasUtils.getBlobGasPrice(ebg, bi.block.header.unixTimestamp, c.ctx.blockchainConfig)
        ),
        Field(
          "createdContract",
          OptionType(AccountType),
          arguments = List(BlockNumberArg),
          resolve = { c =>
            c.value.blockInfo.flatMap { bi =>
              if (c.value.stx.tx.isContractInit) {
                SignedTransaction.getSender(c.value.stx).map { sender =>
                  val createdAddress = createContractAddress(sender, c.value.stx.tx.nonce)
                  val blockNum = c.arg(BlockNumberArg).map(BigInt(_)).getOrElse(bi.block.header.number)
                  GAccount(createdAddress.bytes, blockNum)
                }
              } else None
            }
          }
        ),
        Field(
          "logs",
          OptionType(ListType(LogType)),
          resolve = { c =>
            receiptBundle(c.ctx, c.value).map { rb =>
              rb.receipt.logs.zipWithIndex.map { case (log, i) =>
                GLog(c.value, rb.baseLogIndex + i, log)
              }
            }
          }
        ),
        Field("r", BigIntType, resolve = _.value.stx.signature.r),
        Field("s", BigIntType, resolve = _.value.stx.signature.s),
        Field("v", BigIntType, resolve = _.value.stx.signature.v),
        Field("type", OptionType(LongType), resolve = c => Some(txType(c.value.stx.tx))),
        Field("accessList", OptionType(ListType(AccessTupleType)), resolve = c => txAccessList(c.value.stx.tx)),
        Field("raw", BytesType, resolve = c => rlpEncodeTransaction(c.value.stx)),
        Field(
          "rawReceipt",
          BytesType,
          resolve = c => receiptBundle(c.ctx, c.value).map(rb => rlpEncodeReceipt(rb.receipt)).getOrElse(ByteString.empty)
        ),
        Field(
          "blobVersionedHashes",
          OptionType(ListType(Bytes32Type)),
          resolve = c => txBlobVersionedHashes(c.value.stx.tx)
        )
      )
  )

  // ---------------------------------------------------------------------------
  // Block
  // ---------------------------------------------------------------------------

  lazy val BlockType: ObjectType[GraphQLContext, GBlock] = ObjectType(
    "Block",
    () =>
      fields[GraphQLContext, GBlock](
        Field("number", LongType, resolve = _.value.number.toLong),
        Field("hash", Bytes32Type, resolve = _.value.hash),
        Field(
          "parent",
          OptionType(BlockType),
          resolve = c =>
            c.ctx.blockchainReader.getBlockByHash(c.value.header.parentHash).map(b => buildGBlock(c.ctx, b))
        ),
        Field("nonce", BytesType, resolve = _.value.header.nonce),
        Field("transactionsRoot", Bytes32Type, resolve = _.value.header.transactionsRoot),
        Field("transactionCount", OptionType(LongType), resolve = c => Some(c.value.block.body.transactionList.size.toLong)),
        Field("stateRoot", Bytes32Type, resolve = _.value.header.stateRoot),
        Field("receiptsRoot", Bytes32Type, resolve = _.value.header.receiptsRoot),
        Field(
          "miner",
          AccountType,
          arguments = List(BlockNumberArg),
          resolve = c => GAccount(c.value.header.beneficiary, c.arg(BlockNumberArg).map(BigInt(_)).getOrElse(c.value.number))
        ),
        Field("extraData", BytesType, resolve = _.value.header.extraData),
        Field("gasLimit", LongType, resolve = _.value.header.gasLimit.toLong),
        Field("gasUsed", LongType, resolve = _.value.header.gasUsed.toLong),
        Field("baseFeePerGas", OptionType(BigIntType), resolve = _.value.header.baseFee),
        Field(
          "nextBaseFeePerGas",
          OptionType(BigIntType),
          resolve = { c =>
            c.value.header.baseFee.map { _ =>
              // Best-effort: fetch next block's baseFee if known; otherwise use current.
              c.ctx.blockchainReader
                .getBlockHeaderByNumber(c.value.number + 1)
                .flatMap(_.baseFee)
                .orElse(c.value.header.baseFee)
                .get
            }
          }
        ),
        Field("timestamp", LongType, resolve = _.value.header.unixTimestamp),
        Field("logsBloom", BytesType, resolve = _.value.header.logsBloom),
        Field("mixHash", Bytes32Type, resolve = _.value.header.mixHash),
        Field("difficulty", BigIntType, resolve = _.value.header.difficulty),
        Field("totalDifficulty", BigIntType, resolve = c => c.value.totalDifficulty.getOrElse(c.value.header.difficulty)),
        Field(
          "ommerCount",
          OptionType(LongType),
          resolve = c => Some(c.value.block.body.uncleNodesList.size.toLong)
        ),
        Field(
          "ommers",
          OptionType(ListType(OptionType(BlockType))),
          resolve = { c =>
            Some(c.value.block.body.uncleNodesList.map { uncleHeader =>
              // Uncle blocks don't have a body stored; wrap the header in a Block with an empty body.
              val emptyBody = com.chipprbots.ethereum.domain.BlockBody.empty
              Some(GBlock(Block(uncleHeader, emptyBody), None))
            })
          }
        ),
        Field(
          "ommerAt",
          OptionType(BlockType),
          arguments = List(IndexArg),
          resolve = { c =>
            val idx = c.arg(IndexArg).toInt
            val uncles = c.value.block.body.uncleNodesList
            if (idx >= 0 && idx < uncles.size) {
              val emptyBody = com.chipprbots.ethereum.domain.BlockBody.empty
              Some(GBlock(Block(uncles(idx), emptyBody), None))
            } else None
          }
        ),
        Field("ommerHash", Bytes32Type, resolve = _.value.header.ommersHash),
        Field(
          "transactions",
          OptionType(ListType(TransactionType)),
          resolve = { c =>
            Some(c.value.block.body.transactionList.zipWithIndex.map { case (stx, i) =>
              GTransaction(stx, Some(GTxBlockInfo(c.value.block, i)))
            })
          }
        ),
        Field(
          "transactionAt",
          OptionType(TransactionType),
          arguments = List(IndexArg),
          resolve = { c =>
            val idx = c.arg(IndexArg).toInt
            val txs = c.value.block.body.transactionList
            if (idx >= 0 && idx < txs.size)
              Some(GTransaction(txs(idx), Some(GTxBlockInfo(c.value.block, idx))))
            else None
          }
        ),
        Field(
          "logs",
          ListType(LogType),
          arguments = List(BlockFilterArg),
          resolve = { c =>
            val filter = c.arg(BlockFilterArg)
            val addresses: Seq[ByteString] =
              filter.get("addresses").flatMap(asOption[Vector[ByteString]]).getOrElse(Vector.empty)
            val topics: Seq[Seq[ByteString]] =
              filter
                .get("topics")
                .flatMap(asOption[Vector[Vector[ByteString]]])
                .getOrElse(Vector.empty)
                .map(_.toSeq)
            val receipts = c.ctx.blockchainReader.getReceiptsByHash(c.value.hash).getOrElse(Seq.empty)
            val txs = c.value.block.body.transactionList
            val out = scala.collection.mutable.ArrayBuffer.empty[GLog]
            var baseLogIndex = 0
            receipts.zipWithIndex.foreach { case (r, txIdx) =>
              val stxOpt = txs.lift(txIdx)
              r.logs.zipWithIndex.foreach { case (log, lIdx) =>
                if (logMatches(log, addresses, topics)) {
                  stxOpt.foreach { stx =>
                    out += GLog(
                      GTransaction(stx, Some(GTxBlockInfo(c.value.block, txIdx))),
                      baseLogIndex + lIdx,
                      log
                    )
                  }
                }
              }
              baseLogIndex += r.logs.size
            }
            out.toSeq
          }
        ),
        Field(
          "account",
          AccountType,
          arguments = List(AddressArg),
          resolve = c => GAccount(c.arg(AddressArg), c.value.number)
        ),
        Field(
          "call",
          OptionType(CallResultType),
          arguments = List(CallDataArg),
          resolve = { c =>
            val callTx = toCallTx(c.arg(CallDataArg))
            val req = EthInfoService.CallRequest(callTx, BlockParam.WithNumber(c.value.number))
            val io = c.ctx.ethInfoService.call(req)
            val estGasIo = c.ctx.ethInfoService.estimateGas(req)
            val fut: Future[Option[GCallResult]] = (for {
              callE   <- io
              gasE    <- estGasIo
            } yield {
              (callE, gasE) match {
                case (Right(resp), Right(gasResp)) =>
                  Some(GCallResult(resp.returnData, gasResp.gas.toLong, 1L))
                case (Right(resp), Left(_)) =>
                  Some(GCallResult(resp.returnData, 0L, 1L))
                case (Left(err), _) if err.code == 3 => // execution reverted
                  Some(GCallResult(ByteString.empty, 0L, 0L))
                case _ => None
              }
            }).unsafeToFuture()
            fut
          }
        ),
        Field(
          "estimateGas",
          LongType,
          arguments = List(CallDataArg),
          resolve = { c =>
            val callTx = toCallTx(c.arg(CallDataArg))
            val req = EthInfoService.CallRequest(callTx, BlockParam.WithNumber(c.value.number))
            c.ctx.ethInfoService
              .estimateGas(req)
              .map {
                case Right(r) => r.gas.toLong
                case Left(_)  => 0L
              }
              .unsafeToFuture()
          }
        ),
        Field("rawHeader", BytesType, resolve = c => rlpEncodeHeader(c.value.header)),
        Field("raw", BytesType, resolve = c => rlpEncodeBlock(c.value.block)),
        Field("withdrawalsRoot", OptionType(Bytes32Type), resolve = _.value.header.withdrawalsRoot),
        Field(
          "withdrawals",
          OptionType(ListType(WithdrawalType)),
          resolve = c => c.value.block.body.withdrawals
        ),
        Field("blobGasUsed", OptionType(LongType), resolve = _.value.header.blobGasUsed.map(_.toLong)),
        Field("excessBlobGas", OptionType(LongType), resolve = _.value.header.excessBlobGas.map(_.toLong))
      )
  )

  // ---------------------------------------------------------------------------
  // Pending
  // ---------------------------------------------------------------------------

  lazy val PendingType: ObjectType[GraphQLContext, GPending.type] = ObjectType(
    "Pending",
    () =>
      fields[GraphQLContext, GPending.type](
        Field(
          "transactionCount",
          LongType,
          resolve = c =>
            c.ctx.ethTxService
              .ethPendingTransactions(com.chipprbots.ethereum.jsonrpc.EthTxService.EthPendingTransactionsRequest())
              .map {
                case Right(resp) => resp.pendingTransactions.size.toLong
                case Left(_)     => 0L
              }
              .unsafeToFuture()
        ),
        Field(
          "transactions",
          OptionType(ListType(TransactionType)),
          resolve = c =>
            c.ctx.ethTxService
              .ethPendingTransactions(com.chipprbots.ethereum.jsonrpc.EthTxService.EthPendingTransactionsRequest())
              .map {
                case Right(resp) => Some(resp.pendingTransactions.map(p => GTransaction(p.stx.tx, None)))
                case Left(_)     => None
              }
              .unsafeToFuture()
        ),
        Field(
          "account",
          AccountType,
          arguments = List(AddressArg),
          resolve = c => GAccount(c.arg(AddressArg), c.ctx.blockchainReader.getBestBlockNumber())
        ),
        Field(
          "call",
          OptionType(CallResultType),
          arguments = List(CallDataArg),
          resolve = { c =>
            val callTx = toCallTx(c.arg(CallDataArg))
            val req    = EthInfoService.CallRequest(callTx, BlockParam.Pending)
            val fut = (for {
              callE <- c.ctx.ethInfoService.call(req)
              gasE  <- c.ctx.ethInfoService.estimateGas(req)
            } yield (callE, gasE) match {
              case (Right(resp), Right(gasResp)) => Some(GCallResult(resp.returnData, gasResp.gas.toLong, 1L))
              case (Right(resp), Left(_))        => Some(GCallResult(resp.returnData, 0L, 1L))
              case (Left(err), _) if err.code == 3 => Some(GCallResult(ByteString.empty, 0L, 0L))
              case _                             => None
            }).unsafeToFuture()
            fut
          }
        ),
        Field(
          "estimateGas",
          LongType,
          arguments = List(CallDataArg),
          resolve = { c =>
            val callTx = toCallTx(c.arg(CallDataArg))
            val req    = EthInfoService.CallRequest(callTx, BlockParam.Pending)
            c.ctx.ethInfoService
              .estimateGas(req)
              .map {
                case Right(r) => r.gas.toLong
                case Left(_)  => 0L
              }
              .unsafeToFuture()
          }
        )
      )
  )

  // ---------------------------------------------------------------------------
  // Top-level Query
  // ---------------------------------------------------------------------------

  val QueryType: ObjectType[GraphQLContext, Unit] = ObjectType(
    "Query",
    fields[GraphQLContext, Unit](
      Field(
        "block",
        OptionType(BlockType),
        arguments = List(NumberArg, HashArg),
        resolve = { c =>
          val reader = c.ctx.blockchainReader
          (c.arg(NumberArg), c.arg(HashArg)) match {
            case (Some(_), Some(_)) =>
              // Hive test 18 (wrongParams): both `number` and `hash` provided — Invalid params.
              throw GraphQLDataFetchingError.invalidParams("block")
            case (Some(n), None) =>
              val bn = BigInt(n)
              reader.getBlockByNumber(reader.getBestBranch(), bn) match {
                case Some(b) => Some(buildGBlock(c.ctx, b))
                case None    =>
                  // Hive test 17: numeric block that doesn't exist yields "Block number N was not found".
                  throw GraphQLDataFetchingError.notFound("block", s"Block number $bn was not found")
              }
            case (None, Some(h)) =>
              reader.getBlockByHash(h) match {
                case Some(b) => Some(buildGBlock(c.ctx, b))
                case None    =>
                  val hex = "0x" + h.toArray.map("%02x".format(_)).mkString
                  throw GraphQLDataFetchingError.notFound("block", s"Block hash $hex was not found")
              }
            case (None, None) =>
              reader.getBestBlock().map(b => buildGBlock(c.ctx, b))
          }
        }
      ),
      Field(
        "blocks",
        ListType(BlockType),
        arguments = List(FromArg, ToArg),
        resolve = { c =>
          val reader = c.ctx.blockchainReader
          val best = reader.getBestBlockNumber()
          val from = c.arg(FromArg).map(BigInt(_)).getOrElse(BigInt(0))
          val to   = c.arg(ToArg).map(BigInt(_)).getOrElse(best)
          // Hive test 43 (byWrongRange): `to < from` is Invalid params, not an empty list.
          if (to < from) throw GraphQLDataFetchingError.invalidParams("blocks")
          val count = (to - from + 1).min(MaxBlocksPerRange).toInt
          (0 until count).flatMap { i =>
            reader.getBlockByNumber(reader.getBestBranch(), from + i).map(b => buildGBlock(c.ctx, b))
          }
        }
      ),
      Field("pending", PendingType, resolve = _ => GPending),
      Field(
        "transaction",
        OptionType(TransactionType),
        arguments = List(TxHashArg),
        resolve = { c =>
          val hash = c.arg(TxHashArg)
          val fut = c.ctx.ethTxService
            .getTransactionByHash(com.chipprbots.ethereum.jsonrpc.EthTxService.GetTransactionByHashRequest(hash))
            .map {
              case Right(resp) =>
                resp.txResponse.flatMap { tr =>
                  (tr.blockHash, tr.transactionIndex) match {
                    case (Some(bh), Some(idx)) =>
                      c.ctx.blockchainReader.getBlockByHash(bh).flatMap { b =>
                        b.body.transactionList.lift(idx.toInt).map { stx =>
                          GTransaction(stx, Some(GTxBlockInfo(b, idx.toInt)))
                        }
                      }
                    case _ =>
                      // Pending tx — fetch raw stx
                      c.ctx.blockchainReader
                        .getBestBlock()
                        .flatMap { _ =>
                          c.ctx.ethTxService
                            .getRawTransactionByHash(
                              com.chipprbots.ethereum.jsonrpc.EthTxService.GetTransactionByHashRequest(hash)
                            )
                            .unsafeRunSync() match {
                            case Right(r) => r.transactionResponse.map(stx => GTransaction(stx, None))
                            case Left(_)  => None
                          }
                        }
                  }
                }
              case Left(_) => None
            }
            .unsafeToFuture()
          fut
        }
      ),
      Field(
        "logs",
        ListType(LogType),
        arguments = List(FilterArg),
        resolve = { c =>
          val m = c.arg(FilterArg)
          val fromBlock = m.get("fromBlock").flatMap(asOption[Long]).map(BigInt(_))
          val toBlock   = m.get("toBlock").flatMap(asOption[Long]).map(BigInt(_))
          val addrs: Seq[ByteString] =
            m.get("addresses").flatMap(asOption[Vector[ByteString]]).getOrElse(Vector.empty)
          val topics: Seq[Seq[ByteString]] =
            m.get("topics")
              .flatMap(asOption[Vector[Vector[ByteString]]])
              .getOrElse(Vector.empty)
              .map(_.toSeq)
          val reader = c.ctx.blockchainReader
          val best = reader.getBestBlockNumber()
          val from = fromBlock.getOrElse(best)
          val to = toBlock.getOrElse(best)
          val out = scala.collection.mutable.ArrayBuffer.empty[GLog]
          if (to >= from) {
            val maxBlocks = (to - from + 1).min(MaxBlocksPerRange).toInt
            (0 until maxBlocks).foreach { i =>
              reader.getBlockByNumber(reader.getBestBranch(), from + i).foreach { block =>
                val receipts = reader.getReceiptsByHash(block.header.hash).getOrElse(Seq.empty)
                val txs = block.body.transactionList
                var baseLogIndex = 0
                receipts.zipWithIndex.foreach { case (r, txIdx) =>
                  val stxOpt = txs.lift(txIdx)
                  r.logs.zipWithIndex.foreach { case (log, lIdx) =>
                    if (logMatches(log, addrs, topics)) {
                      stxOpt.foreach { stx =>
                        out += GLog(
                          GTransaction(stx, Some(GTxBlockInfo(block, txIdx))),
                          baseLogIndex + lIdx,
                          log
                        )
                      }
                    }
                  }
                  baseLogIndex += r.logs.size
                }
              }
            }
          }
          out.toSeq
        }
      ),
      Field(
        "gasPrice",
        BigIntType,
        resolve = c =>
          c.ctx.ethTxService
            .getGetGasPrice(com.chipprbots.ethereum.jsonrpc.EthTxService.GetGasPriceRequest())
            .map {
              case Right(r) => r.price
              case Left(_)  => BigInt(0)
            }
            .unsafeToFuture()
      ),
      Field(
        "maxPriorityFeePerGas",
        BigIntType,
        resolve = c =>
          c.ctx.ethBlocksService
            .maxPriorityFeePerGas(com.chipprbots.ethereum.jsonrpc.EthBlocksService.MaxPriorityFeePerGasRequest())
            .map {
              case Right(r) => r.maxPriorityFeePerGas
              case Left(_)  => BigInt(0)
            }
            .unsafeToFuture()
      ),
      Field(
        "syncing",
        OptionType(SyncStateType),
        resolve = c =>
          c.ctx.ethInfoService
            .syncing(com.chipprbots.ethereum.jsonrpc.EthInfoService.SyncingRequest())
            .map {
              case Right(resp) =>
                resp.syncStatus.map(s => GSyncState(s.startingBlock.toLong, s.currentBlock.toLong, s.highestBlock.toLong))
              case Left(_) => None
            }
            .unsafeToFuture()
      ),
      Field(
        "chainID",
        BigIntType,
        resolve = c =>
          c.ctx.ethInfoService
            .chainId(com.chipprbots.ethereum.jsonrpc.EthInfoService.ChainIdRequest())
            .map {
              case Right(r) => r.value
              case Left(_)  => BigInt(0)
            }
            .unsafeToFuture()
      )
    )
  )

  // ---------------------------------------------------------------------------
  // Top-level Mutation
  // ---------------------------------------------------------------------------

  val MutationType: ObjectType[GraphQLContext, Unit] = ObjectType(
    "Mutation",
    fields[GraphQLContext, Unit](
      Field(
        "sendRawTransaction",
        Bytes32Type,
        arguments = List(RawDataArg),
        resolve = { c =>
          val req = com.chipprbots.ethereum.jsonrpc.EthTxService.SendRawTransactionRequest(c.arg(RawDataArg))
          c.ctx.ethTxService
            .sendRawTransaction(req)
            .map {
              case Right(resp) => resp.transactionHash
              case Left(err)   => throw GraphQLUserError(err.message)
            }
            .unsafeToFuture()
        }
      )
    )
  )

  // ---------------------------------------------------------------------------
  // Schema
  // ---------------------------------------------------------------------------

  val schema: Schema[GraphQLContext, Unit] = Schema(
    query = QueryType,
    mutation = Some(MutationType),
    additionalTypes = List(AccessTupleType, WithdrawalType, SyncStateType, CallResultType)
  )
}

/** User-facing error thrown from resolvers. Sangria renders these without the stack trace and
  * exposes the message to the GraphQL client. */
final case class GraphQLUserError(msg: String) extends Exception(msg) with sangria.execution.UserFacingError

/** DataFetchingException emitted in the geth-compatible format hive testcases expect.
  *
  * The constructed `message` matches graphql-java's format: `Exception while fetching data
  * (/<path>) : <reason>`. The `errorCode` and `errorMessage` optional fields map to
  * `extensions.errorCode` / `extensions.errorMessage` per the JSON-RPC error convention. See
  * `src/test/resources/graphql-errors.md` for the expected shapes.
  */
final case class GraphQLDataFetchingError(
    fieldPath: String,
    reason: String,
    errorCode: Option[Int] = None,
    errorMessage: Option[String] = None
) extends Exception(s"Exception while fetching data (/$fieldPath) : $reason")
    with sangria.execution.UserFacingError {

  /** Convenience: the full "Exception while fetching data (...)" message. */
  def message: String = getMessage
}

object GraphQLDataFetchingError {

  /** JSON-RPC error codes that hive testcases expect to flow through `extensions.errorCode`. */
  object Codes {
    val InvalidParams: Int  = -32602
    val NonceTooLow: Int    = -32001
  }

  /** "Invalid params" — EIP-1474 / JSON-RPC -32602. Used when the query mixes incompatible args
    * (e.g. both `number` and `hash` on `block`) or when a numeric arg is out of range. */
  def invalidParams(fieldPath: String): GraphQLDataFetchingError =
    GraphQLDataFetchingError(
      fieldPath,
      reason = "Invalid params",
      errorCode = Some(Codes.InvalidParams),
      errorMessage = Some("Invalid params")
    )

  /** Object not found — plain DataFetchingException (no `errorCode`). Hive's test 15
    * (byHashInvalid) expects exactly this shape for unknown block hashes. */
  def notFound(fieldPath: String, reason: String): GraphQLDataFetchingError =
    GraphQLDataFetchingError(fieldPath, reason, errorCode = None, errorMessage = None)

  /** "Nonce too low" — EIP-1474 -32001. Used by `sendRawTransaction` when the account nonce has
    * already been consumed. */
  def nonceTooLow(fieldPath: String): GraphQLDataFetchingError =
    GraphQLDataFetchingError(
      fieldPath,
      reason = "Nonce too low",
      errorCode = Some(Codes.NonceTooLow),
      errorMessage = Some("Nonce too low")
    )
}
