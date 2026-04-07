package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPSerializable
import com.chipprbots.ethereum.rlp.rawDecode

/** This class represent a block as a header and a body which are returned in two different messages
  *
  * @param header
  *   Block header
  * @param body
  *   Block body
  */
case class Block(header: BlockHeader, body: BlockBody) {
  override def toString: String =
    s"Block { header: $header, body: $body }"

  def idTag: String =
    header.idTag

  def number: BigInt = header.number

  def hash: ByteString = header.hash

  def isParentOf(child: Block): Boolean = header.isParentOf(child.header)
}

object Block {

  implicit class BlockEnc(val obj: Block) extends RLPSerializable {
    import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions.given

    override def toRLPEncodable: RLPEncodeable = RLPList(
      obj.header.toRLPEncodable,
      RLPList(obj.body.transactionList.map(_.toRLPEncodable): _*),
      RLPList(obj.body.uncleNodesList.map(_.toRLPEncodable): _*)
    )
  }

  implicit class BlockDec(val bytes: Array[Byte]) extends AnyVal {
    import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.SignedTransactions.given
    import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.TypedTransaction._
    import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages.TypedTransaction.given
    def toBlock: Block = rawDecode(bytes) match {
      case RLPList(header: RLPList, stx: RLPList, uncles: RLPList) =>
        Block(
          header.toBlockHeader,
          BlockBody(
            stx.items.toTypedRLPEncodables.map(_.toSignedTransaction),
            uncles.items.map(_.toBlockHeader)
          )
        )
      // Shanghai+ blocks include withdrawals as 4th item
      case rlpList: RLPList if rlpList.items.size >= 4 =>
        val header = rlpList.items(0).asInstanceOf[RLPList]
        val stx = rlpList.items(1).asInstanceOf[RLPList]
        val uncles = rlpList.items(2).asInstanceOf[RLPList]
        val withdrawalsRlp = rlpList.items(3).asInstanceOf[RLPList]
        import com.chipprbots.ethereum.rlp.RLPImplicitConversions._
        val ws = withdrawalsRlp.items.collect { case w: RLPList =>
          val idx: BigInt = bigIntFromEncodeable(w.items(0))
          val vIdx: BigInt = bigIntFromEncodeable(w.items(1))
          val addr: ByteString = byteStringFromEncodeable(w.items(2))
          val amt: BigInt = bigIntFromEncodeable(w.items(3))
          Withdrawal(idx, vIdx, Address(addr), amt)
        }
        Block(
          header.toBlockHeader,
          BlockBody(
            stx.items.toTypedRLPEncodables.map(_.toSignedTransaction),
            uncles.items.map(_.toBlockHeader),
            Some(ws.toSeq)
          )
        )
      case _ => throw new RuntimeException("Cannot decode block")
    }
  }

  def size(block: Block): Long = (block.toBytes: Array[Byte]).length
}
