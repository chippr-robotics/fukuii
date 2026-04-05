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
    case HefPostOlympia(fee)              => Some(fee)
    case HefPostShanghai(fee, _)          => Some(fee)
    case HefPostCancun(fee, _, _, _, _)   => Some(fee)
    case HefPostPrague(fee, _, _, _, _, _) => Some(fee)
    case _                                => None
  }

  val withdrawalsRoot: Option[ByteString] = extraFields match {
    case HefPostShanghai(_, wr)          => Some(wr)
    case HefPostCancun(_, wr, _, _, _)   => Some(wr)
    case HefPostPrague(_, wr, _, _, _, _) => Some(wr)
    case _                               => None
  }

  val blobGasUsed: Option[BigInt] = extraFields match {
    case HefPostCancun(_, _, bgu, _, _)   => Some(bgu)
    case HefPostPrague(_, _, bgu, _, _, _) => Some(bgu)
    case _                                => None
  }

  val excessBlobGas: Option[BigInt] = extraFields match {
    case HefPostCancun(_, _, _, ebg, _)   => Some(ebg)
    case HefPostPrague(_, _, _, ebg, _, _) => Some(ebg)
    case _                                => None
  }

  val parentBeaconBlockRoot: Option[ByteString] = extraFields match {
    case HefPostCancun(_, _, _, _, pbbr)   => Some(pbbr)
    case HefPostPrague(_, _, _, _, pbbr, _) => Some(pbbr)
    case _                                 => None
  }

  val requestsHash: Option[ByteString] = extraFields match {
    case HefPostPrague(_, _, _, _, _, rh) => Some(rh)
    case _                               => None
  }

  /** True if this is a post-merge block (difficulty == 0, used as prevRandao). */
  def isPostMerge: Boolean = difficulty == 0 && baseFee.isDefined

  /** Post-merge, mixHash carries the prevRandao value from the beacon chain. */
  def prevRandao: Option[ByteString] = if (isPostMerge) Some(mixHash) else None

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
      case HefPostPrague(_, _, _, _, _, _) => 6
      case HefPostCancun(_, _, _, _, _)    => 5
      case HefPostShanghai(_, _)           => 2
      case HefPostOlympia(_)               => 1
      case HefEmpty                        => 0
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

    /** Shanghai: adds withdrawalsRoot to the header (EIP-4895). RLP = 17 items. */
    case class HefPostShanghai(baseFee: BigInt, withdrawalsRoot: ByteString) extends HeaderExtraFields

    /** Cancun: adds blob gas fields and parent beacon block root (EIP-4844, EIP-4788). RLP = 20 items. */
    case class HefPostCancun(
        baseFee: BigInt,
        withdrawalsRoot: ByteString,
        blobGasUsed: BigInt,
        excessBlobGas: BigInt,
        parentBeaconBlockRoot: ByteString
    ) extends HeaderExtraFields

    /** Prague/Electra: adds requestsHash (EIP-7685). RLP = 21 items. */
    case class HefPostPrague(
        baseFee: BigInt,
        withdrawalsRoot: ByteString,
        blobGasUsed: BigInt,
        excessBlobGas: BigInt,
        parentBeaconBlockRoot: ByteString,
        requestsHash: ByteString
    ) extends HeaderExtraFields
  }
}

object BlockHeaderImplicits {

  import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
  import com.chipprbots.ethereum.rlp.RLPImplicits._
  import com.chipprbots.ethereum.rlp.RLPValue
  import com.chipprbots.ethereum.utils.ByteUtils

  import BlockHeader.HeaderExtraFields._

