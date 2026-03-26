package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPSerializable
import com.chipprbots.ethereum.rlp.rawDecode
import com.chipprbots.ethereum.rlp.{encode => rlpEncode}
import com.chipprbots.ethereum.utils.ByteStringUtils

import BlockHeader.HeaderExtraFields
import BlockHeader.HeaderExtraFields._
import BlockHeaderImplicits._

case class BlockHeader(
    parentHash: ByteString,
    ommersHash: ByteString,
    beneficiary: ByteString,
    stateRoot: ByteString,
    transactionsRoot: ByteString,
    receiptsRoot: ByteString,
    logsBloom: ByteString,
    difficulty: BigInt,
    number: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    unixTimestamp: Long,
    extraData: ByteString,
    mixHash: ByteString,
    nonce: ByteString,
    extraFields: HeaderExtraFields = HeaderExtraFields.HefEmpty
) {

  def withAdditionalExtraData(additionalBytes: ByteString): BlockHeader =
    copy(extraData = extraData ++ additionalBytes)

  def dropRightNExtraDataBytes(n: Int): BlockHeader =
    copy(extraData = extraData.dropRight(n))

  val baseFee: Option[BigInt] = extraFields match {
    case HefPostOlympia(fee) => Some(fee)
    case _                   => None
  }

  def isParentOf(child: BlockHeader): Boolean = number + 1 == child.number && child.parentHash == hash

  override def toString: String =
    s"BlockHeader { " +
      s"hash: $hashAsHexString, " +
      s"parentHash: ${ByteStringUtils.hash2string(parentHash)}, " +
      s"ommersHash: ${ByteStringUtils.hash2string(ommersHash)}, " +
      s"beneficiary: ${ByteStringUtils.hash2string(beneficiary)} " +
      s"stateRoot: ${ByteStringUtils.hash2string(stateRoot)} " +
      s"transactionsRoot: ${ByteStringUtils.hash2string(transactionsRoot)} " +
      s"receiptsRoot: ${ByteStringUtils.hash2string(receiptsRoot)} " +
      s"logsBloom: ${ByteStringUtils.hash2string(logsBloom)} " +
      s"difficulty: $difficulty, " +
      s"number: $number, " +
      s"gasLimit: $gasLimit, " +
      s"gasUsed: $gasUsed, " +
      s"unixTimestamp: $unixTimestamp, " +
      s"extraData: ${ByteStringUtils.hash2string(extraData)} " +
      s"mixHash: ${ByteStringUtils.hash2string(mixHash)} " +
      s"nonce: ${ByteStringUtils.hash2string(nonce)}" +
      s"}"

  /** calculates blockHash for given block header
    * @return
    *   \- hash that can be used to get block bodies / receipts
    */
  lazy val hash: ByteString = ByteString(kec256(this.toBytes: Array[Byte]))

  lazy val hashAsHexString: String = ByteStringUtils.hash2string(hash)

  def idTag: String =
    s"$number: $hashAsHexString"
}

object BlockHeader {

  import com.chipprbots.ethereum.rlp.RLPImplicits._

  /** Empty MPT root hash. Data type is irrelevant */
  val EmptyMpt: ByteString = ByteString(crypto.kec256(rlp.encode(Array.empty[Byte])))

  val EmptyBeneficiary: ByteString = Address(0).bytes

  val EmptyOmmers: ByteString = ByteString(crypto.kec256(rlp.encode(RLPList())))

  /** Given a block header, returns it's rlp encoded bytes without nonce and mix hash
    *
    * @param blockHeader
    *   to be encoded without PoW fields
    * @return
    *   rlp.encode( [blockHeader.parentHash, ..., blockHeader.extraData] )
    */
  def getEncodedWithoutNonce(blockHeader: BlockHeader): Array[Byte] = {
    // toRLPEncodeable is guaranteed to return a RLPList
    val rlpList: RLPList = blockHeader.toRLPEncodable.asInstanceOf[RLPList]

    val numberOfPowFields = 2
    val numberOfExtraFields = blockHeader.extraFields match {
      case HefPostOlympia(_) => 1 // baseFee
      case HefEmpty          => 0
    }

    val baseFields = rlpList.items.dropRight(numberOfPowFields + numberOfExtraFields)
    val extraFieldsEncoded = rlpList.items.takeRight(numberOfExtraFields)

    val rlpItemsWithoutNonce = baseFields ++ extraFieldsEncoded
    rlpEncode(RLPList(rlpItemsWithoutNonce: _*))
  }

