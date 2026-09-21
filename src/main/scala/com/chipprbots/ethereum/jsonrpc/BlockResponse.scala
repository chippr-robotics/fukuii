package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.pow.RestrictedPoWSigner
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.utils.ByteStringUtils

/*
 * this trait has been introduced to deal with ETS requirements and discrepancies between fukuii and the spec
 * it should be considered a band-aid solution and replaced with something robust and non-intrusive
 */
trait BaseBlockResponse:
  def number: BigInt
  def hash: Option[ByteString]
  def parentHash: ByteString
  def nonce: Option[ByteString]
  def sha3Uncles: ByteString
  def logsBloom: ByteString
  def transactionsRoot: ByteString
  def stateRoot: ByteString
  def receiptsRoot: ByteString
  def miner: Option[ByteString]
  def difficulty: BigInt
  def totalDifficulty: Option[BigInt]
  def extraData: ByteString
  def size: BigInt
  def gasLimit: BigInt
  def gasUsed: BigInt
  def timestamp: BigInt
  def mixHash: ByteString
  def transactions: Either[Seq[ByteString], Seq[BaseTransactionResponse]]
  def uncles: Seq[ByteString]

//scalastyle:off method.length
case class BlockResponse(
    number: BigInt,
    hash: Option[ByteString],
    parentHash: ByteString,
    nonce: Option[ByteString],
    sha3Uncles: ByteString,
    logsBloom: ByteString,
    transactionsRoot: ByteString,
    stateRoot: ByteString,
    receiptsRoot: ByteString,
    miner: Option[ByteString],
    difficulty: BigInt,
    totalDifficulty: Option[BigInt],
    extraData: ByteString,
    size: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    timestamp: BigInt,
    mixHash: ByteString,
    transactions: Either[Seq[ByteString], Seq[TransactionResponse]],
    uncles: Seq[ByteString],
    signature: String,
    signer: String,
    baseFeePerGas: Option[BigInt],
    withdrawalsRoot: Option[ByteString],
    withdrawals: Option[Seq[Map[String, String]]],
    blobGasUsed: Option[BigInt],
    excessBlobGas: Option[BigInt],
    parentBeaconBlockRoot: Option[ByteString],
    requestsHash: Option[ByteString],
    /** Whether the JSON encoder should emit `totalDifficulty`. The factory methods below default it to true, so any
      * construction site not explicitly opting out keeps pre-existing behaviour; only the ETH-family eth_ read path
      * sets it false. No default here: the case class's synthetic apply and the explicit overloads below cannot both
      * carry default arguments. See EthBlocksJsonMethodsImplicits.
      */
    emitTotalDifficulty: Boolean
) extends BaseBlockResponse

object BlockResponse:

  val NotAvailable = "N/A"

  def apply(
      block: Block,
      weight: Option[ChainWeight] = None,
      fullTxs: Boolean = false,
      pendingBlock: Boolean = false,
      emitTotalDifficulty: Boolean = true
  ): BlockResponse =
    val transactions =
      if fullTxs then
        Right(block.body.transactionList.zipWithIndex.map { case (stx, transactionIndex) =>
          TransactionResponse(stx = stx, blockHeader = Some(block.header), transactionIndex = Some(transactionIndex))
        })
      else Left(block.body.transactionList.map(_.hash.value))

    val td = weight.map(_.totalDifficulty.value)

    val signature =
      if block.header.extraData.length >= ECDSASignature.EncodedLength then
        ECDSASignature.fromBytes(block.header.extraData.takeRight(ECDSASignature.EncodedLength))
      else None

    val signatureStr = signature.map(_.toBytes).map(ByteStringUtils.hash2string).getOrElse(NotAvailable)
    val signerStr = signature
      .flatMap(_.publicKey(RestrictedPoWSigner.hashHeaderForSigning(block.header)))
      .map(ByteStringUtils.hash2string)
      .getOrElse(NotAvailable)

    val withdrawals = block.body.withdrawals.map(_.map { w =>
      Map(
        "index" -> s"0x${w.index.toString(16)}",
        "validatorIndex" -> s"0x${w.validatorIndex.toString(16)}",
        "address" -> s"0x${com.chipprbots.ethereum.utils.ByteStringUtils.hash2string(w.address.bytes).stripPrefix("0x")}",
        "amount" -> s"0x${w.amount.toString(16)}"
      )
    })

    BlockResponse(
      number = block.header.number.value,
      hash = if pendingBlock then None else Some(block.header.hash.value),
      parentHash = block.header.parentHash.value,
      nonce = if pendingBlock then None else Some(block.header.nonce),
      sha3Uncles = block.header.ommersHash.value,
      logsBloom = block.header.logsBloom.value,
      transactionsRoot = block.header.transactionsRoot.value,
      stateRoot = block.header.stateRoot.value,
      receiptsRoot = block.header.receiptsRoot.value,
      miner = if pendingBlock then None else Some(block.header.beneficiary),
      difficulty = block.header.difficulty.value,
      totalDifficulty = td,
      extraData = block.header.extraData,
      size = Block.size(block),
      gasLimit = block.header.gasLimit.value,
      gasUsed = block.header.gasUsed.value,
      timestamp = BigInt(block.header.unixTimestamp.toLong),
      mixHash = block.header.mixHash.value,
      transactions = transactions,
      uncles = block.body.uncleNodesList.map(_.hash.value),
      signature = signatureStr,
      signer = signerStr,
      baseFeePerGas = block.header.baseFee,
      withdrawalsRoot = block.header.withdrawalsRoot,
      withdrawals = withdrawals,
      blobGasUsed = block.header.blobGasUsed,
      excessBlobGas = block.header.excessBlobGas,
      parentBeaconBlockRoot = block.header.parentBeaconBlockRoot.map(_.value),
      requestsHash = block.header.requestsHash,
      emitTotalDifficulty = emitTotalDifficulty
    )

  /** Header-only variant. Deliberately keeps its original signature and always emits `totalDifficulty`: Scala forbids
    * two overloaded `apply`s that both carry default arguments, and the block-based overload above owns the defaults.
    * Callers that need the ETH-family gate use that one with an explicit empty body.
    */
  def apply(blockHeader: BlockHeader, weight: Option[ChainWeight], pendingBlock: Boolean): BlockResponse =
    BlockResponse(
      block = Block(blockHeader, BlockBody(Nil, Nil)),
      weight = weight,
      pendingBlock = pendingBlock,
      emitTotalDifficulty = true
    )
