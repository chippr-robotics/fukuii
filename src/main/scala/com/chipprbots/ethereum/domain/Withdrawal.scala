package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.rlp.*
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.*
import com.chipprbots.ethereum.utils.ByteUtils

/** EIP-4895: Beacon chain push withdrawals as operations.
  *
  * @param index
  *   monotonically increasing index
  * @param validatorIndex
  *   index of the validator on the beacon chain
  * @param address
  *   target address for the withdrawn ether
  * @param amount
  *   amount of ether given in Gwei (NOT Wei)
  */
case class Withdrawal(
    index: BigInt,
    validatorIndex: BigInt,
    address: Address,
    amount: BigInt
)

object Withdrawal:

  implicit class WithdrawalEnc(val w: Withdrawal) extends RLPSerializable:
    override def toRLPEncodable: RLPEncodeable =
      RLPList(
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(w.index)),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(w.validatorIndex)),
        RLPValue(w.address.bytes.toArray),
        RLPValue(ByteUtils.bigIntToUnsignedByteArray(w.amount))
      )

  extension (rlpEncodeable: RLPEncodeable)
    def toWithdrawal: Withdrawal =
      rlpEncodeable match
        case RLPList(index, validatorIndex, address, amount) =>
          Withdrawal(
            index = bigIntFromEncodeable(index),
            validatorIndex = bigIntFromEncodeable(validatorIndex),
            address = Address(byteStringFromEncodeable(address)),
            amount = bigIntFromEncodeable(amount)
          )
        case _ => throw new RuntimeException("Cannot decode Withdrawal")

  extension (bytes: Array[Byte]) def toWithdrawal: Withdrawal = rawDecode(bytes).toWithdrawal
