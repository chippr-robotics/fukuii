package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPSerializable
import com.chipprbots.ethereum.rlp.encode as rlpEncode
import com.chipprbots.ethereum.rlp.rawDecode
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.NetworkType

import BlockHeader.HeaderExtraFields
import BlockHeader.HeaderExtraFields.*
import BlockHeaderImplicits.*

case class BlockHeader(
    parentHash: BlockHash,
    ommersHash: BlockHash,
    beneficiary: ByteString,
    stateRoot: TrieRoot,
    transactionsRoot: TrieRoot,
    receiptsRoot: TrieRoot,
    logsBloom: BloomFilter,
    difficulty: Difficulty,
    number: BlockNumber,
    gasLimit: GasAmount,
    gasUsed: GasAmount,
    unixTimestamp: Timestamp,
    extraData: ByteString,
    mixHash: BlockHash,
    nonce: ByteString,
    extraFields: HeaderExtraFields = HeaderExtraFields.HefEmpty
):

  def withAdditionalExtraData(additionalBytes: ByteString): BlockHeader =
    copy(extraData = extraData ++ additionalBytes)

  def dropRightNExtraDataBytes(n: Int): BlockHeader =
    copy(extraData = extraData.dropRight(n))

  val baseFee: Option[BigInt] = extraFields match
    case HefPostOlympia(fee)                        => Some(fee)
    case HefPostShanghai(fee, _)                    => Some(fee)
    case HefPostCancun(fee, _, _, _, _)             => Some(fee)
    case HefPostPrague(fee, _, _, _, _, _)          => Some(fee)
    case HefPostAmsterdam(fee, _, _, _, _, _, _, _) => Some(fee)
    case _                                          => None

  val withdrawalsRoot: Option[ByteString] = extraFields match
    case HefPostShanghai(_, wr)                    => Some(wr)
    case HefPostCancun(_, wr, _, _, _)             => Some(wr)
    case HefPostPrague(_, wr, _, _, _, _)          => Some(wr)
    case HefPostAmsterdam(_, wr, _, _, _, _, _, _) => Some(wr)
    case _                                         => None

  val blobGasUsed: Option[BigInt] = extraFields match
    case HefPostCancun(_, _, bgu, _, _)             => Some(bgu)
    case HefPostPrague(_, _, bgu, _, _, _)          => Some(bgu)
    case HefPostAmsterdam(_, _, bgu, _, _, _, _, _) => Some(bgu)
    case _                                          => None

  val excessBlobGas: Option[BigInt] = extraFields match
    case HefPostCancun(_, _, _, ebg, _)             => Some(ebg)
    case HefPostPrague(_, _, _, ebg, _, _)          => Some(ebg)
    case HefPostAmsterdam(_, _, _, ebg, _, _, _, _) => Some(ebg)
    case _                                          => None

  val parentBeaconBlockRoot: Option[BlockHash] = extraFields match
    case HefPostCancun(_, _, _, _, pbbr)             => Some(BlockHash(pbbr))
    case HefPostPrague(_, _, _, _, pbbr, _)          => Some(BlockHash(pbbr))
    case HefPostAmsterdam(_, _, _, _, pbbr, _, _, _) => Some(BlockHash(pbbr))
    case _                                           => None

  val requestsHash: Option[ByteString] = extraFields match
    case HefPostPrague(_, _, _, _, _, rh)          => Some(rh)
    case HefPostAmsterdam(_, _, _, _, _, rh, _, _) => Some(rh)
    case _                                         => None

  def isPoS: Boolean = difficulty == Difficulty.Zero && baseFee.isDefined
  def isPoW: Boolean = !isPoS

  /** Post-merge, mixHash carries the prevRandao value from the beacon chain. */
  def prevRandao: Option[ByteString] = if isPoS then Some(mixHash.value) else None

  def isParentOf(child: BlockHeader): Boolean = number + 1L == child.number && child.parentHash == hash

  override def toString: String =
    s"BlockHeader { " +
      s"hash: $hashAsHexString, " +
      s"parentHash: ${ByteStringUtils.hash2string(parentHash.value)}, " +
      s"ommersHash: ${ByteStringUtils.hash2string(ommersHash.value)}, " +
      s"beneficiary: ${ByteStringUtils.hash2string(beneficiary)} " +
      s"stateRoot: ${ByteStringUtils.hash2string(stateRoot.value)} " +
      s"transactionsRoot: ${ByteStringUtils.hash2string(transactionsRoot.value)} " +
      s"receiptsRoot: ${ByteStringUtils.hash2string(receiptsRoot.value)} " +
      s"logsBloom: ${ByteStringUtils.hash2string(logsBloom.value)} " +
      s"difficulty: $difficulty, " +
      s"number: $number, " +
      s"gasLimit: $gasLimit, " +
      s"gasUsed: $gasUsed, " +
      s"unixTimestamp: $unixTimestamp, " +
      s"extraData: ${ByteStringUtils.hash2string(extraData)} " +
      s"mixHash: ${ByteStringUtils.hash2string(mixHash.value)} " +
      s"nonce: ${ByteStringUtils.hash2string(nonce)}" +
      s"}"

  /** calculates blockHash for given block header
    * @return
    *   \- hash that can be used to get block bodies / receipts
    */
  lazy val hash: BlockHash = BlockHash(ByteString(kec256(this.toBytes: Array[Byte])))

  lazy val hashAsHexString: String = ByteStringUtils.hash2string(hash.value)

  def idTag: String =
    s"$number: $hashAsHexString"