  sealed trait HeaderExtraFields
  object HeaderExtraFields {
    case object HefEmpty extends HeaderExtraFields
    case class HefPostOlympia(baseFee: BigInt) extends HeaderExtraFields
  }
}

object BlockHeaderImplicits {

  import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
  import com.chipprbots.ethereum.rlp.RLPValue
  import com.chipprbots.ethereum.utils.ByteUtils

  import BlockHeader.HeaderExtraFields._

  implicit class BlockHeaderEnc(blockHeader: BlockHeader) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = {
      import blockHeader._
      extraFields match {
        case HefPostOlympia(bf) =>
          RLPList(
            parentHash.toArray,
            ommersHash.toArray,
            beneficiary.toArray,
            stateRoot.toArray,
            transactionsRoot.toArray,
            receiptsRoot.toArray,
            logsBloom.toArray,
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(difficulty)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(number)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasUsed)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(unixTimestamp)),
            extraData.toArray,
            mixHash.toArray,
            nonce.toArray,
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(bf))
          )

        case HefEmpty =>
          RLPList(
            parentHash.toArray,
            ommersHash.toArray,
            beneficiary.toArray,
            stateRoot.toArray,
            transactionsRoot.toArray,
            receiptsRoot.toArray,
            logsBloom.toArray,
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(difficulty)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(number)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasUsed)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(unixTimestamp)),
            extraData.toArray,
            mixHash.toArray,
            nonce.toArray
          )
      }
    }
  }

  implicit class BlockHeaderByteArrayDec(val bytes: Array[Byte]) extends AnyVal {
    def toBlockHeader: BlockHeader = BlockHeaderDec(rawDecode(bytes)).toBlockHeader
  }

  implicit class BlockHeaderDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {
    // Fork-agnostic RLP decoder: decode mandatory fields by position, then detect optional
    // fields by list length. This matches the universal pattern used by go-ethereum, besu,
    // erigon, nethermind, and core-geth — decoding is separated from fork validation.
    def toBlockHeader: BlockHeader =
      rlpEncodeable match {
        case rlpList: RLPList =>
          val items = rlpList.items
          if (items.length < 15)
            throw new Exception(s"BlockHeader cannot be decoded: expected at least 15 fields, got ${items.length}")

          // 15 mandatory fields (positions 0-14)
          val parentHash = byteStringFromEncodeable(items(0))
          val ommersHash = byteStringFromEncodeable(items(1))
          val beneficiary = byteStringFromEncodeable(items(2))
          val stateRoot = byteStringFromEncodeable(items(3))
          val transactionsRoot = byteStringFromEncodeable(items(4))
          val receiptsRoot = byteStringFromEncodeable(items(5))
          val logsBloom = byteStringFromEncodeable(items(6))
          val difficulty = bigIntFromEncodeable(items(7))
          val number = bigIntFromEncodeable(items(8))
          val gasLimit = bigIntFromEncodeable(items(9))
          val gasUsed = bigIntFromEncodeable(items(10))
          val unixTimestamp = longFromEncodeable(items(11))
          val extraData = byteStringFromEncodeable(items(12))
          val mixHash = byteStringFromEncodeable(items(13))
          val nonce = byteStringFromEncodeable(items(14))

          // Optional field at position 15: baseFee (EIP-1559 / Olympia)
          val extraFields: HeaderExtraFields =
            if (items.length > 15) HefPostOlympia(bigIntFromEncodeable(items(15)))
            else HefEmpty

          BlockHeader(
            parentHash, ommersHash, beneficiary, stateRoot, transactionsRoot,
            receiptsRoot, logsBloom, difficulty, number, gasLimit, gasUsed,
            unixTimestamp, extraData, mixHash, nonce, extraFields
          )

        case _ =>
          throw new Exception("BlockHeader cannot be decoded: not an RLPList")
      }
  }
}
