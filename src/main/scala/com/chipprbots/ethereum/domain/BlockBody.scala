package com.chipprbots.ethereum.domain

import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.domain.Withdrawal._
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPSerializable
import com.chipprbots.ethereum.rlp.rawDecode
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

case class BlockBody(
    transactionList: Seq[SignedTransaction],
    uncleNodesList: Seq[BlockHeader],
    withdrawals: Option[Seq[Withdrawal]] = None
) {
  override def toString: String =
    s"BlockBody{ transactionList: $transactionList, uncleNodesList: $uncleNodesList, withdrawals: $withdrawals }"

  def toShortString: String =
    s"BlockBody { transactionsList: ${transactionList.map(_.hash.toHex)}, uncleNodesList: ${uncleNodesList.map(_.hashAsHexString)}, withdrawals: ${withdrawals.map(_.size)} }"

  lazy val numberOfTxs: Int = transactionList.size

  lazy val numberOfUncles: Int = uncleNodesList.size
}

object BlockBody {

  val empty: BlockBody = BlockBody(Seq.empty, Seq.empty)

  import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.TypedTransaction._

  def blockBodyToRlpEncodable(
      blockBody: BlockBody,
      signedTxToRlpEncodable: SignedTransaction => RLPEncodeable,
      blockHeaderToRlpEncodable: BlockHeader => RLPEncodeable
  ): RLPEncodeable = {
    val baseParts: Seq[RLPEncodeable] = Seq(
      RLPList(blockBody.transactionList.map(signedTxToRlpEncodable): _*),
      RLPList(blockBody.uncleNodesList.map(blockHeaderToRlpEncodable): _*)
    )
    val withdrawalsPart: Seq[RLPEncodeable] = blockBody.withdrawals match {
      case Some(ws) => Seq(RLPList(ws.map(w => WithdrawalEnc(w).toRLPEncodable): _*))
      case None     => Seq.empty
    }
    RLPList((baseParts ++ withdrawalsPart): _*)
  }

  implicit class BlockBodyEnc(msg: BlockBody) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = {
      import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions._

      blockBodyToRlpEncodable(
        msg,
        stx => SignedTransactionEnc(stx).toRLPEncodable,
        header => BlockHeaderEnc(header).toRLPEncodable
      )
    }
  }

  implicit class BlockBlodyDec(val bytes: Array[Byte]) extends AnyVal {
    def toBlockBody: BlockBody = BlockBodyRLPEncodableDec(rawDecode(bytes)).toBlockBody
  }

  def rlpEncodableToBlockBody(
      rlpEncodeable: RLPEncodeable,
      rlpEncodableToSignedTransaction: RLPEncodeable => SignedTransaction,
      rlpEncodableToBlockHeader: RLPEncodeable => BlockHeader
  ): BlockBody =
    rlpEncodeable match {
      case rlpList: RLPList if rlpList.items.length >= 2 =>
        val transactions = rlpList.items(0).asInstanceOf[RLPList]
        val uncles = rlpList.items(1).asInstanceOf[RLPList]
        val withdrawals = if (rlpList.items.length >= 3) {
          Some(rlpList.items(2).asInstanceOf[RLPList].items.map(_.toWithdrawal))
        } else {
          None
        }
        BlockBody(
          transactions.items.toTypedRLPEncodables.map(rlpEncodableToSignedTransaction),
          uncles.items.map(rlpEncodableToBlockHeader),
          withdrawals
        )
      case _ => throw new RuntimeException("Cannot decode BlockBody")
    }

  implicit class BlockBodyRLPEncodableDec(val rlpEncodeable: RLPEncodeable) {
    def toBlockBody: BlockBody = {
      import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions._

      rlpEncodableToBlockBody(
        rlpEncodeable,
        rlp => SignedTransactionRlpEncodableDec(rlp).toSignedTransaction,
        rlp => BlockHeaderDec(rlp).toBlockHeader
      )

    }
  }

}
