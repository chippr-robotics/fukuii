package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.BlockHeaderImplicits.*
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
case class Block(header: BlockHeader, body: BlockBody):
  override def toString: String =
    s"Block { header: $header, body: $body }"

  def idTag: String =
    header.idTag

  def number: BlockNumber = header.number

  def hash: BlockHash = header.hash

  def isParentOf(child: Block): Boolean = header.isParentOf(child.header)

object Block:

  implicit class BlockEnc(val obj: Block) extends RLPSerializable:
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
    import com.chipprbots.ethereum.rlp.RLPImplicitConversions.*
    import com.chipprbots.ethereum.rlp.RLPImplicits.given

    override def toRLPEncodable: RLPEncodeable =
      // EIP-2718: typed transactions in block body must be RLP byte strings
      val txItems = obj.body.transactionList.map { stx =>
        stx.toRLPEncodable match
          case p: com.chipprbots.ethereum.rlp.PrefixedRLPEncodable =>
            // Typed tx: encode as raw bytes, wrap in RLPValue for proper string encoding
            com.chipprbots.ethereum.rlp.RLPValue(com.chipprbots.ethereum.rlp.encode(p))
          case other => other
      }
      val base = Seq(
        obj.header.toRLPEncodable,
        RLPList(txItems*),
        RLPList(obj.body.uncleNodesList.map(_.toRLPEncodable)*)
      )
      // Shanghai+ blocks include withdrawals as 4th item
      val withWithdrawals = obj.body.withdrawals match
        case Some(ws) =>
          base :+ RLPList(ws.map { w =>
            RLPList(
              toEncodeable[BigInt](w.index),
              toEncodeable[BigInt](w.validatorIndex),
              byteStringToEncodeable(w.address.bytes),
              toEncodeable[BigInt](w.amount)
            )
          }*)
        case None => base
      RLPList(withWithdrawals*)

  extension (bytes: Array[Byte])
    def toBlock: Block =
      import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
      import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.TypedTransaction.*
      rawDecode(bytes) match
        case RLPList(header: RLPList, stx: RLPList, uncles: RLPList) =>
          val decodedHeader = header.toBlockHeader
          // EIP-4895: a header that declares a withdrawalsRoot must be paired with a
          // withdrawals field in the block body. A 3-item RLP with a Shanghai+ header
          // is malformed — reject rather than silently treating the body as pre-Shanghai.
          if decodedHeader.withdrawalsRoot.isDefined then
            throw new RuntimeException("Cannot decode block: Shanghai+ header requires withdrawals in body")
          Block(
            decodedHeader,
            BlockBody(
              stx.items.toTypedRLPEncodables.map(_.toSignedTransaction),
              uncles.items.map(_.toBlockHeader)
            )
          )
        // Shanghai+ blocks include withdrawals as 4th item
        case rlpList: RLPList if rlpList.items.size >= 4 =>
          val header = rlpList.items(0) match
            case rl: RLPList => rl
            case _           => throw new RuntimeException("Cannot decode block: expected RLPList at index 0 (header)")
          val stx = rlpList.items(1) match
            case rl: RLPList => rl
            case _ => throw new RuntimeException("Cannot decode block: expected RLPList at index 1 (transactions)")
          val uncles = rlpList.items(2) match
            case rl: RLPList => rl
            case _           => throw new RuntimeException("Cannot decode block: expected RLPList at index 2 (uncles)")
          val withdrawalsRlp = rlpList.items(3) match
            case rl: RLPList => rl
            case _ => throw new RuntimeException("Cannot decode block: expected RLPList at index 3 (withdrawals)")
          import com.chipprbots.ethereum.rlp.RLPImplicitConversions.*
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

  def size(block: Block): Long = (block.toBytes: Array[Byte]).length