object BlockHeader:

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
  def getEncodedWithoutNonce(blockHeader: BlockHeader): Array[Byte] =
    val rlpList: RLPList = blockHeader.toRLPEncodable match
      case rl: RLPList => rl
      case _           => throw new RuntimeException("BlockHeader.toRLPEncodable did not return RLPList")

    val numberOfPowFields = 2
    val numberOfExtraFields = blockHeader.extraFields match
      case HefPostAmsterdam(_, _, _, _, _, _, _, _) => 8
      case HefPostPrague(_, _, _, _, _, _)          => 6
      case HefPostCancun(_, _, _, _, _)             => 5
      case HefPostShanghai(_, _)                    => 2
      case HefPostOlympia(_)                        => 1
      case HefEmpty                                 => 0

    val baseFields = rlpList.items.dropRight(numberOfPowFields + numberOfExtraFields)
    val extraFieldsEncoded = rlpList.items.takeRight(numberOfExtraFields)

    val rlpItemsWithoutNonce = baseFields ++ extraFieldsEncoded
    rlpEncode(RLPList(rlpItemsWithoutNonce*))

  /** Structural check: a decoded header's ExtraFields shape must be consistent with the fork timestamps active at its
    * timestamp. ETC has no timestamp forks; this check is a no-op for ETC chains. Intended as an early, cheap gate
    * before the full [[com.chipprbots.ethereum.consensus.engine.PoSBlockHeaderValidator]].
    */
  def validateFieldCount(header: BlockHeader, config: BlockchainConfig): Either[String, Unit] =
    if config.networkType != NetworkType.ETH then Right(())
    else if config.isAmsterdamTimestamp(header.unixTimestamp) && !isAmsterdamShape(header.extraFields) then
      Left(
        s"Amsterdam-era header at timestamp ${header.unixTimestamp} missing blockAccessListHash/slotNumber " +
          s"(RLP field count below 23 — expected HefPostAmsterdam)"
      )
    else if config.isCancunTimestamp(header.unixTimestamp) && header.blobGasUsed.isEmpty then
      Left(
        s"Cancun-era header at timestamp ${header.unixTimestamp} missing blobGasUsed " +
          s"(RLP field count below 20 — expected HefPostCancun or HefPostPrague)"
      )
    else if config.isShanghaiTimestamp(header.unixTimestamp) && header.withdrawalsRoot.isEmpty then
      Left(
        s"Shanghai-era header at timestamp ${header.unixTimestamp} missing withdrawalsRoot " +
          s"(RLP field count below 17 — expected HefPostShanghai+)"
      )
    else Right(())

  private def isAmsterdamShape(extraFields: HeaderExtraFields): Boolean = extraFields match
    case _: HefPostAmsterdam => true
    case _                   => false

  sealed trait HeaderExtraFields
  object HeaderExtraFields:
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

    /** Amsterdam: adds blockAccessListHash (EIP-7928, RLP item 21) and slotNumber (EIP-7843, RLP item 22). RLP = 23
      * items.
      *
      * Field ordering is measured, not derived from EIP prose: block 600 of hive's devp2p fixture was re-encoded from
      * its decoded parts in this order and keccak-hashed byte-identically to `headblock.json.hash`. See
      * `specs/009-amsterdam-fork-support/contracts/header-rlp.md`. Do not reorder.
      */
    case class HefPostAmsterdam(
        baseFee: BigInt,
        withdrawalsRoot: ByteString,
        blobGasUsed: BigInt,
        excessBlobGas: BigInt,
        parentBeaconBlockRoot: ByteString,
        requestsHash: ByteString,
        blockAccessListHash: ByteString,
        slotNumber: BigInt
    ) extends HeaderExtraFields