  implicit class BlockHeaderEnc(blockHeader: BlockHeader) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = {
      import blockHeader._

      val baseItems: Seq[RLPEncodeable] = Seq(
        RLPValue(parentHash.toArray),
        RLPValue(ommersHash.toArray),
        RLPValue(beneficiary.toArray),
        RLPValue(stateRoot.toArray),
        RLPValue(transactionsRoot.toArray),
        RLPValue(receiptsRoot.toArray),
        RLPValue(logsBloom.toArray),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(difficulty)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(number)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasUsed)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(unixTimestamp)),
        RLPValue(extraData.toArray),
        RLPValue(mixHash.toArray),
        RLPValue(nonce.toArray)
      )

      val extraItems: Seq[RLPEncodeable] = extraFields match {
        case HefPostPrague(bf, wr, bgu, ebg, pbbr, rh) =>
          Seq(
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(bf)),
            RLPValue(wr.toArray),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(bgu)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(ebg)),
            RLPValue(pbbr.toArray),
            RLPValue(rh.toArray)
          )
        case HefPostCancun(bf, wr, bgu, ebg, pbbr) =>
          Seq(
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(bf)),
            RLPValue(wr.toArray),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(bgu)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(ebg)),
            RLPValue(pbbr.toArray)
          )
        case HefPostShanghai(bf, wr) =>
          Seq(
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(bf)),
            RLPValue(wr.toArray)
          )
        case HefPostOlympia(bf) =>
          Seq(RLPValue(ByteUtils.bigIntToUnsignedByteArray(bf)))
        case HefEmpty =>
          Seq.empty
      }

      RLPList((baseItems ++ extraItems): _*)
    }
  }

  implicit class BlockHeaderByteArrayDec(val bytes: Array[Byte]) extends AnyVal {
    def toBlockHeader: BlockHeader = BlockHeaderDec(rawDecode(bytes)).toBlockHeader
  }

  implicit class BlockHeaderDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {
    def toBlockHeader: BlockHeader = {
      rlpEncodeable match {
        case rlpList: RLPList =>
          val items = rlpList.items
          if (items.length < 15)
            throw new Exception(s"BlockHeader cannot be decoded: expected >= 15 items, got ${items.length}")

          val base = BlockHeader(
            parentHash = byteStringFromEncodeable(items(0)),
            ommersHash = byteStringFromEncodeable(items(1)),
            beneficiary = byteStringFromEncodeable(items(2)),
            stateRoot = byteStringFromEncodeable(items(3)),
            transactionsRoot = byteStringFromEncodeable(items(4)),
            receiptsRoot = byteStringFromEncodeable(items(5)),
            logsBloom = byteStringFromEncodeable(items(6)),
            difficulty = bigIntFromEncodeable(items(7)),
            number = bigIntFromEncodeable(items(8)),
            gasLimit = bigIntFromEncodeable(items(9)),
            gasUsed = bigIntFromEncodeable(items(10)),
            unixTimestamp = longFromEncodeable(items(11)),
            extraData = byteStringFromEncodeable(items(12)),
            mixHash = byteStringFromEncodeable(items(13)),
            nonce = byteStringFromEncodeable(items(14))
          )

          items.length match {
            case 15 => base // HefEmpty
            case 16 => base.copy(extraFields = HefPostOlympia(bigIntFromEncodeable(items(15))))
            case 17 =>
              base.copy(extraFields = HefPostShanghai(
                baseFee = bigIntFromEncodeable(items(15)),
                withdrawalsRoot = byteStringFromEncodeable(items(16))
              ))
            case 20 =>
              base.copy(extraFields = HefPostCancun(
                baseFee = bigIntFromEncodeable(items(15)),
                withdrawalsRoot = byteStringFromEncodeable(items(16)),
                blobGasUsed = bigIntFromEncodeable(items(17)),
                excessBlobGas = bigIntFromEncodeable(items(18)),
                parentBeaconBlockRoot = byteStringFromEncodeable(items(19))
              ))
            case n if n >= 21 =>
              base.copy(extraFields = HefPostPrague(
                baseFee = bigIntFromEncodeable(items(15)),
                withdrawalsRoot = byteStringFromEncodeable(items(16)),
                blobGasUsed = bigIntFromEncodeable(items(17)),
                excessBlobGas = bigIntFromEncodeable(items(18)),
                parentBeaconBlockRoot = byteStringFromEncodeable(items(19)),
                requestsHash = byteStringFromEncodeable(items(20))
              ))
            case n =>
              throw new Exception(s"BlockHeader cannot be decoded: unexpected item count $n")
          }

        case _ =>
          throw new Exception("BlockHeader cannot be decoded: not an RLPList")
      }
    }
  }
}
