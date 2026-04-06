package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.implicits._

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
trait BaseBlockResponse {
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
}

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
    requestsHash: Option[ByteString]
) extends BaseBlockResponse

object BlockResponse {

  val NotAvailable = "N/A"

  def apply(
      block: Block,
      weight: Option[ChainWeight] = None,
      fullTxs: Boolean = false,
      pendingBlock: Boolean = false,
      coinbase: Option[ByteString] = None
  ): BlockResponse = {
    val transactions =
      if (fullTxs)
        Right(block.body.transactionList.zipWithIndex.map { case (stx, transactionIndex) =>
          TransactionResponse(stx = stx, blockHeader = Some(block.header), transactionIndex = Some(transactionIndex))
        })
      else
        Left(block.body.transactionList.map(_.hash))

    val td = weight.map(_.totalDifficulty)

    val signature =
      if (block.header.extraData.length >= ECDSASignature.EncodedLength)
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
      number = block.header.number,
      hash = if (pendingBlock) None else Some(block.header.hash),
      parentHash = block.header.parentHash,
      nonce = if (pendingBlock) None else Some(block.header.nonce),
      sha3Uncles = block.header.ommersHash,
      logsBloom = block.header.logsBloom,
      transactionsRoot = block.header.transactionsRoot,
      stateRoot = block.header.stateRoot,
      receiptsRoot = block.header.receiptsRoot,
      miner = if (pendingBlock) None else Some(block.header.beneficiary),
      difficulty = block.header.difficulty,
      totalDifficulty = td,
      extraData = block.header.extraData,
      size = Block.size(block),
      gasLimit = block.header.gasLimit,
      gasUsed = block.header.gasUsed,
      timestamp = block.header.unixTimestamp,
      mixHash = block.header.mixHash,
      transactions = transactions,
      uncles = block.body.uncleNodesList.map(_.hash),
      signature = signatureStr,
      signer = signerStr,
      baseFeePerGas = block.header.baseFee,
      withdrawalsRoot = block.header.withdrawalsRoot,
      withdrawals = withdrawals,
      blobGasUsed = block.header.blobGasUsed,
      excessBlobGas = block.header.excessBlobGas,
      parentBeaconBlockRoot = block.header.parentBeaconBlockRoot,
      requestsHash = block.header.requestsHash
    )
  }

  def apply(blockHeader: BlockHeader, weight: Option[ChainWeight], pendingBlock: Boolean): BlockResponse =
    BlockResponse(
      block = Block(blockHeader, BlockBody(Nil, Nil)),
      weight = weight,
      pendingBlock = pendingBlock
    )

}