object BlockHeaderImplicits:

  import com.chipprbots.ethereum.rlp.RLPImplicitConversions.*
  import com.chipprbots.ethereum.rlp.RLPValue
  import com.chipprbots.ethereum.utils.ByteUtils

  import BlockHeader.HeaderExtraFields.*

  implicit class BlockHeaderEnc(blockHeader: BlockHeader) extends RLPSerializable:
    override def toRLPEncodable: RLPEncodeable =
      import blockHeader.*

      val baseItems: Seq[RLPEncodeable] = Seq(
        RLPValue(parentHash.value.toArray),
        RLPValue(ommersHash.value.toArray),
        RLPValue(beneficiary.toArray),
        RLPValue(stateRoot.value.toArray),
        RLPValue(transactionsRoot.value.toArray),
        RLPValue(receiptsRoot.value.toArray),
        RLPValue(logsBloom.toArray),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(difficulty.value)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(number.value)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasLimit.value)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(gasUsed.value)),
        // toUnsignedBigInt, NOT the implicit Long->BigInt widening: the latter sign-extends,
        // and bigIntToUnsignedByteArray then emits BigInt(-1).toByteArray = the single byte
        // 0xFF (it only ever strips a leading 0x00, never a leading 0xFF). A 2^64-1 timestamp
        // would encode as the 1-byte value 255, and since hash = kec256(toBytes) the block
        // hash would be wrong — engine newPayload rejects it as "block hash mismatch".
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(unixTimestamp.toUnsignedBigInt)),
        RLPValue(extraData.toArray),
        RLPValue(mixHash.value.toArray),
        RLPValue(nonce.toArray)
      )

      val extraItems: Seq[RLPEncodeable] = extraFields match
        case HefPostAmsterdam(bf, wr, bgu, ebg, pbbr, rh, balh, slot) =>
          Seq(
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(bf)),
            RLPValue(wr.toArray),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(bgu)),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(ebg)),
            RLPValue(pbbr.toArray),
            RLPValue(rh.toArray),
            RLPValue(balh.toArray),
            RLPValue(ByteUtils.bigIntToUnsignedByteArray(slot))
          )
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

      RLPList((baseItems ++ extraItems)*)

  implicit class BlockHeaderByteArrayDec(val bytes: Array[Byte]) extends AnyVal:
    def toBlockHeader: BlockHeader = BlockHeaderDec(rawDecode(bytes)).toBlockHeader

  implicit class BlockHeaderDec(val rlpEncodeable: RLPEncodeable) extends AnyVal:
    def toBlockHeader: BlockHeader =
      rlpEncodeable match
        case rlpList: RLPList =>
          val items = rlpList.items
          if items.length < 15 then
            throw new Exception(s"BlockHeader cannot be decoded: expected >= 15 items, got ${items.length}")

          val base = BlockHeader(
            parentHash = BlockHash(byteStringFromEncodeable(items(0))),
            ommersHash = BlockHash(byteStringFromEncodeable(items(1))),
            beneficiary = byteStringFromEncodeable(items(2)),
            stateRoot = TrieRoot(byteStringFromEncodeable(items(3))),
            transactionsRoot = TrieRoot(byteStringFromEncodeable(items(4))),
            receiptsRoot = TrieRoot(byteStringFromEncodeable(items(5))),
            logsBloom = BloomFilter(byteStringFromEncodeable(items(6))),
            difficulty = Difficulty(bigIntFromEncodeable(items(7))),
            number = BlockNumber(bigIntFromEncodeable(items(8))),
            gasLimit = GasAmount(bigIntFromEncodeable(items(9))),
            gasUsed = GasAmount(bigIntFromEncodeable(items(10))),
            unixTimestamp = Timestamp(longFromEncodeable(items(11))),
            extraData = byteStringFromEncodeable(items(12)),
            mixHash = BlockHash(byteStringFromEncodeable(items(13))),
            nonce = byteStringFromEncodeable(items(14))
          )

          items.length match
            case 15 => base // HefEmpty
            case 16 => base.copy(extraFields = HefPostOlympia(bigIntFromEncodeable(items(15))))
            case 17 =>
              base.copy(extraFields =
                HefPostShanghai(
                  baseFee = bigIntFromEncodeable(items(15)),
                  withdrawalsRoot = byteStringFromEncodeable(items(16))
                )
              )
            case 20 =>
              base.copy(extraFields =
                HefPostCancun(
                  baseFee = bigIntFromEncodeable(items(15)),
                  withdrawalsRoot = byteStringFromEncodeable(items(16)),
                  blobGasUsed = bigIntFromEncodeable(items(17)),
                  excessBlobGas = bigIntFromEncodeable(items(18)),
                  parentBeaconBlockRoot = byteStringFromEncodeable(items(19))
                )
              )
            case 21 =>
              base.copy(extraFields =
                HefPostPrague(
                  baseFee = bigIntFromEncodeable(items(15)),
                  withdrawalsRoot = byteStringFromEncodeable(items(16)),
                  blobGasUsed = bigIntFromEncodeable(items(17)),
                  excessBlobGas = bigIntFromEncodeable(items(18)),
                  parentBeaconBlockRoot = byteStringFromEncodeable(items(19)),
                  requestsHash = byteStringFromEncodeable(items(20))
                )
              )
            case 23 =>
              base.copy(extraFields =
                HefPostAmsterdam(
                  baseFee = bigIntFromEncodeable(items(15)),
                  withdrawalsRoot = byteStringFromEncodeable(items(16)),
                  blobGasUsed = bigIntFromEncodeable(items(17)),
                  excessBlobGas = bigIntFromEncodeable(items(18)),
                  parentBeaconBlockRoot = byteStringFromEncodeable(items(19)),
                  requestsHash = byteStringFromEncodeable(items(20)),
                  blockAccessListHash = byteStringFromEncodeable(items(21)),
                  slotNumber = bigIntFromEncodeable(items(22))
                )
              )
            case n =>
              throw new Exception(s"BlockHeader cannot be decoded: unexpected item count $n")

        case _ =>
          throw new Exception("BlockHeader cannot be decoded: not an RLPList")
